package com.gathin.tvhome;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

/**
 * magic_pointer, part two: whenever the LG TV shows this player's plain HDMI input, open the magic4pc webOS app
 * instead, which shows the same picture with the Magic Remote pointer (MagicPointer). Covers TV power-on, One Touch
 * Play and picking HDMI 1 by hand; other inputs (the sat box) are left alone.
 *
 * Talks to the TV's SSAP websocket (port 3000) with the registration of an already paired client, pushed here as
 * files/lg_ssap.json = {"host": "<tv ip>", "hdmi": "com.webos.app.hdmi1", "register": {<register message>}}.
 * No file: nothing happens. While the TV is off the connection fails and is retried every RETRY_MS.
 */
final class MagicAutoStart {

    private static final String TAG = "MagicAutoStart";
    private static final String APP = "me.wouterdek.magic4pc";
    private static final long RETRY_MS = 15000;
    /** After a launch, no second one for this long (the TV passes through HDMI while the app starts). */
    private static final long LAUNCH_GAP_MS = 8000;

    private final Context context;
    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;
    private final SecureRandom random = new SecureRandom();
    /**
     * Run when the TV closed magic4pc and fell back to the plain HDMI input: that is the Magic Remote's Back held
     * down, which webOS keeps for itself (it never reaches magic4pc). HomeKeyService makes it the player's Home
     * (2026-11-26), since the remote's own Home button opens the TV's menu.
     */
    volatile Runnable onBackHeld;
    /** The TV's app in front and since when (uptime ms), to tell magic4pc being closed from its start-up. */
    private String lastApp = "";
    private long lastAppSince;
    /** magic4pc must have been in front this long before its closing counts as Back held. */
    private static final long SETTLED_MS = 4000;

    MagicAutoStart(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, TAG);
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        Socket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        while (running) {
            try {
                File dir = context.getExternalFilesDir(null);
                File f = dir == null ? null : new File(dir, "lg_ssap.json");
                if (f != null && f.isFile()) {
                    JSONObject cfg = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    session(cfg.getString("host"), cfg.optString("hdmi", "com.webos.app.hdmi1"),
                            cfg.getJSONObject("register").toString());
                }
            } catch (Exception e) {
                if (running) Log.d(TAG, "session: " + e);
            } finally {
                Socket s = socket;
                socket = null;
                if (s != null) try { s.close(); } catch (IOException ignored) {}
            }
            if (running) SystemClock.sleep(RETRY_MS);
        }
    }

    private void session(String host, String hdmiApp, String register) throws Exception {
        Socket s = new Socket();
        socket = s;
        s.connect(new InetSocketAddress(host, 3000), 5000);
        s.setSoTimeout(30000);
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        handshake(host, in, out);
        sendText(out, register);
        long lastLaunch = 0;
        int n = 0;
        while (running) {
            String text;
            try {
                text = readText(in, out);
            } catch (SocketTimeoutException e) {
                sendFrame(out, 0x9, new byte[0]);   // ping; a TV that went away fails the next read
                continue;
            }
            if (text == null) return;
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type");
            if ("registered".equals(type)) {
                Log.i(TAG, "registered with the TV");
                sendText(out, "{\"type\":\"subscribe\",\"id\":\"fg\",\"uri\":"
                        + "\"ssap://com.webos.applicationManager/getForegroundAppInfo\"}");
            } else if ("error".equals(type)) {
                Log.w(TAG, "TV: " + msg.optString("error"));
                if ("register_0".equals(msg.optString("id"))) return;
            } else if ("fg".equals(msg.optString("id"))) {
                String app = msg.optJSONObject("payload") == null ? "" : msg.getJSONObject("payload").optString("appId");
                long now = SystemClock.uptimeMillis();
                boolean backHeld = APP.equals(lastApp) && hdmiApp.equals(app) && now - lastAppSince > SETTLED_MS;
                if (!app.equals(lastApp)) {
                    lastApp = app;
                    lastAppSince = now;
                }
                if (backHeld) {
                    Log.i(TAG, "magic4pc closed by the TV (Back held): Home");
                    Runnable r = onBackHeld;
                    if (r != null) r.run();
                }
                if (hdmiApp.equals(app) && (backHeld || now - lastLaunch > LAUNCH_GAP_MS)) {
                    lastLaunch = now;
                    Log.i(TAG, "TV on " + app + ": opening " + APP);
                    sendText(out, "{\"type\":\"request\",\"id\":\"launch" + (n++) + "\",\"uri\":"
                            + "\"ssap://system.launcher/launch\",\"payload\":{\"id\":\"" + APP + "\"}}");
                }
            }
        }
    }

    // --- minimal websocket client (text frames, ping/pong, close) ---

    private void handshake(String host, InputStream in, OutputStream out) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String req = "GET / HTTP/1.1\r\nHost: " + host + ":3000\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("closed during handshake");
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (head.size() > 8192) throw new IOException("handshake too long");
        }
        String h = head.toString("US-ASCII");
        if (!h.startsWith("HTTP/1.1 101")) throw new IOException("handshake: " + h.split("\r\n")[0]);
    }

    private void sendText(OutputStream out, String text) throws IOException {
        sendFrame(out, 0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(OutputStream out, int opcode, byte[] data) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 14);
        f.write(0x80 | opcode);
        int len = data.length;
        if (len < 126) {
            f.write(0x80 | len);
        } else if (len < 65536) {
            f.write(0x80 | 126);
            f.write(len >> 8);
            f.write(len);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (len >> (8 * i)));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < len; i++) f.write(data[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** The next text message, answering pings on the way; null when the TV closes. */
    private String readText(InputStream raw, OutputStream out) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.readUnsignedByte();
            int b1 = in.readUnsignedByte();
            int opcode = b0 & 0x0f;
            long len = b1 & 0x7f;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            if (len > 4 * 1024 * 1024) throw new IOException("frame too big");
            byte[] mask = (b1 & 0x80) != 0 ? new byte[4] : null;
            if (mask != null) in.readFully(mask);
            byte[] data = new byte[(int) len];
            in.readFully(data);
            if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            switch (opcode) {
                case 0x8: return null;
                case 0x9: sendFrame(out, 0xA, data); continue;
                case 0xA: continue;
                default:
                    msg.write(data);
                    if ((b0 & 0x80) != 0) return msg.toString("UTF-8");
            }
        }
    }
}
