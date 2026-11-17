package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Keeps the screen on while something plays sound. Some players (Nuvio 1.2.0-beta.2, 2026-11-17) do not keep the
 * screen on during a film, so after the screen-off timeout with no key pressed Android starts the screen saver,
 * which stops the film. While AudioManager reports active playback this holds a screen wake lock, which keeps the
 * screen saver away; it is let go after two quiet checks in a row. A TV switched off still puts the player to sleep
 * (that goes past wake locks), and the launcher's own music screen saver (MusicSaver) is not a system dream.
 */
final class PlaybackAwake {

    private static final String TAG = "PlaybackAwake";
    private static final long CHECK_MS = 20_000;

    private final AudioManager audio;
    private final PowerManager.WakeLock lock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int quiet;
    private boolean running;

    @SuppressWarnings("deprecation")   // SCREEN_BRIGHT_WAKE_LOCK: the only screen lock an app without a window has
    PlaybackAwake(Context context) {
        audio = context.getSystemService(AudioManager.class);
        lock = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "tvhome:playback");
        lock.setReferenceCounted(false);
    }

    void start() {
        if (running) return;
        running = true;
        handler.post(check);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(check);
        if (lock.isHeld()) lock.release();
    }

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (audio.isMusicActive()) {
                quiet = 0;
                if (!lock.isHeld()) {
                    lock.acquire();
                    Log.d(TAG, "playing: screen kept on");
                }
            } else if (lock.isHeld() && ++quiet >= 2) {
                lock.release();
                Log.d(TAG, "quiet: screen may sleep again");
            }
            handler.postDelayed(this, CHECK_MS);
        }
    };
}
