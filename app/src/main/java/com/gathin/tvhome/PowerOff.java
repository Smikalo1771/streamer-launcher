package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/**
 * Switching the TV off by itself, from HomeKeyService:
 * <ul>
 * <li>a sleep timer set by voice ("vypni o 30 minút"; "zruš časovač" cancels it);</li>
 * <li>idle_off_minutes: no key for that long while the screen is on ("Pozeráš ešte?"), except over a music app
 * playing music.</li>
 * </ul>
 * Either way a box at the bottom of the screen counts the last minutes down first, and any key cancels it (that key
 * does nothing else). Then standby, as the remote's power button: the TV follows over HDMI-CEC.
 */
final class PowerOff {
    /** The sleep timer's warning comes up this long before it switches off. */
    static final long TIMER_WARNING_MS = 60_000L;
    /** "Pozeráš ešte?" counts down this long. */
    static final long IDLE_WARNING_MS = 120_000L;
    private static final long CHECK_MS = 10_000L;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long idleLimitMs;
    private final Set<String> musicApps;
    private long lastInput = SystemClock.elapsedRealtime();
    private long timerAt;          // elapsedRealtime of the sleep timer, 0 for none
    private long warningEnd;       // elapsedRealtime the shown countdown ends, 0 when none is shown
    private boolean warningIsTimer;
    private boolean wasOn = true;
    private String front;
    private TextView box;
    private boolean running;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            checkNow();
            handler.postDelayed(this, warningEnd > 0 ? 1000 : CHECK_MS);
        }
    };

    PowerOff(AccessibilityService service, long idleLimitMs, Set<String> musicApps) {
        this.service = service;
        this.idleLimitMs = idleLimitMs;
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
        hideBox();
    }

    /** A key from the remote. True when it only cancelled a countdown and must do nothing else. */
    boolean onKey() {
        lastInput = SystemClock.elapsedRealtime();
        if (warningEnd == 0) return false;
        if (warningIsTimer) timerAt = 0;
        warningEnd = 0;
        hideBox();
        Toast.makeText(service, R.string.power_off_cancelled, Toast.LENGTH_SHORT).show();
        return true;
    }

    void onWindow(CharSequence pkg) {
        if (pkg == null || "com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
        front = pkg.toString();
    }

    /** The sleep timer from a voice command: switch off in this many minutes. */
    void setTimer(int minutes) {
        timerAt = SystemClock.elapsedRealtime() + minutes * 60_000L;
        Toast.makeText(service, service.getString(R.string.power_off_timer_set, duration(minutes * 60_000L)),
                Toast.LENGTH_LONG).show();
        handler.removeCallbacks(check);
        handler.post(check);
    }

    void cancelTimer() {
        boolean had = timerAt != 0;
        timerAt = 0;
        if (warningEnd > 0 && warningIsTimer) {
            warningEnd = 0;
            hideBox();
        }
        Toast.makeText(service, had ? R.string.power_off_timer_cancelled : R.string.power_off_no_timer,
                Toast.LENGTH_SHORT).show();
    }

    /** Pure, for tests: whether "Pozeráš ešte?" is due. */
    static boolean idleDue(long idleMs, long limitMs, boolean screenOn, boolean musicFront, boolean musicPlaying) {
        return limitMs > 0 && screenOn && idleMs >= limitMs && !(musicFront && musicPlaying);
    }

    private void checkNow() {
        long now = SystemClock.elapsedRealtime();
        PowerManager power = service.getSystemService(PowerManager.class);
        boolean on = power != null && power.isInteractive();
        if (!on) {
            // Off already (remote, timer or the TV itself): nothing left to count down.
            if (warningEnd > 0) hideBox();
            warningEnd = 0;
            timerAt = 0;
            wasOn = false;
            return;
        }
        if (!wasOn) {   // just woken: a fresh start for the idle count
            wasOn = true;
            lastInput = now;
        }
        if (warningEnd > 0) {
            if (now >= warningEnd) {
                warningEnd = 0;
                timerAt = 0;
                hideBox();
                lastInput = now;
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            } else {
                showBox(warningEnd - now);
            }
            return;
        }
        if (timerAt > 0 && now >= timerAt - TIMER_WARNING_MS) {
            warningIsTimer = true;
            warningEnd = Math.max(timerAt, now + 5_000L);
            showBox(warningEnd - now);
            return;
        }
        AudioManager audio = service.getSystemService(AudioManager.class);
        boolean playing = audio != null && audio.isMusicActive();
        boolean musicFront = front != null && musicApps.contains(front);
        if (idleDue(now - lastInput, idleLimitMs, true, musicFront, playing)) {
            warningIsTimer = false;
            warningEnd = now + IDLE_WARNING_MS;
            showBox(IDLE_WARNING_MS);
        }
    }

    private void showBox(long leftMs) {
        String time = clock(leftMs);
        String text = service.getString(warningIsTimer ? R.string.power_off_timer_warning
                : R.string.power_off_idle_warning, time) + "\n" + service.getString(R.string.power_off_any_key);
        if (box != null) {
            box.setText(text);
            return;
        }
        float dp = service.getResources().getDisplayMetrics().density;
        box = new TextView(service);
        box.setText(text);
        box.setTextColor(0xFFFFFFFF);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        box.setGravity(Gravity.CENTER);
        box.setLineSpacing(0, 1.2f);
        int pad = (int) (24 * dp);
        box.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202124);
        bg.setCornerRadius(20 * dp);
        bg.setStroke((int) (2 * dp), 0x55FFFFFF);
        box.setBackground(bg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (80 * dp);
        try {
            service.getSystemService(WindowManager.class).addView(box, lp);
        } catch (RuntimeException e) {
            box = null;
            Toast.makeText(service, text, Toast.LENGTH_LONG).show();
        }
    }

    private void hideBox() {
        if (box == null) return;
        try {
            service.getSystemService(WindowManager.class).removeView(box);
        } catch (RuntimeException ignored) {
        }
        box = null;
    }

    /** 1:59 */
    static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** "30 min" or "1 h 30 min". */
    static String duration(long ms) {
        long min = ms / 60_000L;
        if (min < 60) return min + " min";
        return (min / 60) + " h" + (min % 60 > 0 ? " " + (min % 60) + " min" : "");
    }
}
