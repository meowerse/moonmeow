package com.limelight.meow.cursor;

import com.limelight.meow.viewport.ViewportRect;

/**
 * Decides how far the visible crop must move to keep the host cursor on screen. Pure
 * arithmetic, no Android types, so the whole behaviour is unit tested directly
 * (CLAUDE.md §5 rule 3).
 *
 * <h2>The two behaviours, and why they are one function</h2>
 * The user described these as separate wishes — "if it's not here move to it" and "make
 * cursor to the top of viewing part and it will move viewing part to the top while cursor
 * still stays on the top border" — but they are the same rule at two distances:
 *
 * <ul>
 *   <li><b>Edge-pan.</b> The cursor is inside the crop but within {@code margin} of an
 *       edge. The crop shifts so the cursor sits exactly on that margin line, which is what
 *       makes the cursor appear pinned to the border while the content scrolls under it.
 *   <li><b>Catch-up.</b> The cursor is outside the crop entirely — it jumped, or the user
 *       zoomed somewhere else. The same formula produces a large delta and the crop lands
 *       with the cursor back on the margin line. No separate "recentre" case, because a
 *       recentre would move the crop further than the user asked and lose the region they
 *       were reading.
 * </ul>
 *
 * <p>One rule means there is no discontinuity between them: a cursor that leaves the crop
 * quickly gets the same treatment as one that crept to the edge, only further.
 *
 * <h2>Clamping is what lets the cursor reach the corner</h2>
 * The crop is clamped into the desktop content box. At the far edge the clamp wins and the
 * crop stops moving — and because this class only ever moves the <em>crop</em>, never the
 * cursor, the cursor then keeps travelling to the true corner on its own. That is the one
 * behaviour that a naive "always keep the cursor on the margin line" implementation gets
 * wrong: it would refuse to let the pointer touch the edge of the screen.
 *
 * <h2>Coordinate space</h2>
 * Everything is in <b>stream-frame pixels</b>, the same space as {@link ViewportRect} and
 * {@code LiSendMousePositionEvent}. Callers convert to view pixels themselves; see
 * {@code CursorFollowController}.
 */
public final class CursorFollowPlanner {

    /**
     * Fraction of the crop's shorter side that counts as the border zone.
     *
     * <p>Small enough that ordinary pointing near the middle never triggers a pan, large
     * enough that the user does not have to hit a one-pixel line to start scrolling. At the
     * 4x zoom this feature is for, 12% of a 1340px-wide crop is ~160 host pixels — roughly a
     * fingertip.
     */
    public static final float DEFAULT_EDGE_MARGIN_FRACTION = 0.12f;

    /**
     * Never let the margin eat more than this share of an axis. Without it, a very narrow
     * crop would have overlapping left and right border zones and the two would fight,
     * producing an oscillation the user sees as jitter.
     */
    private static final float MAX_MARGIN_FRACTION_PER_AXIS = 0.4f;

    private final float edgeMarginFraction;

    public CursorFollowPlanner() {
        this(DEFAULT_EDGE_MARGIN_FRACTION);
    }

    public CursorFollowPlanner(float edgeMarginFraction) {
        // A negative or absurd fraction is a programming error, not user input; clamp rather
        // than throw, because this sits on the mouse-event path.
        this.edgeMarginFraction = Math.max(0f, Math.min(edgeMarginFraction, 0.5f));
    }

    /**
     * @param visible   the crop currently on screen, in stream-frame pixels
     * @param cursorX   cursor position in stream-frame pixels
     * @param cursorY   cursor position in stream-frame pixels
     * @param boundsX   left edge of the desktop content box, in stream-frame pixels
     * @param boundsY   top edge of the desktop content box
     * @param boundsW   width of the desktop content box
     * @param boundsH   height of the desktop content box
     * @return how far to move the crop; {@link CursorFollowPlan#NONE} when it should not move
     */
    public CursorFollowPlan plan(ViewportRect visible, int cursorX, int cursorY,
                                 int boundsX, int boundsY, int boundsW, int boundsH) {
        if (visible == null || boundsW <= 0 || boundsH <= 0) {
            return CursorFollowPlan.NONE;
        }

        // A crop that already covers the whole content box has nowhere to go. Checking this
        // explicitly keeps the unzoomed case — every event, on every stream — free of the
        // arithmetic below, and makes "zoomed out means no follow" a property of the class
        // rather than something each caller has to remember.
        if (visible.width >= boundsW && visible.height >= boundsH) {
            return CursorFollowPlan.NONE;
        }

        int dx = axis(visible.x, visible.width, cursorX, boundsX, boundsW);
        int dy = axis(visible.y, visible.height, cursorY, boundsY, boundsH);
        return CursorFollowPlan.of(dx, dy);
    }

    /**
     * One axis. Returns how far the crop must move along it, already clamped into bounds.
     *
     * @param start     current left/top of the crop
     * @param size      current width/height of the crop
     * @param cursor    cursor position on this axis
     * @param boundsMin left/top of the content box
     * @param boundsSize width/height of the content box
     */
    private int axis(int start, int size, int cursor, int boundsMin, int boundsSize) {
        if (size >= boundsSize) {
            // This axis is fully visible; only the other one can scroll.
            return 0;
        }

        int margin = marginFor(size);
        int desiredStart = start;

        int lowLine = start + margin;
        int highLine = start + size - margin;

        if (cursor < lowLine) {
            desiredStart = cursor - margin;
        }
        else if (cursor > highLine) {
            desiredStart = cursor + margin - size;
        }
        else {
            return 0;
        }

        // The clamp is what lets the cursor reach the true edge of the desktop: once the
        // crop is against the boundary it stops, and the cursor carries on into the margin.
        int maxStart = boundsMin + boundsSize - size;
        desiredStart = Math.max(boundsMin, Math.min(desiredStart, maxStart));

        return desiredStart - start;
    }

    /** The border zone for a crop of this size on one axis, in stream-frame pixels. */
    private int marginFor(int size) {
        int margin = Math.round(size * edgeMarginFraction);
        int cap = (int) (size * MAX_MARGIN_FRACTION_PER_AXIS);
        return Math.max(1, Math.min(margin, Math.max(1, cap)));
    }
}
