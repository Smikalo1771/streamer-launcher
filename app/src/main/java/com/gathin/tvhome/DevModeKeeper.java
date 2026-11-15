package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the LG TV's Developer Mode session alive, so the magic4pc app MagicPointer needs is not uninstalled when it
 * expires. Every 12 h (checked every 30 min while this launcher runs) it asks LG how long the session has left and
 * resets it when under RESET_BELOW_H hours, the same calls the TV's Developer Mode app makes.
 *
 * The session token is the TV's /var/luna/preferences/devmode_enabled (readable over the Developer Mode SSH), pushed
 * here as files/lg_devmode_token:
 *   adb push token /sdcard/Android/data/com.filip.tvhome/files/lg_devmode_token
 * No token file: nothing happens. Results go to files/devmode.log (last 50 lines).
 */
final class DevModeKeeper {
    private DevModeKeeper() {}

    private static final String TAG = "DevModeKeeper";
    private static final String BASE = "https://developer.lge.com/secure/";
    private static final long TICK_MS = 30 * 60 * 1000L;
    private static final long CHECK_EVERY_MS = 12 * 60 * 60 * 1000L;
    private static final int RESET_BELOW_H = 240;

    private static boolean started;

    static synchronized void start(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        final Handler worker = new Handler(thread.getLooper());
        worker.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    tick(app);
                } catch (Throwable t) {
                    Log.w(TAG, "tick: " + t);
                }
                worker.postDelayed(this, TICK_MS);
            }
        }, 60 * 1000L);
    }

    private static void tick(Context context) throws Exception {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        File tokenFile = new File(dir, "lg_devmode_token");
        if (!tokenFile.isFile()) return;
        SharedPreferences prefs = context.getSharedPreferences("devmode_keeper", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("last_ok", 0) < CHECK_EVERY_MS) return;
        String token = new String(Files.readAllBytes(tokenFile.toPath()), StandardCharsets.UTF_8).trim();
        if (!token.matches("[0-9a-fA-F]{64}")) {
            log(dir, "token file is not a 64-hex token");
            prefs.edit().putLong("last_ok", now).apply();
            return;
        }
        int hours = remainingHours(get(BASE + "CheckDevModeSession.dev?sessionToken=" + token));
        if (hours < 0) {
            log(dir, "check failed, retry in 30 min");
            return;
        }
        String line = "left " + hours + " h";
        if (hours < RESET_BELOW_H) {
            get(BASE + "ResetDevModeSession.dev?sessionToken=" + token);
            int after = remainingHours(get(BASE + "CheckDevModeSession.dev?sessionToken=" + token));
            line += ", reset -> " + after + " h";
            if (after < 0) {
                log(dir, line + " (check after reset failed, retry in 30 min)");
                return;
            }
        }
        log(dir, line);
        prefs.edit().putLong("last_ok", now).apply();
    }

    /** {"result":"success","errorCode":"200","errorMsg":"557:53:44"} -> 557; -1 when not a success. */
    private static int remainingHours(String body) {
        if (body == null || !body.contains("\"success\"")) return -1;
        Matcher m = Pattern.compile("\"(\\d+):\\d\\d:\\d\\d\"").matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[4096];
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
            }
        } catch (Exception e) {
            Log.w(TAG, "http: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void log(File dir, String text) {
        Log.i(TAG, text);
        File f = new File(dir, "devmode.log");
        try {
            java.util.List<String> lines = f.isFile()
                    ? new java.util.ArrayList<>(Files.readAllLines(f.toPath())) : new java.util.ArrayList<>();
            lines.add(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + " " + text);
            while (lines.size() > 50) lines.remove(0);
            try (FileWriter w = new FileWriter(f)) {
                for (String l : lines) w.write(l + "\n");
            }
        } catch (Exception ignored) {
        }
    }
}
