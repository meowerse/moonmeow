package com.limelight.meow.keyboard;

/**
 * Where the stream goes when a keyboard covers the bottom of the window. Pure arithmetic in
 * window pixels; the controller measures the views and applies the result as a
 * {@code translationY} on the stream container.
 *
 * <h2>The rule: move, never shrink</h2>
 * <ul>
 *   <li>If nothing of the stream is covered, it does not move.</li>
 *   <li>If the whole stream fits above the keyboard (portrait: a 16:9 desktop on a tall phone
 *       almost always does), it is centred in the space that is left.</li>
 *   <li>If it does not fit (landscape), it keeps its size — a desktop scaled down to the
 *       strip above a keyboard is unreadable — and slides up so that the point of interest
 *       (the host cursor or text caret, when known) sits in the middle of the visible band.
 *       With no point of interest, the stream's bottom edge is placed on the keyboard: the
 *       part the keyboard was hiding is what the user just asked to see.</li>
 *   <li>It only ever moves up, and never further than the stream's top reaching the top of
 *       the visible band would take it.</li>
 * </ul>
 */
public final class StreamLift {

    private StreamLift() {
    }

    /**
     * The nudge for an overlay the stream is not otherwise moved for (the quick bar): zero
     * while {@code focusY} (container pixels) is at least {@code margin} above
     * {@code visibleBottom}; otherwise the stream's bottom edge on {@code visibleBottom}, which
     * costs at most the overlay's size. Not "just enough": the focus is reported once per
     * eighth of the view, so a cursor that keeps drifting after the report would end under
     * the overlay again. Works on either axis (bottom edge, or right edge for a side bar).
     */
    public static float nudgeFor(float containerTop, float containerBottom, float visibleBottom,
                                 float focusY, float margin) {
        if (Float.isNaN(focusY) || containerBottom <= visibleBottom) {
            return 0f;
        }
        float focusWindowY = containerTop + focusY;
        if (visibleBottom - margin - focusWindowY >= 0f) {
            return 0f;
        }
        return visibleBottom - containerBottom;
    }

    /**
     * The sideways twin of {@link #liftFor}, for something standing at the right edge (the
     * quick bar in landscape, in the letterbox when it is wide enough): slide left out from
     * under it by as much as the space on the left allows, never shrinking and never moving
     * right.
     *
     * @return the translationX to apply, zero or negative
     */
    public static float shiftFor(float containerLeft, float containerRight, float visibleLeft,
                                 float visibleRight) {
        if (containerRight <= visibleRight) {
            return 0f;
        }
        float room = Math.max(0f, containerLeft - visibleLeft);
        return -Math.min(containerRight - visibleRight, room);
    }

    /**
     * @param containerTop    the stream container's top, in window pixels, without any lift
     * @param containerBottom its bottom, likewise
     * @param visibleTop      the first window row not covered from above (a visible status bar)
     * @param visibleBottom   the first window row covered from below (a keyboard's top edge)
     * @param focusY          a point of interest inside the container, in container pixels,
     *                        or {@link Float#NaN} when there is none
     * @return the translationY to apply, zero or negative
     */
    public static float liftFor(float containerTop, float containerBottom, float visibleTop,
                                float visibleBottom, float focusY) {
        if (containerBottom <= visibleBottom || visibleBottom <= visibleTop) {
            return 0f;
        }
        float height = containerBottom - containerTop;
        float band = visibleBottom - visibleTop;
        float lift;
        if (height <= band) {
            lift = (visibleTop + visibleBottom) / 2f - (containerTop + containerBottom) / 2f;
        } else {
            float bottomAligned = visibleBottom - containerBottom;
            float topAligned = visibleTop - containerTop;
            if (Float.isNaN(focusY)) {
                lift = bottomAligned;
            } else {
                lift = (visibleTop + visibleBottom) / 2f - (containerTop + focusY);
                lift = Math.max(bottomAligned, Math.min(topAligned, lift));
            }
        }
        return Math.min(0f, lift);
    }
}
