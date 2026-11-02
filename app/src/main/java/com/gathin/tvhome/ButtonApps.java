package com.gathin.tvhome;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Remote buttons whose app is picked in Launcher settings, on top of app_keys (config.xml or a theme). Each button
 * has two picks: a press and a hold (RemoteKeys.HOLD_MS). A press pick replaces every plain entry for that keycode
 * ("191:..."), a hold pick every "hold:191:..." entry; "front:" entries stay as configured.
 * Unset keeps app_keys; {@link #NONE} makes the press or hold do nothing.
 */
final class ButtonApps {
    private ButtonApps() {}

    /** Google TV Streamer remote: the YouTube button, the Netflix button and the star button (MACRO_1). */
    static final int YOUTUBE = 190, NETFLIX = 191, STAR = 313;
    static final int[] BUTTONS = {YOUTUBE, NETFLIX, STAR};
    static final String NONE = "-";
    static final String HOLD = "hold:";

    /** The Launcher settings key for a button's press (hold false) or hold (hold true). */
    static String key(int keycode, boolean hold) {
        return hold ? "button_app_hold_" + keycode : "button_app_" + keycode;
    }

    static String key(int keycode) {
        return key(keycode, false);
    }

    /** appKeys with the picks from Launcher settings applied; appKeys itself is not changed. */
    static List<String> apply(List<String> appKeys, SharedPreferences choices) {
        List<String> out = new ArrayList<>(appKeys);
        for (int code : BUTTONS) {
            for (boolean hold : new boolean[] {false, true}) {
                String pkg = choices.getString(key(code, hold), null);
                if (pkg == null || pkg.isEmpty()) continue;
                String prefix = (hold ? HOLD : "") + code + ":";
                out.removeIf(entry -> entry != null && entry.trim().startsWith(prefix));
                if (!NONE.equals(pkg)) out.add(prefix + pkg);
            }
        }
        return out;
    }
}
