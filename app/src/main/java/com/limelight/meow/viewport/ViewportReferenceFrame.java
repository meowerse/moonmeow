package com.limelight.meow.viewport;

/**
 * Where the host's uncropped desktop sits inside the stream frame, and how to keep a
 * rectangle inside it. Pure arithmetic, no Android types.
 *
 * <p><b>Why this exists.</b> The host does not stretch the desktop to fill the encode
 * surface — it preserves aspect ratio and pads. A 5360x1440 desktop negotiated at 1920x516
 * lands as 1920x515 with a row of padding; at 1920x1080 it lands as 1920x515 with 282 rows
 * of black above and below. A rectangle the client reports that lies wholly in that
 * padding maps to no desktop at all, and the host answers by refusing the request and
 * streaming the whole desktop — the exact opposite of what the user asked for.
 *
 * <p>The client cannot compute this until the host tells it the captured desktop size,
 * which arrives on the {@code ConnListenerSetViewport} echo. Until then there is no
 * reference frame and {@link ViewportReporter} sends rectangles unclamped; the host clamps
 * them itself, so nothing is unsafe, it is just less precise.
 *
 * <p><b>The arithmetic is a deliberate mirror.</b> It reproduces
 * {@code meow::viewport::full_frame_plan()} in {@code sunmeow/src/meow/viewport.h} exactly,
 * including the {@code float} scalar, the truncating multiply and the integer halving. Not
 * "equivalent to" — identical, because the two ends have to agree on which pixel row the
 * content starts at, and a {@code double} here would disagree with the host's {@code float}
 * by a row on some sizes. Do not tidy it up.
 */
public final class ViewportReferenceFrame {

    /** Left edge of the desktop image inside the stream frame, in stream pixels. */
    public final int contentX;
    /** Top edge of the desktop image inside the stream frame, in stream pixels. */
    public final int contentY;
    /** Width of the desktop image inside the stream frame, in stream pixels. */
    public final int contentWidth;
    /** Height of the desktop image inside the stream frame, in stream pixels. */
    public final int contentHeight;

    private ViewportReferenceFrame(int contentX, int contentY,
                                   int contentWidth, int contentHeight) {
        this.contentX = contentX;
        this.contentY = contentY;
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
    }

    /**
     * @param desktopWidth  captured desktop width in host pixels
     * @param desktopHeight captured desktop height in host pixels
     * @param streamWidth   negotiated stream width
     * @param streamHeight  negotiated stream height
     * @return the frame, or null when any input is degenerate or the content collapses
     */
    public static ViewportReferenceFrame of(int desktopWidth, int desktopHeight,
                                            int streamWidth, int streamHeight) {
        if (desktopWidth <= 0 || desktopHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) {
            return null;
        }
        float scalar = Math.min((float) streamWidth / (float) desktopWidth,
                (float) streamHeight / (float) desktopHeight);
        int outWidth = (int) ((float) desktopWidth * scalar);
        int outHeight = (int) ((float) desktopHeight * scalar);
        if (outWidth <= 0 || outHeight <= 0) {
            return null;
        }
        return new ViewportReferenceFrame((streamWidth - outWidth) / 2,
                (streamHeight - outHeight) / 2, outWidth, outHeight);
    }

    /**
     * Intersects a rectangle with the content area.
     *
     * @return the clamped rectangle, or null when it does not overlap the content at all —
     *         which means the user is looking only at padding, and there is nothing
     *         truthful to report
     */
    public ViewportRect clamp(ViewportRect rect) {
        if (rect == null) {
            return null;
        }
        int left = Math.max(rect.x, contentX);
        int top = Math.max(rect.y, contentY);
        int right = Math.min(rect.x + rect.width, contentX + contentWidth);
        int bottom = Math.min(rect.y + rect.height, contentY + contentHeight);
        if (right <= left || bottom <= top) {
            return null;
        }
        return new ViewportRect(left, top, right - left, bottom - top);
    }

    /** The whole desktop, expressed in stream pixels. The rectangle that means "uncrop". */
    public ViewportRect fullContent() {
        return new ViewportRect(contentX, contentY, contentWidth, contentHeight);
    }

    @Override
    public String toString() {
        return "ViewportReferenceFrame{" + contentX + "," + contentY
                + " " + contentWidth + "x" + contentHeight + "}";
    }
}
