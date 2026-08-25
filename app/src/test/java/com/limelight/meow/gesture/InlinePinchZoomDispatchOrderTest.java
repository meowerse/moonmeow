package com.limelight.meow.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins the one guarantee that keeps inline pinch-to-zoom from eating the 3/4/5 finger
 * gestures: <b>{@code Game.handleMotionEvent} offers every {@code pointerCount > 2} event
 * to {@code handleMultiTouchGesture} before it offers anything to
 * {@link InlinePinchZoomController}.</b>
 *
 * <p>Why this cannot be a plain controller test. Once a gesture latches to ZOOM the
 * controller swallows every later {@code ACTION_POINTER_DOWN}, and that is correct — the
 * view is mid-transform and a third finger is not a gesture it can interpret. The bug was
 * never in the controller; it was in <em>who gets asked first</em>. So the unit under test
 * here is the dispatch order itself, modelled by {@link FingerBranch} below.
 *
 * <p>{@link FingerBranch} mirrors the finger branch of {@code Game.handleMotionEvent} and
 * {@link MultiFingerGestures} mirrors {@code handleMultiTouchGesture} plus
 * {@code cancelStaleTouchState}, including the synthetic {@code ACTION_CANCEL} that
 * re-enters the branch. <b>Both must be kept in step with {@code Game}.</b> The model is
 * only worth having because it is falsifiable: {@link FingerBranch#hookFirst} replays the
 * old ordering, and {@link #theOldHookFirstOrderingStealsTheSameGesture} asserts that it
 * still loses the gesture. A test that cannot fail on the old code proves nothing, which
 * is exactly the trap {@code threeFingerGestureIsNeverStolen} and
 * {@code aThirdFingerDoesNotReArmAfterItLifts} fell into — both put the third finger down
 * with no intervening {@code ACTION_MOVE}, the one ordering that can never fail.
 */
public class InlinePinchZoomDispatchOrderTest {

    /** Upstream's {@code THREE_FINGER_TAP_THRESHOLD} and friends are all 300ms. */
    private static final long TAP_THRESHOLD_MS = 300L;

    /**
     * Stands in for {@code Game.handleMultiTouchGesture} and the
     * {@code cancelStaleTouchState} it calls. Faithful to the parts that decide the
     * outcome: the down-time bookkeeping, the tap window, and the synthetic
     * {@code ACTION_CANCEL} dispatched back through the view.
     */
    private static final class MultiFingerGestures {
        final List<String> offered = new ArrayList<>();
        final List<String> fired = new ArrayList<>();

        private FingerBranch branch;
        private long threeFingerDownTime;
        private long fourFingerDownTime;
        private long fiveFingerDownTime;

        boolean handle(int action, int pointerCount, long eventTimeMs,
                       float x0, float y0, float x1, float y1) {
            offered.add(name(action) + "/" + pointerCount);

            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                if (pointerCount == 3) {
                    threeFingerDownTime = eventTimeMs;
                } else if (pointerCount == 4) {
                    threeFingerDownTime = 0;
                    fourFingerDownTime = eventTimeMs;
                } else if (pointerCount == 5) {
                    threeFingerDownTime = 0;
                    fourFingerDownTime = 0;
                    fiveFingerDownTime = eventTimeMs;
                }
                return false;
            }

            if (action != MotionEvent.ACTION_POINTER_UP && action != MotionEvent.ACTION_UP) {
                return false;
            }

            boolean recognised = true;
            if (pointerCount >= 5 && fiveFingerDownTime > 0
                    && eventTimeMs - fiveFingerDownTime < TAP_THRESHOLD_MS) {
                fired.add("gameMenu");
                fiveFingerDownTime = 0;
            } else if (pointerCount == 4 && fourFingerDownTime > 0
                    && eventTimeMs - fourFingerDownTime < TAP_THRESHOLD_MS) {
                fired.add("fullKeyboard");
                fourFingerDownTime = 0;
            } else if (pointerCount == 3 && threeFingerDownTime > 0
                    && eventTimeMs - threeFingerDownTime < TAP_THRESHOLD_MS) {
                fired.add("keyboard");
                threeFingerDownTime = 0;
            } else {
                recognised = false;
                threeFingerDownTime = fourFingerDownTime = fiveFingerDownTime = 0;
            }

            // cancelStaleTouchState: a synthetic ACTION_CANCEL is dispatched back through
            // the view, which re-enters the branch we are being called from.
            branch.dispatch(MotionEvent.ACTION_CANCEL, pointerCount, eventTimeMs, x0, y0, x1, y1);
            return recognised;
        }

        private static String name(int action) {
            switch (action) {
                case MotionEvent.ACTION_DOWN: return "DOWN";
                case MotionEvent.ACTION_POINTER_DOWN: return "POINTER_DOWN";
                case MotionEvent.ACTION_MOVE: return "MOVE";
                case MotionEvent.ACTION_POINTER_UP: return "POINTER_UP";
                case MotionEvent.ACTION_UP: return "UP";
                case MotionEvent.ACTION_CANCEL: return "CANCEL";
                default: return "ACTION_" + action;
            }
        }
    }

    /** Mirrors the finger branch of {@code Game.handleMotionEvent}. */
    private static final class FingerBranch {
        final MultiFingerGestures multiFinger = new MultiFingerGestures();
        final InlinePinchZoomController pinch;

        /** Replays the pre-fix ordering, where the inline hook got first refusal. */
        final boolean hookFirst;

        int zoomBeginCount;
        int zoomEndCount;

        FingerBranch(boolean hookFirst) {
            this.hookFirst = hookFirst;
            this.pinch = new InlinePinchZoomController(
                    new TwoFingerGestureArbiter(24f, 24f, 1.0f),
                    new InlinePinchZoomController.ZoomTarget() {
                        @Override
                        public void pinchBy(float scaleDelta, float focusX, float focusY) {
                        }

                        @Override
                        public void panBy(float dx, float dy) {
                        }
                    },
                    () -> zoomBeginCount++,
                    () -> zoomEndCount++);
            multiFinger.branch = this;
        }

        boolean dispatch(int action, int pointerCount, long eventTimeMs,
                         float x0, float y0, float x1, float y1) {
            if (hookFirst && pinch.handle(action, pointerCount, x0, y0, x1, y1)) {
                return true;
            }

            // Upstream only ever consults the multi-finger recognisers for these three
            // actions at pointerCount > 2. ACTION_MOVE is deliberately absent, which is
            // what makes putting this block first free for zoom.
            if (pointerCount > 2
                    && (action == MotionEvent.ACTION_POINTER_DOWN
                            || action == MotionEvent.ACTION_POINTER_UP
                            || action == MotionEvent.ACTION_UP)
                    && multiFinger.handle(action, pointerCount, eventTimeMs, x0, y0, x1, y1)) {
                return true;
            }

            return !hookFirst && pinch.handle(action, pointerCount, x0, y0, x1, y1);
        }
    }

    private FingerBranch branch;
    private long nowMs;

    private FingerBranch newBranch(boolean hookFirst) {
        branch = new FingerBranch(hookFirst);
        nowMs = 100_000L;
        return branch;
    }

    private boolean dispatch(int action, int pointerCount,
                             float x0, float y0, float x1, float y1) {
        return branch.dispatch(action, pointerCount, nowMs, x0, y0, x1, y1);
    }

    /**
     * Two fingers land and pinch decisively, so ZOOM latches and the controller starts
     * consuming. Frames keep arriving. This is the state the old ordering could not
     * survive.
     */
    private void latchAZoomWithInterveningMoveFrames(int moveFrames) {
        nowMs += 500L;
        assertFalse(dispatch(MotionEvent.ACTION_DOWN, 1, 100f, 100f, 0f, 0f));
        nowMs += 8L;
        assertFalse(dispatch(MotionEvent.ACTION_POINTER_DOWN, 2, 100f, 100f, 200f, 100f));
        nowMs += 8L;
        assertTrue("a decisive pinch out must latch immediately",
                dispatch(MotionEvent.ACTION_MOVE, 2, 50f, 100f, 250f, 100f));
        for (int frame = 1; frame <= moveFrames; frame++) {
            nowMs += 8L;
            assertTrue("frame " + frame + " should be consumed as a zoom",
                    dispatch(MotionEvent.ACTION_MOVE, 2,
                            50f - frame, 100f, 250f + frame, 100f));
        }
        assertTrue("the gesture must actually be latched to zoom", branch.pinch.isZooming());
        assertEquals(1, branch.zoomBeginCount);
    }

    @Test
    public void aThreeFingerTapAfterAZoomHasLatchedStillOpensTheKeyboard() {
        newBranch(false);
        latchAZoomWithInterveningMoveFrames(6);

        nowMs += 8L;
        dispatch(MotionEvent.ACTION_POINTER_DOWN, 3, 40f, 100f, 260f, 100f);
        nowMs += 40L;
        dispatch(MotionEvent.ACTION_POINTER_UP, 3, 40f, 100f, 260f, 100f);

        assertEquals("the soft keyboard gesture must survive a latched zoom",
                List.of("keyboard"), branch.multiFinger.fired);
    }

    @Test
    public void aFiveFingerTapAfterAZoomHasLatchedStillOpensTheGameMenu() {
        newBranch(false);
        latchAZoomWithInterveningMoveFrames(4);

        for (int pointerCount = 3; pointerCount <= 5; pointerCount++) {
            nowMs += 8L;
            dispatch(MotionEvent.ACTION_POINTER_DOWN, pointerCount, 40f, 100f, 260f, 100f);
            nowMs += 8L;
            // Move frames between each landing finger, which is what real hands produce.
            dispatch(MotionEvent.ACTION_MOVE, pointerCount, 40f, 100f, 260f, 100f);
        }
        nowMs += 30L;
        dispatch(MotionEvent.ACTION_POINTER_UP, 5, 40f, 100f, 260f, 100f);

        assertEquals(List.of("gameMenu"), branch.multiFinger.fired);
    }

    /**
     * The claim the removed 40ms dwell could never make. It withheld the ZOOM latch for a
     * fixed window and then gave up; a third finger landing later was still stolen, with no
     * bound on how much later. Ordering has no window at all.
     */
    @Test
    public void theThirdFingerIsReachedHoweverLongTheZoomHasBeenRunning() {
        newBranch(false);
        latchAZoomWithInterveningMoveFrames(3);

        // Ten seconds of zooming — orders of magnitude past any dwell that could be called
        // imperceptible — and then the third finger lands.
        for (int frame = 1; frame <= 100; frame++) {
            nowMs += 100L;
            assertTrue(dispatch(MotionEvent.ACTION_MOVE, 2, 40f, 100f, 260f, 100f));
        }

        nowMs += 8L;
        dispatch(MotionEvent.ACTION_POINTER_DOWN, 3, 40f, 100f, 260f, 100f);
        nowMs += 40L;
        dispatch(MotionEvent.ACTION_POINTER_UP, 3, 40f, 100f, 260f, 100f);

        assertEquals(List.of("keyboard"), branch.multiFinger.fired);
    }

    /**
     * Proof the tests above bite. Same event stream, old ordering: the hook consumes the
     * third finger's pointer-down and the recognisers are never consulted at all.
     */
    @Test
    public void theOldHookFirstOrderingStealsTheSameGesture() {
        newBranch(true);
        latchAZoomWithInterveningMoveFrames(6);

        nowMs += 8L;
        assertTrue("the old ordering swallows it",
                dispatch(MotionEvent.ACTION_POINTER_DOWN, 3, 40f, 100f, 260f, 100f));
        nowMs += 40L;
        assertTrue(dispatch(MotionEvent.ACTION_POINTER_UP, 3, 40f, 100f, 260f, 100f));

        assertTrue("this is the defect: the recognisers never saw the third finger",
                branch.multiFinger.offered.isEmpty());
        assertTrue(branch.multiFinger.fired.isEmpty());
    }

    /**
     * The synthetic {@code ACTION_CANCEL} that {@code cancelStaleTouchState} dispatches
     * re-enters the branch. It must land on the controller — the multi-finger block ignores
     * {@code ACTION_CANCEL} — and end the zoom cleanly rather than leaving it latched.
     */
    @Test
    public void theSyntheticCancelEndsTheZoomExactlyOnce() {
        newBranch(false);
        latchAZoomWithInterveningMoveFrames(5);
        assertEquals(0, branch.zoomEndCount);

        nowMs += 8L;
        dispatch(MotionEvent.ACTION_POINTER_DOWN, 3, 40f, 100f, 260f, 100f);
        nowMs += 40L;
        dispatch(MotionEvent.ACTION_POINTER_UP, 3, 40f, 100f, 260f, 100f);

        assertEquals("the zoom ends once, via the synthetic cancel", 1, branch.zoomEndCount);
        assertFalse(branch.pinch.isZooming());

        // And the controller is genuinely reusable afterwards, not stuck swallowing.
        nowMs += 500L;
        assertFalse(dispatch(MotionEvent.ACTION_DOWN, 1, 100f, 100f, 0f, 0f));
        assertFalse(dispatch(MotionEvent.ACTION_POINTER_DOWN, 2, 100f, 100f, 200f, 100f));
        nowMs += 8L;
        assertTrue(dispatch(MotionEvent.ACTION_MOVE, 2, 50f, 100f, 250f, 100f));
        assertEquals(2, branch.zoomBeginCount);
    }

    /**
     * The reason moving the hook below the multi-finger block is free for zoom: that block
     * never handles {@code ACTION_MOVE}, and {@code ACTION_MOVE} is the only action zoom is
     * driven by. If upstream ever adds {@code ACTION_MOVE} to it, this goes red.
     */
    @Test
    public void theMultiFingerBlockIsNeverOfferedAMoveFrame() {
        newBranch(false);
        latchAZoomWithInterveningMoveFrames(4);

        nowMs += 8L;
        dispatch(MotionEvent.ACTION_POINTER_DOWN, 3, 40f, 100f, 260f, 100f);
        for (int frame = 1; frame <= 10; frame++) {
            nowMs += 8L;
            dispatch(MotionEvent.ACTION_MOVE, 3, 40f - frame, 100f, 260f + frame, 100f);
        }

        for (String offered : branch.multiFinger.offered) {
            assertFalse("a move frame reached the multi-finger block: " + offered,
                    offered.startsWith("MOVE"));
        }
    }

    /**
     * The model above only mirrors {@code Game}; on its own it would go on passing if
     * somebody moved the hook back. This reads the real source and pins the ordering
     * directly, which is the cheapest way to make the mirror non-fictional. It asserts
     * exactly one thing — that inside {@code handleMotionEvent} the call to
     * {@code handleMultiTouchGesture} precedes the {@code inlinePinchZoom} hook — so it
     * survives ordinary editing of everything around it.
     */
    @Test
    public void gameOffersMultiFingerGesturesTheEventBeforeTheInlinePinchHook() throws IOException {
        String game = readGameSource();
        int multiFinger = game.indexOf("handleMultiTouchGesture(event, eventAction, pointerCount, view)");
        int pinchHook = game.indexOf("inlinePinchZoom.onTouchEvent(event)");

        assertTrue("handleMultiTouchGesture call site not found in Game.java", multiFinger > 0);
        assertTrue("inline pinch hook not found in Game.java", pinchHook > 0);
        assertTrue("Game.handleMotionEvent must offer pointerCount > 2 events to "
                        + "handleMultiTouchGesture BEFORE the inline pinch hook, or a third "
                        + "finger landing after a ZOOM latch is swallowed and the soft "
                        + "keyboard / full keyboard / game menu gestures die. See "
                        + "docs/meow/TOUCHPOINTS.md.",
                multiFinger < pinchHook);
    }

    private static String readGameSource() throws IOException {
        // Unit tests run with the module directory as the working directory; walk up in
        // case that ever changes rather than hard coding a depth.
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "app/src/main/java/com/limelight/Game.java");
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            File here = new File(dir, "src/main/java/com/limelight/Game.java");
            if (here.isFile()) {
                return new String(Files.readAllBytes(here.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not locate Game.java from " + new File("").getAbsolutePath());
    }

    /** An ordinary two finger pinch must not wake the multi-finger path at all. */
    @Test
    public void anOrdinaryPinchNeverReachesTheMultiFingerBlock() {
        newBranch(false);
        latchAZoomWithInterveningMoveFrames(6);
        nowMs += 8L;
        assertTrue(dispatch(MotionEvent.ACTION_UP, 1, 0f, 0f, 0f, 0f));

        assertTrue("nothing at two fingers may be offered to the multi-finger recognisers: "
                + branch.multiFinger.offered, branch.multiFinger.offered.isEmpty());
        assertEquals(1, branch.zoomEndCount);
    }
}
