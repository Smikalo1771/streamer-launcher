package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the home-screen channel rows other apps publish to the TV provider: their preview channels with
 * the programs in each, and the Watch Next row.
 *
 * Without ACCESS_ALL_EPG_DATA the provider refuses any selection (SecurityException "Selection not allowed")
 * and quietly limits the result to the caller's own rows and searchable ones. So every query here passes no
 * selection and the filtering (preview type, browsable) is done in Java. Blocking: call off the UI thread.
 */
final class ChannelRepo {

    private static final String TAG = "TvHome.ChannelRepo";
    private static final float DEFAULT_ASPECT = 16f / 9f;
    /** Rows a TV could never show; a runaway provider must not make the load unbounded. */
    private static final int MAX_CHANNELS = 40;

    /** One tile in a row. */
    static final class Program {
        final long id;
        final String title;
        /** Episode title, else the short description; null when there is neither. */
        final String subtitle;
        /** poster_art_uri, else thumbnail_uri; null when the program has no picture. */
        final String imageUri;
        /** The Intent URI that opens the program in its app; null when the app gave none. */
        final String intentUri;
        final String pkg;
        /** Width over height of the picture. */
        final float aspect;

        Program(long id, String title, String subtitle, String imageUri, String intentUri, String pkg,
                float aspect) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.imageUri = imageUri;
            this.intentUri = intentUri;
            this.pkg = pkg;
            this.aspect = aspect;
        }
    }

    /** One row: a channel and its programs. */
    static final class Row {
        /** Stable across loads: package + "/" + internal_provider_id (else display_name); "watchnext" for Watch Next. */
        final String key;
        /** Owning package; "" for Watch Next. */
        final String pkg;
        final String title;
        /** Unmodifiable, never empty. */
        final List<Program> programs;

        Row(String key, String pkg, String title, List<Program> programs) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.programs = Collections.unmodifiableList(programs);
        }
    }

    private ChannelRepo() {
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission("android.permission.READ_TV_LISTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Never throws: on a refusal or any other failure it logs and returns the rows read so far. */
    static List<Row> load(Context c, int maxPerRow, boolean watchNext) {
        List<Row> rows = new ArrayList<>();
        if (maxPerRow <= 0) return rows;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (!hasPermission(app)) return rows;
        if (watchNext) {
            try {
                Row row = readWatchNext(app, maxPerRow);
                if (row != null) rows.add(row);
            } catch (RuntimeException e) {
                Log.w(TAG, "Watch Next not read", e);
            }
        }
        try {
            readChannels(app, maxPerRow, rows);
        } catch (RuntimeException e) {
            Log.w(TAG, "channels not read", e);
        }
        return rows;
    }

    private static void readChannels(Context c, int maxPerRow, List<Row> rows) {
        String[] projection = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_PACKAGE_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.Channels.COLUMN_TYPE,
                TvContract.Channels.COLUMN_BROWSABLE,
        };
        List<Object[]> channels = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.Channels.CONTENT_URI, projection,
                null, null, null)) {
            if (cur == null) return;
            int iId = cur.getColumnIndex(TvContract.Channels._ID);
            int iPkg = cur.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME);
            int iName = cur.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            int iProv = cur.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID);
            int iType = cur.getColumnIndex(TvContract.Channels.COLUMN_TYPE);
            int iBrowse = cur.getColumnIndex(TvContract.Channels.COLUMN_BROWSABLE);
            if (iId < 0 || iPkg < 0) return;
            int seen = 0, notPreview = 0, notBrowsable = 0;
            while (cur.moveToNext() && channels.size() < MAX_CHANNELS * 4) {
                seen++;
                if (iType >= 0 && !TvContract.Channels.TYPE_PREVIEW.equals(cur.getString(iType))) {
                    notPreview++;
                    continue;
                }
                // Not filtered on browsable: Google TV leaves every app channel at browsable=0 until the stock home
                // promotes it, so the filter hid them all on the Streamer. The owner hides rows from the menu instead.
                if (iBrowse >= 0 && !cur.isNull(iBrowse) && cur.getInt(iBrowse) == 0) notBrowsable++;
                channels.add(new Object[]{
                        cur.getLong(iId),
                        str(cur, iPkg),
                        iName >= 0 ? str(cur, iName) : null,
                        iProv >= 0 ? str(cur, iProv) : null,
                });
            }
            Log.i(TAG, "channels seen " + seen + ", not preview " + notPreview + ", not browsable " + notBrowsable);
        } catch (RuntimeException e) {
            // Keep the channels read before the failure.
            Log.w(TAG, "channel list cut short", e);
        }
        int taken = 0;
        for (Object[] ch : channels) {
            if (taken >= MAX_CHANNELS) break;
            long id = (Long) ch[0];
            String pkg = (String) ch[1];
            String name = (String) ch[2];
            String prov = (String) ch[3];
            if (pkg == null) pkg = "";
            try {
                List<Program> programs = readPrograms(c, TvContract.buildPreviewProgramsUriForChannel(id),
                        maxPerRow, true);
                if (programs.isEmpty()) continue;
                String key = !isEmpty(prov) ? pkg + "/" + prov : pkg + "/" + (name == null ? "" : name);
                String title = !isEmpty(name) ? name : appLabel(c, pkg);
                rows.add(new Row(key, pkg, title, programs));
                taken++;
            } catch (RuntimeException e) {
                Log.w(TAG, "programs of channel " + id + " not read", e);
            }
        }
    }

    private static Row readWatchNext(Context c, int maxPerRow) {
        // Watch Next has no browsable column, but does carry the time of the last engagement.
        List<Program> programs = readPrograms(c, TvContract.WatchNextPrograms.CONTENT_URI, maxPerRow, false);
        if (programs.isEmpty()) return null;
        return new Row("watchnext", "", c.getString(R.string.gtv_watch_next), programs);
    }

    /**
     * The programs at uri, newest engagement first for Watch Next and by weight for a channel. The provider's own
     * sorting is tried first and dropped if it refuses.
     */
    private static List<Program> readPrograms(Context c, Uri uri, int max, boolean channel) {
        List<Program> out = new ArrayList<>();
        List<long[]> engagement = new ArrayList<>();
        String[] projection = channel
                ? new String[]{
                        TvContract.PreviewPrograms._ID,
                        TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
                        TvContract.PreviewPrograms.COLUMN_TITLE,
                        TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
                        TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                        TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                        TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
                        TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                        TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
                        TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                        TvContract.PreviewPrograms.COLUMN_BROWSABLE,
                }
                : new String[]{
                        TvContract.WatchNextPrograms._ID,
                        TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                        TvContract.WatchNextPrograms.COLUMN_TITLE,
                        TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                        TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
                        TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                        TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                        TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                        TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
                        TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                        TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                };
        Cursor cur;
        try {
            cur = c.getContentResolver().query(uri, projection, null, null,
                    channel ? TvContract.PreviewPrograms.COLUMN_WEIGHT + " DESC" : null);
        } catch (RuntimeException e) {
            cur = c.getContentResolver().query(uri, projection, null, null, null);
        }
        if (cur == null) return out;
        try (Cursor cursor = cur) {
            int iId = cursor.getColumnIndex("_id");
            int iPkg = cursor.getColumnIndex("package_name");
            int iTitle = cursor.getColumnIndex("title");
            int iEpisode = cursor.getColumnIndex("episode_title");
            int iDesc = cursor.getColumnIndex("short_description");
            int iPoster = cursor.getColumnIndex("poster_art_uri");
            int iThumb = cursor.getColumnIndex("thumbnail_uri");
            int iPosterAspect = cursor.getColumnIndex("poster_art_aspect_ratio");
            int iThumbAspect = cursor.getColumnIndex("thumbnail_aspect_ratio");
            int iIntent = cursor.getColumnIndex("intent_uri");
            int iBrowse = cursor.getColumnIndex("browsable");
            int iEngaged = cursor.getColumnIndex("last_engagement_time_utc_millis");
            if (iId < 0) return out;
            // Watch Next is sorted after reading, so every row is read; a channel stops at max.
            while (cursor.moveToNext() && (!channel || out.size() < max)) {
                if (channel && iBrowse >= 0 && !cursor.isNull(iBrowse) && cursor.getInt(iBrowse) == 0) continue;
                String poster = iPoster >= 0 ? str(cursor, iPoster) : null;
                String thumb = iThumb >= 0 ? str(cursor, iThumb) : null;
                String image;
                float aspect;
                if (!isEmpty(poster)) {
                    image = poster;
                    aspect = aspectOf(cursor, iPosterAspect);
                } else if (!isEmpty(thumb)) {
                    image = thumb;
                    aspect = aspectOf(cursor, iThumbAspect);
                } else {
                    image = null;
                    aspect = DEFAULT_ASPECT;
                }
                String episode = iEpisode >= 0 ? str(cursor, iEpisode) : null;
                String desc = iDesc >= 0 ? str(cursor, iDesc) : null;
                String subtitle = !isEmpty(episode) ? episode : (!isEmpty(desc) ? desc : null);
                String title = iTitle >= 0 ? str(cursor, iTitle) : null;
                String intent = iIntent >= 0 ? str(cursor, iIntent) : null;
                long id = cursor.getLong(iId);
                out.add(new Program(id, title == null ? "" : title, subtitle, image,
                        isEmpty(intent) ? null : intent, iPkg >= 0 ? nonNull(str(cursor, iPkg)) : "", aspect));
                if (!channel) {
                    long when = iEngaged >= 0 && !cursor.isNull(iEngaged) ? cursor.getLong(iEngaged) : 0L;
                    engagement.add(new long[]{when, out.size() - 1});
                }
            }
        }
        if (!channel) {
            Collections.sort(engagement, new Comparator<long[]>() {
                @Override
                public int compare(long[] a, long[] b) {
                    return Long.compare(b[0], a[0]);
                }
            });
            List<Program> sorted = new ArrayList<>();
            for (long[] e : engagement) {
                if (sorted.size() >= max) break;
                sorted.add(out.get((int) e[1]));
            }
            return sorted;
        }
        return out;
    }

    /** Width over height for a TvContract ASPECT_RATIO_* constant; 16:9 when the column is missing or unknown. */
    private static float aspectOf(Cursor cur, int index) {
        if (index < 0 || cur.isNull(index)) return DEFAULT_ASPECT;
        switch (cur.getInt(index)) {
            case TvContract.PreviewPrograms.ASPECT_RATIO_16_9:
                return 16f / 9f;
            case TvContract.PreviewPrograms.ASPECT_RATIO_3_2:
                return 1.5f;
            case TvContract.PreviewPrograms.ASPECT_RATIO_4_3:
                return 4f / 3f;
            case TvContract.PreviewPrograms.ASPECT_RATIO_1_1:
                return 1f;
            case TvContract.PreviewPrograms.ASPECT_RATIO_2_3:
                return 2f / 3f;
            case 5:   // TvContractCompat ASPECT_RATIO_MOVIE_POSTER; not in the framework class
                return 0.69f;
            default:
                return DEFAULT_ASPECT;
        }
    }

    private static String appLabel(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // Not visible or gone: fall through to the package name.
        }
        return pkg;
    }

    private static String str(Cursor cur, int index) {
        return cur.isNull(index) ? null : cur.getString(index);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
