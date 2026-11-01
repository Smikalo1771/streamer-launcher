package com.gathin.tvhome;

import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Runs /data/local/tmp/tvguard.sh as Shizuku's shell user as soon as Shizuku is up after boot, then every
 * 10 minutes while the launcher runs (tv/tvguard.sh: Cast receiver repair, screen saver timeout).
 * MacroDroid's Device Boot trigger cannot do this on the Google TV Streamer: the set boots with its clock at a
 * fixed date until the network time arrives, so MacroDroid takes every boot for a repeat of the last one
 * ("Ignoring repeated BOOT_COMPLETED") and never fires. No script on the TV, no Shizuku or no permission: nothing
 * happens. The first time Shizuku answers without a grant, its permission dialog comes up once.
 */
final class TvGuard {
    private TvGuard() {}

    static final String SCRIPT = "/data/local/tmp/tvguard.sh";
    private static final long PERIOD_MS = 10 * 60 * 1000L;
    private static final int SHIZUKU_REQUEST = 4712;

    private static boolean started;
    private static Handler worker;
    private static boolean asked;

    /** Idempotent: HomeKeyService and MainActivity both call it, whichever comes up first wins. */
    static synchronized void start() {
        if (started) return;
        started = true;
        HandlerThread thread = new HandlerThread("TvGuard");
        thread.start();
        worker = new Handler(thread.getLooper());
        try {
            Shizuku.addBinderReceivedListenerSticky(TvGuard::onBinder, worker);
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST && grantResult == PackageManager.PERMISSION_GRANTED) runNow();
            }, worker);
        } catch (Throwable ignored) {   // no Shizuku API at all
        }
    }

    private static void onBinder() {
        try {
            if (Shizuku.isPreV11()) return;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                runNow();
            } else if (!asked && !Shizuku.shouldShowRequestPermissionRationale()) {
                asked = true;
                Shizuku.requestPermission(SHIZUKU_REQUEST);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void runNow() {
        worker.removeCallbacks(TICK);
        worker.post(TICK);
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            runScript();
            worker.postDelayed(this, PERIOD_MS);
        }
    };

    private static void runScript() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            Method newProcess = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Process p = (Process) newProcess.invoke(null,
                    new String[] {"sh", "-c", "[ -f " + SCRIPT + " ] && sh " + SCRIPT + " >/dev/null 2>&1"},
                    null, null);
            p.waitFor();
        } catch (Throwable ignored) {   // Shizuku gone mid-call: the next tick or binder tries again
        }
    }
}
