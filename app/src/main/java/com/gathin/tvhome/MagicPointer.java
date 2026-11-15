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

    MagicPointer(Context context) {
        this.context = context.getApplicationContext();
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
        lastX = mapX(b.getInt());
        lastY = mapY(b.getInt());
        if (touching) touch(MotionEvent.ACTION_MOVE, lastX, lastY);
    }

    private void onMouse(JSONObject m) {
        float x = mapX(m.optInt("x")), y = mapY(m.optInt("y"));
        lastX = x;
        lastY = y;
        String type = m.optString("type");
        if ("mousedown".equals(type)) {
            if (touching) touchUp();
            touchDownTime = SystemClock.uptimeMillis();
            touching = true;
            touch(MotionEvent.ACTION_DOWN, x, y);
        } else if ("mouseup".equals(type) && touching) {
            touchUp();
        }
    }

    private void touchUp() {
        touch(MotionEvent.ACTION_UP, lastX, lastY);
        touching = false;
    }

    /** A wheel notch is one Up/Down press: TV apps move focus on D-pad keys and mostly ignore mouse scrolling. */
    private void onWheel(JSONObject w) {
        int delta = w.optInt("delta");
        if (delta == 0) return;
        int code = delta > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        long now = SystemClock.uptimeMillis();
        injectKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, virtualKeyboard(), 0, 0, InputDevice.SOURCE_DPAD));
        injectKey(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, virtualKeyboard(), 0, 0, InputDevice.SOURCE_DPAD));
    }

    private void onKey(JSONObject k) {
        int code = androidKey(k.optInt("keyCode"));
        if (code == KeyEvent.KEYCODE_UNKNOWN) return;
        boolean down = k.optBoolean("isDown");
        long now = SystemClock.uptimeMillis();
        int source = isDpad(code) ? InputDevice.SOURCE_DPAD : InputDevice.SOURCE_KEYBOARD;
        if (down) {
            if (repeatKey == code) {
                repeatCount++;
            } else {
                repeatKey = code;
                repeatCount = 0;
                keyDownTime = now;
            }
            injectKey(new KeyEvent(keyDownTime, now, KeyEvent.ACTION_DOWN, code, repeatCount, 0,
                    virtualKeyboard(), 0, 0, source));
        } else {
            injectKey(new KeyEvent(keyDownTime, now, KeyEvent.ACTION_UP, code, 0, 0,
                    virtualKeyboard(), 0, 0, source));
            repeatKey = -1;
        }
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
