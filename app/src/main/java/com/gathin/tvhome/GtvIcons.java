package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * gtv_home: an app icon drawn edge to edge into a circle, like the stock launcher's "Your apps" row. One bitmap per
 * app, rendered once (GtvHome caches it) and cheap to draw afterwards.
 * An adaptive icon shows the circle of its mask: both 108-unit layers are drawn 1.5 times the circle, so the
 * central 72 units fill it. A legacy icon that fills its square is cropped to the circle; one with a lot of
 * transparent padding sits on a plain disc, scaled up so it does not look tiny.
 */
final class GtvIcons {
    private GtvIcons() {}

    static Drawable circle(Resources res, Drawable src, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (src instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) src;
            int pad = size / 4;
            Rect bounds = new Rect(-pad, -pad, size + pad, size + pad);
            canvas.drawColor(0xFF2A2A2E);
            if (adaptive.getBackground() != null) {
                adaptive.getBackground().setBounds(bounds);
                adaptive.getBackground().draw(canvas);
            }
            if (adaptive.getForeground() != null) {
                adaptive.getForeground().setBounds(bounds);
                adaptive.getForeground().draw(canvas);
            }
        } else {
            drawLegacy(canvas, src, size);
        }
        mask(canvas, size);
        return new BitmapDrawable(res, out);
    }

    private static void drawLegacy(Canvas canvas, Drawable src, int size) {
        // Render at twice the size first, to find the opaque part and to scale it up without going soft.
        int big = size * 2;
        Bitmap tmp = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888);
        Canvas tc = new Canvas(tmp);
        int w = src.getIntrinsicWidth() > 0 ? src.getIntrinsicWidth() : big;
        int h = src.getIntrinsicHeight() > 0 ? src.getIntrinsicHeight() : big;
        float fit = Math.min((float) big / w, (float) big / h);
        int dw = Math.round(w * fit), dh = Math.round(h * fit);
        Rect saved = src.copyBounds();
        src.setBounds((big - dw) / 2, (big - dh) / 2, (big + dw) / 2, (big + dh) / 2);
        src.draw(tc);
        src.setBounds(saved);

        int[] px = new int[big * big];
        tmp.getPixels(px, 0, big, 0, 0, big, big);
        int left = big, top = big, right = -1, bottom = -1;
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < big; y++) {
            for (int x = 0; x < big; x++) {
                int p = px[y * big + x];
                if (Color.alpha(p) < 24) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
                if (Color.alpha(p) > 200) {
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            }
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (right < left || bottom < top) return;   // nothing drawn: an empty circle
        int bw = right - left + 1, bh = bottom - top + 1;
        boolean fills = bw >= big * 0.9f && bh >= big * 0.9f;
        if (fills) {
            canvas.drawBitmap(tmp, null, new Rect(0, 0, size, size), paint);
            return;
        }
        // Mostly transparent: a plain disc (dark under a light icon), the icon at about two thirds of it.
        double luma = n > 0 ? (0.299 * r + 0.587 * g + 0.114 * b) / n / 255.0 : 0;
        canvas.drawColor(luma > 0.75 ? 0xFF2A2A2E : 0xFFFFFFFF);
        float scale = 0.66f * size / Math.max(bw, bh);
        float cx = (left + right + 1) / 2f, cy = (top + bottom + 1) / 2f;
        Rect dst = new Rect(Math.round(size / 2f - cx * scale), Math.round(size / 2f - cy * scale),
                Math.round(size / 2f + (big - cx) * scale), Math.round(size / 2f + (big - cy) * scale));
        canvas.drawBitmap(tmp, null, dst, paint);
    }

    /** Clears everything outside the circle, with an antialiased edge. */
    private static void mask(Canvas canvas, int size) {
        // DST_IN with drawCircle only touches the pixels the circle covers, leaving the corners: clear the
        // inverse of the circle instead.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        android.graphics.Path outside = new android.graphics.Path();
        outside.addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW);
        outside.setFillType(android.graphics.Path.FillType.INVERSE_WINDING);
        canvas.drawPath(outside, paint);
    }
}
