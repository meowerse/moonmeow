package com.limelight.meow.cursor;

import com.limelight.meow.viewport.ViewportRect;

/**
 * Pure-java decision for when the local Android cursor should be shown enlarged.
 *
 * <p>Host-drawn cursor stays untouched (baked into video). This only affects the
 * system pointer overlay shown when {@code checkbox_mouse_local_cursor} is on.
 *
 * <p>Two signals both mean "not zoomed":
 * <ul>
 *   <li>view scale ~ 1.0 (streamContainer.getScaleX() / PanZoomHandler.getScaleFactor())
 *   <li>visible crop covers the whole desktop (ViewportGeometry full-frame case)
 * </ul>
 * Either being true while the enlarge preference is enabled should show the bigger arrow.
 */
public final class LocalCursorScalePolicy {

    /**
     * Scale at or below this is considered "low" and triggers enlargement.
     * ~1.0 is exactly not zoomed; allow a small epsilon for float rounding
     * and for a tiny pinch that is still effectively overview. Zoomed-in
     * starts clearly above this (typical pinch lands at 1.5-3x).
     */
    public static final float LOW_SCALE_THRESHOLD = 1.15f;

    /**
     * Fraction of host desktop that must be visible to count as "overview"
     * for the rect-based overload. 0.98 means 98% in each axis.
     */
    public static final float COVERAGE_FRACTION = 0.98f;

    /** How much bigger the low-zoom arrow is than system default. */
    public static final float ENLARGED_SCALE = 1.6f;

    private LocalCursorScalePolicy() {}

    /**
     * Scale-based decision. This is the primary path: wired to PanZoomHandler
     * scale or View.getScaleX().
     *
     * @param scaleFactor current zoom scale (1.0 = not zoomed)
     * @param enlargeEnabled preference checkbox_enlarge_cursor_at_low_zoom
     * @return true if enlarged pointer should be shown
     */
    public static boolean shouldEnlarge(float scaleFactor, boolean enlargeEnabled) {
        if (!enlargeEnabled) return false;
        // NaN/infinite or nonsense scale -> do not enlarge (fail closed, keep normal)
        if (!(scaleFactor > 0f) || !Float.isFinite(scaleFactor)) return false;
        return scaleFactor <= LOW_SCALE_THRESHOLD;
    }

    /**
     * Viewport-rect based decision. Alternate path when caller has ViewportGeometry
     * visible rect: "overview" means the crop covers basically the whole desktop.
     *
     * @param visible the crop currently on screen (ViewportRect)
     * @param streamWidth negotiated stream/desktop width
     * @param streamHeight negotiated stream/desktop height
     * @param enlargeEnabled preference
     */
    public static boolean shouldEnlarge(ViewportRect visible, int streamWidth, int streamHeight,
                                        boolean enlargeEnabled) {
        if (!enlargeEnabled) return false;
        if (visible == null) return false;
        int safeW = Math.max(1, streamWidth);
        int safeH = Math.max(1, streamHeight);
        // Full-frame or very close: within 2% of each axis.
        boolean coversWidth = visible.width >= safeW * COVERAGE_FRACTION;
        boolean coversHeight = visible.height >= safeH * COVERAGE_FRACTION;
        // Also require origin near (0,0) - otherwise a zoomed window coincidentally same size but offset.
        boolean nearOrigin = visible.x <= safeW * (1f - COVERAGE_FRACTION)
                && visible.y <= safeH * (1f - COVERAGE_FRACTION);
        return coversWidth && coversHeight && nearOrigin;
    }

    /**
     * Unified: enlarge if either signal says low zoom.
     */
    public static boolean shouldEnlarge(float scaleFactor, ViewportRect visible,
                                        int streamWidth, int streamHeight,
                                        boolean enlargeEnabled) {
        return shouldEnlarge(scaleFactor, enlargeEnabled)
                || shouldEnlarge(visible, streamWidth, streamHeight, enlargeEnabled);
    }
}
