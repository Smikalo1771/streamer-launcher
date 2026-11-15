package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

/**
 * magic_pointer: picks the on-screen keyboard that fits the remote in use. Gboard for TV moves only on D-pad keys and
 * ignores taps, so the Magic Remote pointer cannot press its keys; a touch keyboard (FlorisBoard) takes the taps but
 * has no D-pad focus. A pointer click on Gboard's window switches to the touch keyboard (that click is spent on the
 * switch), and an arrow key while the touch keyboard is up switches back to Gboard. Wheel notches are arrow keys too,
 * so MagicPointer marks them with WHEEL_SCANCODE and they don't count.
 *
 * Needs the touch keyboard installed and enabled (ime enable). Without it nothing changes.
 */
final class KeyboardSwitch {

    private static final String TAG = "KeyboardSwitch";
    static final String DPAD_IME = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME";
    static final String TOUCH_IME = "dev.patrickgold.florisboard/.FlorisImeService";
    /** Scan code MagicPointer puts on the arrow keys it makes from wheel notches. */
    static final int WHEEL_SCANCODE = 0x7e57;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());

    KeyboardSwitch(AccessibilityService service) {
        this.service = service;
        // getWindows() needs this flag; the XML leaves it off so other setups are unchanged.
        AccessibilityServiceInfo info = service.getServiceInfo();
        if (info != null && (info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            service.setServiceInfo(info);
        }
    }

    /**
     * A pointer press at (x, y) on screen, from MagicPointer's thread. True when it landed on Gboard and started the
     * switch to the touch keyboard: the caller drops that press.
     */
    boolean onPointerDown(float x, float y) {
        if (!isTouchImeEnabled()) return false;
        Rect ime = imeBounds();
        if (ime == null || !ime.contains((int) x, (int) y)) return false;
        if (!DPAD_IME.equals(currentIme())) return false;
        main.post(() -> switchTo(TOUCH_IME));
        return true;
    }

    /** Every key the service filters. Never consumes it: the arrow still moves Gboard's focus after the switch. */
    void onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return;
        int code = e.getKeyCode();
        if (code < KeyEvent.KEYCODE_DPAD_UP || code > KeyEvent.KEYCODE_DPAD_RIGHT) return;
        if (e.getScanCode() == WHEEL_SCANCODE) return;
        if (!TOUCH_IME.equals(currentIme()) || imeBounds() == null) return;
        switchTo(DPAD_IME);
    }

    private String currentIme() {
        String id = Settings.Secure.getString(service.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return id == null ? "" : id;
    }

    /** Settings' enabled_input_methods is closed to apps that target Android 14, so ask the input method service. */
    private boolean isTouchImeEnabled() {
        try {
            for (InputMethodInfo i : service.getSystemService(InputMethodManager.class).getEnabledInputMethodList()) {
                if (TOUCH_IME.equals(i.getId())) return true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "ime list: " + e);
        }
        return false;
    }

    /** The on-screen keyboard's window while one is up, else null. */
    private Rect imeBounds() {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    return r.isEmpty() ? null : r;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "windows: " + e);
        }
        return null;
    }

    private void switchTo(String ime) {
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ok = service.getSoftKeyboardController().switchToInputMethod(ime);
        }
        if (!ok) {
            // WRITE_SECURE_SETTINGS (granted over adb) also works; the system follows the setting.
            try {
                ok = Settings.Secure.putString(service.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, ime);
            } catch (SecurityException e) {
                Log.w(TAG, "switch: " + e);
            }
        }
        Log.i(TAG, "keyboard -> " + ime + (ok ? "" : " (failed)"));
    }
}
