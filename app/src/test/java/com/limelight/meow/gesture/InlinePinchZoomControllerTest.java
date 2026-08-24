package com.limelight.meow.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Exercises the event routing through {@link InlinePinchZoomController#handle}. The
 * MotionEvent action codes are compile time constants, so no Android runtime is needed.
 */
public class InlinePinchZoomControllerTest {

    private static class RecordingTarget implements InlinePinchZoomController.ZoomTarget {
        final List<String> calls = new ArrayList<>();
        float accumulatedScale = 1f;
        float panX, panY;

        @Override
        public void pinchBy(float scaleDelta, float focusX, float focusY) {
            calls.add("pinch");
            accumulatedScale *= scaleDelta;
        }

        @Override
        public void panBy(float dx, float dy) {
            calls.add("pan");
            panX += dx;
            panY += dy;
        }
    }

    private RecordingTarget target;
    private int zoomBeginCount;
    private int zoomEndCount;
    private InlinePinchZoomController controller;

    @Before
    public void setUp() {
        target = new RecordingTarget();
        zoomBeginCount = 0;
        zoomEndCount = 0;
        controller = new InlinePinchZoomController(
                new TwoFingerGestureArbiter(24f, 24f, 1.0f),
                target,
                () -> zoomBeginCount++,
                () -> zoomEndCount++);
    }

    private boolean down(float x, float y) {
        return controller.handle(MotionEvent.ACTION_DOWN, 1, x, y, 0f, 0f);
    }

    private boolean secondDown(float x0, float y0, float x1, float y1) {
        return controller.handle(MotionEvent.ACTION_POINTER_DOWN, 2, x0, y0, x1, y1);
    }

    private boolean move(float x0, float y0, float x1, float y1) {
        return controller.handle(MotionEvent.ACTION_MOVE, 2, x0, y0, x1, y1);
    }

    private boolean up() {
        return controller.handle(MotionEvent.ACTION_UP, 1, 0f, 0f, 0f, 0f);
    }

    @Test
    public void theSlopActuallyUsedInProductionStaysUnderTheScrollLeakThreshold() {
        // The Context constructor is what Game uses, and it does NOT use the arbiter's
        // defaults -- it derives the slop from ViewConfiguration and caps it at
        // MAX_SLOP_PX. Asserting on the DEFAULT_* constants alone leaves that cap
        // unpinned, so raising it would silently reintroduce the scroll leak on every
        // device. Assert through the real construction path instead.
        //
        // Feed it the densities that matter. 26px is what the Poco X7 Pro reports (8dp at
        // 520dpi, measured); 32px is 8dp at 640dpi. Both are past RelativeTouchContext's
        // 20px move threshold, so both MUST be capped -- this is the assertion that goes
        // red if anyone raises MAX_SLOP_PX.
        for (float platformSlop : new float[]{20f, 24f, 26f, 32f, 64f}) {
            assertTrue("platform slop " + platformSlop + "px must be capped below 20px, was "
                            + InlinePinchZoomController.effectiveSlopPx(platformSlop),
                    InlinePinchZoomController.effectiveSlopPx(platformSlop) < 20f);
        }

        // A low-density device keeps its own smaller slop rather than being raised to ours.
        assertEquals(8f, InlinePinchZoomController.effectiveSlopPx(8f), 1e-4f);
        // An unavailable platform value falls back to something still under the threshold.
        assertTrue(InlinePinchZoomController.effectiveSlopPx(0f) < 20f);
        assertTrue(InlinePinchZoomController.effectiveSlopPx(-1f) < 20f);

        // And the arbiter Game actually gets is built from that same capped value.
        TwoFingerGestureArbiter arbiter =
                new InlinePinchZoomController((Context) null, target, () -> {}, () -> {}).getArbiter();
        assertTrue(arbiter.getSpanSlopPx() < 20f);
        assertTrue(arbiter.getTranslationSlopPx() < 20f);
    }

    @Test
    public void constructorRejectsMissingCollaborators() {
        try {
            new InlinePinchZoomController(new TwoFingerGestureArbiter(), null, () -> {}, () -> {});
            fail("expected rejection of a null target");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void singleFingerIsNeverConsumed() {
        assertFalse(down(100f, 100f));
        assertFalse(controller.handle(MotionEvent.ACTION_MOVE, 1, 300f, 400f, 0f, 0f));
        assertFalse(up());
        assertTrue(target.calls.isEmpty());
        assertEquals(0, zoomBeginCount);
        assertEquals(0, zoomEndCount);
    }

    @Test
    public void twoFingerScrollIsNeverConsumedAndNeverTouchesTheZoomTarget() {
        down(100f, 100f);
        assertFalse(secondDown(100f, 100f, 200f, 100f));
        for (int i = 1; i <= 20; i++) {
            assertFalse("frame " + i + " must pass through to the trackpad handling",
                    move(100f, 100f + 5f * i, 200f, 100f + 5f * i));
        }
        assertFalse(up());
        assertTrue("scroll must not drive zoom: " + target.calls, target.calls.isEmpty());
        assertEquals(0, zoomBeginCount);
        assertEquals(0, zoomEndCount);
    }

    @Test
    public void pinchIsConsumedAndDrivesTheZoomTarget() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);

        boolean consumed = false;
        for (int i = 1; i <= 10 && !consumed; i++) {
            consumed = move(100f - 5f * i, 100f, 200f + 5f * i, 100f);
        }
        assertTrue("a pinch must eventually be consumed", consumed);
        assertEquals("the begin callback fires exactly once", 1, zoomBeginCount);
        assertTrue("zoom must have been applied", target.accumulatedScale > 1f);

        // Every later frame of the gesture stays consumed.
        assertTrue(move(50f, 100f, 250f, 100f));
        assertEquals(1, zoomBeginCount);

        assertTrue(up());
        assertEquals(1, zoomEndCount);
        assertFalse(controller.isZooming());
    }

    @Test
    public void pinchThenTwoFingerPanKeepsZoomingAndPansTheView() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        assertTrue(move(50f, 100f, 250f, 100f));   // decisive pinch out

        target.calls.clear();
        target.panX = target.panY = 0f;

        // Fingers now translate together without changing separation.
        assertTrue(move(150f, 200f, 350f, 200f));
        assertEquals(100f, target.panX, 1e-4f);
        assertEquals(100f, target.panY, 1e-4f);
        assertTrue("panning must be reported", target.calls.contains("pan"));
    }

    @Test
    public void lingeringFingerAfterAPinchIsSwallowedUntilTheGestureEnds() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        assertTrue(move(50f, 100f, 250f, 100f));

        // Second finger leaves.
        assertTrue(controller.handle(MotionEvent.ACTION_POINTER_UP, 2, 50f, 100f, 250f, 100f));
        target.calls.clear();
        // The remaining finger flails; nothing must reach the cursor or the view.
        assertTrue(controller.handle(MotionEvent.ACTION_MOVE, 1, 400f, 900f, 0f, 0f));
        assertTrue(target.calls.isEmpty());

        assertTrue(up());
        assertEquals(1, zoomEndCount);
    }

    @Test
    public void aThirdFingerMidZoomHoldsStillAndResumesWithoutJumping() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        assertTrue(move(50f, 100f, 250f, 100f));   // decisive pinch out
        float scaleAfterPinch = target.accumulatedScale;

        // Third finger lands, then the fingers move a long way while it is down.
        assertTrue(controller.handle(MotionEvent.ACTION_POINTER_DOWN, 3, 50f, 100f, 250f, 100f));
        target.calls.clear();
        assertTrue(controller.handle(MotionEvent.ACTION_MOVE, 3, 10f, 500f, 600f, 500f));
        assertTrue("nothing may be applied while a third finger is down", target.calls.isEmpty());

        // Back to two fingers, far from where they were: the first frame re-baselines.
        assertTrue(controller.handle(MotionEvent.ACTION_POINTER_UP, 3, 10f, 500f, 600f, 500f));
        assertTrue(controller.handle(MotionEvent.ACTION_MOVE, 2, 10f, 500f, 600f, 500f));
        assertTrue("the re-baseline frame must not move the view", target.calls.isEmpty());
        assertEquals(scaleAfterPinch, target.accumulatedScale, 1e-4f);

        // And then zooming continues normally from the new positions.
        assertTrue(controller.handle(MotionEvent.ACTION_MOVE, 2, 5f, 500f, 900f, 500f));
        assertTrue(target.accumulatedScale > scaleAfterPinch);
    }

    @Test
    public void aLiftedFingerComingBackMidZoomDoesNotJumpEither() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        assertTrue(move(50f, 100f, 250f, 100f));
        float scaleAfterPinch = target.accumulatedScale;

        assertTrue(controller.handle(MotionEvent.ACTION_POINTER_UP, 2, 50f, 100f, 250f, 100f));
        assertTrue(controller.handle(MotionEvent.ACTION_MOVE, 1, 400f, 700f, 0f, 0f));
        // Second finger returns somewhere else entirely.
        assertTrue(controller.handle(MotionEvent.ACTION_POINTER_DOWN, 2, 400f, 700f, 900f, 700f));
        target.calls.clear();
        assertTrue(controller.handle(MotionEvent.ACTION_MOVE, 2, 400f, 700f, 900f, 700f));
        assertTrue(target.calls.isEmpty());
        assertEquals(scaleAfterPinch, target.accumulatedScale, 1e-4f);
    }

    @Test
    public void threeFingerGestureIsNeverStolen() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        assertFalse(controller.handle(MotionEvent.ACTION_POINTER_DOWN, 3, 100f, 100f, 200f, 100f));
        // Even a violent pinch between the first two pointers must pass through now.
        for (int i = 1; i <= 20; i++) {
            assertFalse(controller.handle(MotionEvent.ACTION_MOVE, 3,
                    100f - 10f * i, 100f, 200f + 10f * i, 100f));
        }
        assertEquals(0, zoomBeginCount);
        assertTrue(target.calls.isEmpty());
    }

    /**
     * Scoped to the controller in isolation. At the {@code Game} level the guarantee is
     * weaker: {@code handleMultiTouchGesture} calls {@code cancelStaleTouchState}, which
     * re-dispatches a synthetic ACTION_CANCEL back through this controller, and that
     * resets the arbiter and clears the disqualification. Harmless — the user is back to
     * a clean slate either way — but do not read this test as a claim about the real
     * dispatch path.
     */
    @Test
    public void aThirdFingerDoesNotReArmAfterItLifts() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        controller.handle(MotionEvent.ACTION_POINTER_DOWN, 3, 100f, 100f, 200f, 100f);
        controller.handle(MotionEvent.ACTION_POINTER_UP, 3, 100f, 100f, 200f, 100f);
        for (int i = 1; i <= 20; i++) {
            assertFalse(move(100f - 10f * i, 100f, 200f + 10f * i, 100f));
        }
        assertEquals(0, zoomBeginCount);
    }

    @Test
    public void liftingBackToOneFingerReArmsForTheNextTwoFingerAttempt() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        move(100f, 105f, 200f, 105f);                                    // still undecided
        assertFalse(controller.handle(MotionEvent.ACTION_POINTER_UP, 2, 100f, 105f, 200f, 105f));

        // Second finger comes back and this time it is a clear pinch.
        secondDown(100f, 105f, 200f, 105f);
        assertTrue(move(50f, 105f, 250f, 105f));
        assertEquals(1, zoomBeginCount);
    }

    @Test
    public void cancelEndsAZoomingGestureAndResetsState() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        assertTrue(move(50f, 100f, 250f, 100f));
        assertTrue(controller.handle(MotionEvent.ACTION_CANCEL, 2, 50f, 100f, 250f, 100f));
        assertEquals(1, zoomEndCount);
        assertFalse(controller.isZooming());

        // A brand new gesture arbitrates from scratch.
        assertFalse(down(100f, 100f));
        assertFalse(secondDown(100f, 100f, 200f, 100f));
        for (int i = 1; i <= 20; i++) {
            assertFalse(move(100f, 100f + 5f * i, 200f, 100f + 5f * i));
        }
    }

    @Test
    public void aNewDownClearsAStaleZoomLatch() {
        down(100f, 100f);
        secondDown(100f, 100f, 200f, 100f);
        assertTrue(move(50f, 100f, 250f, 100f));
        assertTrue(controller.isZooming());

        // A fresh ACTION_DOWN without a matching UP (dropped events) must not leave the
        // controller permanently swallowing touches.
        assertFalse(down(100f, 100f));
        assertFalse(controller.isZooming());
        assertFalse(controller.handle(MotionEvent.ACTION_MOVE, 1, 500f, 500f, 0f, 0f));
    }
}
