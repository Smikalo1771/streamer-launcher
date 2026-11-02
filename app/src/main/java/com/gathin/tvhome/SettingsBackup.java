package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Launcher settings (columns, wallpaper, the remote buttons' apps, open on start...) to a file and back, so a reset
 * or a second Streamer gets the same choices. The file sits in the launcher's own folder, where adb reaches it:
 * adb pull /sdcard/Android/data/com.filip.tvhome/files/launcher-settings.json   (adb push to copy it to another set).
 * Each value keeps its type: {"key": {"t": "int", "v": 5}}.
 */
final class SettingsBackup {
    private SettingsBackup() {}

    static final String FILE_NAME = "launcher-settings.json";
    /** Config's record of the theme the choices were made under: it belongs to this set, so it is neither saved nor
     *  overwritten (a copied one would hide this set's own theme change from Config.syncThemeChoices). */
    private static final String THEME_SIGNATURE_KEY = "theme_signature";

    static File file(Context ctx) {
        File dir = Config.dir(ctx);
        return dir == null ? null : new File(dir, FILE_NAME);
    }

    /** Writes every Launcher setting to the file. False when storage is missing or the write fails. */
    static boolean save(Context ctx) {
        File file = file(ctx);
        if (file == null) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        try {
            JSONObject out = new JSONObject();
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if (THEME_SIGNATURE_KEY.equals(e.getKey())) continue;
                Object v = e.getValue();
                JSONObject item = new JSONObject();
                if (v instanceof Boolean) item.put("t", "bool").put("v", v);
                else if (v instanceof Integer) item.put("t", "int").put("v", v);
                else if (v instanceof Long) item.put("t", "long").put("v", v);
                else if (v instanceof Float) item.put("t", "float").put("v", ((Float) v).doubleValue());
                else if (v instanceof String) item.put("t", "string").put("v", v);
                else if (v instanceof Set) item.put("t", "set").put("v", new JSONArray((Set<?>) v));
                else continue;
                out.put(e.getKey(), item);
            }
            byte[] bytes = out.toString(2).getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getPath() + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(bytes);
            }
            return tmp.renameTo(file);
        } catch (JSONException | IOException e) {
            return false;
        }
    }

    /** True when a backup file is there to restore. */
    static boolean exists(Context ctx) {
        File file = file(ctx);
        return file != null && file.isFile();
    }

    /**
     * Replaces the Launcher settings with the file's. The settings revision goes up, so Home rebuilds when it
     * resumes. False when there is no readable backup; the settings then stay as they were.
     */
    static boolean restore(Context ctx) {
        File file = file(ctx);
        if (file == null || !file.isFile()) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        long revision = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0);
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject json = new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
            String signature = prefs.getString(THEME_SIGNATURE_KEY, null);
            SharedPreferences.Editor edit = prefs.edit().clear();
            if (signature != null) edit.putString(THEME_SIGNATURE_KEY, signature);
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (THEME_SIGNATURE_KEY.equals(key)) continue;
                JSONObject item = json.optJSONObject(key);
                if (item == null) continue;
                switch (item.optString("t")) {
                    case "bool": edit.putBoolean(key, item.getBoolean("v")); break;
                    case "int": edit.putInt(key, item.getInt("v")); break;
                    case "long": edit.putLong(key, item.getLong("v")); break;
                    case "float": edit.putFloat(key, (float) item.getDouble("v")); break;
                    case "string": edit.putString(key, item.getString("v")); break;
                    case "set": {
                        JSONArray arr = item.getJSONArray("v");
                        Set<String> set = new HashSet<>();
                        for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                        edit.putStringSet(key, set);
                        break;
                    }
                    default: break;
                }
            }
            // Past both the old count and the restored one, so Home sees a change whichever it was built with.
            long restored = json.has(Config.SETTINGS_REVISION_KEY)
                    ? json.getJSONObject(Config.SETTINGS_REVISION_KEY).optLong("v", 0) : 0;
            edit.putLong(Config.SETTINGS_REVISION_KEY, Math.max(revision, restored) + 1);
            return edit.commit();
        } catch (JSONException | IOException | RuntimeException e) {
            return false;
        }
    }
}
