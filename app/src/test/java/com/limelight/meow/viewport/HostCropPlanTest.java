package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * {@link HostCropPlan} must place every decoded pixel where the host took it from. These tests
 * model the host independently — its cropped {@code plan()} for a desktop rectangle, and the
 * {@code to_reference()} it answers with — and check that the client, given only the echo,
 * recovers the same mapping.
 */
public class HostCropPlanTest {

    // The live topology from the spec: 5360x1440 union desktop into a 1920x1080 stream.
    private static final int DESKTOP_W = 5360;
    private static final int DESKTOP_H = 1440;
    private static final int STREAM_W = 1920;
    private static final int STREAM_H = 1080;

    /** Independent model of what sunmeow does for a desktop-space source rectangle. */
    private static final class HostCrop {
        final int srcX, srcY, srcW, srcH;
        final int outW, outH, offW, offH;
        final ViewportReferenceFrame reference;
        final int captureW, captureH;

        HostCrop(int srcX, int srcY, int srcW, int srcH, int captureW, int captureH,
                 int surfaceW, int surfaceH) {
            this.srcX = srcX;
            this.srcY = srcY;
            this.srcW = srcW;
            this.srcH = srcH;
            this.captureW = captureW;
            this.captureH = captureH;
            float scalar = Math.min((float) surfaceW / srcW, (float) surfaceH / srcH);
            this.outW = ((int) ((float) srcW * scalar)) & ~1;
            this.outH = ((int) ((float) srcH * scalar)) & ~1;
            this.offW = ((surfaceW - outW) / 2) & ~1;
            this.offH = ((surfaceH - outH) / 2) & ~1;
            this.reference = ViewportReferenceFrame.of(captureW, captureH, surfaceW, surfaceH);
        }

        /** Ground truth: the reference x a decoded pixel column really shows. */
        double referenceX(double frameX) {
            double desktopX = srcX + (frameX - offW) * srcW / outW;
            return reference.contentX + desktopX * reference.contentWidth / captureW;
        }

        double referenceY(double frameY) {
            double desktopY = srcY + (frameY - offH) * srcH / outH;
            return reference.contentY + desktopY * reference.contentHeight / captureH;
        }

        /** {@code meow::viewport::to_reference(source)}: what the echo carries. */
        ViewportRect echo() {
            double sx = (double) reference.contentWidth / captureW;
            double sy = (double) reference.contentHeight / captureH;
            int left = reference.contentX + (int) Math.round(srcX * sx);
            int top = reference.contentY + (int) Math.round(srcY * sy);
            int right = reference.contentX + (int) Math.round((srcX + srcW) * sx);
            int bottom = reference.contentY + (int) Math.round((srcY + srcH) * sy);
            int x = clamp(left, reference.contentX, reference.contentX + reference.contentWidth - 1);
            int y = clamp(top, reference.contentY, reference.contentY + reference.contentHeight - 1);
            int w = clamp(right, x + 1, reference.contentX + reference.contentWidth) - x;
            int h = clamp(bottom, y + 1, reference.contentY + reference.contentHeight) - y;
            return new ViewportRect(x, y, w, h);
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(v, hi));
        }
    }

    @Test
    public void theFullDesktopIsTheIdentity() {
        ViewportReferenceFrame reference =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, STREAM_W, STREAM_H);
        assertSame(FrameMapping.IDENTITY, HostCropPlan.mappingFor(reference.fullContent(),
                DESKTOP_W, DESKTOP_H, STREAM_W, STREAM_H));
    }

    @Test
    public void aRefusedRequestEchoesTheFullContentAndIsTheIdentity() {
        // A host that refuses (sliver aspect, too small, padding only) echoes the whole content
        // area; the client must present it 1:1, not as a crop.
        assertSame(FrameMapping.IDENTITY, HostCropPlan.mappingFor(
                new ViewportRect(0, 0, STREAM_W, STREAM_H), 0, 0, STREAM_W, STREAM_H));
    }

    @Test
    public void aSliverTheHostWouldRefuseIsTheIdentity() {
        // 5360x64 into 1920x1080 would scale to 1920x22 < min_output_extent: the host streams
        // the whole desktop instead.
        HostCrop crop = new HostCrop(0, 600, DESKTOP_W, 64, DESKTOP_W, DESKTOP_H, STREAM_W, STREAM_H);
        assertTrue(crop.outH < HostCropPlan.MIN_OUTPUT_EXTENT);
        assertSame(FrameMapping.IDENTITY,
                HostCropPlan.mappingFor(crop.echo(), DESKTOP_W, DESKTOP_H, STREAM_W, STREAM_H));
    }

    @Test
    public void aCropOnTheLiveTopologyPlacesEveryDecodedPixelWhereTheHostTookIt() {
        // The user zoomed ~4x into the middle of the right monitor.
        HostCrop crop = new HostCrop(3400, 400, 1340, 720, DESKTOP_W, DESKTOP_H, STREAM_W, STREAM_H);
        FrameMapping mapping =
                HostCropPlan.mappingFor(crop.echo(), DESKTOP_W, DESKTOP_H, STREAM_W, STREAM_H);
        assertFalse(mapping.isIdentity());
        assertAgreesEverywhere(crop, mapping);
    }

    @Test
    public void randomCropsAgreeWithTheHostWithinAReferencePixel() {
        // The echo is rounded to whole reference pixels, so it cannot name the desktop source
        // exactly; see HostCropPlan's class comment. This pins how close the client gets:
        // nearly always within one reference pixel, never more than 1.75.
        Random random = new Random(42);
        int[][] topologies = {
                {DESKTOP_W, DESKTOP_H, STREAM_W, STREAM_H},
                {DESKTOP_W, DESKTOP_H, 2712, 1220},
                {1920, 1200, 1920, 1080},
                {3440, 1440, 1280, 720},
                {2560, 1440, 2560, 1440},
        };
        int crops = 0;
        int withinOnePixel = 0;
        double worst = 0;
        for (int[] t : topologies) {
            for (int i = 0; i < 400; i++) {
                int w = 64 + random.nextInt(t[0] - 64);
                int h = 64 + random.nextInt(t[1] - 64);
                int x = random.nextInt(t[0] - w + 1) & ~1;
                int y = random.nextInt(t[1] - h + 1) & ~1;
                // The host's sanitize() has already even-aligned whatever it streams.
                w = Math.min(w, t[0] - x) & ~1;
                h = Math.min(h, t[1] - y) & ~1;
                if (w < 64 || h < 64 || (x == 0 && y == 0 && w == t[0] && h == t[1])) {
                    continue;
                }
                HostCrop crop = new HostCrop(x, y, w, h, t[0], t[1], t[2], t[3]);
                if (crop.outW < 32 || crop.outH < 32) {
                    continue;
                }
                FrameMapping mapping = HostCropPlan.mappingFor(crop.echo(), t[0], t[1], t[2], t[3]);
                double error = worstError(crop, mapping);
                crops++;
                worst = Math.max(worst, error);
                if (error <= 1.0) {
                    withinOnePixel++;
                }
            }
        }
        assertTrue("worst " + worst, worst <= 1.75);
        assertTrue(withinOnePixel + " of " + crops + " within a reference pixel",
                withinOnePixel >= crops * 0.97);
    }

    @Test
    public void anEchoV1HostWithoutTheDesktopExtentIsModelledAsAStreamSizedDesktop() {
        // Same aspect as the stream, so the model is exact: a quarter crop in the middle.
        HostCrop crop = new HostCrop(480, 270, 960, 540, STREAM_W, STREAM_H, STREAM_W, STREAM_H);
        FrameMapping mapping = HostCropPlan.mappingFor(crop.echo(), 0, 0, STREAM_W, STREAM_H);
        assertEquals(0.5, mapping.scaleX, 1e-9);
        assertEquals(0.5, mapping.scaleY, 1e-9);
        assertEquals(480, mapping.offsetX, 1e-9);
        assertEquals(270, mapping.offsetY, 1e-9);
    }

    @Test
    public void theHostsEdgeIsAlwaysAmongTheCandidates() {
        int[] out = new int[HostCropPlan.MAX_EDGE_CANDIDATES];
        for (double toDesktop : new double[] {5360.0 / 1920.0, 1200.0 / 1080.0, 3440.0 / 1280.0,
                1.0}) {
            for (int edge = 0; edge <= 5360; edge += 2) {
                int echoed = (int) Math.round(edge / toDesktop);
                int count = HostCropPlan.edgeCandidates(echoed, toDesktop, 5360, out);
                boolean found = false;
                for (int i = 0; i < count; i++) {
                    assertEquals(0, out[i] & 1);
                    found |= out[i] == edge;
                }
                assertTrue("edge " + edge + " at " + toDesktop, found);
            }
        }
        assertEquals(0, HostCropPlan.floorEven(-3));
        assertEquals(6, HostCropPlan.floorEven(7));
    }

    private static double worstError(HostCrop crop, FrameMapping mapping) {
        double worst = 0;
        for (int step = 0; step <= 8; step++) {
            double fx = crop.offW + crop.outW * step / 8.0;
            double fy = crop.offH + crop.outH * step / 8.0;
            worst = Math.max(worst, Math.abs(crop.referenceX(fx) - mapping.toReferenceX(fx)));
            worst = Math.max(worst, Math.abs(crop.referenceY(fy) - mapping.toReferenceY(fy)));
        }
        return worst;
    }

    private static void assertAgreesEverywhere(HostCrop crop, FrameMapping mapping) {
        // The echo is rounded to whole reference pixels; see HostCropPlan's class comment.
        double tolerance = 1.0;
        for (int step = 0; step <= 8; step++) {
            double fx = crop.offW + crop.outW * step / 8.0;
            double fy = crop.offH + crop.outH * step / 8.0;
            assertEquals("x at frame " + fx + " for " + crop.echo(),
                    crop.referenceX(fx), mapping.toReferenceX(fx), tolerance);
            assertEquals("y at frame " + fy + " for " + crop.echo(),
                    crop.referenceY(fy), mapping.toReferenceY(fy), tolerance);
        }
    }
}
