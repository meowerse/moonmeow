package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Random;

public class FrameLayerGeometryTest {

    private final float[] out = new float[4];

    @Test
    public void anUncroppedFrameFillsTheSurface() {
        FrameLayerGeometry.layer(FrameMapping.IDENTITY, 1220, 2712, 1220, 2712,
                0, 0, 1220, 2712, out);
        assertEquals(0f, out[FrameLayerGeometry.X], 0f);
        assertEquals(0f, out[FrameLayerGeometry.Y], 0f);
        assertEquals(1f, out[FrameLayerGeometry.SCALE_X], 1e-6f);
        assertEquals(1f, out[FrameLayerGeometry.SCALE_Y], 1e-6f);
    }

    @Test
    public void aSurfaceOfAnotherSizeScalesTheWholeFrameOntoIt() {
        FrameLayerGeometry.layer(FrameMapping.IDENTITY, 2400, 644, 5360, 1440,
                0, 0, 5360, 1440, out);
        assertEquals(2400f / 5360f, out[FrameLayerGeometry.SCALE_X], 1e-6f);
    }

    /**
     * The layer under the view's logical transform is exactly the presented transform the
     * view-property path computes ({@link ViewComposition}).
     */
    @Test
    public void composedWithTheViewItIsThePresentedTransform() {
        Random random = new Random(7);
        float[] presented = new float[4];
        for (int i = 0; i < 1000; i++) {
            int surfaceW = 800 + random.nextInt(2000);
            int surfaceH = 600 + random.nextInt(2000);
            int streamW = 1280 + random.nextInt(1000);
            int streamH = 720 + random.nextInt(2000);
            FrameMapping m = new FrameMapping(0.1 + random.nextDouble(), 0.1 + random.nextDouble(),
                    random.nextDouble() * 500, random.nextDouble() * 500);
            float scale = 1f + random.nextFloat() * 9f;
            float x = -random.nextFloat() * 5000f;
            float y = -random.nextFloat() * 5000f;
            FrameLayerGeometry.layer(m, surfaceW, surfaceH, streamW, streamH,
                    0, 0, streamW, streamH, out);
            ViewComposition.present(scale, x, y, surfaceW, surfaceH, streamW, streamH, m,
                    presented);
            // The view scales the layer's position and size by its own scale.
            assertEquals(presented[ViewComposition.X], x + out[FrameLayerGeometry.X] * scale,
                    0.05f);
            assertEquals(presented[ViewComposition.Y], y + out[FrameLayerGeometry.Y] * scale,
                    0.05f);
            // ViewComposition's scale is per view pixel of a view spanning the stream.
            assertEquals(presented[ViewComposition.SCALE_X] * surfaceW / streamW,
                    out[FrameLayerGeometry.SCALE_X] * scale, 1e-3f);
            assertEquals(presented[ViewComposition.SCALE_Y] * surfaceH / streamH,
                    out[FrameLayerGeometry.SCALE_Y] * scale, 1e-3f);
        }
    }

    @Test
    public void aCodecCropOffsetIsSkippedNotShown() {
        // Buffer 1232x2720 (aligned) with the picture at (4, 4) 1220x2712.
        FrameLayerGeometry.layer(FrameMapping.IDENTITY, 1220, 2712, 1220, 2712,
                4, 4, 1220, 2712, out);
        assertEquals(-4f, out[FrameLayerGeometry.X], 1e-4f);
        assertEquals(-4f, out[FrameLayerGeometry.Y], 1e-4f);
    }

    @Test
    public void degenerateSizesAreRefused() {
        assertNull(FrameLayerGeometry.layer(FrameMapping.IDENTITY, 0, 10, 10, 10,
                0, 0, 10, 10, out));
        assertNull(FrameLayerGeometry.layer(FrameMapping.IDENTITY, 10, 10, 10, 10,
                0, 0, 0, 10, out));
    }
}
