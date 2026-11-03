package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * voice_open_apps: does what Google Assistant was asked for when it cannot itself (VoiceMatch: open or close an
 * app, standby, Home, Back, mute, volume, pause, play). The remote's voice button starts the Assistant
 * directly (no key event reaches the launcher), and on the Streamer with the stock launcher disabled the Assistant
 * understands "otevři IPTV" but fails to open it (provider.OPEN, UNKNOWN_ERROR). So HomeKeyService watches the
 * Assistant's window while it is up, reads the words it shows, and once they stop changing opens the app they name
 * (VoiceMatch). Anything else ("jaké je počasí") is left to the Assistant.
 * Content events are asked for only while the Assistant is in front, so other apps cost nothing.
 */
final class AssistantWatch {
    static final String ASSISTANT = VoiceLaunchActivity.ASSISTANT;
    /** How long the shown words have to stay the same before they count as the final question. */
    static final long SETTLE_MS = 700;
    /** Opened once per question: the same words shown again within this time open nothing more. */
    private static final long REPEAT_MS = 5000;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean watching;
    private String shown = "";
    private String opened = "";
    private long openedAt;
    private final Runnable settle = this::settled;
    /** The app in front before the Assistant came up, for "zavri" and "vypni to"; null on Home. */
    private String front;
    /** Waits for the Assistant to leave: Back would only close the Assistant, the screensaver would sit under it. */
    private Runnable afterAssistant;

    AssistantWatch(AccessibilityService service) {
        this.service = service;
    }

    /** Window state changes from every app: start or stop watching the Assistant's content. */
    void onEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        boolean assistant = pkg != null && ASSISTANT.contentEquals(pkg);
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (assistant) {
                watch(true);
                read(event);
            } else if (pkg != null && !"com.android.systemui".contentEquals(pkg) && !"android".contentEquals(pkg)) {
                watch(false);
                front = service.getPackageName().contentEquals(pkg) ? null : pkg.toString();
                if (afterAssistant != null) {
                    Runnable r = afterAssistant;
                    afterAssistant = null;
                    handler.postDelayed(r, 150);
                }
            }
            return;
        }
        if (!assistant || !watching) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            read(event);
        }
    }

    private void watch(boolean on) {
        if (watching == on) return;
        watching = on;
        if (!on) {
            handler.removeCallbacks(settle);
            shown = "";
        }
        AccessibilityServiceInfo info = service.getServiceInfo();
        if (info == null) return;
        int content = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        info.eventTypes = on ? info.eventTypes | content : info.eventTypes & ~content;
        service.setServiceInfo(info);
    }

    /** Where the Assistant shows the words it heard (StreamingTextView), live while you speak. */
    static final String QUERY_VIEW = ASSISTANT + ":id/user_query";

    /** The words the Assistant shows now; a change restarts the settle wait. */
    private void read(AccessibilityEvent event) {
        String text = "";
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                if (QUERY_VIEW.equals(source.getViewIdResourceName()) && source.getText() != null) {
                    text = source.getText().toString();
                } else {
                    List<AccessibilityNodeInfo> found = top(source).findAccessibilityNodeInfosByViewId(QUERY_VIEW);
                    for (AccessibilityNodeInfo n : found) {
                        if (n.getText() != null && n.getText().length() > text.length()) text = n.getText().toString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // the window went away mid-read
        }
        text = text.trim();
        if (text.isEmpty() || text.equals(shown)) return;
        shown = text;
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, SETTLE_MS);
    }

    private static AccessibilityNodeInfo top(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 40; i++) {
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) return cur;
            cur = parent;
        }
        return cur;
    }

    private void settled() {
        if (!watching || shown.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (shown.equals(opened) && now - openedAt < REPEAT_MS) return;
        List<String[]> apps = apps();
        VoiceMatch.Command command = VoiceMatch.parse(shown, apps);
        if (command == null) return;
        opened = shown;
        openedAt = now;
        run(command, apps);
    }

    private void run(VoiceMatch.Command c, List<String[]> apps) {
        AudioManager audio = service.getSystemService(AudioManager.class);
        switch (c.kind) {
            case OPEN:
                say(R.string.voice_opening, label(apps, c.pkg));
                RemoteKeys.launch(service, c.pkg);
                break;
            case CLOSE:
            case CLOSE_FRONT: {
                String pkg = c.kind == VoiceMatch.Kind.CLOSE ? c.pkg : front;
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                if (pkg == null || pkg.equals(service.getPackageName())) break;
                say(R.string.voice_closing, label(apps, pkg));
                forceStop(pkg);
                break;
            }
            case SLEEP:
                // Standby, as the remote's power button: the TV follows over HDMI-CEC.
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                break;
            case HOME:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                break;
            case BACK:
                whenAssistantLeaves(() -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
                break;
            case SCREENSAVER:
                // The launcher's clock screen with the screensaver's pictures (the Streamer has no "start the
                // screensaver now" shortcut); music keeps playing, any key goes back to the app.
                whenAssistantLeaves(() -> RemoteKeys.launch(service, "screensaver"));
                break;
            case MUTE:
                if (audio != null) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_SHOW_UI);
                break;
            case UNMUTE:
                if (audio != null) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE,
                        AudioManager.FLAG_SHOW_UI);
                break;
            case VOLUME_UP:
            case VOLUME_DOWN: {
                if (audio == null) break;
                int step = c.kind == VoiceMatch.Kind.VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                for (int i = 0; i < 3; i++) {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, step, i == 2 ? AudioManager.FLAG_SHOW_UI : 0);
                }
                break;
            }
            case VOLUME_SET: {
                if (audio == null) break;
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int target = Math.round(max * c.value / 100f);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
                // Volume over HDMI-CEC (the Streamer): the TV or soundbar keeps its own level, which the
                // player cannot read, so a number cannot be set; louder and quieter still work.
                if (target != before && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == before) {
                    Toast.makeText(service, R.string.voice_volume_steps, Toast.LENGTH_LONG).show();
                }
                break;
            }
            case PAUSE:
                mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE);
                break;
            case PLAY:
                mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            default:
                break;
        }
    }

    /** Runs once the Assistant has left; if it stays up, Back closes it first after 2.5 s. */
    private void whenAssistantLeaves(Runnable action) {
        afterAssistant = action;
        handler.postDelayed(() -> {
            if (afterAssistant != action) return;
            afterAssistant = null;
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            handler.postDelayed(action, 400);
        }, 2500);
    }

    private static void mediaKey(AudioManager audio, int code) {
        if (audio == null) return;
        long t = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** "am force-stop" through Shizuku, as the launcher's Stop app does; without Shizuku the app only leaves. */
    private static void forceStop(String pkg) {
        new Thread(() -> {
            try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
                Method newProcess = Shizuku.class.getDeclaredMethod("newProcess",
                        String[].class, String[].class, String.class);
                newProcess.setAccessible(true);
                Process p = (Process) newProcess.invoke(null, new String[] {"am", "force-stop", pkg}, null, null);
                p.waitFor();
            } catch (Throwable ignored) {   // no Shizuku: Home already took the app off the screen
            }
        }, "VoiceStop").start();
    }

    private void say(int text, String arg) {
        Toast.makeText(service, service.getString(text, arg), Toast.LENGTH_SHORT).show();
    }

    private static String label(List<String[]> apps, String pkg) {
        for (String[] a : apps) if (a[1].equals(pkg)) return a[0];
        return pkg;
    }

    /** Installed apps with a launcher entry, TV or phone: {label, package}, without this launcher. */
    private List<String[]> apps() {
        PackageManager pm = service.getPackageManager();
        List<String[]> apps = new ArrayList<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(service.getPackageName())) continue;
                boolean seen = false;
                for (String[] a : apps) seen |= a[1].equals(pkg);
                if (!seen) apps.add(new String[] {r.loadLabel(pm).toString(), pkg});
            }
        }
        return apps;
    }

    void stop() {
        handler.removeCallbacks(settle);
    }
}
