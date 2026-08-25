package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The client's mirror of the host's letterbox transform.
 *
 * <p>The numbers here are the configuration the whole feature exists for: a 5360x1440
 * two-monitor desktop. It is 3.72:1, so at any ordinary stream aspect the host pads
 * heavily — and that padding is the thing a naive proportional mapping gets wrong.
 */
public class ViewportReferenceFrameTest {

    private static final int DESKTOP_W = 5360;
    private static final int DESKTOP_H = 1440;

    @Test
    public void aSixteenByNineStreamPadsTheWideDesktopTopAndBottom() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, 1920, 1080);
        assertNotNull(frame);
        // 1920/5360 is the binding scalar, so the content fills the width...
        assertEquals(0, frame.contentX);
        assertEquals(1920, frame.contentWidth);
        // ...and leaves a large band of padding on each side of it vertically.
        assertTrue("expected real padding, got " + frame, frame.contentY > 200);
        assertTrue("content must be far shorter than the surface, got " + frame,
                frame.contentHeight < 600);
        assertTrue("content must fit inside the surface, got " + frame,
                frame.contentY + frame.contentHeight <= 1080);
    }

    @Test
    public void aMatchingAspectLeavesEssentiallyNoPadding() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, 1920, 516);
        assertNotNull(frame);
        assertEquals(0, frame.contentX);
        assertEquals(1920, frame.contentWidth);
        assertTrue("at most a row or two of padding, got " + frame, frame.contentY <= 1);
    }

    @Test
    public void aTallStreamPadsLeftAndRightInstead() {
        ViewportReferenceFrame frame = ViewportReferenceFrame.of(1440, 5360, 1080, 1920);
        assertNotNull(frame);
        assertTrue("expected horizontal padding, got " + frame, frame.contentX > 200);
        assertTrue("content must be far narrower than the surface, got " + frame,
                frame.contentWidth < 600);
        assertEquals("height is the binding axis, so no vertical padding", 0, frame.contentY);
        assertEquals(1920, frame.contentHeight);
    }

    @Test
    public void thePaddingIsCentred() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, 1920, 1080);
        assertNotNull(frame);
        int above = frame.contentY;
        int below = 1080 - (frame.contentY + frame.contentHeight);
        assertTrue("centred within a pixel: " + above + " vs " + below,
                Math.abs(above - below) <= 1);
    }

    @Test
    public void degenerateInputsProduceNoFrameRatherThanADivideByZero() {
        assertNull(ViewportReferenceFrame.of(0, 1440, 1920, 1080));
        assertNull(ViewportReferenceFrame.of(5360, 0, 1920, 1080));
        assertNull(ViewportReferenceFrame.of(5360, 1440, 0, 1080));
        assertNull(ViewportReferenceFrame.of(5360, 1440, 1920, 0));
        assertNull(ViewportReferenceFrame.of(-1, -1, -1, -1));
    }

    @Test
    public void aDesktopSoLargeItScalesToNothingProducesNoFrame() {
        // 65535x65535 into 8x8: the truncating multiply yields 0 on one axis.
        assertNull(ViewportReferenceFrame.of(65535, 1, 1, 8));
    }

    @Test
    public void clampingKeepsARectangleInsideTheContentArea() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, 1920, 1080);
        assertNotNull(frame);
        ViewportRect whole = new ViewportRect(0, 0, 1920, 1080);
        ViewportRect clamped = frame.clamp(whole);
        assertNotNull(clamped);
        assertEquals(frame.fullContent(), clamped);
    }

    @Test
    public void aRectangleAlreadyInsideTheContentAreaIsUnchanged() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, 1920, 1080);
        assertNotNull(frame);
        ViewportRect inside = new ViewportRect(frame.contentX + 10, frame.contentY + 10,
                100, 50);
        assertEquals(inside, frame.clamp(inside));
    }

    @Test
    public void aRectangleEntirelyInThePaddingClampsToNothing() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, 1920, 1080);
        assertNotNull(frame);
        // The band above the content is pure black; there is no desktop there to ask for.
        assertNull(frame.clamp(new ViewportRect(0, 0, 1920, frame.contentY)));
    }

    @Test
    public void clampingNullIsNull() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, 1920, 1080);
        assertNotNull(frame);
        assertNull(frame.clamp(null));
    }

    @Test
    public void fullContentIsTheRectangleThatMeansUncrop() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, 1920, 1080);
        assertNotNull(frame);
        ViewportRect full = frame.fullContent();
        assertEquals(frame.contentX, full.x);
        assertEquals(frame.contentY, full.y);
        assertEquals(frame.contentWidth, full.width);
        assertEquals(frame.contentHeight, full.height);
    }

    @Test
    public void theArithmeticMatchesTheHostsFullFramePlanExactly() {
        // Mirrors meow::viewport::full_frame_plan() in sunmeow/src/meow/viewport.h: a float
        // scalar, a truncating multiply and an integer halving. A double here would disagree
        // with the host by a row on some sizes, and the two ends have to agree on which row
        // the content starts at.
        int[][] cases = {
                {5360, 1440, 1920, 1080},
                {5360, 1440, 1280, 720},
                {3840, 2160, 1920, 1080},
                {2560, 1080, 1920, 1080},
                {1920, 1080, 3840, 2160},
        };
        for (int[] c : cases) {
            float scalar = Math.min((float) c[2] / (float) c[0], (float) c[3] / (float) c[1]);
            int expectedW = (int) ((float) c[0] * scalar);
            int expectedH = (int) ((float) c[1] * scalar);
            ViewportReferenceFrame frame =
                    ViewportReferenceFrame.of(c[0], c[1], c[2], c[3]);
            assertNotNull(frame);
            assertEquals(expectedW, frame.contentWidth);
            assertEquals(expectedH, frame.contentHeight);
            assertEquals((c[2] - expectedW) / 2, frame.contentX);
            assertEquals((c[3] - expectedH) / 2, frame.contentY);
        }
    }
}
