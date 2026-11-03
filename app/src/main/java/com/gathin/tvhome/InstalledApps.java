package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The apps a person can open, as {label, package} pairs without this launcher: for pickers and voice commands. */
final class InstalledApps {
    private InstalledApps() {}

    /** Apps with a TV entry point, sorted by name (the remote button and Open on start pickers). */
    static List<String[]> tv(Context ctx) {
        List<String[]> apps = collect(ctx, Intent.CATEGORY_LEANBACK_LAUNCHER);
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return apps;
    }

    /** Apps with a TV or a phone entry point, TV first (what a voice command can name). */
    static List<String[]> all(Context ctx) {
        return collect(ctx, Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER);
    }

    /** The name an app shows, or its package when it is gone. */
    static String label(List<String[]> apps, String pkg) {
        for (String[] a : apps) if (a[1].equals(pkg)) return a[0];
        return pkg;
    }

    private static List<String[]> collect(Context ctx, String... categories) {
        PackageManager pm = ctx.getPackageManager();
        List<String[]> apps = new ArrayList<>();
        for (String category : categories) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName())) continue;
                boolean seen = false;
                for (String[] a : apps) seen |= a[1].equals(pkg);
                if (!seen) apps.add(new String[] {r.loadLabel(pm).toString(), pkg});
            }
        }
        return apps;
    }
}
