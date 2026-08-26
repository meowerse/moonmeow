package com.limelight.meow.cursor;

/**
 * How far the visible crop should move, in stream-frame pixels, to keep the cursor in view.
 *
 * <p>Immutable and Android-free. {@link #NONE} is the common case by a wide margin — the
 * cursor is comfortably inside the crop and nothing should move — so it is a shared
 * instance rather than a fresh allocation on every mouse event.
 */
public final class CursorFollowPlan {

    /** "Do not move." Returned for every event that does not reach a border. */
    public static final CursorFollowPlan NONE = new CursorFollowPlan(0, 0);

    /** Movement along x in stream-frame pixels. Positive moves the crop to the right. */
    public final int dx;
    /** Movement along y in stream-frame pixels. Positive moves the crop downwards. */
    public final int dy;

    private CursorFollowPlan(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    static CursorFollowPlan of(int dx, int dy) {
        return (dx == 0 && dy == 0) ? NONE : new CursorFollowPlan(dx, dy);
    }

    /** True when the crop should actually be moved. */
    public boolean isMove() {
        return dx != 0 || dy != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CursorFollowPlan)) {
            return false;
        }
        CursorFollowPlan other = (CursorFollowPlan) o;
        return dx == other.dx && dy == other.dy;
    }

    @Override
    public int hashCode() {
        return dx * 31 + dy;
    }

    @Override
    public String toString() {
        return "CursorFollowPlan{" + dx + "," + dy + "}";
    }
}
