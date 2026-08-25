package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The numbers here model the case that motivated the feature: a 5360x1440 two-monitor
 * desktop shown on a phone. At that aspect ratio {@code StreamContainer} measures itself
 * 2400x644 inside a 2400x1080 screen, and the SurfaceView fills it exactly.
 */
public class ViewportGeometryTest {

    private static final int HOST_W = 5360;
    private static final int HOST_H = 1440;
    private static final int VIEW_W = 2400;
    private static final int VIEW_H = 644;

    /** The transform PanZoomHandler produces at a given scale and offset. */
    private static ViewportRect at(float childX, float childY, float scale) {
        return ViewportGeometry.visibleHostRect(
                childX, childY, VIEW_W * scale, VIEW_H * scale,
                0, 0, VIEW_W, VIEW_H,
                HOST_W, HOST_H);
    }

    @Test
    public void unzoomedIsTheWholeDesktop() {
        // At scale 1 the stream view exactly fills its parent, which is why "zoom back to
        // 1:1 uncrops the host" needs no special case anywhere else in the feature.
        assertEquals(ViewportRect.full(HOST_W, HOST_H), at(0, 0, 1f));
    }

    @Test
    public void doubleZoomCentredShowsTheMiddleHalf() {
        // scale 2 centred: the view is 4800 wide, panned to -1200
        ViewportRect rect = at(-1200f, -322f, 2f);
        assertEquals(HOST_W / 4, rect.x);
        assertEquals(HOST_W / 2, rect.width);
        assertEquals(HOST_H / 4, rect.y);
        assertEquals(HOST_H / 2, rect.height);
    }

    @Test
    public void pannedHardRightLandsExactlyOnTheDesktopEdge() {
        // constrainToBounds clamps childX to parentWidth - childWidth at the right bound
        ViewportRect rect = at(VIEW_W - VIEW_W * 4f, 0f, 4f);
        assertEquals(HOST_W, rect.x + rect.width);
        assertEquals(HOST_W / 4, rect.width);
    }

    @Test
    public void pannedHardLeftStartsAtZero() {
        ViewportRect rect = at(0f, 0f, 4f);
        assertEquals(0, rect.x);
        assertEquals(HOST_W / 4, rect.width);
    }

    @Test
    public void theRectangleNeverLeavesTheDesktop() {
        for (float scale = 1f; scale <= 10f; scale += 0.37f) {
            float childWidth = VIEW_W * scale;
            for (float childX = -childWidth; childX <= VIEW_W; childX += 91f) {
                ViewportRect rect = ViewportGeometry.visibleHostRect(
                        childX, 0, childWidth, VIEW_H * scale,
                        0, 0, VIEW_W, VIEW_H, HOST_W, HOST_H);
                assertTrue("x >= 0 for childX=" + childX, rect.x >= 0);
                assertTrue("right <= host for childX=" + childX, rect.x + rect.width <= HOST_W);
                assertTrue("non-empty for childX=" + childX, rect.width >= 1);
            }
        }
    }

    @Test
    public void aWindowSmallerThanTheParentNarrowsTheViewport() {
        // FILL scale mode measures StreamContainer wider than the screen, so part of it is
        // off-screen. Reporting the whole parent box there would claim we can see pixels
        // that are not on the display.
        ViewportRect rect = ViewportGeometry.visibleHostRect(
                0, 0, 3000, 644,
                300, 0, 2700, 644,
                HOST_W, HOST_H);
        assertEquals(Math.round(0.1f * HOST_W), rect.x);
        assertEquals(Math.round(0.8f * HOST_W), rect.width);
    }

    @Test
    public void aViewThatHasNotBeenLaidOutReportsTheWholeDesktop() {
        assertEquals(ViewportRect.full(HOST_W, HOST_H),
                ViewportGeometry.visibleHostRect(0, 0, 0, 0, 0, 0, VIEW_W, VIEW_H, HOST_W, HOST_H));
    }

    @Test
    public void anEmptyWindowReportsTheWholeDesktop() {
        assertEquals(ViewportRect.full(HOST_W, HOST_H),
                at2(0, 0, VIEW_W, VIEW_H, 0, 0, 0, 0));
    }

    @Test
    public void aFrameEntirelyOutsideTheWindowReportsTheWholeDesktop() {
        // Fail open: a rectangle we cannot compute must not crop the host to a guess.
        assertEquals(ViewportRect.full(HOST_W, HOST_H),
                at2(-10000, 0, VIEW_W, VIEW_H, 0, 0, VIEW_W, VIEW_H));
    }

    @Test
    public void aSubPixelSliverStillProducesASendableRectangle() {
        // Rounding could collapse this to zero width, which the protocol rejects.
        ViewportRect rect = ViewportGeometry.visibleHostRect(
                0, 0, 2400 * 10f, 644 * 10f,
                0, 0, 0.4f, 0.4f,
                HOST_W, HOST_H);
        assertTrue(rect.width >= 1);
        assertTrue(rect.height >= 1);
        assertTrue(rect.x + rect.width <= HOST_W);
    }

    @Test
    public void aZeroSizedHostIsClampedRatherThanDividedBy() {
        ViewportRect rect = ViewportGeometry.visibleHostRect(
                0, 0, VIEW_W, VIEW_H, 0, 0, VIEW_W, VIEW_H, 0, 0);
        assertEquals(new ViewportRect(0, 0, 1, 1), rect);
    }

    @Test
    public void verticalAxisIsIndependentOfTheHorizontalOne() {
        // Zoomed on Y only, which a non-uniform surface change can produce.
        ViewportRect rect = ViewportGeometry.visibleHostRect(
                0, -644f, VIEW_W, VIEW_H * 2f,
                0, 0, VIEW_W, VIEW_H,
                HOST_W, HOST_H);
        assertEquals(0, rect.x);
        assertEquals(HOST_W, rect.width);
        assertEquals(HOST_H / 2, rect.y);
        assertEquals(HOST_H / 2, rect.height);
    }

    private static ViewportRect at2(float cx, float cy, float cw, float ch,
                                    float wl, float wt, float wr, float wb) {
        return ViewportGeometry.visibleHostRect(cx, cy, cw, ch, wl, wt, wr, wb, HOST_W, HOST_H);
    }
}
