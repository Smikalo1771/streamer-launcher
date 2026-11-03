package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Set;

/**
 * music_screensaver: the photo screensaver while music plays. Music apps such as Spotify keep the screen on, so the
 * system screensaver never starts over them and the TV shows the same player screen for hours. HomeKeyService feeds
 * this every key and every window change; when a music app (music_apps) is in front, music is playing and no key
 * came for the screensaver delay (Settings > screen saver timeout, 2 min here), the launcher's clock screen with the
 * screensaver's pictures opens over it. Music keeps playing; any key goes back to the app.
 */
final class MusicSaver {
    /** How often the conditions are looked at while the screen is on. */
    static final long CHECK_MS = 15_000L;
    private static final long DEFAULT_DELAY_MS = 120_000L;

    private final Context context;
    private final Set<String> musicApps;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String front;
    private long lastInput = SystemClock.uptimeMillis();
    private boolean running;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            maybeStart();
            handler.postDelayed(this, CHECK_MS);
        }
    };

    MusicSaver(Context context, Set<String> musicApps) {
        this.context = context;
        this.musicApps = musicApps;
    }

    void start() {
        if (running) return;
        running = true;
        handler.postDelayed(check, CHECK_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(check);
    }

    /** Any key from the remote: the person is using the TV. */
    void onKey() {
        lastInput = SystemClock.uptimeMillis();
    }

    /** The app whose window came to the front (window state changes; dialogs and panels count too, harmlessly). */
    void onWindow(CharSequence pkg) {
        if (pkg == null || "com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
        String p = pkg.toString();
        if (!p.equals(front)) lastInput = SystemClock.uptimeMillis();   // a new app counts as use
        front = p;
    }

    /** True when the screensaver should come up now. Pure, for tests. */
    static boolean due(String front, Set<String> musicApps, boolean musicPlaying, boolean screenOn, long idleMs,
                       long delayMs) {
        return front != null && musicApps.contains(front) && musicPlaying && screenOn && idleMs >= delayMs;
    }

    private void maybeStart() {
        PowerManager power = context.getSystemService(PowerManager.class);
        AudioManager audio = context.getSystemService(AudioManager.class);
        boolean screenOn = power != null && power.isInteractive();
        boolean playing = audio != null && audio.isMusicActive();
        long idle = SystemClock.uptimeMillis() - lastInput;
        if (!due(front, musicApps, playing, screenOn, idle, delay())) return;
        lastInput = SystemClock.uptimeMillis();   // once per idle stretch
        RemoteKeys.launch(context, "screensaver");
    }

    /** The screensaver delay set on the TV (screen_off_timeout), at least 30 s. */
    private long delay() {
        try {
            long ms = Settings.System.getLong(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
            return Math.max(30_000L, ms);
        } catch (Settings.SettingNotFoundException e) {
            return DEFAULT_DELAY_MS;
        }
    }
}
