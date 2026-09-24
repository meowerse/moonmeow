package com.limelight.meow.cursor;

/**
 * One frame of the view chasing the cursor, per axis. Pure arithmetic, no allocation.
 *
 * <p>The target is the smallest shift of the visible rectangle that brings the cursor back
 * inside a margin from its edges, clamped so the view never leaves the desktop
 * ({@link #remaining}). How the view gets there ({@link #velocity}) has two regimes:
 * <ul>
 *   <li><b>Gentle</b>, while the cursor is on screen but inside the margin band: an ease with
 *       time constant {@link #GENTLE_TAU_SECONDS}, at most {@link #GENTLE_VIEWS_PER_SECOND}
 *       view-widths a second. This is the "cursor is pushing the edge" case, and it must feel
 *       like scrolling, not like being yanked.</li>
 *   <li><b>Fast</b>, while the cursor is off screen (a host teleport, a jump to the other
 *       monitor, a stale estimate corrected): {@link #FAST_TAU_SECONDS} and
 *       {@link #FAST_VIEWS_PER_SECOND}, so it is back within a few frames.</li>
 * </ul>
 * Both are acceleration-limited, so the view never starts or stops with a jolt, and neither
 * overshoots: the step is never larger than what remains, and anything under
 * {@link #SETTLE_PX} finishes the move. The old follower jumped the whole margin on every
 * input event; this one moves once per vsync, independent of how often input arrives.
 *
 * <p>Units are reference (negotiated-stream) pixels throughout; the caller converts to view
 * pixels for the pan.
 */
public final class CursorFollowMotion {

    public static final float GENTLE_TAU_SECONDS = 0.09f;
    public static final float GENTLE_VIEWS_PER_SECOND = 2.5f;
    public static final float GENTLE_VIEWS_PER_SECOND_SQUARED = 30f;

    public static final float FAST_TAU_SECONDS = 0.045f;
    public static final float FAST_VIEWS_PER_SECOND = 10f;
    public static final float FAST_VIEWS_PER_SECOND_SQUARED = 250f;

    /** Below this remaining distance the step finishes the move. */
    public static final float SETTLE_PX = 0.5f;

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
     * The velocity to move at this frame, in reference pixels per second, signed like
     * {@code remaining}.
     *
     * @param remaining        shift still needed, from {@link #remaining}
     * @param size             visible size on this axis, which scales the caps
     * @param previousVelocity what the last frame moved at (0 when starting)
     * @param dtSeconds        frame time
     * @param offScreen        the cursor is outside the visible rectangle
     */
    public static float velocity(float remaining, float size, float previousVelocity,
                                 float dtSeconds, boolean offScreen) {
        if (remaining == 0f || !(dtSeconds > 0f)) {
            return 0f;
        }
        float views = Math.max(1f, size);
        float tau = offScreen ? FAST_TAU_SECONDS : GENTLE_TAU_SECONDS;
        float cap = (offScreen ? FAST_VIEWS_PER_SECOND : GENTLE_VIEWS_PER_SECOND) * views;
        float accel = (offScreen ? FAST_VIEWS_PER_SECOND_SQUARED
                : GENTLE_VIEWS_PER_SECOND_SQUARED) * views;

        float desired = remaining / tau;
        desired = Math.max(-cap, Math.min(desired, cap));
        float maxChange = accel * dtSeconds;
        return Math.max(previousVelocity - maxChange,
                Math.min(desired, previousVelocity + maxChange));
    }

    /**
     * The part of {@code remaining} to cover this frame at {@code velocity}: never past the
     * target, never backwards, and the whole of it once it is under {@link #SETTLE_PX}.
     */
    public static float step(float remaining, float velocity, float dtSeconds) {
        if (remaining == 0f) {
            return 0f;
        }
        if (Math.abs(remaining) <= SETTLE_PX) {
            return remaining;
        }
        float moved = velocity * Math.max(0f, dtSeconds);
        if (Math.signum(moved) != Math.signum(remaining)) {
            // Still decelerating from the other direction: hold rather than back up.
            return 0f;
        }
        return Math.abs(moved) >= Math.abs(remaining) ? remaining : moved;
    }
}
