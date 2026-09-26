package com.limelight.meow.viewport;

/**
 * Where {@link SurfaceFramePresenter} puts a decoded buffer inside the stream view's surface.
 * Pure arithmetic, no Android types.
 *
 * <p>The stream view keeps the user's <em>logical</em> transform, exactly as
 * {@code PanZoomHandler} writes it: its surface's local space is the uncropped reference frame
 * scaled to the view ({@code surfaceWidth / streamWidth} surface pixels per reference pixel).
 * The presenter's layer is a child of that surface, so everything here is in the surface's
 * local pixels and the view's transform composes on top. A decoded pixel {@code f} of a crop
 * shows reference point {@code offset + f * scale} ({@link FrameMapping}), so it goes to
 * {@code (offset + f * scale) * surfaceWidth / streamWidth}: a layer at
 * {@code offset * surfaceWidth / streamWidth}, scaled by {@code scale * surfaceWidth / streamWidth}
 * per decoded pixel. The buffer may be larger than the stream (codec alignment) with a crop
 * rectangle marking the picture; decoded pixel 0 is the crop's left edge, and a buffer pixel
 * is {@code streamWidth / cropWidth} decoded pixels (1 unless the codec scaled).
 *
 * <p>Composed with the view's transform this is exactly {@link ViewComposition}'s presented
 * transform -- the same numbers, split between the view (logical, changed by gestures, drawn
 * by the UI) and the layer (the crop, changed only together with the buffer it belongs to).
 */
public final class FrameLayerGeometry {

    public static final int X = 0;
    public static final int Y = 1;
    public static final int SCALE_X = 2;
    public static final int SCALE_Y = 3;

    private FrameLayerGeometry() {
    }

    /**
     * @param mapping       what the buffer shows
     * @param surfaceWidth  the stream view's surface, local pixels
     * @param surfaceHeight
     * @param streamWidth   negotiated stream size
     * @param streamHeight
     * @param cropLeft      the picture inside the buffer, buffer pixels
     * @param cropTop
     * @param cropWidth
     * @param cropHeight
     * @param out           {x, y, scaleX, scaleY} of the layer, filled
     * @return {@code out}, or null when a size is degenerate
     */
    public static float[] layer(FrameMapping mapping, int surfaceWidth, int surfaceHeight,
                                int streamWidth, int streamHeight,
                                int cropLeft, int cropTop, int cropWidth, int cropHeight,
                                float[] out) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || streamWidth <= 0 || streamHeight <= 0
                || cropWidth <= 0 || cropHeight <= 0) {
            return null;
        }
        FrameMapping m = mapping != null ? mapping : FrameMapping.IDENTITY;
        double perReferenceX = (double) surfaceWidth / streamWidth;
        double perReferenceY = (double) surfaceHeight / streamHeight;
        double scaleX = m.scaleX * perReferenceX * streamWidth / cropWidth;
        double scaleY = m.scaleY * perReferenceY * streamHeight / cropHeight;
        out[SCALE_X] = (float) scaleX;
        out[SCALE_Y] = (float) scaleY;
        out[X] = (float) (m.offsetX * perReferenceX - cropLeft * scaleX);
        out[Y] = (float) (m.offsetY * perReferenceY - cropTop * scaleY);
        return out;
    }
}
