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

    /**
     * The arbiter takes no timestamps: it is a pure function of pointer positions. A ZOOM
     * latch is safe at any moment because {@code Game.handleMotionEvent} offers every
     * {@code pointerCount > 2} event to the multi-finger recognisers before the inline
     * pinch hook, so there is no window in which latching early could steal one.
     */
    private static void begin(TwoFingerGestureArbiter a, float x0, float y0, float x1, float y1) {
        a.beginTwoFinger(x0, y0, x1, y1);
    }

    private static Decision update(TwoFingerGestureArbiter a,
                                   float x0, float y0, float x1, float y1) {
        return a.update(x0, y0, x1, y1);
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

    // ---- latching is not gated on time ---------------------------------------------

    @Test
    public void zoomLatchesOnTheVeryFirstFrameThatCrossesTheSlop() {
        // There is no time precondition on the ZOOM latch and there must not be one. Any
        // such delay keeps events reaching the touch contexts, which confirm a move at
        // 20px -- barely above the slop -- and start sending scroll to the host, and
        // cancelTouch() cannot un-send that. Latching the instant the slop is crossed is
        // what keeps a pinch from leaking scroll onto the remote desktop.
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 300f, 100f);   // span 200
        assertEquals(Decision.ZOOM, update(a, 113f, 100f, 287f, 100f));   // span 174
    }

    @Test
    public void aFastConvergenceLatchesImmediatelyRatherThanHesitating() {
        // The shape that used to be held back: two fingers converging hard, on frame one.
        // Whether that is a pinch or a hand still landing is settled downstream by dispatch
        // order -- see InlinePinchZoomDispatchOrderTest -- not by making the arbiter wait.
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 300f, 100f);
        assertEquals(Decision.ZOOM, update(a, 140f, 100f, 260f, 100f));
        assertEquals(Decision.ZOOM, a.getDecision());
    }

    @Test
    public void scrollStillLatchesJustAsPromptly() {
        // SCROLL was never delayed and still is not; the two decisions are symmetric now.
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        assertEquals(Decision.SCROLL, update(a, 100f, 130f, 200f, 130f));
    }

    @Test
    public void aGestureThatCrossesNeitherSlopStaysUndecidedHoweverManyFramesItTakes() {
        // Nothing latches on elapsed frames alone; only crossing a slop decides.
        TwoFingerGestureArbiter a = newArbiter();
        begin(a, 100f, 100f, 200f, 100f);
        for (int frame = 1; frame <= 50; frame++) {
            assertEquals("frame " + frame, Decision.UNDECIDED,
                    update(a, 100f, 100f + (frame % 2), 200f, 100f + (frame % 2)));
        }
    }

}
