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
    public void theRequestIsTheViewPlusTwentyPercentAtTheSurfaceAspect() {
        ViewportRect v = new ViewportRect(720, 405, 480, 270);
        ViewportRect r = band.onVisible(v, 1000, FULL, W, H);
        assertNotNull(r);
        assertContains(r, v);
        assertEquals(672, r.width);
        assertEquals(378, r.height);
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
        for (int i = 1; i <= 60; i++) {
            assertNull(band.onVisible(new ViewportRect(720 + i, 405, 480, 270), 1000 + i * 100,
                    FULL, W, H));
        }
        // Past the band's edge minus the slack: a new request, containing the view.
        ViewportRect far = new ViewportRect(720 + 90, 405, 480, 270);
        ViewportRect r = band.onVisible(far, 8000, FULL, W, H);
        assertNotNull(r);
        assertContains(r, far);
    }

    /**
     * The owner's report: the picture resized while moving. Every request made during a pan,
     * slow or fast, anywhere on the desktop (edges and the letterbox included) and after it
     * stops, has the same size.
     */
    @Test
    public void theSizeNeverChangesWhileTheZoomIsConstant() {
        int[] speeds = {1, 8, 40, 120};
        for (int speed : speeds) {
            band.reset();
            ViewportRect first = null;
            long t = 0;
            for (int x = 0; x + 480 <= W; x += speed) {
                t += 16;
                // Round the two edges separately, as ViewportGeometry does: width 480 or 481.
                int width = (x % 3 == 0) ? 481 : 480;
                ViewportRect v = new ViewportRect(x, 300, width, 270);
                ViewportRect r = band.onVisible(v, t, CONTENT, W, H);
                if (r == null) {
                    continue;
                }
                if (first == null) {
                    first = r;
                }
                assertEquals("speed " + speed + " at x " + x, first.width, r.width);
                assertEquals("speed " + speed + " at x " + x, first.height, r.height);
            }
            assertNotNull(first);
            ViewportRect settled = band.onSettled(new ViewportRect(W - 480, 300, 480, 270),
                    CONTENT, W, H);
            assertEquals("settling never resizes", first.width, settled.width);
            assertEquals(first.height, settled.height);
        }
    }

    @Test
    public void aSteadyPanRecropsInStepsNotEveryFrame() {
        ViewportRect v = new ViewportRect(0, 405, 480, 270);
        band.onVisible(v, 0, FULL, W, H);
        int requests = 0;
        long t = 0;
        // 1440 px of travel at 8 px per 16 ms frame (a gentle pan, 1 view a second).
        for (int x = 8; x <= 1440; x += 8) {
            t += 16;
            ViewportRect view = new ViewportRect(x, 405, 480, 270);
            ViewportRect r = band.onVisible(view, t, FULL, W, H);
            if (r != null) {
                requests++;
                assertContains(r, view);
            }
            assertContains(band.current(), view);
        }
        // 180 view updates; without the band every one of them would be a new crop. With it the
        // band moves in steps of about 0.3 view (margin plus lead, less the slack): 3 views of
        // travel is about ten crops, three a second.
        assertTrue("requests " + requests, requests <= 10);
    }

    @Test
    public void aNewBandIsPlacedAheadOfTheMotion() {
        ViewportRect v = new ViewportRect(200, 405, 480, 270);
        band.onVisible(v, 0, FULL, W, H);
        ViewportRect r = null;
        long t = 0;
        int x = 200;
        while (r == null) {
            x += 8;
            t += 16;
            r = band.onVisible(new ViewportRect(x, 405, 480, 270), t, FULL, W, H);
        }
        float viewCentre = x + 240f;
        float bandCentre = r.x + r.width / 2f;
        assertTrue("moving right: the band leads, " + bandCentre + " vs " + viewCentre,
                bandCentre > viewCentre + 20f);
        // Never so far that the view itself falls outside it.
        assertContains(r, new ViewportRect(x, 405, 480, 270));
    }

    @Test
    public void theLeadIsBoundedSoTheViewStaysInsideTheBand() {
        band.setLeadMs(GuardBand.MAX_LEAD_MS);
        ViewportRect v = new ViewportRect(200, 405, 480, 270);
        band.onVisible(v, 0, FULL, W, H);
        long t = 0;
        for (int x = 200; x <= 1300; x += 60) {
            t += 16;
            ViewportRect view = new ViewportRect(x, 405, 480, 270);
            band.onVisible(view, t, FULL, W, H);
            assertContains(band.current(), view);
        }
    }

    @Test
    public void aZoomChangeResizesTheBand() {
        ViewportRect v = new ViewportRect(720, 405, 480, 270);
        band.onVisible(v, 1000, FULL, W, H);
        ViewportRect out = new ViewportRect(600, 340, 720, 405);
        ViewportRect r = band.onVisible(out, 2000, FULL, W, H);
        assertNotNull("zoomed out: a bigger band", r);
        assertEquals(1008, r.width);
        ViewportRect in = new ViewportRect(840, 472, 240, 135);
        r = band.onVisible(in, 3000, FULL, W, H);
        assertNotNull("zoomed far in: the old crop would waste most of the encoder", r);
        assertEquals(336, r.width);
    }

    @Test
    public void settlingOffersTheSameRequestAgainForARetry() {
        ViewportRect v = new ViewportRect(720, 405, 480, 270);
        ViewportRect r = band.onVisible(v, 1000, FULL, W, H);
        assertSame(r, band.onSettled(v, FULL, W, H));
        assertSame(r, band.onSettled(v, FULL, W, H));
    }

    @Test
    public void resetForgetsTheRequest() {
        ViewportRect v = new ViewportRect(720, 405, 480, 270);
        band.onVisible(v, 1000, FULL, W, H);
        band.reset();
        assertNull(band.current());
        assertNotNull(band.onVisible(v, 2000, FULL, W, H));
    }

    /**
     * A constant request is not a constant crop by itself: the host floors one edge, ceils the
     * other and even-aligns, so the same reference width can become two desktop widths. With
     * the desktop known, the band picks a width whose desktop width is the same everywhere.
     */
    @Test
    public void theHostsCropSizeIsTheSameWhereverTheBandSits() {
        SunmeowCropModel host = new SunmeowCropModel(5360, 1440, W, H);
        ViewportRect content = host.content();
        band.setDesktop(5360, 1440);
        java.util.Set<String> hostSizes = new java.util.HashSet<>();
        long t = 0;
        for (int x = content.x; x + 480 <= content.x + content.width; x += 7) {
            t += 16;
            band.onVisible(new ViewportRect(x, 400, 480, 270), t, content, W, H);
            int[] source = host.source(band.current());
            hostSizes.add(source[2] + "x" + source[3]);
        }
        assertEquals("host crop sizes " + hostSizes, 1, hostSizes.size());
    }
}

