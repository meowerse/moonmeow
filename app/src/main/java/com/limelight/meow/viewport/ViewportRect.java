package com.limelight.meow.viewport;

/**
 * An immutable rectangle of the <em>stream frame</em> — the negotiated stream resolution,
 * uncropped — with (0,0) at its top-left. Not host desktop pixels; see
 * {@link ViewportReporter} for why that is the only space both ends can compute.
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

    /** The whole stream frame. Before the reference frame is known, this means "uncrop". */
    public static ViewportRect full(int streamWidth, int streamHeight) {
        return new ViewportRect(0, 0, streamWidth, streamHeight);
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
