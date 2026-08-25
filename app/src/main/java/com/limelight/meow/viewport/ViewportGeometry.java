package com.limelight.meow.viewport;

/**
 * Turns the local view transform into the rectangle of the host frame the user can
 * actually see. Pure arithmetic, no Android types, so it is unit tested directly.
 *
 * <p><b>The geometry this assumes.</b> {@code StreamContainer} sizes itself to the stream's
 * aspect ratio and adds the {@code SurfaceView} as a {@code MATCH_PARENT} child, so the
 * SurfaceView's box <em>is</em> the video frame — there is no letterboxing inside it to
 * correct for. {@code PanZoomHandler} sets the SurfaceView's pivot to (0,0) and then moves
 * it with {@code setX}/{@code setY} and scales it with {@code setScaleX}/{@code setScaleY},
 * so the on-screen box of the frame is
 * {@code [childX, childX + unscaledWidth * scale)}. Everything below is the intersection of
 * that box with the window the user sees it through, expressed as a fraction of the frame
 * and then multiplied up into host pixels.
 *
 * <p><b>Fail open, never closed.</b> Every degenerate input — a zero-sized view, a window
 * that does not overlap the frame, a frame not laid out yet — returns the full frame rather
 * than a guess. Reporting "I can see everything" costs the user nothing but detail;
 * reporting a wrong rectangle crops the host to somewhere they are not looking.
 */
public final class ViewportGeometry {

    private ViewportGeometry() {
    }

    /**
     * @param childX             left edge of the (scaled) stream view, in parent pixels
     * @param childY             top edge of the (scaled) stream view, in parent pixels
     * @param childWidth         width of the stream view <em>after</em> scaling, in parent pixels
     * @param childHeight        height of the stream view <em>after</em> scaling, in parent pixels
     * @param windowLeft         left edge of the visible window, in the same parent pixels
     * @param windowTop          top edge of the visible window
     * @param windowRight        right edge of the visible window (exclusive)
     * @param windowBottom       bottom edge of the visible window (exclusive)
     * @param hostWidth          width of the host frame in host pixels
     * @param hostHeight         height of the host frame in host pixels
     * @return the visible rectangle in host pixels, never null
     */
    public static ViewportRect visibleHostRect(float childX, float childY,
                                               float childWidth, float childHeight,
                                               float windowLeft, float windowTop,
                                               float windowRight, float windowBottom,
                                               int hostWidth, int hostHeight) {
        int safeHostWidth = Math.max(1, hostWidth);
        int safeHostHeight = Math.max(1, hostHeight);
        ViewportRect everything = ViewportRect.full(safeHostWidth, safeHostHeight);

        if (!(childWidth > 0f) || !(childHeight > 0f)) {
            return everything;
        }
        if (!(windowRight > windowLeft) || !(windowBottom > windowTop)) {
            return everything;
        }

        int[] horizontal = axis(childX, childWidth, windowLeft, windowRight, safeHostWidth);
        int[] vertical = axis(childY, childHeight, windowTop, windowBottom, safeHostHeight);
        if (horizontal == null || vertical == null) {
            return everything;
        }

        return new ViewportRect(horizontal[0], vertical[0],
                horizontal[1] - horizontal[0], vertical[1] - vertical[0]);
    }

    /**
     * One axis of the intersection, as {@code {start, end}} in host pixels with
     * {@code end > start}, or null when the frame and the window do not overlap on this axis.
     */
    private static int[] axis(float childStart, float childSize,
                              float windowStart, float windowEnd, int hostSize) {
        float overlapStart = Math.max(childStart, windowStart);
        float overlapEnd = Math.min(childStart + childSize, windowEnd);
        if (!(overlapEnd > overlapStart)) {
            return null;
        }

        float fractionStart = (overlapStart - childStart) / childSize;
        float fractionEnd = (overlapEnd - childStart) / childSize;

        int start = Math.round(fractionStart * hostSize);
        int end = Math.round(fractionEnd * hostSize);

        // Rounding can collapse a very thin sliver, and a zero-width viewport is rejected
        // by the protocol. Keep at least one pixel, biased so the rectangle stays inside
        // the frame.
        start = Math.max(0, Math.min(start, hostSize - 1));
        end = Math.max(start + 1, Math.min(end, hostSize));
        return new int[] { start, end };
    }
}
