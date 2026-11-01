package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * icon_only_buttons: the VPN state on the round network button, which has no room for the VpnBadge capsule. The
 * network icon gets a small round badge on its bottom-end corner: a dark lock on green while a VPN is up, an open
 * white lock on red while none is and vpn_app is installed. The pill's tint (focus, wifi_status_color) reaches the
 * network icon only, so the badge keeps its colours on the frosted and on the focused button alike.
 */
final class VpnIconBadge extends Drawable {

    private final Drawable icon;
    private final Drawable lock;
    private final int fill;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** @param up true for the green "VPN on" badge, false for the red "VPN off" one */
    VpnIconBadge(Drawable icon, Drawable lock, boolean up, float density) {
        this.icon = icon.mutate();
        this.lock = lock.mutate();
        this.lock.setTint(up ? VpnBadge.INK : VpnBadge.INK_OFF);
        this.fill = up ? VpnBadge.GREEN : VpnBadge.RED;
        this.density = density;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        icon.draw(canvas);
        Rect b = getBounds();
        float r = 5.5f * density;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        // Centred a little inside the icon's corner, so most of it stays within the button's padding.
        float cx = rtl ? b.left + r - 4 * density : b.right - r + 4 * density;
        float cy = b.bottom - r + 4 * density;
        // A dark ring parts the badge from the icon under it on the frosted and on the white focused button.
        paint.setColor(VpnBadge.INK);
        canvas.drawCircle(cx, cy, r + 1.25f * density, paint);
        paint.setColor(fill);
        canvas.drawCircle(cx, cy, r, paint);
        int half = Math.round(3.5f * density);
        lock.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
        lock.draw(canvas);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        icon.setBounds(bounds);
    }

    @Override
    public int getIntrinsicWidth() {
        return icon.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return icon.getIntrinsicHeight();
    }

    // Tint and state go to the network icon only; the badge keeps its own colours.

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        icon.setTintList(tint);
    }

    @Override
    public void setTintMode(@Nullable PorterDuff.Mode tintMode) {
        icon.setTintMode(tintMode);
    }

    @Override
    public boolean isStateful() {
        return icon.isStateful();
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        boolean changed = icon.setState(state);
        if (changed) invalidateSelf();
        return changed;
    }

    @Override
    public void setAlpha(int alpha) {
        icon.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        icon.setColorFilter(colorFilter);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
