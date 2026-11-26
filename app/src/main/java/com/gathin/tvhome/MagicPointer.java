package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONObject;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * magic_pointer: the LG TV's Magic Remote drives this player while the TV runs the magic4pc webOS app, which shows
 * this player's HDMI input full screen with the webOS pointer drawn over it and streams the remote over UDP
 * (github.com/Wouterdek/magic4pc protocol: the TV advertises on 42830, we subscribe on its port 42831).
 *
 * The TV draws the pointer itself, so nothing is drawn here. A wheel press becomes a touch at the pointer (held and
 * moved, it drags), a wheel notch is an Up/Down press, and the keys the webOS app now catches (arrows, OK, Back, playback, colours,
 * digits) are injected as this player's own keys. Injection goes through Shizuku (shell uid); without Shizuku
 * nothing happens.
 */
final class MagicPointer {

    private static final String TAG = "MagicPointer";
    private static final int AD_PORT = 42830;
    private static final int SUB_PORT = 42831;
    /** webOS UI size: coordinates from the TV are in this space. */
    private static final float TV_W = 1920f, TV_H = 1080f;
    private static final String PREFS = "magic_pointer";

    private final Context context;
    private volatile boolean running;
    private Thread thread;

    private Object inputManager;
    private Method inject;
    private int screenW = 1920, screenH = 1080;

    private boolean touching;
    private long touchDownTime;
    private float lastX, lastY;
    private long keyDownTime;
    private int repeatKey = -1, repeatCount;
    /** Picks Gboard or the touch keyboard (KeyboardSwitch); null when the service has none. */
    volatile KeyboardSwitch keyboard;
    /**
     * Sees each remote key before it is injected and returns true to consume it. Injected keys skip accessibility
     * key filtering, so without this the service's app_keys (colour buttons) and KeyboardSwitch never saw them.
     */
    volatile java.util.function.Predicate<KeyEvent> keyHook;
    /** Presses clickable nodes through accessibility (PointerClick); null when the service has none. */
    volatile PointerClick clicker;
    /** The node under the current press, clicked on release; null when the press is a touch. */
    private android.view.accessibility.AccessibilityNodeInfo pressNode;
    private long pressNodeTime;
    /** The press went to a keyboard switch: its release is dropped too. */
    private boolean pressSpent;
    /**
     * Focus was last moved by the wheel or an arrow key, with the pointer here: a wheel press before the pointer
     * moves NAV_SLOP away is OK on the focused item, not a touch wherever the pointer happens to be.
     */
    private boolean navigating;
    private float navX, navY;
    private boolean centerDown;
    private long centerDownTime;
    /**
     * Edge scroll: the pointer resting this close to the left or right screen edge for EDGE_DWELL_MS scrolls the row
     * under it that way, again every EDGE_REPEAT_MS while it stays.
     */
    private static final float EDGE = 40f;
    private static final long EDGE_DWELL_MS = 250, EDGE_REPEAT_MS = 800;
    private int edgeSide;
    private long edgeSince, lastEdgeScroll;
    private static final float NAV_SLOP = 160f;
    /**
     * A held press drags by how far the pointer moved since it went down: the sensor stream and the press positions
     * differ by an offset, so following the sensor stream as such made every press start with a jump, which apps
     * (the IPTV WebView) took for a drag, not a tap. Moves within TAP_SLOP of the press are dropped for the same
     * reason: hand tremor.
     */
    private static final float TAP_SLOP = 15f;
    private float offX, offY, downX, downY, touchX, touchY;
    private boolean dragging;
    private float lastSensorX, lastSensorY;   // the hand drifts ~100 px while scrolling and pressing the wheel

    /** pointer_button_rows: package -> its button discs {x, y, r} in Left/Right order. */
    private final java.util.Map<String, int[][]> buttonRows = new java.util.HashMap<>();
    /** A button walk is running: presses meanwhile are dropped, so two walks never fight over the highlight. */
    private final java.util.concurrent.atomic.AtomicBoolean walking = new java.util.concurrent.atomic.AtomicBoolean();
    /** pointer_ok_zones: package -> x right of which a press is OK, not a touch. */
    private final java.util.Map<String, Integer> okZones = new java.util.HashMap<>();

    MagicPointer(Context context) {
        this.context = context.getApplicationContext();
        for (String entry : this.context.getResources().getStringArray(R.array.pointer_button_rows)) {
            int bar = entry.indexOf('|');
            if (bar <= 0) continue;
            try {
                String[] discs = entry.substring(bar + 1).split(";");
                int[][] row = new int[discs.length][];
                for (int i = 0; i < discs.length; i++) {
                    String[] v = discs[i].trim().split(",");
                    row[i] = new int[] {Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()),
                            Integer.parseInt(v[2].trim())};
                }
                buttonRows.put(entry.substring(0, bar).trim(), row);
            } catch (RuntimeException ignored) {
                // a malformed entry is skipped
            }
        }
        for (String entry : this.context.getResources().getStringArray(R.array.pointer_ok_zones)) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0) continue;
            try {
                okZones.put(entry.substring(0, colon).trim(), Integer.parseInt(entry.substring(colon + 1).trim()));
            } catch (NumberFormatException ignored) {
                // a malformed entry is skipped
            }
        }
        Display d = this.context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point size = new Point();
        d.getRealSize(size);
        screenW = Math.max(size.x, size.y);
        screenH = Math.min(size.x, size.y);
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "MagicPointer");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tv = prefs.getString("tv", "");
        DatagramSocket socket = null;
        byte[] buf = new byte[4096];
        long lastFromTv = 0, lastSub = 0, lastKeepalive = 0;
        while (running) {
            try {
                if (socket == null) {
                    socket = new DatagramSocket(null);
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.bind(new InetSocketAddress(AD_PORT));
                    socket.setSoTimeout(500);
                }
                long now = SystemClock.uptimeMillis();
                boolean connected = now - lastFromTv < 3000;
                if (!tv.isEmpty()) {
                    if (!connected && now - lastSub > 2000) {
                        send(socket, tv, "{\"t\":\"sub_sensor\",\"updateFreq\":30,\"filter\":[\"coordinate\"]}");
                        lastSub = now;
                    } else if (connected && now - lastKeepalive > 1000) {
                        send(socket, tv, "{}");
                        lastKeepalive = now;
                    }
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    if (!connected && touching) touchUp();
                    continue;
                }
                String from = p.getAddress().getHostAddress();
                JSONObject msg = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                String t = msg.optString("t");
                if ("magic4pc_ad".equals(t)) {
                    if (!from.equals(tv)) {
                        tv = from;
                        prefs.edit().putString("tv", tv).apply();
                        lastSub = 0;
                    }
                    continue;
                }
                if (!from.equals(tv)) continue;
                lastFromTv = SystemClock.uptimeMillis();
                switch (t) {
                    case "remote_update": onCoordinate(msg.optString("payload")); break;
                    case "mouse": onMouse(msg.getJSONObject("mouse")); break;
                    case "wheel": onWheel(msg.getJSONObject("wheel")); break;
                    case "input": onKey(msg.getJSONObject("parameters")); break;
                    default: break;
                }
            } catch (Exception e) {
                Log.w(TAG, "loop: " + e);
                if (socket != null && !socket.isClosed() && !(e instanceof org.json.JSONException)) {
                    socket.close();
                    socket = null;
                }
                SystemClock.sleep(1000);
            }
        }
        if (socket != null) socket.close();
    }

    private static void send(DatagramSocket s, String host, String text) throws Exception {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        s.send(new DatagramPacket(b, b.length, InetAddress.getByName(host), SUB_PORT));
    }

    // --- events from the TV ---

    private void onCoordinate(String payload) {
        if (payload.isEmpty()) return;
        ByteBuffer b = ByteBuffer.wrap(Base64.decode(payload, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 8) return;
        lastX = lastSensorX = mapX(b.getInt());
        lastY = lastSensorY = mapY(b.getInt());
        if (touching) dragTo(lastX + offX, lastY + offY);
        else edgeScroll();
    }

    private void onMouse(JSONObject m) {
        float x = mapX(m.optInt("x")), y = mapY(m.optInt("y"));
        lastX = x;
        lastY = y;
        String type = m.optString("type");
        if (navigating && Math.hypot(x - navX, y - navY) > NAV_SLOP) navigating = false;
        if ("mousedown".equals(type)) {
            if (touching) touchUp();
            if (navigating || inOkZone(x, y)) {
                centerDown = true;
                centerDownTime = SystemClock.uptimeMillis();
                injectKey(new KeyEvent(centerDownTime, centerDownTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                        0, 0, virtualKeyboard(), 0, 0, InputDevice.SOURCE_DPAD));
                return;
            }
            KeyboardSwitch k = keyboard;
            pressSpent = k != null && k.onPointerDown(x, y);
            if (pressSpent) return;
            int[][] row = buttonRowAt(x, y);
            int target = row == null ? -1 : buttonAt(row, x, y);
            if (target >= 0) {
                pressSpent = true;   // the release is dropped; the walk ends with its own OK
                if (walking.compareAndSet(false, true)) {
                    new Thread(() -> {
                        try {
                            walkTo(row, target);
                        } finally {
                            walking.set(false);
                        }
                    }, "ButtonWalk").start();
                }
                return;
            }
            PointerClick c = clicker;
            pressNode = c == null ? null : c.nodeAt(x, y);
            if (pressNode != null) {
                pressNodeTime = SystemClock.uptimeMillis();
                return;
            }
            touchDownTime = SystemClock.uptimeMillis();
            touching = true;
            offX = x - lastSensorX;
            offY = y - lastSensorY;
            downX = touchX = x;
            downY = touchY = y;
            dragging = false;
            touch(MotionEvent.ACTION_DOWN, x, y);
        } else if ("mouseup".equals(type) && centerDown) {
            centerDown = false;
            navigating = false;   // one OK per scroll: the next press is a touch again until the wheel turns
            injectKey(new KeyEvent(centerDownTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, virtualKeyboard(), 0, 0, InputDevice.SOURCE_DPAD));
        } else if ("mouseup".equals(type) && pressNode != null) {
            boolean longPress = SystemClock.uptimeMillis() - pressNodeTime > 600;
            PointerClick c = clicker;
            boolean ok = c != null && c.clickAt(pressNode, x, y, longPress);
            pressNode = null;
            if (!ok) {   // the app refused the click: tap there instead
                touchDownTime = SystemClock.uptimeMillis();
                touch(MotionEvent.ACTION_DOWN, x, y);
                touch(MotionEvent.ACTION_UP, x, y);
            }
        } else if ("mouseup".equals(type) && pressSpent) {
            pressSpent = false;
        } else if ("mouseup".equals(type) && touching) {
            touchUp();
        }
    }

    private void edgeScroll() {
        long now = SystemClock.uptimeMillis();
        int side = lastX >= screenW - EDGE ? 1 : lastX <= EDGE ? -1 : 0;
        if (side != edgeSide) {
            edgeSide = side;
            edgeSince = now;
            lastEdgeScroll = 0;
        }
        if (side == 0 || now - edgeSince < EDGE_DWELL_MS || now - lastEdgeScroll < EDGE_REPEAT_MS) return;
        lastEdgeScroll = now;
        PointerClick c = clicker;
        if (c != null) c.scrollRowAt(lastY, side > 0);
    }

    private void dragTo(float x, float y) {
        if (!dragging && Math.hypot(x - downX, y - downY) < TAP_SLOP) return;
        dragging = true;
        touchX = x;
        touchY = y;
        touch(MotionEvent.ACTION_MOVE, x, y);
    }

    private void touchUp() {
        touch(MotionEvent.ACTION_UP, touchX, touchY);
        touching = false;
    }

    /** A wheel notch is one Up/Down press: TV apps move focus on D-pad keys and mostly ignore mouse scrolling. */
    private void onWheel(JSONObject w) {
        int delta = w.optInt("delta");
        if (delta == 0) return;
        // The wheel message carries the pointer in the same space as a press; the sensor stream may not.
        startNavigating(mapX(w.optInt("x", (int) lastX)), mapY(w.optInt("y", (int) lastY)));
        int code = delta > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        long now = SystemClock.uptimeMillis();
        // KeyboardSwitch must not take this arrow for an arrow key: it skips arrows whose event time it is told here.
        KeyboardSwitch k = keyboard;
        if (k != null) k.wheelKeyTime = now;
        injectKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, virtualKeyboard(), 0, 0, InputDevice.SOURCE_DPAD));
        injectKey(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, virtualKeyboard(), 0, 0, InputDevice.SOURCE_DPAD));
    }

    /** Back held this long is Home (2026-11-26): the LG remote's own Home button never reaches magic4pc. */
    private static final long BACK_HOLD_MS = 600;
    private final android.os.Handler keyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean backHeld;
    private boolean backWasHome;
    private final Runnable backToHome = () -> {
        if (!backHeld) return;
        backWasHome = true;
        long t = SystemClock.uptimeMillis();
        remoteKey(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0, 0, virtualKeyboard(), 0, 0,
                InputDevice.SOURCE_KEYBOARD));
        remoteKey(new KeyEvent(t, t + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0, 0, virtualKeyboard(), 0, 0,
                InputDevice.SOURCE_KEYBOARD));
        Log.d(TAG, "back held: home");
    };

    /**
     * Back is sent on release, not on press: a short press is Back, one held BACK_HOLD_MS is Home. The webOS
     * remote's key repeats while Back is held are dropped.
     */
    private void onBack(boolean down) {
        if (down) {
            if (backHeld) return;
            backHeld = true;
            backWasHome = false;
            keyHandler.postDelayed(backToHome, BACK_HOLD_MS);
            return;
        }
        if (!backHeld) return;
        backHeld = false;
        keyHandler.removeCallbacks(backToHome);
        if (backWasHome) return;
        long t = SystemClock.uptimeMillis();
        remoteKey(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0, virtualKeyboard(), 0, 0,
                InputDevice.SOURCE_KEYBOARD));
        remoteKey(new KeyEvent(t, t + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0, virtualKeyboard(), 0, 0,
                InputDevice.SOURCE_KEYBOARD));
    }

    private void onKey(JSONObject k) {
        int code = androidKey(k.optInt("keyCode"));
        if (code == KeyEvent.KEYCODE_UNKNOWN) return;
        boolean down = k.optBoolean("isDown");
        if (code == KeyEvent.KEYCODE_BACK) {
            keyHandler.post(() -> onBack(down));
            return;
        }
        long now = SystemClock.uptimeMillis();
        int source = isDpad(code) ? InputDevice.SOURCE_DPAD : InputDevice.SOURCE_KEYBOARD;
        if (down) {
            if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) startNavigating(lastX, lastY);
            if (repeatKey == code) {
                repeatCount++;
            } else {
                repeatKey = code;
                repeatCount = 0;
                keyDownTime = now;
            }
            remoteKey(new KeyEvent(keyDownTime, now, KeyEvent.ACTION_DOWN, code, repeatCount, 0,
                    virtualKeyboard(), 0, 0, source));
        } else {
            remoteKey(new KeyEvent(keyDownTime, now, KeyEvent.ACTION_UP, code, 0, 0,
                    virtualKeyboard(), 0, 0, source));
            repeatKey = -1;
        }
    }

    private void remoteKey(KeyEvent e) {
        java.util.function.Predicate<KeyEvent> hook = keyHook;
        if (hook != null && hook.test(e)) return;
        injectKey(e);
    }

    /** The button row of the app under (x, y), from pointer_button_rows; null for other apps. */
    private int[][] buttonRowAt(float x, float y) {
        PointerClick c = clicker;
        if (buttonRows.isEmpty() || c == null) return null;
        return buttonRows.get(c.appAt(x, y));
    }

    /** Index of the disc (x, y) is on, a little generous around it, or -1. */
    private static int buttonAt(int[][] row, float x, float y) {
        for (int i = 0; i < row.length; i++) {
            if (Math.hypot(x - row[i][0], y - row[i][1]) <= row[i][2] * 1.5f) return i;
        }
        return -1;
    }

    /**
     * Moves the app's own highlight to button target with Left/Right, then presses OK. Which disc is lit is read
     * off the screen (Shizuku screencap): the lit one is bright, the others dark. OK goes only once the target is
     * seen lit, so a wrong guess never activates another button; with no disc lit (the bar hidden) nothing is sent.
     */
    private void walkTo(int[][] row, int target) {
        for (int attempt = 0; attempt < 3; attempt++) {
            int lit = litButton(row);
            if (lit < 0) {
                Log.d(TAG, "walk: no lit button");
                return;
            }
            if (lit == target) {
                long now = SystemClock.uptimeMillis();
                injectKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
                        virtualKeyboard(), 0, 0, InputDevice.SOURCE_DPAD));
                injectKey(new KeyEvent(now, now + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
                        virtualKeyboard(), 0, 0, InputDevice.SOURCE_DPAD));
                Log.d(TAG, "walk: OK on " + target);
                return;
            }
            int code = target > lit ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            for (int i = Math.abs(target - lit); i > 0; i--) {
                long now = SystemClock.uptimeMillis();
                injectKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, virtualKeyboard(), 0, 0,
                        InputDevice.SOURCE_DPAD));
                injectKey(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, virtualKeyboard(), 0, 0,
                        InputDevice.SOURCE_DPAD));
                SystemClock.sleep(120);
            }
            SystemClock.sleep(200);
        }
        Log.d(TAG, "walk: gave up");
    }

    /** Index of the one disc drawn lit, or -1. Samples four points on each disc's ring, past its icon. */
    private int litButton(int[][] row) {
        String file = "/data/local/tmp/mp.raw";
        StringBuilder cmd = new StringBuilder("screencap " + file + " && dd if=" + file + " bs=16 count=1 2>/dev/null");
        int n = row.length * 4;
        for (int[] d : row) {
            int rr = d[2] * 6 / 10;
            int[][] pts = {{d[0] + rr, d[1]}, {d[0] - rr, d[1]}, {d[0], d[1] - rr},
                    {d[0] + rr * 7 / 10, d[1] + rr * 7 / 10}};
            for (int[] p : pts) {
                cmd.append("; dd if=").append(file).append(" bs=4 count=1 skip=").append(4 + p[1] * 1920 + p[0])
                        .append(" 2>/dev/null");
            }
        }
        byte[] out;
        try {
            Method newProcess = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class,
                    String.class);
            newProcess.setAccessible(true);
            Process p = (Process) newProcess.invoke(null, new String[] {"sh", "-c", cmd.toString()}, null, null);
            out = readAll(p.getInputStream());
            p.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "screencap: " + t);
            return -1;
        }
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        if (out.length < 16 + n * 4 || b.getInt(0) != 1920) return -1;   // 1920-wide RGBA expected
        int lit = -1;
        for (int i = 0; i < row.length; i++) {
            int sum = 0;
            for (int j = 0; j < 4; j++) {
                int o = 16 + (i * 4 + j) * 4;
                sum += ((out[o] & 0xff) + (out[o + 1] & 0xff) + (out[o + 2] & 0xff)) / 3;
            }
            if (sum / 4 > 150) {
                if (lit >= 0) return -1;   // two lit: not the bar we know
                lit = i;
            }
        }
        return lit;
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
        return bytes.toByteArray();
    }

    private boolean inOkZone(float x, float y) {
        PointerClick c = clicker;
        if (okZones.isEmpty() || c == null) return false;
        Integer from = okZones.get(c.appAt(x, y));
        return from != null && x > from && c.overBareScreen(x, y);
    }

    private void startNavigating(float x, float y) {
        navigating = true;
        navX = x;
        navY = y;
    }

    private static int virtualKeyboard() {
        return android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
    }

    private static boolean isDpad(int code) {
        return code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER;
    }

    /** webOS key codes (what the magic4pc app sees) to Android key codes. */
    private static int androidKey(int webos) {
        if (webos >= 48 && webos <= 57) return KeyEvent.KEYCODE_0 + (webos - 48);
        switch (webos) {
            case 37: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 38: return KeyEvent.KEYCODE_DPAD_UP;
            case 39: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 40: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 13: return KeyEvent.KEYCODE_DPAD_CENTER;
            case 461: case 8: return KeyEvent.KEYCODE_BACK;
            case 415: return KeyEvent.KEYCODE_MEDIA_PLAY;
            case 19: return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case 413: return KeyEvent.KEYCODE_MEDIA_STOP;
            case 412: return KeyEvent.KEYCODE_MEDIA_REWIND;
            case 417: return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case 33: return KeyEvent.KEYCODE_CHANNEL_UP;
            case 34: return KeyEvent.KEYCODE_CHANNEL_DOWN;
            case 403: return KeyEvent.KEYCODE_PROG_RED;
            case 404: return KeyEvent.KEYCODE_PROG_GREEN;
            case 405: return KeyEvent.KEYCODE_PROG_YELLOW;
            case 406: return KeyEvent.KEYCODE_PROG_BLUE;
            case 457: return KeyEvent.KEYCODE_INFO;
            case 1056: return KeyEvent.KEYCODE_GUIDE;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    // --- injection ---

    private float mapX(int x) {
        return Math.max(0, Math.min(screenW - 1, x * screenW / TV_W));
    }

    private float mapY(int y) {
        return Math.max(0, Math.min(screenH - 1, y * screenH / TV_H));
    }

    private void touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(touchDownTime, now, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        injectEvent(e);
        e.recycle();
    }

    private void injectKey(KeyEvent e) {
        injectEvent(e);
    }

    private void injectEvent(InputEvent e) {
        try {
            if (inject == null && !bindInput()) return;
            inject.invoke(inputManager, e, 0);   // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Throwable t) {
            Log.w(TAG, "inject: " + t);
            inject = null;   // Shizuku restarted: bind again on the next event
        }
    }

    private boolean bindInput() throws Exception {
        if (!Shizuku.pingBinder()
                || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("");
        IBinder binder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.INPUT_SERVICE));
        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        inputManager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        inject = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        Log.i(TAG, "input bound, screen " + screenW + "x" + screenH);
        return true;
    }
}
