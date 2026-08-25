package com.limelight.meow.viewport;

/**
 * An immutable rectangle of the host's video frame, in host pixels, with (0,0) at the
 * top-left.
 *
 * <p>The wire format ({@code LiSendViewportEvent}) carries four {@code uint16}s, so every
 * rectangle is clamped into that range at construction. The C side clamps again — the
 * implicit conversion at the {@code LiSendViewportEvent} call site truncates silently, and
 * a truncated 70000 becomes 4464, which is a plausible-looking rectangle in the wrong
 * place rather than an obvious failure.
 *
 * <p>Width and height are always at least 1: the protocol rejects a zero-sized viewport.
 */
public final class ViewportRect {

    /** Largest coordinate the wire format can carry. */
    public static final int MAX_COORD = 65535;

    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public ViewportRect(int x, int y, int width, int height) {
        this.x = clamp(x, 0, MAX_COORD);
        this.y = clamp(y, 0, MAX_COORD);
        this.width = clamp(width, 1, MAX_COORD);
        this.height = clamp(height, 1, MAX_COORD);
    }

    /** The whole host frame. This is the rectangle that means "stop cropping". */
    public static ViewportRect full(int hostWidth, int hostHeight) {
        return new ViewportRect(0, 0, hostWidth, hostHeight);
    }

    /** True when this rectangle covers the entire {@code hostWidth} x {@code hostHeight} frame. */
    public boolean coversAllOf(int hostWidth, int hostHeight) {
        return x == 0 && y == 0
                && width == clamp(hostWidth, 1, MAX_COORD)
                && height == clamp(hostHeight, 1, MAX_COORD);
    }

    /**
     * The largest absolute difference between any pair of corresponding edges. Used by
     * {@link ViewportThrottle} to decide whether a change is worth a control-stream packet.
     */
    public int maxEdgeDelta(ViewportRect other) {
        if (other == null) {
            return Integer.MAX_VALUE;
        }
        int dx = Math.abs(x - other.x);
        int dy = Math.abs(y - other.y);
        int dRight = Math.abs((x + width) - (other.x + other.width));
        int dBottom = Math.abs((y + height) - (other.y + other.height));
        return Math.max(Math.max(dx, dy), Math.max(dRight, dBottom));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ViewportRect)) {
            return false;
        }
        ViewportRect other = (ViewportRect) o;
        return x == other.x && y == other.y && width == other.width && height == other.height;
    }

    @Override
    public int hashCode() {
        return ((x * 31 + y) * 31 + width) * 31 + height;
    }

    @Override
    public String toString() {
        return "ViewportRect{" + x + "," + y + " " + width + "x" + height + "}";
    }
}
