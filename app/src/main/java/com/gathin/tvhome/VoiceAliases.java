package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Own names for apps in voice commands: "spusti telku" opens IPTV when "telka" is an alias for it. Picked in Launcher
 * settings and kept there; until the first change there, voice_aliases from config.xml apply. Each alias is
 * "name:package", or "name:https://..." for a web page opened in the browser ("otvor Claude" for claude.ai).
 * Voice commands read them as extra app names (VoiceMatch tolerates "telku" for "telka").
 */
final class VoiceAliases {
    private VoiceAliases() {}

    static final String KEY = "voice_aliases";

    /** The aliases as {name, package}: Launcher settings' list, or config.xml's until there is one. */
    static List<String[]> load(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(KEY, null);
        Collection<String> entries = saved != null ? saved
                : Arrays.asList(ctx.getResources().getStringArray(R.array.voice_aliases));
        return parse(entries);
    }

    /** True for an alias that opens a web page instead of an app. */
    static boolean isWeb(String target) {
        return target.startsWith("https://") || target.startsWith("http://");
    }

    static void save(Context ctx, List<String[]> aliases) {
        Set<String> out = new HashSet<>();
        for (String[] a : aliases) out.add(a[0] + ":" + a[1]);
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putStringSet(KEY, out).apply();
    }

    /** "name:package" entries as {name, package}, sorted by name; malformed ones are skipped. */
    static List<String[]> parse(Collection<String> entries) {
        List<String[]> out = new ArrayList<>();
        for (String e : entries) {
            if (e == null) continue;
            int colon = e.indexOf(':');   // names never hold one; a web address after it does
            if (colon <= 0 || colon == e.length() - 1) continue;
            String name = e.substring(0, colon).trim(), pkg = e.substring(colon + 1).trim();
            if (!name.isEmpty() && pkg.indexOf('.') > 0 && !pkg.contains(" ")) out.add(new String[] {name, pkg});
        }
        out.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        return out;
    }
}
