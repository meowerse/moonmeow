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

    // ---- live gesture streams -----------------------------------------------------

    @Test
    public void startsInactiveAndUndecidedOnlyAfterTwoFingersLand() {
        TwoFingerGestureArbiter a = newArbiter();
        assertEquals(Decision.INACTIVE, a.getDecision());
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        assertEquals(Decision.UNDECIDED, a.getDecision());
    }

    @Test
    public void symmetricPinchOutIsZoom() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);   // span 100, focus (150,100)
        // Both fingers move outward 20px each: span 140, focus unchanged.
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 4; i++) {
            d = a.update(100f - 5f * i, 100f, 200f + 5f * i, 100f);
        }
        assertEquals(Decision.ZOOM, d);
    }

    @Test
    public void symmetricPinchInIsZoom() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 300f, 100f);   // span 200
        Decision d = a.update(120f, 100f, 280f, 100f); // span 160, focus unchanged
        assertEquals(Decision.ZOOM, d);
    }

    @Test
    public void anchoredPinchIsZoomEvenThoughTheCentroidMoves() {
        // One finger stays put, the other slides away. The centroid travels half the
        // span change, so span must cross its slop first for this to be read correctly.
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 8 && d == Decision.UNDECIDED; i++) {
            d = a.update(100f, 100f, 200f + 5f * i, 100f);
        }
        assertEquals(Decision.ZOOM, d);
    }

    @Test
    public void verticalTwoFingerScrollIsScroll() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 10 && d == Decision.UNDECIDED; i++) {
            d = a.update(100f, 100f + 5f * i, 200f, 100f + 5f * i);
        }
        assertEquals(Decision.SCROLL, d);
    }

    @Test
    public void horizontalTwoFingerScrollIsScroll() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 10 && d == Decision.UNDECIDED; i++) {
            d = a.update(100f - 5f * i, 100f, 200f - 5f * i, 100f);
        }
        assertEquals(Decision.SCROLL, d);
    }

    @Test
    public void sloppyScrollWhereTheFingersAlsoDriftApartIsStillScroll() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        // Translate 6px per frame while separating 3px per frame: translation dominates.
        for (int i = 1; i <= 20 && d == Decision.UNDECIDED; i++) {
            d = a.update(100f, 100f + 6f * i, 200f + 3f * i, 100f + 6f * i);
        }
        assertEquals(Decision.SCROLL, d);
    }

    @Test
    public void decisionLatchesAndDoesNotFlipLater() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        Decision d = Decision.UNDECIDED;
        for (int i = 1; i <= 10 && d == Decision.UNDECIDED; i++) {
            d = a.update(100f, 100f + 5f * i, 200f, 100f + 5f * i);
        }
        assertEquals(Decision.SCROLL, d);
        // Now pinch hard. The latch must hold.
        assertEquals(Decision.SCROLL, a.update(0f, 400f, 600f, 400f));
        assertEquals(Decision.SCROLL, a.getDecision());
    }

    @Test
    public void zoomLatchAlsoHoldsThroughPureTranslation() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        assertEquals(Decision.ZOOM, a.update(60f, 100f, 240f, 100f));
        // A pinch followed by two finger panning stays a zoom gesture.
        assertEquals(Decision.ZOOM, a.update(160f, 300f, 340f, 300f));
    }

    @Test
    public void disqualifyBlocksArbitrationUntilReset() {
        TwoFingerGestureArbiter a = newArbiter();
        a.disqualify();
        assertTrue(a.isDisqualified());
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        assertEquals(Decision.INACTIVE, a.getDecision());
        assertEquals(Decision.INACTIVE, a.update(0f, 100f, 400f, 100f));

        a.reset();
        assertFalse(a.isDisqualified());
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        assertEquals(Decision.ZOOM, a.update(60f, 100f, 240f, 100f));
    }

    @Test
    public void endTwoFingerAllowsReArmingButKeepsDisqualification() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        a.endTwoFinger();
        assertEquals(Decision.INACTIVE, a.getDecision());
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        assertEquals(Decision.UNDECIDED, a.getDecision());

        a.disqualify();
        a.endTwoFinger();
        a.beginTwoFinger(100f, 100f, 200f, 100f);
        assertEquals(Decision.INACTIVE, a.getDecision());
    }

    // ---- reported deltas ----------------------------------------------------------

    @Test
    public void scaleDeltaIsPerFrameNotSinceGestureStart() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 100f);   // span 100
        a.update(75f, 100f, 225f, 100f);            // span 150 -> 1.5x
        assertEquals(1.5f, a.getScaleDelta(), 1e-4f);
        a.update(50f, 100f, 250f, 100f);            // span 200 -> another 1.333x, not 2.0x
        assertEquals(200f / 150f, a.getScaleDelta(), 1e-4f);
    }

    @Test
    public void focusAndFocusDeltaTrackTheCentroid() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 200f, 200f);   // focus (150,150)
        a.update(110f, 130f, 210f, 230f);           // focus (160,180)
        assertEquals(160f, a.getFocusX(), 1e-4f);
        assertEquals(180f, a.getFocusY(), 1e-4f);
        assertEquals(10f, a.getFocusDeltaX(), 1e-4f);
        assertEquals(30f, a.getFocusDeltaY(), 1e-4f);
    }

    @Test
    public void updateWhileInactiveReportsNeutralDeltas() {
        TwoFingerGestureArbiter a = newArbiter();
        assertEquals(Decision.INACTIVE, a.update(0f, 0f, 500f, 500f));
        assertEquals(1f, a.getScaleDelta(), 1e-6f);
        assertEquals(0f, a.getFocusDeltaX(), 1e-6f);
        assertEquals(0f, a.getFocusDeltaY(), 1e-6f);
    }

    @Test
    public void coincidentFingersDoNotProduceAnInsaneScaleDelta() {
        TwoFingerGestureArbiter a = newArbiter();
        a.beginTwoFinger(100f, 100f, 100f, 100f);   // span 0
        a.update(50f, 100f, 250f, 100f);            // span 200 from 0
        float delta = a.getScaleDelta();
        assertTrue("scale delta must stay finite and bounded, was " + delta,
                delta >= 0.1f && delta <= 10f);
    }
}
