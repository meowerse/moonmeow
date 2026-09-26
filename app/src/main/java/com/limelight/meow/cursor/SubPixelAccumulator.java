package com.limelight.meow.cursor;

/**
 * Turns fractional pointer deltas into whole ones without losing the fraction. Plain Java, no
 * allocation.
 *
 * <p>The wire carries relative mouse motion as whole pixels. A touch context that scales each
 * sample by a sensitivity and truncates the result throws away up to a pixel per sample; at
 * 1.5x with a finger moving a pixel per sample that is a third of all motion, and at 0.7x a
 * slow drag sends nothing at all. Carrying the remainder into the next sample makes the sum of
 * what is sent equal the sum of what was asked for, to within one pixel, in either direction.
 * Truncation toward zero keeps the remainder's sign with the motion, so left and right behave
 * the same.
 */
public final class SubPixelAccumulator {

    private float remainderX;
    private float remainderY;

    /** The whole pixels of {@code delta} plus what was carried, keeping the new remainder. */
    public short x(float delta) {
        float total = remainderX + delta;
        short whole = (short) total;
        remainderX = total - whole;
        return whole;
    }

    /** As {@link #x}, for the vertical axis. */
    public short y(float delta) {
        float total = remainderY + delta;
        short whole = (short) total;
        remainderY = total - whole;
        return whole;
    }

    public void reset() {
        remainderX = 0f;
        remainderY = 0f;
    }
}
