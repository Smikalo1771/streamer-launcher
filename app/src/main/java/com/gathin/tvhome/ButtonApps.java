package com.gathin.tvhome;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Remote buttons whose app is picked in Launcher settings, on top of app_keys (config.xml or a theme). The pick
 * replaces every plain entry for that keycode ("191:..."); "hold:" and "front:" entries stay as configured.
 * Unset keeps app_keys; {@link #NONE} makes the button do nothing.
 */
final class ButtonApps {
    private ButtonApps() {}

    /** Google TV Streamer remote: the Netflix button and the star button (MACRO_1). */
    static final int NETFLIX = 191, STAR = 313;
    static final String NONE = "-";

    static String key(int keycode) {
        return "button_app_" + keycode;
    }

    /** appKeys with the picks from Launcher settings applied; appKeys itself is not changed. */
    static List<String> apply(List<String> appKeys, SharedPreferences choices) {
        List<String> out = new ArrayList<>(appKeys);
        for (int code : new int[] {NETFLIX, STAR}) {
            String pkg = choices.getString(key(code), null);
            if (pkg == null || pkg.isEmpty()) continue;
            out.removeIf(entry -> entry.startsWith(code + ":"));
            if (!NONE.equals(pkg)) out.add(code + ":" + pkg);
        }
        return out;
    }
}
