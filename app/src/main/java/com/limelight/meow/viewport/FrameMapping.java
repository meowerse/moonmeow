package com.limelight.meow.viewport;

/**
 * Where a decoded frame's pixels sit in the uncropped reference frame. Immutable, pure.
 *
 * <p>Per axis, {@code reference = offset + frame * scale}, both in negotiated-stream pixels.
 * While the host streams the whole desktop this is the identity. When it streams a crop the
 * decoded frame shows only that crop, stretched to the encoder resolution (and letterboxed
 * inside it), so one decoded pixel covers {@code scale < 1} reference pixels starting at
 * {@code offset}. {@link HostCropPlan} derives it from the host's echo.
 */
public final class FrameMapping {

    /** The uncropped stream: decoded pixel == reference pixel. */
    public static final FrameMapping IDENTITY = new FrameMapping(1.0, 1.0, 0.0, 0.0);

    public final double scaleX;
    public final double scaleY;
    public final double offsetX;
    public final double offsetY;

    public FrameMapping(double scaleX, double scaleY, double offsetX, double offsetY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public boolean isIdentity() {
        return scaleX == 1.0 && scaleY == 1.0 && offsetX == 0.0 && offsetY == 0.0;
    }

    /** Decoded-frame x to reference x. */
    public double toReferenceX(double frameX) {
        return offsetX + frameX * scaleX;
    }

    /** Decoded-frame y to reference y. */
    public double toReferenceY(double frameY) {
        return offsetY + frameY * scaleY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FrameMapping)) {
            return false;
        }
        FrameMapping other = (FrameMapping) o;
        return scaleX == other.scaleX && scaleY == other.scaleY
                && offsetX == other.offsetX && offsetY == other.offsetY;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(scaleX) * 31 * 31 * 31 + Double.hashCode(scaleY) * 31 * 31
                + Double.hashCode(offsetX) * 31 + Double.hashCode(offsetY);
    }

    @Override
    public String toString() {
        return "FrameMapping{scale=" + scaleX + "x" + scaleY
                + " offset=" + offsetX + "," + offsetY + "}";
    }
}
