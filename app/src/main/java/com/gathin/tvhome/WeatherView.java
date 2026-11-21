package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Random;

/**
 * A moving weather scene behind Home (config.xml weather_scene, 2026-11-21), in the spirit of YoWindow: the sky follows
 * the time of day and the clouds at config.xml latitude/longitude, the sun or the moon (with its phase) travels across
 * it, clouds drift with the wind, and rain, drizzle, snow, fog or a thunderstorm play over hills with a small village.
 *
 * The current weather comes from Open-Meteo (no key) at most every 15 minutes while Home is in front, and is kept in
 * SharedPreferences so a restart shows the last known sky at once. The static part (sky, sun/moon, stars, hills,
 * village) is one 960x540 bitmap redrawn once a minute; only clouds and precipitation are drawn per frame, and only
 * while Home is resumed (update/stop from MainActivity). A file files/weather_force.txt holding a WMO code (and
 * optionally a cloud cover in %, "61 90") overrides the real weather, for testing.
 */
public class WeatherView extends View {

    static final int CLEAR = 0, CLOUDS = 1, FOG = 2, DRIZZLE = 3, RAIN = 4, SNOW = 5, STORM = 6;

    private static final long REFRESH_MS = 15 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;
    private static final int TEX_W = 960, TEX_H = 540;
    private static final int MAX_CLOUDS = 20, MAX_RAIN = 360, MAX_SNOW = 260;

    // Weather as last fetched.
    private int kind = CLOUDS;
    private float cover = 0.4f;     // 0..1
    private float precip;           // 0..1 intensity
    private float wind = 10f;       // km/h
    private float temp = Float.NaN; // °C
    @Nullable
    private Runnable onWeather;
    private long fetchedAt;
    private long lastAttempt;
    private boolean fetching;

    private boolean running;
    private boolean animate = true;
    private final int dimAlpha;
    private long lastFrame;

    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cloudPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    private Bitmap backdrop;
    private int backdropMinute = -1;
    private boolean backdropDirty = true;
    private final Bitmap[] cloudTex = new Bitmap[3];
    private int cloudTint = 0xFFFFFFFF, fogTint = 0xFFC8CCD0;

    private boolean particlesDirty = true;
    private int nClouds;
    private final float[] cX = new float[MAX_CLOUDS], cY = new float[MAX_CLOUDS], cW = new float[MAX_CLOUDS],
            cSpeed = new float[MAX_CLOUDS], cAlpha = new float[MAX_CLOUDS];
    private final int[] cVar = new int[MAX_CLOUDS];
    private final float[] fogX = new float[3];
    private int nRain;
    private final float[] rX = new float[MAX_RAIN], rY = new float[MAX_RAIN], rLen = new float[MAX_RAIN],
            rSpeed = new float[MAX_RAIN];
    private final float[] rainLines = new float[MAX_RAIN * 4];
    private int nSnow;
    private final float[] sX = new float[MAX_SNOW], sY = new float[MAX_SNOW], sSize = new float[MAX_SNOW],
            sSpeed = new float[MAX_SNOW], sPhase = new float[MAX_SNOW];
    private long nextFlash, flashAt = -100_000;

    public WeatherView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dimAlpha = Math.max(0, Math.min(90, context.getResources().getInteger(R.integer.weather_dim))) * 255 / 100;
        rainPaint.setStrokeCap(Paint.Cap.ROUND);
        load();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** reduced_motion: false draws a still scene, refreshed once a minute. */
    void setMotion(boolean on) {
        animate = on;
    }

    /** Called on the UI thread each time new weather arrives (MainActivity puts the temperature in the date line). */
    void setOnWeather(@Nullable Runnable r) {
        onWeather = r;
    }

    /** "8 °C", or "" without a reading from the last 3 hours. */
    String temperature() {
        long age = System.currentTimeMillis() - fetchedAt;
        if (Float.isNaN(temp) || age > 3 * 3600_000L || age < 0) return "";
        int t = Math.round(temp);
        return (t == 0 ? 0 : t) + " °C";   // no "-0 °C"
    }

    /** Clouds thin enough for the falling stars at night to make sense. */
    boolean starsVisible() {
        return cover < 0.6f && (kind == CLEAR || kind == CLOUDS);
    }

    /** Home came to the front, or a minute passed. */
    void update() {
        if (!running) {
            running = true;
            lastFrame = 0;
        }
        int minute = LocalTime.now().toSecondOfDay() / 60;
        if (minute != backdropMinute) backdropDirty = true;
        long now = System.currentTimeMillis();
        if (!fetching && (now - fetchedAt > REFRESH_MS || fetchedAt > now || Float.isNaN(temp)) && now - lastAttempt > RETRY_MS) fetch();
        invalidate();
    }

    /** Home left the front: no more frames until update(). */
    void stop() {
        running = false;
    }

    // ---------------------------------------------------------------- weather data

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    private void load() {
        SharedPreferences p = prefs();
        if (!p.contains("code")) return;
        setWeather(p.getInt("code", 2), p.getFloat("cover", 40f), p.getFloat("wind", 10f));
        fetchedAt = p.getLong("at", 0);
        temp = p.getFloat("temp", Float.NaN);
    }

    private void fetch() {
        fetching = true;
        lastAttempt = System.currentTimeMillis();
        final String lat = getContext().getString(R.string.latitude);
        final String lon = getContext().getString(R.string.longitude);
        final File force = new File(getContext().getExternalFilesDir(null), "weather_force.txt");
        new Thread(() -> {
            int code;
            float cc, w, tc;
            try {
                if (force.isFile()) {
                    String[] f = new String(Files.readAllBytes(force.toPath()), StandardCharsets.UTF_8).trim().split("\\s+");
                    code = Integer.parseInt(f[0]);
                    cc = f.length > 1 ? Float.parseFloat(f[1]) : -1;
                    w = f.length > 2 ? Float.parseFloat(f[2]) : 15;
                    tc = f.length > 3 ? Float.parseFloat(f[3]) : temp;
                } else {
                    JSONObject cur = new JSONObject(get(String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                                    + "&current=weather_code,cloud_cover,wind_speed_10m,temperature_2m&timezone=auto", lat, lon)))
                            .getJSONObject("current");
                    code = cur.getInt("weather_code");
                    cc = (float) cur.optDouble("cloud_cover", -1);
                    w = (float) cur.optDouble("wind_speed_10m", 10);
                    tc = (float) cur.optDouble("temperature_2m", Double.NaN);
                }
            } catch (Exception e) {
                post(() -> fetching = false);
                return;
            }
            final int fc = code;
            final float fcc = cc, fw = w, ftc = tc;
            post(() -> {
                fetching = false;
                fetchedAt = System.currentTimeMillis();
                prefs().edit().putInt("code", fc).putFloat("cover", fcc).putFloat("wind", fw)
                        .putFloat("temp", ftc).putLong("at", fetchedAt).apply();
                temp = ftc;
                setWeather(fc, fcc, fw);
                invalidate();
                if (onWeather != null) onWeather.run();
            });
        }, "weather").start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather code, cloud cover in % (negative: unknown) and wind in km/h. */
    private void setWeather(int code, float coverPct, float windKmh) {
        int k;
        float p = 0f, minCover;
        switch (code) {
            case 0: k = CLEAR; minCover = 0f; break;
            case 1: k = CLOUDS; minCover = 0.1f; break;
            case 2: k = CLOUDS; minCover = 0.4f; break;
            case 3: k = CLOUDS; minCover = 0.9f; break;
            case 45: case 48: k = FOG; minCover = 0.6f; break;
            case 51: p = 0.15f; k = DRIZZLE; minCover = 0.85f; break;
            case 53: case 56: p = 0.25f; k = DRIZZLE; minCover = 0.85f; break;
            case 55: case 57: p = 0.4f; k = DRIZZLE; minCover = 0.85f; break;
            case 61: p = 0.3f; k = RAIN; minCover = 0.85f; break;
            case 80: p = 0.35f; k = RAIN; minCover = 0.7f; break;
            case 63: case 66: p = 0.6f; k = RAIN; minCover = 0.9f; break;
            case 81: p = 0.65f; k = RAIN; minCover = 0.8f; break;
            case 65: case 67: case 82: p = 1f; k = RAIN; minCover = 0.95f; break;
            case 71: case 77: p = 0.3f; k = SNOW; minCover = 0.85f; break;
            case 85: p = 0.4f; k = SNOW; minCover = 0.8f; break;
            case 73: p = 0.6f; k = SNOW; minCover = 0.9f; break;
            case 75: case 86: p = 1f; k = SNOW; minCover = 0.95f; break;
            case 95: p = 0.8f; k = STORM; minCover = 0.95f; break;
            case 96: case 99: p = 1f; k = STORM; minCover = 1f; break;
            default: k = CLOUDS; minCover = 0.5f; break;
        }
        float c = coverPct < 0 ? Math.max(minCover, k == CLEAR ? 0.05f : 0.5f) : Math.max(minCover, coverPct / 100f);
        if (k == CLEAR) c = Math.min(c, 0.15f);
        kind = k;
        cover = Math.min(1f, c);
        precip = p;
        wind = Math.max(0f, windKmh);
        backdropDirty = true;
        particlesDirty = true;
    }

    // ---------------------------------------------------------------- time of day

    private int[] sunTimes(LocalDate date) {
        try {
            int[] t = Sun.riseAndSet(date, Double.parseDouble(getContext().getString(R.string.latitude)),
                    Double.parseDouble(getContext().getString(R.string.longitude)), ZoneId.systemDefault());
            if (t != null) return t;
        } catch (NumberFormatException ignored) {
            // fixed hours below
        }
        return new int[]{7 * 60, 17 * 60};
    }

    /** 1 in full daylight, 0 at night, ramps across dawn and dusk. */
    static float dayness(int m, int rise, int set) {
        if (m < rise - 30 || m > set + 30) return 0f;
        if (m < rise + 45) return (m - (rise - 30)) / 75f;
        if (m > set - 45) return ((set + 30) - m) / 75f;
        return 1f;
    }

    /** 1 right at sunrise or sunset, fading over 50 minutes either side. */
    static float duskness(int m, int rise, int set) {
        float a = 1f - Math.abs(m - rise) / 50f, b = 1f - Math.abs(m - set) / 50f;
        return clamp(Math.max(a, b));
    }

    /** 0 new moon, 0.5 full moon. */
    static double moonPhase(long epochMillis) {
        double days = (epochMillis - 947182440000L) / 86_400_000d;   // new moon 2000-01-06 18:14 UTC
        double p = days / 29.530588853 % 1.0;
        return p < 0 ? p + 1 : p;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    static int lerp(int a, int b, float t) {
        t = clamp(t);
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private float overcast() {
        float oc = clamp((cover - 0.35f) / 0.6f);
        if (kind >= DRIZZLE) oc = Math.max(oc, 0.85f);
        if (kind == STORM) oc = 1f;
        return oc;
    }

    // ---------------------------------------------------------------- static backdrop

    /** Height of hill layer {@code layer} (0 far, 1 middle, 2 near) at x, in texture pixels. */
    private static float hill(int layer, float x) {
        float u = x / TEX_W;
        switch (layer) {
            case 0:
                return TEX_H * (0.70f - 0.045f * (float) Math.sin(u * 5.1 + 0.6) - 0.025f * (float) Math.sin(u * 11.3 + 2.1)
                        - 0.012f * (float) Math.sin(u * 27 + 1));
            case 1:
                return TEX_H * (0.79f - 0.030f * (float) Math.sin(u * 3.7 + 2.4) - 0.015f * (float) Math.sin(u * 9.4 + 0.3)
                        - 0.006f * (float) Math.sin(u * 31 + 4));
            default:
                return TEX_H * (0.885f - 0.022f * (float) Math.sin(u * 2.6 + 4.2) - 0.010f * (float) Math.sin(u * 7.9 + 1.7));
        }
    }

    private void renderBackdrop() {
        LocalDate today = LocalDate.now();
        int m = LocalTime.now().toSecondOfDay() / 60;
        backdropMinute = m;
        backdropDirty = false;
        int[] sun = sunTimes(today);
        int rise = sun[0], set = sun[1];
        float d = dayness(m, rise, set);
        float oc = overcast();
        float kk = duskness(m, rise, set) * (1f - 0.75f * oc);
        boolean fog = kind == FOG;

        int top = lerp(lerp(0xFF040815, 0xFF0A0C11, oc), lerp(0xFF2B66A8, 0xFF5B636D, oc), d);
        int hor = lerp(lerp(0xFF16233F, 0xFF1B1E25, oc), lerp(0xFF8CBBE0, 0xFF9AA1A8, oc), d);
        if (kind == STORM) {
            top = lerp(top, 0xFF101318, 0.5f);
            hor = lerp(hor, 0xFF2A2E35, 0.4f);
        }
        hor = lerp(hor, 0xFFE58A4E, kk);
        top = lerp(top, 0xFF34406E, kk * 0.5f);
        if (fog) hor = lerp(hor, lerp(0xFF2A2D33, 0xFFB0B4B8, d), 0.6f);
        int mid = lerp(top, hor, 0.55f);

        // Cloud and fog colours follow the sky.
        int dayCloud = kind == STORM ? 0xFF6A7078 : lerp(0xFFFFFFFF, 0xFFA3AAB2, oc);
        int nightCloud = lerp(0xFF3A4458, 0xFF23272F, oc);
        cloudTint = lerp(lerp(nightCloud, dayCloud, d), 0xFFF2A27A, kk * 0.55f);
        fogTint = lerp(0xFF34383F, 0xFFD0D4D8, d);

        if (backdrop == null) backdrop = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(backdrop);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setShader(new LinearGradient(0, 0, 0, TEX_H * 0.8f, new int[]{top, mid, hor},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, TEX_W, TEX_H, paint);
        paint.setShader(null);

        // Stars, on clear nights.
        float starA = (1f - oc) * clamp(1f - 2f * d) * (fog ? 0.3f : 1f);
        if (starA > 0.02f) {
            Random r = new Random(7);
            for (int i = 0; i < 110; i++) {
                float sx = r.nextFloat() * TEX_W, sy = r.nextFloat() * TEX_H * 0.62f;
                float br = 0.35f + r.nextFloat() * 0.65f;
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha((int) (255 * starA * br * (1f - sy / (TEX_H * 0.75f))));
                c.drawCircle(sx, sy, 0.5f + r.nextFloat() * 1.1f, paint);
            }
        }

        // The sun by day, low and orange near the horizon.
        if (d > 0f) {
            float t = clamp((m - rise) / (float) (set - rise));
            float x = TEX_W * (0.08f + 0.84f * t);
            float y = TEX_H * 0.74f - (float) Math.sin(Math.PI * t) * TEX_H * 0.42f;
            float vis = (1f - oc * 0.92f) * clamp(d * 1.6f) * (fog ? 0.5f : 1f);
            int glow = lerp(0xFFFFE7B0, 0xFFFF9A50, kk);
            paint.setShader(new RadialGradient(x, y, TEX_H * 0.32f,
                    new int[]{((int) (150 * vis) << 24) | (glow & 0xFFFFFF), glow & 0xFFFFFF},
                    null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, TEX_W, TEX_H, paint);
            paint.setShader(null);
            paint.setColor(lerp(0xFFFFF6DC, 0xFFFFB070, kk));
            paint.setAlpha((int) (255 * clamp(vis * 1.2f - oc * 0.3f)));
            c.drawCircle(x, y, TEX_H * 0.032f, paint);
        }

        // The moon at night, with today's phase.
        if (d < 1f) {
            int nightLen = rise + 1440 - set;
            float tn = ((m - set + 1440) % 1440) / (float) nightLen;
            double ph = moonPhase(System.currentTimeMillis());
            float vis = (1f - d) * (1f - oc * 0.95f) * (fog ? 0.4f : 1f);
            if (tn <= 1f && vis > 0.03f && Math.abs(ph - 0.5) < 0.47) {
                float x = TEX_W * (0.1f + 0.8f * tn);
                float y = TEX_H * 0.70f - (float) Math.sin(Math.PI * tn) * TEX_H * 0.48f;
                float r = TEX_H * 0.03f;
                paint.setShader(new RadialGradient(x, y, r * 6,
                        new int[]{((int) (70 * vis * (1 - Math.abs(ph - 0.5) * 2)) << 24) | 0xC8D4F0, 0x00C8D4F0},
                        null, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, r * 6, paint);
                paint.setShader(null);
                int save = c.save();
                Path disc = new Path();
                disc.addCircle(x, y, r, Path.Direction.CW);
                c.clipPath(disc);
                paint.setColor(0xFFEDEBDF);
                paint.setAlpha((int) (255 * vis));
                c.drawCircle(x, y, r, paint);
                // The shadow: a sky-coloured disc sliding across, from the left while waxing, the right while waning.
                float off = (float) (ph < 0.5 ? -r * 4 * ph : r * 4 * (1 - ph));
                paint.setColor(lerp(top, mid, 0.5f));
                paint.setAlpha((int) (235 * vis));
                c.drawCircle(x + off, y, r * 1.02f, paint);
                c.restoreToCount(save);
            }
        }

        // Hills, far to near, hazier in the distance.
        boolean snowy = kind == SNOW;
        int[] dayHill = {0xFF5E7A80, 0xFF4E5A3A, 0xFF353D27};
        int[] nightHill = {0xFF0E1522, 0xFF0A0F18, 0xFF06090F};
        float[] haze = fog ? new float[]{0.75f, 0.5f, 0.25f} : new float[]{0.4f, 0.15f, 0.04f};
        int[] hillColor = new int[3];
        for (int layer = 0; layer < 3; layer++) {
            int day = dayHill[layer];
            if (snowy && layer > 0) day = lerp(day, 0xFFD5DCE3, 0.75f);
            int col = lerp(nightHill[layer], lerp(day, 0xFF7B828A, oc * 0.35f), d);
            col = lerp(col, 0xFF3A2A35, kk * 0.3f);
            col = lerp(col, hor, haze[layer]);
            hillColor[layer] = col;
            Path p = new Path();
            p.moveTo(0, TEX_H);
            for (int x = 0; x <= TEX_W; x += 6) p.lineTo(x, hill(layer, x));
            p.lineTo(TEX_W, TEX_H);
            p.close();
            paint.setColor(col);
            c.drawPath(p, paint);
            if (layer == 1) drawVillage(c, col, d, fog);
        }
        // A little shading so the foreground is not flat.
        paint.setShader(new LinearGradient(0, TEX_H * 0.85f, 0, TEX_H, 0x00000000, 0x55000000, Shader.TileMode.CLAMP));
        c.drawRect(0, TEX_H * 0.85f, TEX_W, TEX_H, paint);
        paint.setShader(null);
    }

    /** Houses and a church on the middle hills; windows light up after dusk. */
    private void drawVillage(Canvas c, int hillCol, float d, boolean fog) {
        int col = lerp(hillCol, 0xFF000000, 0.25f);
        Random r = new Random(LocalDate.now().toEpochDay());
        Random shape = new Random(11);
        float lightA = clamp(1f - d / 0.35f) * (fog ? 0.6f : 1f);
        Path p = new Path();
        float x = TEX_W * 0.58f;
        int i = 0;
        while (x < TEX_W * 0.86f) {
            float w = 14 + shape.nextFloat() * 10, h = 9 + shape.nextFloat() * 6;
            float base = Math.max(hill(1, x), hill(1, x + w)) + 2;
            paint.setColor(col);
            if (i == 4) {
                // The church: nave and a tower with a spire.
                float tw = 8;
                c.drawRect(x, base - 16, x + 22, base, paint);
                p.reset();
                p.moveTo(x - 1, base - 16);
                p.lineTo(x + 11, base - 24);
                p.lineTo(x + 23, base - 16);
                p.close();
                c.drawPath(p, paint);
                c.drawRect(x + 22, base - 40, x + 22 + tw, base, paint);
                p.reset();
                p.moveTo(x + 21, base - 40);
                p.lineTo(x + 22 + tw / 2, base - 56);
                p.lineTo(x + 23 + tw, base - 40);
                p.close();
                c.drawPath(p, paint);
                if (lightA > 0) {
                    paint.setColor(0xFFFFC870);
                    paint.setAlpha((int) (200 * lightA));
                    c.drawRect(x + 24.5f, base - 32, x + 27.5f, base - 27, paint);
                }
                x += 22 + tw + 8;
            } else {
                c.drawRect(x, base - h, x + w, base, paint);
                p.reset();
                p.moveTo(x - 2, base - h);
                p.lineTo(x + w / 2, base - h - 7 - shape.nextFloat() * 3);
                p.lineTo(x + w + 2, base - h);
                p.close();
                c.drawPath(p, paint);
                if (lightA > 0 && r.nextFloat() < 0.7f) {
                    paint.setColor(0xFFFFC870);
                    paint.setAlpha((int) (230 * lightA));
                    c.drawRect(x + 3, base - h + 3, x + 6, base - h + 6, paint);
                    if (w > 19 && r.nextBoolean()) c.drawRect(x + w - 6, base - h + 3, x + w - 3, base - h + 6, paint);
                }
                x += w + 4 + shape.nextFloat() * 12;
            }
            i++;
        }
    }

    // ---------------------------------------------------------------- moving parts

    private void buildCloudTextures() {
        for (int v = 0; v < 3; v++) {
            Bitmap b = Bitmap.createBitmap(512, 220, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Random r = new Random(100 + v);
            for (int j = 0; j < 18; j++) {
                float cx = 70 + r.nextFloat() * 372;
                float centre = 1f - Math.abs(cx - 256) / 256f;
                float rad = 34 + r.nextFloat() * 30 + 46 * centre;
                float cy = 178 - rad * (0.55f + r.nextFloat() * 0.35f);
                p.setShader(new RadialGradient(cx, cy, rad, new int[]{0xD8FFFFFF, 0x80FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rad, p);
            }
            // Darker bellies.
            p.setShader(new LinearGradient(0, 60, 0, 200, 0x00000000, 0x50000000, Shader.TileMode.CLAMP));
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            c.drawRect(0, 0, 512, 220, p);
            cloudTex[v] = b;
        }
    }

    private void seedParticles(int w, int h) {
        particlesDirty = false;
        float oc = overcast();
        nClouds = cover < 0.04f ? 0 : Math.min(MAX_CLOUDS, Math.round(2 + cover * 16));
        float[] scale = {0.55f, 0.85f, 1.25f}, spd = {0.45f, 0.75f, 1.2f};
        for (int i = 0; i < nClouds; i++) {
            int layer = i * 3 / Math.max(1, nClouds);
            cW[i] = w * (0.22f + random.nextFloat() * 0.2f) * scale[layer] * (1f + oc * 0.4f);
            cX[i] = random.nextFloat() * (w + cW[i]) - cW[i];
            cY[i] = h * (random.nextFloat() * (0.18f + 0.22f * oc) - 0.06f * layer);
            cSpeed[i] = (5f + wind * 0.7f) * spd[layer] * (0.8f + random.nextFloat() * 0.4f);
            cAlpha[i] = Math.min(1f, 0.55f + random.nextFloat() * 0.3f + oc * 0.3f);
            cVar[i] = random.nextInt(3);
        }
        for (int i = 0; i < 3; i++) fogX[i] = -random.nextFloat() * w * 0.6f;

        boolean rain = kind == RAIN || kind == DRIZZLE || kind == STORM;
        nRain = rain ? Math.min(MAX_RAIN, Math.round((kind == DRIZZLE ? 60 : 90) + precip * 260)) : 0;
        for (int i = 0; i < nRain; i++) seedRain(i, w, h, true);
        nSnow = kind == SNOW ? Math.min(MAX_SNOW, Math.round(50 + precip * 200)) : 0;
        for (int i = 0; i < nSnow; i++) seedSnow(i, w, h, true);
        nextFlash = SystemClock.uptimeMillis() + 3000;
    }

    private void seedRain(int i, int w, int h, boolean anywhere) {
        boolean drizzle = kind == DRIZZLE;
        rLen[i] = drizzle ? 10 + random.nextFloat() * 12 : 26 + random.nextFloat() * 34;
        rSpeed[i] = drizzle ? 600 + random.nextFloat() * 300 : 1300 + random.nextFloat() * 700;
        rX[i] = random.nextFloat() * w * 1.2f - w * 0.2f;
        rY[i] = anywhere ? random.nextFloat() * h : -rLen[i] - random.nextFloat() * 100;
    }

    private void seedSnow(int i, int w, int h, boolean anywhere) {
        sSize[i] = 1.5f + random.nextFloat() * 4.5f;
        sSpeed[i] = 35 + sSize[i] * 14 + random.nextFloat() * 25;
        sX[i] = random.nextFloat() * w;
        sY[i] = anywhere ? random.nextFloat() * h : -sSize[i] * 2;
        sPhase[i] = random.nextFloat() * 6.28f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        particlesDirty = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        if (backdropDirty || backdrop == null) renderBackdrop();
        if (cloudTex[0] == null) buildCloudTextures();
        if (particlesDirty) seedParticles(w, h);

        dst.set(0, 0, w, h);
        canvas.drawBitmap(backdrop, null, dst, bitmapPaint);

        long now = SystemClock.uptimeMillis();
        float dt = !animate || !running || lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float t = now / 1000f;

        // Clouds.
        cloudPaint.setColorFilter(new PorterDuffColorFilter(cloudTint, PorterDuff.Mode.MULTIPLY));
        for (int i = 0; i < nClouds; i++) {
            cX[i] += cSpeed[i] * dt;
            if (cX[i] > w) {
                cX[i] = -cW[i];
                cY[i] = h * (random.nextFloat() * (0.18f + 0.22f * overcast()));
            }
            float ch = cW[i] * 220f / 512f;
            dst.set(cX[i], cY[i], cX[i] + cW[i], cY[i] + ch);
            cloudPaint.setAlpha((int) (255 * cAlpha[i]));
            canvas.drawBitmap(cloudTex[cVar[i]], null, dst, cloudPaint);
        }

        // Fog banks drifting over the hills.
        if (kind == FOG) {
            cloudPaint.setColorFilter(new PorterDuffColorFilter(fogTint, PorterDuff.Mode.MULTIPLY));
            for (int i = 0; i < 3; i++) {
                float bw = w * 1.7f;
                fogX[i] += (5f + i * 4f) * dt;
                if (fogX[i] > w) fogX[i] = -bw;
                float y = h * (0.48f + i * 0.11f);
                dst.set(fogX[i], y, fogX[i] + bw, y + h * 0.3f);
                cloudPaint.setAlpha(150);
                canvas.drawBitmap(cloudTex[i], null, dst, cloudPaint);
                // The same bank once more, a screen width behind, so the screen is never bare.
                dst.offset(-bw * 0.85f, 0);
                canvas.drawBitmap(cloudTex[(i + 1) % 3], null, dst, cloudPaint);
            }
        }

        // Rain and drizzle.
        if (nRain > 0) {
            float slant = Math.min(0.45f, wind / 70f);
            rainPaint.setStrokeWidth(kind == DRIZZLE ? 1.5f : 2f);
            rainPaint.setColor(kind == DRIZZLE ? 0x70D0DCE8 : 0x88C8D6E8);
            for (int i = 0; i < nRain; i++) {
                float dy = rSpeed[i] * dt;
                rY[i] += dy;
                rX[i] += dy * slant;
                if (rY[i] > h) seedRain(i, w, h, false);
                int k = i * 4;
                rainLines[k] = rX[i];
                rainLines[k + 1] = rY[i];
                rainLines[k + 2] = rX[i] + rLen[i] * slant;
                rainLines[k + 3] = rY[i] + rLen[i];
            }
            canvas.drawLines(rainLines, 0, nRain * 4, rainPaint);
        }

        // Snow.
        if (nSnow > 0) {
            float drift = wind * 0.8f;
            snowPaint.setColor(0xFFF4F7FA);
            for (int i = 0; i < nSnow; i++) {
                sY[i] += sSpeed[i] * dt;
                sX[i] += drift * dt;
                if (sY[i] > h + sSize[i] * 2) seedSnow(i, w, h, false);
                if (sX[i] > w + 10) sX[i] -= w + 20;
                float x = sX[i] + (float) Math.sin(t * 0.9f + sPhase[i]) * (8 + sSize[i] * 3);
                snowPaint.setAlpha((int) (120 + sSize[i] * 22));
                canvas.drawCircle(x, sY[i], sSize[i], snowPaint);
            }
        }

        // Lightning: a soft flash, a pause and a fainter second one.
        if (kind == STORM && animate && running) {
            if (now > nextFlash) {
                flashAt = now;
                nextFlash = now + 7000 + random.nextInt(15000);
            }
            long since = now - flashAt;
            float a = since < 110 ? 0.30f : since < 200 ? 0f : since < 520 ? 0.22f * (1f - (since - 200) / 320f) : 0f;
            if (a > 0) canvas.drawColor(((int) (255 * a) << 24) | 0xE8ECFF);
        }

        if (dimAlpha > 0) canvas.drawColor(dimAlpha << 24);

        if (running && animate) {
            if (nRain > 0 || nSnow > 0 || kind == STORM) postInvalidateOnAnimation();
            else postInvalidateDelayed(33);   // clouds alone are smooth at 30 fps
        }
    }
}
