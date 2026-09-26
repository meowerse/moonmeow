package com.limelight.meow.viewport;

/**
 * The transform the stream view actually needs, given the user's logical zoom and what the
 * decoded frame currently shows. Pure arithmetic, no Android types.
 *
 * <h2>The two transforms</h2>
 * <ul>
 *   <li><b>Logical</b> — what {@code PanZoomHandler} computes from pinches and pans: scale
 *       {@code S} and origin {@code (x, y)} of the <em>uncropped reference frame</em> inside
 *       the parent. This is the user's view V, and it is what they asked to see.</li>
 *   <li><b>Presented</b> — what is put on the {@code SurfaceView}. While the host streams the
 *       whole desktop the two are identical. Once it streams a crop, the decoded frame is that
 *       crop already magnified, so presenting it under the logical transform magnifies it a
 *       second time (the "zoom²" defect). The presented transform undoes exactly the host's
 *       part.</li>
 * </ul>
 *
 * <p>With pivot (0,0) — which {@code PanZoomHandler} sets — a decoded pixel {@code f} lands at
 * {@code x' + f * (viewWidth / streamWidth) * scaleX'}. It must land where its reference point
 * {@code r = offset + f * k} lands under the logical transform,
 * {@code x + r * (viewWidth / streamWidth) * S}. Equating the two for every {@code f}:
 * <pre>
 *   scaleX' = S * k
 *   x'      = x + offset * (viewWidth / streamWidth) * S
 * </pre>
 * and likewise for y. When the mapping is the identity this is the logical transform itself,
 * bit for bit — the invariant that makes the change invisible to an unzoomed stream.
 */
public final class ViewComposition {

    /** Indices into the four-element result. */
    public static final int SCALE_X = 0;
    public static final int SCALE_Y = 1;
    public static final int X = 2;
    public static final int Y = 3;

    private ViewComposition() {
    }

    /**
     * @param logicalScale the user's zoom {@code S}
     * @param logicalX     left edge of the reference frame in parent pixels
     * @param logicalY     top edge of the reference frame in parent pixels
     * @param viewWidth    the stream view's unscaled width (it spans the whole frame)
     * @param viewHeight   the stream view's unscaled height
     * @param streamWidth  negotiated stream width
     * @param streamHeight negotiated stream height
     * @param mapping      what the decoded frame shows
     * @param out          four-element buffer, filled with scaleX, scaleY, x, y
     * @return {@code out}
     */
    public static float[] present(float logicalScale, float logicalX, float logicalY,
                                  int viewWidth, int viewHeight,
                                  int streamWidth, int streamHeight,
                                  FrameMapping mapping, float[] out) {
        if (mapping == null || mapping.isIdentity()
                || viewWidth <= 0 || viewHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) {
            out[SCALE_X] = logicalScale;
            out[SCALE_Y] = logicalScale;
            out[X] = logicalX;
            out[Y] = logicalY;
            return out;
        }
        double pixelsPerReferenceX = (double) viewWidth / streamWidth * logicalScale;
        double pixelsPerReferenceY = (double) viewHeight / streamHeight * logicalScale;
        out[SCALE_X] = (float) (logicalScale * mapping.scaleX);
        out[SCALE_Y] = (float) (logicalScale * mapping.scaleY);
        out[X] = (float) (logicalX + mapping.offsetX * pixelsPerReferenceX);
        out[Y] = (float) (logicalY + mapping.offsetY * pixelsPerReferenceY);
        return out;
    }
}
