package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class GuardBandTest {

    private static final int W = 1920;
    private static final int H = 1080;
    /** The live topology: 5360x1440 letterboxed into 1920x1080. */
    private static final ViewportRect CONTENT = new ViewportRect(0, 282, 1920, 515);
    private static final ViewportRect FULL = ViewportRect.full(W, H);

    private GuardBand band;

    @Before
    public void setUp() {
        band = new GuardBand();
    }

    private static void assertContains(ViewportRect outer, ViewportRect inner) {
        assertTrue(outer + " must contain " + inner, outer.x <= inner.x && outer.y <= inner.y
                && outer.x + outer.width >= inner.x + inner.width
                && outer.y + outer.height >= inner.y + inner.height);
    }

    @Test
    public void atRestTheRequestIsTheViewPlusTenPercentAtTheSurfaceAspect() {
        ViewportRect v = new ViewportRect(720, 405, 480, 270);
        ViewportRect r = band.onVisible(v, 1000, FULL, W, H);
        assertNotNull(r);
        assertContains(r, v);
        assertEquals(576, r.width);
        assertEquals(324, r.height);
        assertEquals((float) W / H, (float) r.width / r.height, 0.01f);
    }

    @Test
    public void aViewThatIsNotSurfaceShapedIsWidenedNotLetterboxed() {
        // A tall visible rect (portrait window over a landscape stream).
        ViewportRect v = new ViewportRect(800, 300, 200, 400);
        ViewportRect r = band.onVisible(v, 1000, FULL, W, H);
        assertContains(r, v);
        assertEquals((float) W / H, (float) r.width / r.height, 0.02f);
    }

    @Test
    public void theRequestStaysInsideTheDesktopNotInThePadding() {
        ViewportRect v = new ViewportRect(0, 282, 480, 135);
        ViewportRect r = band.onVisible(v, 1000, CONTENT, W, H);
        assertContains(r, v);
        assertContains(CONTENT, r);
        assertEquals(0, r.x);
        assertEquals(282, r.y);
    }

    @Test
    public void aViewCoveringTheDesktopAsksForAllOfIt() {
        assertEquals(CONTENT, band.onVisible(FULL, 1000, CONTENT, W, H));
        assertNull("unchanged: nothing new to ask", band.onVisible(FULL, 1100, CONTENT, W, H));
    }

    @Test
    public void aViewOverlappingTheLetterboxStillGetsHysteresis() {
        // ~1.5x on the letterboxed topology: the view spills into the padding, so no request
        // clamped to the desktop can contain it. The band works on its desktop part.
        ViewportRect v = new ViewportRect(300, 200, 1280, 720);
        assertNotNull(band.onVisible(v, 1000, CONTENT, W, H));
        for (int i = 1; i <= 20; i++) {
            assertNull("pan " + i, band.onVisible(new ViewportRect(300 + i * 2, 200, 1280, 720),
                    1000 + i * 100, CONTENT, W, H));
        }
    }

    @Test
    public void smallPansInsideTheBandKeepTheRequest() {
        ViewportRect v = new ViewportRect(720, 405, 480, 270);
        band.onVisible(v, 1000, FULL, W, H);
        for (int i = 1; i <= 30; i++) {
            assertNull(band.onVisible(new ViewportRect(720 + i, 405, 480, 270), 1000 + i * 100,
                    FULL, W, H));
        }
        // Past the band's edge minus the slack: a new request, containing the view.
        ViewportRect far = new ViewportRect(720 + 45, 405, 480, 270);
        ViewportRect r = band.onVisible(far, 5000, FULL, W, H);
        assertNotNull(r);
        assertContains(r, far);
    }

    @Test
    public void zoomingOutPastTheBandAsksAgainAndZoomingFarInTightens() {
        ViewportRect v = new ViewportRect(720, 405, 480, 270);
        band.onVisible(v, 1000, FULL, W, H);
        ViewportRect wider = new ViewportRect(700, 395, 520, 290);
        assertNull("still inside the band", band.onVisible(wider, 2000, FULL, W, H));
        ViewportRect out = new ViewportRect(600, 340, 720, 405);
        assertNotNull("outside it", band.onVisible(out, 3000, FULL, W, H));
        ViewportRect in = new ViewportRect(840, 472, 240, 135);
        ViewportRect r = band.onVisible(in, 4000, FULL, W, H);
        assertNotNull("zoomed far in: the old crop would waste most of the encoder", r);
        assertEquals(288, r.width);
    }

    @Test
    public void theBandWidensWhileMovingFastAndTightensWhenSettled() {
        ViewportRect v = new ViewportRect(100, 405, 480, 270);
        band.onVisible(v, 0, FULL, W, H);
        ViewportRect moving = null;
        for (int i = 1; i <= 10; i++) {
            ViewportRect r = band.onVisible(new ViewportRect(100 + i * 80, 405, 480, 270),
                    i * 16L, FULL, W, H);
            if (r != null) {
                moving = r;
            }
        }
        assertNotNull(moving);
        assertTrue("fast pan: a wider band, " + moving.width, moving.width > 576 + 20);
        ViewportRect settled = band.onSettled(new ViewportRect(900, 405, 480, 270), FULL, W, H);
        assertNotNull(settled);
        assertEquals(576, settled.width);
        assertSame("settled again: the same request is offered again, for a retry",
                settled, band.onSettled(new ViewportRect(900, 405, 480, 270), FULL, W, H));
    }

    @Test
    public void resetForgetsTheRequest() {
        ViewportRect v = new ViewportRect(720, 405, 480, 270);
        band.onVisible(v, 1000, FULL, W, H);
        band.reset();
        assertNull(band.current());
        assertNotNull(band.onVisible(v, 2000, FULL, W, H));
    }
}
