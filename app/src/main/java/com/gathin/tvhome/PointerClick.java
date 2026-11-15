package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.List;

/**
 * magic_pointer: presses what is under the Magic Remote pointer through accessibility. Many TV apps (Nuvio's home
 * rows) build their items for D-pad focus only and ignore touches and mouse clicks, yet still publish them as
 * clickable nodes, so ACTION_CLICK on the smallest clickable node under the pointer opens them anyway. With no such
 * node (or over the on-screen keyboard, which needs real taps) MagicPointer falls back to a touch.
 */
final class PointerClick {

    private static final String TAG = "PointerClick";
    private final AccessibilityService service;

    PointerClick(AccessibilityService service) {
        this.service = service;
    }

    /** The smallest visible clickable node under (x, y) in an app window, or null. */
    AccessibilityNodeInfo nodeAt(float x, float y) {
        int px = (int) x, py = (int) y;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            Rect r = new Rect();
            for (AccessibilityWindowInfo w : windows) {   // topmost first
                w.getBoundsInScreen(r);
                if (!r.contains(px, py)) continue;
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) return null;   // keyboard, system UI
                AccessibilityNodeInfo root = w.getRoot();
                return root == null ? null : smallestClickable(root, px, py);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "nodeAt: " + e);
        }
        return null;
    }

    private static AccessibilityNodeInfo smallestClickable(AccessibilityNodeInfo root, int x, int y) {
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        Rect r = new Rect();
        ArrayDeque<AccessibilityNodeInfo> todo = new ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            AccessibilityNodeInfo n = todo.poll();
            n.getBoundsInScreen(r);
            if (!r.contains(x, y) || !n.isVisibleToUser()) continue;
            if (n.isClickable() && n.isEnabled()) {
                long area = (long) r.width() * r.height();
                if (area < bestArea) {
                    best = n;
                    bestArea = area;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        return best;
    }

    /**
     * Clicks what is under (x, y) now. The service subscribes to no content events, so its node cache can still
     * hold a row from before the last scroll: a click on such a stale node fails, and then the cache is dropped
     * and the point looked up again.
     */
    boolean clickAt(AccessibilityNodeInfo node, float x, float y, boolean longPress) {
        if (node != null && press(node, longPress)) return true;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            service.clearCache();
        }
        AccessibilityNodeInfo fresh = nodeAt(x, y);
        return fresh != null && press(fresh, longPress);
    }

    /** Clicks (or long-clicks) the node; false when the app refused. */
    boolean press(AccessibilityNodeInfo node, boolean longPress) {
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return node.performAction(longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                    : AccessibilityNodeInfo.ACTION_CLICK);
        } catch (RuntimeException e) {
            Log.w(TAG, "press: " + e);
            return false;
        }
    }
}
