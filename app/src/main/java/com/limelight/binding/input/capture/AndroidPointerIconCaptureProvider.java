package com.limelight.binding.input.capture;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.PointerIcon;
import android.view.View;

import com.limelight.meow.cursor.LocalCursorScalePolicy;

/**
 * Shows/hides the Android system pointer over the stream container.
 *
 * <p>Host-drawn cursor is baked into video and untouched. This only controls
 * the local overlay used when "Use local mouse cursor" is enabled.
 *
 * <p>When {@code checkbox_enlarge_cursor_at_low_zoom} is on and the zoom
 * scale is low (~1.0, overview), a slightly larger custom PointerIcon is shown
 * via {@code PointerIcon.create(Bitmap, hotspotX, hotspotY)} on top of
 * {@code View.setPointerIcon}. At higher zoom it reverts to the normal system
 * arrow (null). The icon is a programmatic arrow mimicking the system shape,
 * scaled by {@link LocalCursorScalePolicy#ENLARGED_SCALE}.
 */
public class AndroidPointerIconCaptureProvider extends InputCaptureProvider {
    private final View targetView;
    private final Context context;

    // MEOW-CURSOR: low-zoom enlargement state
    private boolean enlargeEnabled = false;
    private boolean isLowScale = true; // assume overview until first zoom callback
    private PointerIcon enlargedIcon;

    public AndroidPointerIconCaptureProvider(Activity activity, View targetView) {
        this.context = activity;
        this.targetView = targetView;
    }

    @Override
    public void hideCursor() {
        super.hideCursor();
        targetView.setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL));
    }

    @Override
    public void showCursor() {
        super.showCursor();
        applyPointerIcon();
    }

    @Override
    public void setEnlargeAtLowZoomEnabled(boolean enabled) {
        this.enlargeEnabled = enabled;
        // If cursor is currently visible, re-apply immediately so toggle takes effect without zoom change.
        if (isCursorVisible) {
            applyPointerIcon();
        }
    }

    @Override
    public void onZoomScaleChanged(float scale) {
        boolean shouldBeLow = LocalCursorScalePolicy.shouldEnlarge(scale, true);
        // Only care about low-scale flag; enlargeEnabled gates actual icon choice in apply().
        if (this.isLowScale != shouldBeLow) {
            this.isLowScale = shouldBeLow;
            if (isCursorVisible) {
                applyPointerIcon();
            }
        } else {
            // Even if flag didn't flip, first call may need to set initial isLowScale correctly
            // when provider was created after Game.onCreate. Ensure not stuck on default.
            // Only update if we never applied before (icon null and enlarge expected)?
            // Simpler: if enlargeEnabled and isCursorVisible, ensure correct icon.
            // This path is cheap; keep flag sync but still refresh on first call after enable.
            this.isLowScale = shouldBeLow;
        }
    }

    private void applyPointerIcon() {
        if (!isCursorVisible) {
            targetView.setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL));
            return;
        }
        boolean wantEnlarged = enlargeEnabled && isLowScale;
        if (wantEnlarged) {
            PointerIcon icon = getOrCreateEnlargedIcon();
            if (icon != null) {
                targetView.setPointerIcon(icon);
                return;
            }
        }
        // Normal system arrow (letting system pick density-appropriate glyph)
        targetView.setPointerIcon(null);
    }

    private PointerIcon getOrCreateEnlargedIcon() {
        if (enlargedIcon != null) return enlargedIcon;
        try {
            enlargedIcon = createEnlargedIcon();
        } catch (Exception e) {
            // Never let icon creation crash stream start
            enlargedIcon = null;
        }
        return enlargedIcon;
    }

    /**
     * Creates a slightly larger arrow bitmap and wraps it in PointerIcon.
     * Uses programmatic drawing so no drawable resource is required and the
     * size adapts to display density.
     */
    private PointerIcon createEnlargedIcon() {
        float density = context.getResources().getDisplayMetrics().density;
        // Base arrow ~24dp, enlarged ~38dp at 1.6x
        int baseDp = 24;
        float scale = LocalCursorScalePolicy.ENLARGED_SCALE;
        int sizePx = Math.round(baseDp * density * scale);
        // Clamp to avoid IllegalArgumentException on huge densities; max ~128px is safe.
        sizePx = Math.max(24, Math.min(sizePx, 96));
        // Keep bitmap square, but arrow occupies most of it
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, sizePx * 0.055f));
        stroke.setStrokeJoin(Paint.Join.ROUND);

        Path path = new Path();
        float w = sizePx;
        float h = sizePx;
        // Classic arrow outline: origin top-left
        path.moveTo(0f, 0f);
        path.lineTo(0f, h * 0.78f);
        path.lineTo(w * 0.22f, h * 0.60f);
        path.lineTo(w * 0.35f, h * 0.78f);
        path.lineTo(w * 0.40f, h * 0.72f);
        path.lineTo(w * 0.28f, h * 0.55f);
        path.lineTo(w * 0.48f, h * 0.55f);
        path.close();

        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);

        // Hotspot at tip (0,0) - matches system default
        return PointerIcon.create(bitmap, 0f, 0f);
    }
}
