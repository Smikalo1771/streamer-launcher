package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Random;

/**
 * Small stars falling slowly behind Home once it is dark (config.xml starfall, 2026-11-20): from 20 min after sunset
 * to 20 min before sunrise at config.xml latitude/longitude, or 17:00-07:00 without a place. Each star drifts down
 * with a slight sway, turns slowly and twinkles. Runs only while Home is in front (start/stop from MainActivity) and
 * the hour is dark; otherwise the view is gone and costs nothing.
 */
public class StarfallView extends View {

    private static final int COUNT = 34;
    private static final int DUSK_MIN = 20;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path star = new Path();
    private final Random random = new Random();
    private final float[] x = new float[COUNT], y = new float[COUNT], size = new float[COUNT],
            speed = new float[COUNT], sway = new float[COUNT], phase = new float[COUNT], spin = new float[COUNT];
    private final int[] color = new int[COUNT];
    private boolean running;
    private boolean seeded;
    private long lastFrame;

    public StarfallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // A five-pointed star of radius 1, scaled per star when drawn.
        for (int i = 0; i < 10; i++) {
            double a = Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 0 ? 1f : 0.42f;
            float px = (float) (r * Math.cos(a)), py = (float) (-r * Math.sin(a));
            if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
        }
        star.close();
        setVisibility(GONE);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Home came to the front, or a minute passed: falls while it is dark, otherwise stays hidden. */
    void update() {
        boolean dark = isDark();
        if (dark && !running) {
            running = true;
            lastFrame = 0;
            setVisibility(VISIBLE);
            invalidate();
        } else if (!dark && running) {
            stop();
        }
    }

    /** Home left the front: no more frames until update(). */
    void stop() {
        running = false;
        setVisibility(GONE);
    }

    private boolean isDark() {
        int now = LocalTime.now().toSecondOfDay() / 60;
        int rise = 7 * 60, set = 17 * 60;
        String lat = getContext().getString(R.string.latitude);
        String lon = getContext().getString(R.string.longitude);
        if (!lat.isEmpty() && !lon.isEmpty()) {
            try {
                int[] t = Sun.riseAndSet(LocalDate.now(), Double.parseDouble(lat), Double.parseDouble(lon),
                        ZoneId.systemDefault());
                if (t != null) {
                    rise = t[0];
                    set = t[1];
                }
            } catch (NumberFormatException ignored) {
                // keep the fixed hours
            }
        }
        return now >= set + DUSK_MIN || now < rise - DUSK_MIN;
    }

    private void seed(int i, boolean anywhere) {
        float w = getWidth(), h = getHeight();
        size[i] = 3f + random.nextFloat() * 7f;                 // 3-10 px radius
        x[i] = random.nextFloat() * w;
        y[i] = anywhere ? random.nextFloat() * h : -size[i] * 2;
        speed[i] = 14f + size[i] * 3f + random.nextFloat() * 10f;   // px/s: bigger ones a bit faster, as if nearer
        sway[i] = 6f + random.nextFloat() * 18f;
        phase[i] = random.nextFloat() * 6.28f;
        spin[i] = (random.nextFloat() - 0.5f) * 40f;            // deg/s
        int warm = random.nextInt(3);
        color[i] = warm == 0 ? 0xFFFFE9A8 : warm == 1 ? 0xFFFFFFFF : 0xFFDDE7FF;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running || getWidth() == 0) return;
        if (!seeded) {
            for (int i = 0; i < COUNT; i++) seed(i, true);
            seeded = true;
        }
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float t = now / 1000f;
        for (int i = 0; i < COUNT; i++) {
            y[i] += speed[i] * dt;
            if (y[i] > getHeight() + size[i] * 2) seed(i, false);
            float sx = x[i] + (float) Math.sin(t * 0.6f + phase[i]) * sway[i];
            float twinkle = 0.55f + 0.45f * (float) Math.sin(t * 1.7f + phase[i] * 3f);
            paint.setColor(color[i]);
            paint.setAlpha((int) (40 + 150 * twinkle));
            canvas.save();
            canvas.translate(sx, y[i]);
            canvas.rotate(t * spin[i] + phase[i] * 57f);
            canvas.scale(size[i], size[i]);
            canvas.drawPath(star, paint);
            canvas.restore();
        }
        postInvalidateOnAnimation();
    }
}
