package com.limelight.meow.gesture;

import static com.limelight.meow.gesture.TwoFingerGestureArbiter.Decision;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The two finger gesture arbiter is the whole reason inline pinch-to-zoom can coexist
 * with two finger scrolling, so it is tested as a pure function here rather than by
 * feel on a device.
 */
public class TwoFingerGestureArbiterTest {

    private static final float SLOP = 24f;
    private static final float BIAS = 1.0f;

    private TwoFingerGestureArbiter newArbiter() {
        return new TwoFingerGestureArbiter(SLOP, SLOP, BIAS);
    }

    /** Arbitrary but fixed origin for the fake timebase; MotionEvent's is uptime millis. */
    private static final long T0 = 10_000L;

    /**
     * A move frame that arrives after the ZOOM latch dwell has elapsed. Every test below
     * that is about <em>classification</em> uses this, so the dwell is out of its way; the
     * tests that are about the dwell itself pass their own timestamps to
     * {@link TwoFingerGestureArbiter#update} directly. There is deliberately no helper
     * that omits the timestamp -- see the dwell tests for why.
     */
    private static final long AFTER_DWELL = T0 + TwoFingerGestureArbiter.DEFAULT_ZOOM_LATCH_DWELL_MS;

    private static void begin(TwoFingerGestureArbiter a, float x0, float y0, float x1, float y1) {
        a.beginTwoFinger(x0, y0, x1, y1, T0);
    }

    private static Decision update(TwoFingerGestureArbiter a,
                                   float x0, float y0, float x1, float y1) {
        return a.update(x0, y0, x1, y1, AFTER_DWELL);
    }

    // ---- classify(): the decision truth table ------------------------------------

    @Test
    public void classifyStaysUndecidedBelowBothSlops() {
        assertEquals(Decision.UNDECIDED, TwoFingerGestureArbiter.classify(0f, 0f, SLOP, SLOP, BIAS));
        assertEquals(Decision.UNDECIDED, TwoFingerGestureArbiter.classify(23.9f, 23.9f, SLOP, SLOP, BIAS));
    }

    @Test
    public void classifyPicksZoomWhenOnlySpanCrosses() {
        assertEquals(Decision.ZOOM, TwoFingerGestureArbiter.classify(24f, 0f, SLOP, SLOP, BIAS));
        assertEquals(Decision.ZOOM, TwoFingerGestureArbiter.classify(60f, 5f, SLOP, SLOP, BIAS));
    }

    @Test
    public void classifyPicksScrollWhenOnlyTranslationCrosses() {
        assertEquals(Decision.SCROLL, TwoFingerGestureArbiter.classify(0f, 24f, SLOP, SLOP, BIAS));
        assertEquals(Decision.SCROLL, TwoFingerGestureArbiter.classify(5f, 90f, SLOP, SLOP, BIAS));
    }

    @Test
    public void classifyBreaksTiesByDominanceWhenBothCross() {
        assertEquals(Decision.ZOOM, TwoFingerGestureArbiter.classify(80f, 30f, SLOP, SLOP, BIAS));
        assertEquals(Decision.SCROLL, TwoFingerGestureArbiter.classify(30f, 80f, SLOP, SLOP, BIAS));
        // Exactly equal is not a pinch; scroll is the pre-existing behaviour and wins.
        assertEquals(Decision.SCROLL, TwoFingerGestureArbiter.classify(50f, 50f, SLOP, SLOP, BIAS));
    }

    @Test
    public void classifyBiasShiftsTheTieBreak() {
        // bias > 1 favours scroll
        assertEquals(Decision.SCROLL, TwoFingerGestureArbiter.classify(40f, 30f, SLOP, SLOP, 2.0f));
        // bias < 1 favours zoom
        assertEquals(Decision.ZOOM, TwoFingerGestureArbiter.classify(30f, 40f, SLOP, SLOP, 0.5f));
    }

    @Test
    public void constructorRejectsNonPositiveConfiguration() {
        for (float[] bad : new float[][]{{0f, SLOP, BIAS}, {SLOP, 0f, BIAS}, {SLOP, SLOP, 0f}, {-1f, SLOP, BIAS}}) {
            try {
                new TwoFingerGestureArbiter(bad[0], bad[1], bad[2]);
                fail("expected rejection for " + bad[0] + "/" + bad[1] + "/" + bad[2]);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void defaultSlopsStayUnderTheThresholdAtWhichScrollLeaksToTheHost() {
        // RelativeTouchContext.TAP_MOVEMENT_THRESHOLD is 20px and TrackpadContext's is 30px;
        // those are the points at which the touch contexts confirm a move and start sending
        // scroll to the remote desktop. We have to have committed to zoom before then, or a
        // pinch scrolls the host on its way in. Raising these constants past 20 reintroduces
        // that bug silently, so pin them here.
        assertTrue("span slop must stay under RelativeTouchContext's 20px move threshold",
                TwoFingerGestureArbiter.DEFAULT_SPAN_SLOP_PX < 20f);
        assertTrue("translation slop must stay under the same threshold",
                TwoFingerGestureArbiter.DEFAULT_TRANSLATION_SLOP_PX < 20f);
    }

    // ---- live gesture streams -----------------------------------------------------

    @Test
    public void startsInactiveAndUndecidedOnlyAfterTwoFingersLand() {
        TwoFingerGestureArbiter a = newArbiter();
        assertEquals(Decision.INACTIVE, a.getDecision());
        begin(a, 100f, 100f, 200f, 100f);
        assertEquals(Decision.UNDECIDED, a.getDecision());
    }

    @Test
    public void symmetricPinchOutIsZoom() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);   // span 100, focus (150,100)
        // Both fingers move outward 20px each: span 140, focus unchanged.
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 4; i++) {
            d = update(a, 100f - 5f * i, 100f, 200f + 5f * i, 100f);
        }
        assertEquals(Decision.ZOOM, d);
    }

    @Test
    public void symmetricPinchInIsZoom() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 300f, 100f);   // span 200
        Decision d = update(a, 120f, 100f, 280f, 100f); // span 160, focus unchanged
        assertEquals(Decision.ZOOM, d);
    }

    @Test
    public void anchoredPinchIsZoomEvenThoughTheCentroidMoves() {
        // One finger stays put, the other slides away. The centroid travels half the
        // span change, so span must cross its slop first for this to be read correctly.
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 8 && d == Decision.UNDECIDED; i++) {
            d = update(a, 100f, 100f, 200f + 5f * i, 100f);
        }
        assertEquals(Decision.ZOOM, d);
    }

    @Test
    public void verticalTwoFingerScrollIsScroll() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 10 && d == Decision.UNDECIDED; i++) {
            d = update(a, 100f, 100f + 5f * i, 200f, 100f + 5f * i);
        }
        assertEquals(Decision.SCROLL, d);
    }

    @Test
    public void horizontalTwoFingerScrollIsScroll() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 10 && d == Decision.UNDECIDED; i++) {
            d = update(a, 100f - 5f * i, 100f, 200f - 5f * i, 100f);
        }
        assertEquals(Decision.SCROLL, d);
    }

    @Test
    public void sloppyScrollWhereTheFingersAlsoDriftApartIsStillScroll() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        // Translate 6px per frame while separating 3px per frame: translation dominates.
        for (int i = 1; i <= 20 && d == Decision.UNDECIDED; i++) {
            d = update(a, 100f, 100f + 6f * i, 200f + 3f * i, 100f + 6f * i);
        }
        assertEquals(Decision.SCROLL, d);
    }

    @Test
    public void decisionLatchesAndDoesNotFlipLater() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 10 && d == Decision.UNDECIDED; i++) {
            d = update(a, 100f, 100f + 5f * i, 200f, 100f + 5f * i);
        }
        assertEquals(Decision.SCROLL, d);
        // Now pinch hard. The latch must hold.
        assertEquals(Decision.SCROLL, update(a, 0f, 400f, 600f, 400f));
        assertEquals(Decision.SCROLL, a.getDecision());
    }

    @Test
    public void zoomLatchAlsoHoldsThroughPureTranslation() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        assertEquals(Decision.ZOOM, update(a, 60f, 100f, 240f, 100f));
        // A pinch followed by two finger panning stays a zoom gesture.
        assertEquals(Decision.ZOOM, update(a, 160f, 300f, 340f, 300f));
    }

    @Test
    public void disqualifyBlocksArbitrationUntilReset() {
        TwoFingerGestureArbiter a = newArbiter();
        a.disqualify();
        assertTrue(a.isDisqualified());
        begin(a, 100f, 100f, 200f, 100f);
        assertEquals(Decision.INACTIVE, a.getDecision());
        assertEquals(Decision.INACTIVE, update(a, 0f, 100f, 400f, 100f));

        a.reset();
        assertFalse(a.isDisqualified());
        begin(a, 100f, 100f, 200f, 100f);
        assertEquals(Decision.ZOOM, update(a, 60f, 100f, 240f, 100f));
    }

    @Test
    public void endTwoFingerAllowsReArmingButKeepsDisqualification() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        a.endTwoFinger();
        assertEquals(Decision.INACTIVE, a.getDecision());
        begin(a, 100f, 100f, 200f, 100f);
        assertEquals(Decision.UNDECIDED, a.getDecision());

        a.disqualify();
        a.endTwoFinger();
        begin(a, 100f, 100f, 200f, 100f);
        assertEquals(Decision.INACTIVE, a.getDecision());
    }

    // ---- reported deltas ----------------------------------------------------------

    @Test
    public void scaleDeltaIsPerFrameNotSinceGestureStart() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);   // span 100
        update(a, 75f, 100f, 225f, 100f);            // span 150 -> 1.5x
        assertEquals(1.5f, a.getScaleDelta(), 1e-4f);
        update(a, 50f, 100f, 250f, 100f);            // span 200 -> another 1.333x, not 2.0x
        assertEquals(200f / 150f, a.getScaleDelta(), 1e-4f);
    }

    @Test
    public void focusAndFocusDeltaTrackTheCentroid() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 200f);   // focus (150,150)
        update(a, 110f, 130f, 210f, 230f);           // focus (160,180)
        assertEquals(160f, a.getFocusX(), 1e-4f);
        assertEquals(180f, a.getFocusY(), 1e-4f);
        assertEquals(10f, a.getFocusDeltaX(), 1e-4f);
        assertEquals(30f, a.getFocusDeltaY(), 1e-4f);
    }

    @Test
    public void updateWhileInactiveReportsNeutralDeltas() {
        TwoFingerGestureArbiter a = newArbiter();
        assertEquals(Decision.INACTIVE, update(a, 0f, 0f, 500f, 500f));
        assertEquals(1f, a.getScaleDelta(), 1e-6f);
        assertEquals(0f, a.getFocusDeltaX(), 1e-6f);
        assertEquals(0f, a.getFocusDeltaY(), 1e-6f);
    }

    @Test
    public void coincidentFingersDoNotProduceAnInsaneScaleDelta() {
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 100f, 100f);   // span 0
        update(a, 50f, 100f, 250f, 100f);            // span 200 from 0
        float delta = a.getScaleDelta();
        assertTrue("scale delta must stay finite and bounded, was " + delta,
                delta >= 0.1f && delta <= 10f);
    }

    // ---- the ZOOM latch dwell -----------------------------------------------------

    @Test
    public void zoomDoesNotLatchWhileTheDwellIsStillRunning() {
        // This is the shape of a 3/4/5 finger tap in progress: two fingers are down, they
        // converge as the rest of the hand lands, and the span crosses the slop long
        // before the third finger arrives. Latching here is what steals the gesture.
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 300f, 100f, T0);   // span 200

        // 8 frames at 240Hz spans ~33ms, inside the 40ms dwell, and pulls the span in by
        // 80px -- more than three times the 24px slop.
        for (int frame = 1; frame <= 8; frame++) {
            long t = T0 + (long) (frame * 4.16f);
            assertEquals("frame " + frame + " at +" + (t - T0) + "ms must not latch ZOOM yet",
                    Decision.UNDECIDED, a.update(100f + 5f * frame, 100f, 300f - 5f * frame, 100f, t));
        }
        assertEquals(Decision.UNDECIDED, a.getDecision());
    }

    @Test
    public void zoomLatchesOnceTheDwellHasElapsed() {
        // The same gesture, sampled past the dwell instead: the guard delays ZOOM, it does
        // not disable it. Without this assertion the dwell could be "fixed" by never
        // zooming at all.
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 300f, 100f, T0);
        assertEquals(Decision.UNDECIDED, a.update(140f, 100f, 260f, 100f, T0 + 20L));
        assertEquals(Decision.ZOOM,
                a.update(140f, 100f, 260f, 100f, T0 + TwoFingerGestureArbiter.DEFAULT_ZOOM_LATCH_DWELL_MS));
    }

    @Test
    public void theDwellBoundaryIsInclusive() {
        long dwell = TwoFingerGestureArbiter.DEFAULT_ZOOM_LATCH_DWELL_MS;
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 300f, 100f, T0);
        assertEquals(Decision.UNDECIDED, a.update(140f, 100f, 260f, 100f, T0 + dwell - 1L));
        assertEquals(Decision.ZOOM, a.update(140f, 100f, 260f, 100f, T0 + dwell));
    }

    @Test
    public void scrollStillLatchesInsideTheDwell() {
        // Only ZOOM is held back. SCROLL latching early costs nothing -- the caller passes
        // the events through either way -- and delaying it would make two finger scrolling
        // feel worse for no benefit.
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f, T0);
        assertEquals(Decision.SCROLL, a.update(100f, 130f, 200f, 130f, T0 + 5L));
    }

    @Test
    public void aDwellHeldGestureStillArbitratesTheOtherWayIfItTurnsIntoAScroll() {
        // Held back from ZOOM at first, the gesture must stay genuinely undecided rather
        // than latching ZOOM as soon as the clock allows: if it has become a scroll by
        // then, the dominance rule still applies.
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f, T0);
        assertEquals(Decision.UNDECIDED, a.update(70f, 100f, 230f, 100f, T0 + 5L));
        // Span change 60px, translation 200px: translation dominates.
        assertEquals(Decision.SCROLL, a.update(270f, 100f, 430f, 100f, T0 + 100L));
    }

    @Test
    public void theDwellRestartsWhenTwoFingersAreReArmed() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 300f, 100f, T0);
        a.endTwoFinger();
        // Second finger comes back much later; the dwell is measured from *that* landing,
        // not from the original one, or the re-armed gesture would skip the guard.
        long t1 = T0 + 5_000L;
        a.beginTwoFinger(100f, 100f, 300f, 100f, t1);
        assertEquals(Decision.UNDECIDED, a.update(140f, 100f, 260f, 100f, t1 + 5L));
        assertEquals(Decision.ZOOM,
                a.update(140f, 100f, 260f, 100f, t1 + TwoFingerGestureArbiter.DEFAULT_ZOOM_LATCH_DWELL_MS));
    }

    @Test
    public void theDwellIsSurvivedByTheMonotonicClockWrapping() {
        // eventTime is uptime millis; subtracting rather than comparing keeps this correct
        // if it ever wraps. Straddle Long.MAX_VALUE to prove the arithmetic.
        TwoFingerGestureArbiter a = newArbiter();
        long near = Long.MAX_VALUE - 10L;
        a.beginTwoFinger(100f, 100f, 300f, 100f, near);
        assertEquals(Decision.UNDECIDED, a.update(140f, 100f, 260f, 100f, near + 5L));
        assertEquals(Decision.ZOOM, a.update(140f, 100f, 260f, 100f,
                near + TwoFingerGestureArbiter.DEFAULT_ZOOM_LATCH_DWELL_MS));
    }

    @Test
    public void theDefaultDwellIsShortEnoughToStayImperceptible() {
        // The dwell is paid by every pinch, in leaked scroll frames and in latency. Past
        // ~60ms it starts to read as lag on a gesture that should feel direct; pin it.
        assertTrue("dwell must stay short enough to feel immediate",
                TwoFingerGestureArbiter.DEFAULT_ZOOM_LATCH_DWELL_MS <= 60L);
        assertTrue("a dwell of zero would not cover the gap between landing fingers",
                TwoFingerGestureArbiter.DEFAULT_ZOOM_LATCH_DWELL_MS >= 25L);
    }

    @Test
    public void constructorRejectsANegativeDwell() {
        try {
            new TwoFingerGestureArbiter(SLOP, SLOP, BIAS, -1L);
            fail("expected rejection of a negative dwell");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

}
