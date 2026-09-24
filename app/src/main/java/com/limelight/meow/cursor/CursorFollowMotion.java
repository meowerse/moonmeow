package com.limelight.meow.cursor;

/**
 * One frame of the view chasing the cursor, per axis. Pure arithmetic, no allocation.
 *
 * <p>The target is {@link CursorFollowPlanner}'s: the smallest shift of the visible rectangle
 * that brings the cursor back inside a margin from its edges, clamped so the view never leaves
 * the desktop. What this adds is how the view gets there: an exponential ease with time
 * constant {@link #TAU_SECONDS}, capped at {@link #MAX_VIEWS_PER_SECOND} view-widths a
 * second, and snapped once it is within half a pixel. The old follower jumped the whole margin
 * on every input event, so a cursor held at the edge slammed the view along in 12% steps; this
 * one moves at most one frame's worth per vsync, independent of how often input arrives.
 *
 * <p>Units are reference (negotiated-stream) pixels throughout; the caller converts to view
 * pixels for the pan.
 */
public final class CursorFollowMotion {

    /** Time constant of the ease. Short enough to feel attached to the cursor. */
    static final float TAU_SECONDS = 0.07f;

    /** Speed cap, in widths of the visible rectangle per second. */
    static final float MAX_VIEWS_PER_SECOND = 3f;

    /** Below this remaining distance the step finishes the move. */
    static final float SETTLE_PX = 0.5f;

    /** The margin never eats more than this of the visible size, so both edges keep a band. */
    private static final float MAX_MARGIN_FRACTION = 0.4f;

    private CursorFollowMotion() {
    }

    /**
     * How far the visible rectangle still needs to move on this axis to satisfy the margin.
     *
     * @param start          visible start on this axis
     * @param size           visible size on this axis
     * @param cursor         cursor position on this axis
     * @param boundsMin      content start (the desktop inside the frame)
     * @param boundsMax      content end
     * @param marginFraction margin as a fraction of {@code size}
     * @return the full remaining shift; 0 when the cursor is inside the margin or the axis is
     *         wholly visible
     */
    public static float remaining(float start, float size, float cursor,
                                  float boundsMin, float boundsMax, float marginFraction) {
        if (!(size > 0f) || size >= boundsMax - boundsMin) {
            return 0f;
        }
        float margin = Math.max(1f, Math.min(size * marginFraction, size * MAX_MARGIN_FRACTION));
        float desiredStart = start;
        if (cursor < start + margin) {
            desiredStart = cursor - margin;
        } else if (cursor > start + size - margin) {
            desiredStart = cursor + margin - size;
        } else {
            return 0f;
        }
        desiredStart = Math.max(boundsMin, Math.min(desiredStart, boundsMax - size));
        return desiredStart - start;
    }

    /**
     * The part of {@code remaining} to cover in a frame of {@code dtSeconds}.
     *
     * @param remaining shift still needed, from {@link #remaining}
     * @param size      visible size on this axis, which scales the speed cap
     */
    public static float step(float remaining, float size, float dtSeconds) {
        if (remaining == 0f) {
            return 0f;
        }
        float magnitude = Math.abs(remaining);
        if (magnitude <= SETTLE_PX) {
            return remaining;
        }
        float dt = Math.max(0f, dtSeconds);
        float eased = magnitude * (float) (1.0 - Math.exp(-dt / TAU_SECONDS));
        float cap = MAX_VIEWS_PER_SECOND * Math.max(1f, size) * dt;
        float moved = Math.max(SETTLE_PX, Math.min(eased, cap));
        return Math.copySign(Math.min(moved, magnitude), remaining);
    }
}
