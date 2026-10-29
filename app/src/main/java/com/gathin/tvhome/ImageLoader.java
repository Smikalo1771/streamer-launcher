package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Loads the pictures of channel programs into ImageViews: http(s) over the network, content://, android.resource://
 * and file:// through the content resolver.
 *
 * Two small caches. Memory holds decoded bitmaps (a twelfth of the heap, by bytes). Disk holds the original bytes of
 * what came from the network, in cache/img, trimmed to 60 MB. A view has one request at a time: loading again, or
 * {@link #cancel}, drops the old one, and a late result is applied only if the view still asks for that uri.
 */
final class ImageLoader {

    private static final String TAG = "TvHome.ImageLoader";
    private static final long DISK_LIMIT = 60L * 1024 * 1024;
    private static final long TRIM_EVERY_MS = 30_000L;
    private static final int MAX_HOPS = 3;
    private static final int FADE_MS = 150;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) TvHome";

    private static ImageLoader instance;

    static synchronized ImageLoader get(Context c) {
        if (instance == null) {
            Context app = c.getApplicationContext();
            instance = new ImageLoader(app != null ? app : c);
        }
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool;
    private final LruCache<String, Bitmap> memory;
    private final File dir;
    private long lastTrim;

    private ImageLoader(Context c) {
        context = c;
        pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private int n;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "TvHome-image-" + (n++));
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });
        long max = Runtime.getRuntime().maxMemory();
        int bytes = (int) Math.max(4L * 1024 * 1024, Math.min(Integer.MAX_VALUE, max / 12));
        memory = new LruCache<String, Bitmap>(bytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        dir = new File(c.getCacheDir(), "img");
    }

    /** One view's request: the uri it wants and the work under way for it. */
    private static final class Request {
        final String uri;
        volatile Future<?> future;

        Request(String uri) {
            this.uri = uri;
        }
    }

    /** Main thread. A null or empty uri clears the view. */
    void load(final ImageView v, final String uri, final int targetW, final int targetH) {
        cancel(v);
        if (uri == null || uri.isEmpty()) {
            v.setImageDrawable(null);
            return;
        }
        final String key = uri + "@" + targetW + "x" + targetH;
        Bitmap hit = memory.get(key);
        final Request req = new Request(uri);
        v.setTag(R.id.image_request, req);
        if (hit != null) {
            v.animate().cancel();
            v.setAlpha(1f);
            v.setImageBitmap(hit);
            return;
        }
        v.setImageDrawable(null);
        req.future = pool.submit(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp;
                try {
                    bmp = fetch(uri, targetW, targetH);
                } catch (Throwable e) {
                    Log.w(TAG, "not loaded: " + uri, e);
                    return;
                }
                if (bmp == null || Thread.currentThread().isInterrupted()) return;
                memory.put(key, bmp);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (v.getTag(R.id.image_request) != req) return;
                        v.setImageBitmap(bmp);
                        v.animate().cancel();
                        v.setAlpha(0f);
                        v.animate().alpha(1f).setDuration(FADE_MS).start();
                    }
                });
            }
        });
    }

    /** Drops the view's request; whatever it already shows stays. */
    void cancel(ImageView v) {
        Object tag = v.getTag(R.id.image_request);
        if (tag instanceof Request) {
            Future<?> f = ((Request) tag).future;
            if (f != null) f.cancel(true);
        }
        v.setTag(R.id.image_request, null);
    }

    /** onTrimMemory: the decoded bitmaps go; the disk cache stays. */
    void trim() {
        memory.evictAll();
    }

    // ---- Worker thread ----

    private Bitmap fetch(String uri, int w, int h) throws IOException {
        Uri parsed = Uri.parse(uri);
        String scheme = parsed.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            File cached = new File(dir, sha1(uri));
            if (cached.isFile() && cached.length() > 0) {
                Bitmap b = decodeFile(cached, w, h);
                if (b != null) {
                    cached.setLastModified(System.currentTimeMillis());
                    return b;
                }
                cached.delete();
            }
            byte[] data = download(uri);
            if (data == null || Thread.currentThread().isInterrupted()) return null;
            Bitmap b = decodeBytes(data, w, h);
            if (b != null) store(cached, data);
            return b;
        }
        if ("content".equals(scheme) || "android.resource".equals(scheme) || "file".equals(scheme)) {
            byte[] data;
            try (InputStream in = context.getContentResolver().openInputStream(parsed)) {
                if (in == null) return null;
                data = readAll(in);
            }
            return decodeBytes(data, w, h);
        }
        return null;
    }

    private static byte[] download(String uri) throws IOException {
        String current = uri;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            try {
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    // HttpURLConnection will not follow http to https by itself.
                    String loc = conn.getHeaderField("Location");
                    if (loc == null) return null;
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) return null;
                try (InputStream in = conn.getInputStream()) {
                    return readAll(in);
                }
            } finally {
                conn.disconnect();
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static Bitmap decodeBytes(byte[] data, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, w, h);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static Bitmap decodeFile(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, w, h);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** The largest power of two that still leaves the picture at least the target size. */
    static int sample(int srcW, int srcH, int w, int h) {
        int s = 1;
        if (srcW <= 0 || srcH <= 0 || w <= 0 || h <= 0) return s;
        while (srcW / (s * 2) >= w && srcH / (s * 2) >= h) s *= 2;
        return s;
    }

    private void store(File target, byte[] data) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (!tmp.renameTo(target)) tmp.delete();
        } catch (IOException e) {
            Log.w(TAG, "not cached: " + target.getName(), e);
            return;
        }
        trimDisk();
    }

    /** Deletes the oldest files until the directory is under the limit; runs at most once every 30 s. */
    private void trimDisk() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastTrim < TRIM_EVERY_MS) return;
            lastTrim = now;
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_LIMIT) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= DISK_LIMIT) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
