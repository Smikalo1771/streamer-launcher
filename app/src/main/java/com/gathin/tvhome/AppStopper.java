package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * Stop app in the tile and library menus. The launcher has no system rights, so up to Android 13 this is
 * killBackgroundProcesses (KILL_BACKGROUND_PROCESSES, a normal permission): with Home in front the app is in the
 * background and its processes go, unless it keeps a foreground service. Android 14 limits that call to the
 * caller's own processes ("Invalid packageName" in logcat), so there, and whenever the process is still seen
 * running or the kill is refused, the app's App info page opens instead, where Force stop always works.
 * Where Shizuku runs, "am force-stop" goes through it instead and the app stops with one press; the first use
 * asks for Shizuku's permission, and a refusal falls back to App info.
 */
final class AppStopper {
    private AppStopper() {}

    /** Never offered for this launcher itself. */
    static boolean canStop(Context context, String pkg) {
        return pkg != null && !pkg.equals(context.getPackageName());
    }

    private static final int SHIZUKU_REQUEST = 4711;

    static void stop(Activity activity, String pkg, CharSequence label) {
        if (viaShizuku(activity, pkg, label)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            fallBack(activity, pkg, label);
            return;
        }
        ActivityManager am = activity.getSystemService(ActivityManager.class);
        try {
            am.killBackgroundProcesses(pkg);
        } catch (SecurityException e) {
            fallBack(activity, pkg, label);
            return;
        }
        // The kill is asynchronous on the system side; give it a moment before looking.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (stillRunning(am, pkg)) fallBack(activity, pkg, label);
            else Toast.makeText(activity, activity.getString(R.string.app_stopped, label), Toast.LENGTH_SHORT).show();
        }, 400);
    }

    /** True when Shizuku took the job (stopping, or asking for its permission first); false to stop without it. */
    private static boolean viaShizuku(Activity activity, String pkg, CharSequence label) {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                forceStop(activity, pkg, label);
                return true;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) return false;   // refused for good
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != SHIZUKU_REQUEST) return;
                    Shizuku.removeRequestPermissionResultListener(this);
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) forceStop(activity, pkg, label);
                    else fallBack(activity, pkg, label);
                }
            });
            Shizuku.requestPermission(SHIZUKU_REQUEST);
            return true;
        } catch (Throwable t) {   // Shizuku gone mid-call, or an API it does not have
            return false;
        }
    }

    /** "am force-stop" as Shizuku's shell user, off the main thread; App info if it fails. */
    private static void forceStop(Activity activity, String pkg, CharSequence label) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok;
            try {
                Method newProcess = Shizuku.class.getDeclaredMethod("newProcess",
                        String[].class, String[].class, String.class);
                newProcess.setAccessible(true);
                Process p = (Process) newProcess.invoke(null, new String[] {"am", "force-stop", pkg}, null, null);
                ok = p.waitFor() == 0;
            } catch (Throwable t) {
                ok = false;
            }
            boolean stopped = ok;
            main.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (stopped) {
                    Toast.makeText(activity, activity.getString(R.string.app_stopped, label), Toast.LENGTH_SHORT).show();
                } else {
                    fallBack(activity, pkg, label);
                }
            });
        }, "AppStopper").start();
    }

    /** Only proof of life counts: on stock Android a third-party caller sees just its own processes here. */
    private static boolean stillRunning(ActivityManager am, String pkg) {
        List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
        if (running == null) return false;
        for (ActivityManager.RunningAppProcessInfo p : running) {
            if (p.processName.equals(pkg) || p.processName.startsWith(pkg + ":")
                    || p.pkgList != null && Arrays.asList(p.pkgList).contains(pkg)) return true;
        }
        return false;
    }

    private static void fallBack(Activity activity, String pkg, CharSequence label) {
        Toast.makeText(activity, activity.getString(R.string.app_force_stop_hint, label), Toast.LENGTH_LONG).show();
        Intent info = SystemSettings.appInfo(activity, pkg);
        if (info == null) return;
        try {
            activity.startActivity(info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }
}
