package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The single invariant behind "no double magnification": every decoded pixel lands on screen
 * exactly where the reference point it shows lands under the user's logical transform.
 */
public class ViewCompositionTest {

    private static final int VIEW_W = 2712;
    private static final int VIEW_H = 1220;
    private static final int STREAM_W = 1920;
    private static final int STREAM_H = 1080;

    @Test
    public void anUncroppedStreamIsPresentedExactlyUnderTheLogicalTransform() {
        float[] out = ViewComposition.present(3.5f, -1200f, -800f, VIEW_W, VIEW_H,
                STREAM_W, STREAM_H, FrameMapping.IDENTITY, new float[4]);
        assertEquals(3.5f, out[ViewComposition.SCALE_X], 0f);
        assertEquals(3.5f, out[ViewComposition.SCALE_Y], 0f);
        assertEquals(-1200f, out[ViewComposition.X], 0f);
        assertEquals(-800f, out[ViewComposition.Y], 0f);
    }

    @Test
    public void aCroppedFrameLandsWhereItsReferencePointsBelong() {
        // Host streams the reference box starting at (600, 300) at a quarter of the scale.
        FrameMapping mapping = new FrameMapping(0.25, 0.25, 600, 300);
        float scale = 4f;
        float logicalX = -2000f;
        float logicalY = -900f;
        float[] out = ViewComposition.present(scale, logicalX, logicalY, VIEW_W, VIEW_H,
                STREAM_W, STREAM_H, mapping, new float[4]);

        double pxPerRefX = (double) VIEW_W / STREAM_W;
        double pxPerRefY = (double) VIEW_H / STREAM_H;
        for (int f = 0; f <= STREAM_W; f += 240) {
            double presentedX = out[ViewComposition.X]
                    + f * pxPerRefX * out[ViewComposition.SCALE_X];
            double logicalScreenX = logicalX + mapping.toReferenceX(f) * pxPerRefX * scale;
            assertEquals(logicalScreenX, presentedX, 0.01);
        }
        for (int f = 0; f <= STREAM_H; f += 135) {
            double presentedY = out[ViewComposition.Y]
                    + f * pxPerRefY * out[ViewComposition.SCALE_Y];
            double logicalScreenY = logicalY + mapping.toReferenceY(f) * pxPerRefY * scale;
            assertEquals(logicalScreenY, presentedY, 0.01);
        }
        // And the net magnification of the decoded picture is the logical zoom times the
        // host's reduction: 4 x 0.25 = 1, i.e. the crop is shown at its native size.
        assertEquals(1f, out[ViewComposition.SCALE_X], 1e-6f);
    }

    @Test
    public void degenerateSizesFallBackToTheLogicalTransform() {
        FrameMapping mapping = new FrameMapping(0.5, 0.5, 10, 10);
        float[] out = ViewComposition.present(2f, 5f, 6f, 0, 0, STREAM_W, STREAM_H, mapping,
                new float[4]);
        assertEquals(2f, out[ViewComposition.SCALE_X], 0f);
        assertEquals(5f, out[ViewComposition.X], 0f);
    }
}
