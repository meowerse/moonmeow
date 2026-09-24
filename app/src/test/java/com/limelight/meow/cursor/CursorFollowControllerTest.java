package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Choreographer;

import com.limelight.meow.gesture.InlinePinchZoomController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;

/**
 * The controller against a fake view: threading of its inputs, what arms it, and the pan it
 * asks for. The end-to-end behaviour with real views is in {@code CursorFollowBindingTest} and
 * {@code GameCursorFollowModesTest}.
 */
public class CursorFollowControllerTest {

    /** A 4x view over (720, 405, 480, 270), 4 parent pixels per reference pixel. */
    private static final class FakeView implements CursorFollowController.ViewportView {
        float x = 720f;
        float y = 405f;
        float zoom = 4f;

        @Override
        public boolean visibleReferenceRect(float[] out) {
            out[0] = x;
            out[1] = y;
            out[2] = 480f;
            out[3] = 270f;
            return true;
        }

        @Override
        public void contentBounds(float[] out) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 1920f;
            out[3] = 1080f;
        }

        @Override
        public boolean transform(float[] out) {
            out[0] = -x * 4f;
            out[1] = -y * 4f;
            out[2] = 4f;
            out[3] = 4f;
            out[4] = zoom;
            return true;
        }

        @Override
        public boolean window(float[] out) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 1920f;
            out[3] = 1080f;
            return true;
        }
    }

    /** Pans the fake view the way PanZoomHandler would. */
    private final class Pan implements InlinePinchZoomController.ZoomTarget {
        @Override public void pinchBy(float s, float fx, float fy) { }
        @Override public void panBy(float dx, float dy) {
            // Clamped to the frame, as PanZoomHandler.constrainToBounds does.
            view.x = Math.max(0f, Math.min(view.x - dx / 4f, 1920f - 480f));
            view.y = Math.max(0f, Math.min(view.y - dy / 4f, 1080f - 270f));
        }
        @Override public float getScaleFactor() { return 4f; }
        @Override public float getChildX() { return -view.x * 4f; }
        @Override public float getChildY() { return -view.y * 4f; }
    }

    private static final class Frames implements CursorFollowController.Frames {
        Choreographer.FrameCallback frame;
        final ArrayDeque<Runnable> ui = new ArrayDeque<>();
        boolean onUiThread = true;
        long now = 1000L;
        long nanos = 1_000_000_000L;

        @Override public long uptimeMillis() { return now; }
        @Override public void requestFrame(Choreographer.FrameCallback callback) { frame = callback; }
        @Override public boolean isUiThread() { return onUiThread; }
        @Override public void postToUi(Runnable task) { ui.add(task); }
        boolean animations = true;
        @Override public boolean animationsEnabled() { return animations; }
        final java.util.List<Object[]> delayed = new java.util.ArrayList<>();
        @Override public void postToUiDelayed(Runnable task, long delayMs) {
            delayed.add(new Object[] {now + delayMs, task});
        }

        /** Advances the clock and runs what came due. */
        void advance(long ms) {
            now += ms;
            java.util.List<Object[]> due = new java.util.ArrayList<>();
            for (Object[] d : delayed) {
                if ((long) d[0] <= now) {
                    due.add(d);
                }
            }
            delayed.removeAll(due);
            for (Object[] d : due) {
                ((Runnable) d[1]).run();
            }
            runUi();
        }

        void runUi() {
            while (!ui.isEmpty()) {
                ui.poll().run();
            }
        }

        int settle() {
            int n = 0;
            while (frame != null && n < 600) {
                Choreographer.FrameCallback f = frame;
                frame = null;
                now += 17;
                f.doFrame(nanos += 16_666_667L);
                n++;
            }
            return n;
        }
    }

    private FakeView view;
    private Frames frames;
    private CursorFollowController controller;

    @Before
    public void setUp() {
        view = new FakeView();
        frames = new Frames();
        controller = new CursorFollowController(view, new Pan(), true, frames, new FollowLog(line -> { }));
        controller.onStreamStarted(1920, 1080);
    }

    @After
    public void tearDown() {
        controller.onStreamStopped();
    }

    @Test
    public void aHostPositionFromTheCallbackThreadIsDrainedOnTheUiThread() {
        frames.onUiThread = false;
        controller.onCursorPosition(1300, 540, true, 1);
        controller.onCursorPosition(1350, 540, true, 2);
        assertEquals("coalesced into one drain", 1, frames.ui.size());
        assertFalse(controller.cursor().isKnown());
        frames.runUi();
        assertEquals(1350f, controller.cursor().x(), 0f);
        assertTrue(controller.cursor().isHostReporting());
        frames.settle();
        assertTrue(view.x + 480f - 1350f >= 480f * CursorFollowController.COMFORT_MARGIN - 1f);
    }

    @Test
    public void relativeMovesFromATimerThreadAreAccumulated() {
        controller.onAbsolutePosition(960, 540, 1920, 1080);
        frames.onUiThread = false;
        for (int i = 0; i < 10; i++) {
            controller.onRelativeMove(5, 0);
        }
        frames.onUiThread = true;
        frames.runUi();
        assertEquals(960f / 1919f * 1920f + 50f, controller.cursor().x(), 0.01f);
    }

    @Test
    public void thePanIsInViewPixelsAndInTheOppositeDirection() {
        controller.onCursorPosition(1300, 540, true, 1);
        frames.runUi();
        float before = view.x;
        frames.settle();
        assertTrue("the visible box moves right toward the cursor", view.x > before);
        assertTrue(1300f <= view.x + 480f);
    }

    @Test
    public void nothingArmsBeforeTheStreamStartsOrAfterItStops() {
        controller.onStreamStopped();
        controller.onCursorPosition(1300, 540, true, 1);
        controller.onAbsolutePosition(1900, 540, 1920, 1080);
        frames.runUi();
        assertEquals(0, frames.settle());
    }

    @Test
    public void aDisabledControllerNeverSubscribesOrPans() {
        CursorFollowController off =
                new CursorFollowController(view, new Pan(), false, frames, new FollowLog(line -> { }));
        off.onStreamStarted(1920, 1080);
        off.subscribeTask().run();
        off.onAbsolutePosition(1900, 540, 1920, 1080);
        assertEquals(0, frames.settle());
        off.onStreamStopped();
    }

    @Test
    public void directPointingSelectsTheEdgeMargin() {
        // A host that moves its pointer to a native touch 10% in from the right edge: inside
        // the edge band's reach, outside the comfort margin's.
        controller.onDirectPointing(CursorInputTap.TOUCH_MOVE, Float.NaN, Float.NaN);
        controller.onCursorPosition(1152, 540, true, 1);
        frames.runUi();
        float before = view.x;
        frames.settle();
        assertEquals(before, view.x, 0f);
    }

    // ---- the client-owned pointer and its limits ----------------------------------------

    /** Records placements and replays them into the tap, as NvConnection would. */
    private final java.util.List<int[]> placed = new java.util.ArrayList<>();

    private void withSink() {
        controller.setPointerSink((x, y, w, h) -> {
            placed.add(new int[] {x, y, w, h});
            controller.onAbsolutePosition(x, y, w, h);
        });
    }

    @Test
    public void zoomedWithoutHostReportsARelativeMoveIsPlacedInsideTheView() {
        withSink();
        assertTrue(controller.onRelativeMove(100000, 0));
        assertEquals(1, placed.size());
        // The view scrolled as far as the desktop goes, and the cursor reached the desktop's
        // last pixel: at the desktop edge nothing is held back for the sprite, so the panel,
        // the tray and the corner stay reachable.
        assertEquals(1440f, view.x, 0f);
        assertEquals(1919f, controller.cursor().x(), 0.01f);
        assertTrue(controller.cursor().isExact());
        assertEquals(1920 * 4, placed.get(0)[2]);
    }

    @Test
    public void unzoomedRelativeMovesStayRelative() {
        withSink();
        view.zoom = 1f;
        assertFalse(controller.onRelativeMove(10, 0));
        assertTrue(placed.isEmpty());
    }

    @Test
    public void aHostThatReportsItsCursorIsNeverOverridden() {
        withSink();
        controller.onCursorPosition(900, 500, true, 1);
        frames.runUi();
        assertFalse(controller.onRelativeMove(10, 0));
        assertTrue(placed.isEmpty());
    }

    @Test
    public void offTheUiThreadAMoveIsNeverIntercepted() {
        withSink();
        frames.onUiThread = false;
        assertFalse(controller.onRelativeMove(10, 0));
    }

    @Test
    public void withoutASinkNothingIsPlaced() {
        assertFalse(controller.onRelativeMove(10, 0));
    }

    @Test
    public void aHostReportJustAfterTheClientMovedThePointerWaitsOutTheGraceWindow() {
        withSink();
        controller.onCursorPosition(900, 500, true, 1);
        frames.runUi();
        controller.onAbsolutePosition(1100, 600, 1920, 1080);
        // A report arrives a moment later: maybe stale, maybe the host's correction.
        controller.onCursorPosition(1090, 600, true, 2);
        frames.runUi();
        assertEquals("not taken inside the window",
                1100f / 1919f * 1920f, controller.cursor().x(), 0.01f);
        frames.advance(CursorFollowController.HOST_REPORT_GRACE_MS + 1);
        assertEquals("but not lost either", 1090f, controller.cursor().x(), 0f);
    }

    @Test
    public void aProvenHostGetsTimeToReportBeforeTheClientMovesThePointer() {
        withSink();
        controller.subscribeTask().run();
        assertFalse("within the first-report wait, relative stays relative",
                controller.onRelativeMove(10, 0));
        frames.advance(CursorFollowController.FIRST_REPORT_WAIT_MS);
        assertTrue("a proven host that never reported: the client owns the pointer",
                controller.onRelativeMove(10, 0));
        view.zoom = 1f;
        assertFalse("but never unzoomed: relative input stays relative there (games, host "
                + "acceleration)", controller.onRelativeMove(10, 0));
    }

    @Test
    public void aPlacementThatCarriesTheCursorDoesNotArmTheFollower() {
        withSink();
        // A tap puts the cursor inside the 15% comfort band but outside the 4% edge band
        // (visible 720..1200): nothing moves.
        controller.onAbsolutePosition(1170, 540, 1919, 1079);
        frames.settle();
        assertEquals(720f, view.x, 0f);
        frames.advance(CursorFollowController.ABSOLUTE_INPUT_WINDOW_MS + 1);
        // The user pans 40 ref px right; the cursor is carried to stay put on screen, so it is
        // still in the comfort band. The follower must not start pulling against the fingers.
        view.x += 40f;
        controller.onViewTransformChanged();
        assertEquals(1171.2f + 40f, controller.cursor().x(), 0.6f);
        assertEquals("the carry does not start the follower mid-gesture", 0, frames.settle());
        assertEquals(760f, view.x, 0f);
    }

    @Test
    public void withAnimationsOffTheViewJumpsInOneFrame() {
        frames.animations = false;
        controller.onCursorPosition(1700, 540, true, 1);
        frames.runUi();
        assertEquals(1, frames.settle());
        assertTrue(1700f <= view.x + 480f);
    }

    @Test
    public void inADirectTouchModeAZoomAwayFromTheCursorIsNotChased() {
        controller.setTouchMode(() -> true);
        controller.onCursorPosition(1300, 540, true, 1);
        frames.runUi();
        frames.settle();
        float settled = view.x;
        // The user zooms/pans elsewhere with their fingers.
        view.x = 100f;
        controller.onViewTransformChanged();
        assertEquals(0, frames.settle());
        assertEquals(100f, view.x, 0f);
        assertTrue(settled != 100f);
    }

    @Test
    public void inAPointerModeARemoteCursorOffScreenAfterAViewChangeIsBroughtBack() {
        controller.onCursorPosition(900, 500, true, 1);
        frames.runUi();
        frames.settle();
        // Something moved the view with no carry possible (no sink): the cursor is off screen.
        view.x = 100f;
        controller.onViewTransformChanged();
        frames.settle();
        assertTrue(900f >= view.x && 900f <= view.x + 480f);
    }

    @Test
    public void pushingPastTheEdgeScrollsTheViewAtFingerSpeed() {
        withSink();
        controller.onAbsolutePosition(1190, 540, 1919, 1079);   // near the right edge
        float cursorBefore = controller.cursor().x();
        float viewBefore = view.x;
        assertTrue(controller.onRelativeMove(30, 0));
        float moved = controller.cursor().x() - cursorBefore;
        // The whole move happened in this event, not over the next frames...
        assertEquals(30f, moved, 1f);
        // ...and the view scrolled by the part that would have left it.
        assertEquals(moved - (1194f - cursorBefore), view.x - viewBefore, 1f);
        assertTrue(controller.cursor().x() <= view.x + 480f);
    }

    @Test
    public void pushingLeftAndRightIsSymmetric() {
        withSink();
        controller.onAbsolutePosition(960, 540, 1919, 1079);
        float start = controller.cursor().x();
        float viewStart = view.x;
        for (int i = 0; i < 20; i++) {
            controller.onRelativeMove(-37, 0);
        }
        float leftTravel = start - controller.cursor().x();
        float leftPan = viewStart - view.x;
        for (int i = 0; i < 20; i++) {
            controller.onRelativeMove(37, 0);
        }
        assertEquals("back where it started", start, controller.cursor().x(), 1f);
        for (int i = 0; i < 20; i++) {
            controller.onRelativeMove(37, 0);
        }
        float rightTravel = controller.cursor().x() - start;
        assertEquals("the same travel either way", leftTravel, rightTravel, 1f);
        assertTrue(leftPan > 0f);
    }

    @Test
    public void slowMotionKeepsItsFullGainDespiteTheReferenceGrid() {
        // 2.8 desktop px per reference px, as on the 5360-wide desktop in a 1920 stream: one
        // desktop px is 0.36 reference px, less than a step of the 4x grid rounds cleanly to.
        controller.onDesktopExtent(5360, 1440);
        withSink();
        controller.onAbsolutePosition(900, 540, 1919, 1079);
        for (int perEvent = 1; perEvent <= 3; perEvent++) {
            float start = controller.cursor().x();
            for (int i = 0; i < 50; i++) {
                controller.onRelativeMove(perEvent, 0);
            }
            float expected = 50 * perEvent * 1920f / 5360f;
            assertEquals(perEvent + " px per event", expected,
                    controller.cursor().x() - start, 0.5f);
        }
    }

    @Test
    public void aFingerDraggedIntoTheEdgeBandScrollsTheViewInADirectTouchMode() {
        controller.setTouchMode(() -> true);
        // A native-touch drag toward the right edge of the visible box (720..1200).
        float before = view.x;
        controller.onDirectPointing(CursorInputTap.TOUCH_DOWN, 1000f / 1920f, 0.5f);
        controller.onDirectPointing(CursorInputTap.TOUCH_MOVE, 1100f / 1920f, 0.5f);
        frames.settle();
        assertEquals("inside the view, away from the edge: nothing moves", before, view.x, 0f);
        controller.onDirectPointing(CursorInputTap.TOUCH_MOVE, 1195f / 1920f, 0.5f);
        frames.settle();
        assertTrue("dragged into the 4% edge band: the view scrolls", view.x > before);
        assertTrue("and the finger's point is back out of the band",
                1195f <= view.x + 480f * (1f - CursorFollowController.EDGE_MARGIN) + 1f);
    }

    @Test
    public void aTapInTheEdgeBandDoesNotScrollTheView() {
        controller.setTouchMode(() -> true);
        float before = view.x;
        // Down 1% inside the right edge, a pixel of jitter, and up.
        controller.onDirectPointing(CursorInputTap.TOUCH_DOWN, 1195f / 1920f, 0.5f);
        controller.onDirectPointing(CursorInputTap.TOUCH_MOVE, 1196f / 1920f, 0.5f);
        controller.onDirectPointing((byte) 0x02, Float.NaN, Float.NaN);
        frames.settle();
        assertEquals(before, view.x, 0f);
    }

    @Test
    public void aTapStillMovesTheEstimateWithoutScrolling() {
        controller.setTouchMode(() -> true);
        controller.onDirectPointing(CursorInputTap.TOUCH_DOWN, 1000f / 1920f, 0.5f);
        controller.onDirectPointing((byte) 0x02, Float.NaN, Float.NaN);
        assertEquals(0, frames.settle());
        assertEquals(1000f, controller.cursor().x(), 0.5f);
    }

    @Test
    public void aHoveringPenAtTheEdgeIsFollowed() {
        controller.setTouchMode(() -> true);
        float before = view.x;
        controller.onDirectPointing(CursorInputTap.TOUCH_HOVER, 1195f / 1920f, 0.5f);
        frames.settle();
        assertTrue(view.x > before);
    }

    @Test
    public void aReportingHostsPointerIsNotMovedByTheFinger() {
        controller.setTouchMode(() -> true);
        controller.onCursorPosition(900, 500, true, 1);
        frames.runUi();
        assertTrue(controller.cursor().isHostReporting());
        controller.onDirectPointing(CursorInputTap.TOUCH_HOVER, 1195f / 1920f, 0.5f);
        frames.runUi();
        assertEquals(900f, controller.cursor().x(), 0f);
    }

    @Test
    public void aSecondFingerOrALiftCarriesNoPositionToFollow() {
        controller.setTouchMode(() -> true);
        controller.onDirectPointing(CursorInputTap.TOUCH_MOVE, Float.NaN, Float.NaN);
        assertEquals(0, frames.settle());
        assertFalse(controller.cursor().isKnown());
    }

    /**
     * Emulator run against sunmeow PR #20: during a trackpad swipe every frame starts with the
     * input (a relative move the controller does not take over, since the host reports) and
     * only then runs the follow step. One shared rate limit gave every slot to the input line,
     * so the log showed no "follow pan" at all while the view was following.
     */
    @Test
    public void aFollowPanIsLoggedDuringAContinuousSwipeAgainstAReportingHost() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        CursorFollowController logged = new CursorFollowController(view, new Pan(), true, frames,
                new FollowLog(lines::add));
        controller.onStreamStopped();
        logged.onStreamStarted(1920, 1080);
        logged.setPointerSink((x, y, w, h) -> { });
        logged.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        float before = view.x;
        int x = 960;
        for (int i = 0; i < 60; i++) {
            // One vsync: the input first, as Choreographer runs CALLBACK_INPUT before
            // CALLBACK_ANIMATION, then the follow step -- both at the frame's time.
            frames.now += 17;
            logged.onRelativeMove(12, 0);
            x += 12;
            logged.onCursorPosition(x, 540, true, i + 2);
            frames.runUi();
            Choreographer.FrameCallback f = frames.frame;
            frames.frame = null;
            if (f != null) {
                f.doFrame(frames.nanos += 16_666_667L);
            }
        }
        assertTrue("the view followed", view.x > before);
        boolean pan = false;
        boolean input = false;
        for (String line : lines) {
            pan |= line.startsWith("follow pan");
            input |= line.startsWith("relative move");
        }
        assertTrue("a follow pan line: " + lines, pan);
        assertTrue("and the input line too: " + lines, input);
        logged.onStreamStopped();
    }

    @Test
    public void aReportingHostsRelativeMovesAreSaidOnceNotPerSwipe() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        CursorFollowController logged = new CursorFollowController(view, new Pan(), true, frames,
                new FollowLog(lines::add));
        controller.onStreamStopped();
        logged.onStreamStarted(1920, 1080);
        logged.setPointerSink((x, y, w, h) -> { });
        logged.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        for (int i = 0; i < 100; i++) {
            frames.now += 17;
            logged.onRelativeMove(1, 0);
        }
        long said = lines.stream()
                .filter(l -> l.equals("relative moves go out as relative: the host reports its cursor"))
                .count();
        assertEquals(lines.toString(), 1, said);
        assertFalse(lines.toString(),
                lines.stream().anyMatch(l -> l.contains("sent as relative: sink")));
        assertFalse(lines.toString(), lines.stream().anyMatch(
                l -> l.contains("waiting for first report true")));
        logged.onStreamStopped();
    }

    @Test
    public void theNotTakenOverLineNamesTheRealReason() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        CursorFollowController logged = new CursorFollowController(view, new Pan(), true, frames,
                new FollowLog(lines::add));
        controller.onStreamStopped();
        logged.onStreamStarted(1920, 1080);
        // No pointer sink wired: the one reason, and nothing about a report wait.
        logged.onRelativeMove(12, 0);
        String line = lines.get(lines.size() - 1);
        assertTrue(line, line.contains("sink false"));
        assertTrue(line, line.contains("waiting for first report false"));
        logged.onStreamStopped();
    }

    @Test
    public void aCursorTheHostCallsHiddenIsFollowedWhileTheUserMovesIt() {
        controller.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        frames.settle();
        float before = view.x;
        // A swipe right; the host reports the cursor hidden at the desktop's right edge.
        controller.onRelativeMove(40, 0);
        controller.onCursorPosition(1919, 540, false, 2);
        frames.runUi();
        frames.settle();
        assertTrue("followed to the edge", view.x > before);
        assertTrue(1919f <= view.x + 480f);
    }

    @Test
    public void aCursorAGameHidLongAgoIsNotChasedThoughTheMouseMoves() {
        controller.onCursorPosition(960, 540, false, 1);
        frames.runUi();
        frames.settle();
        float before = view.x;
        int x = 960;
        for (int i = 0; i < 30; i++) {
            controller.onRelativeMove(20, 0);
            x += 20;
            controller.onCursorPosition(x, 540, false, i + 2);
            frames.runUi();
            frames.settle();
        }
        assertEquals(before, view.x, 0f);
    }

    @Test
    public void aFollowStartedOnADrivenHiddenCursorRunsToItsEnd() {
        controller.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        frames.settle();
        controller.onRelativeMove(40, 0);
        controller.onCursorPosition(1919, 540, false, 2);
        frames.runUi();
        // A few frames of the follow, then the input window runs out mid-follow.
        for (int i = 0; i < 3 && frames.frame != null; i++) {
            Choreographer.FrameCallback f = frames.frame;
            frames.frame = null;
            frames.now += 17;
            f.doFrame(frames.nanos += 16_666_667L);
        }
        float partWay = view.x;
        assertTrue("under way, not done", partWay + 480f < 1919f);
        frames.advance(CursorFollowController.POINTER_INPUT_WINDOW_MS);
        frames.settle();
        assertTrue(1919f <= view.x + 480f);
    }

    @Test
    public void aCursorHiddenByTheHostOnItsOwnIsNotChasedEvenWhenTheMouseMovesLater() {
        controller.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        frames.settle();
        frames.advance(CursorFollowController.POINTER_INPUT_WINDOW_MS + 1);
        float before = view.x;
        controller.onCursorPosition(1100, 540, false, 2);
        frames.runUi();
        frames.settle();
        assertEquals(before, view.x, 0f);
        for (int i = 0; i < 20; i++) {
            controller.onRelativeMove(20, 0);
            controller.onCursorPosition(1100 + 20 * i, 540, false, i + 3);
            frames.runUi();
            frames.settle();
        }
        assertEquals(before, view.x, 0f);
    }

    @Test
    public void aCursorHiddenWhileDrivenThatWandersOnIsNotChased() {
        // An RTS hides the cursor on a right-drag while the mouse moves, and it wanders.
        controller.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        frames.settle();
        controller.onRelativeMove(20, 0);
        controller.onCursorPosition(1150, 540, false, 2);
        frames.runUi();
        frames.settle();
        float atFirstHiddenPoint = view.x;
        for (int i = 0; i < 30; i++) {
            controller.onRelativeMove(25, 0);
            controller.onCursorPosition(1175 + 25 * i, 540, false, i + 3);
            frames.runUi();
            frames.settle();
        }
        assertEquals(atFirstHiddenPoint, view.x, 1f);
    }

    @Test
    public void aHiddenCursorCreepingOnePixelAtATimeIsNotChased() {
        controller.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        frames.settle();
        controller.onRelativeMove(1, 0);
        controller.onCursorPosition(1150, 540, false, 2);
        frames.runUi();
        frames.settle();
        float atFirstHiddenPoint = view.x;
        for (int i = 1; i <= 600; i++) {
            controller.onRelativeMove(1, 0);
            controller.onCursorPosition(1150 + i, 540, false, i + 2);
            frames.runUi();
            frames.settle();
        }
        // At most the one pixel of the anchor's tolerance, never the 600 it crept.
        assertEquals(atFirstHiddenPoint, view.x, 2f);
    }

    @Test
    public void aGameCursorHiddenAfterTheHostShowedItIsNotChasedToTheEdge() {
        // Visible, then a game hides it (no input then), then play pins it at the edge.
        controller.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        frames.settle();
        frames.advance(CursorFollowController.POINTER_INPUT_WINDOW_MS + 1);
        controller.onCursorPosition(960, 540, false, 2);
        frames.runUi();
        frames.settle();
        float before = view.x;
        controller.onRelativeMove(900, 0);
        controller.onCursorPosition(1919, 540, false, 3);
        frames.runUi();
        frames.settle();
        assertEquals(before, view.x, 0f);
    }

    @Test
    public void aHiddenCursorSlidAlongTheEdgeByADiagonalSwipeStaysFollowed() {
        controller.onCursorPosition(960, 540, true, 1);
        frames.runUi();
        frames.settle();
        controller.onRelativeMove(40, 5);
        controller.onCursorPosition(1919, 540, false, 2);
        frames.runUi();
        for (int i = 0; i < 10; i++) {
            // One frame of the follow, then the host re-sends the hidden cursor lower down.
            Choreographer.FrameCallback f = frames.frame;
            frames.frame = null;
            frames.now += 17;
            if (f != null) {
                f.doFrame(frames.nanos += 16_666_667L);
            }
            controller.onRelativeMove(40, 5);
            controller.onCursorPosition(1919, 545 + 5 * i, false, i + 3);
            frames.runUi();
        }
        frames.settle();
        assertTrue("followed to the edge: " + view.x, 1919f <= view.x + 480f);
    }

    @Test
    public void aResumedCursorHiddenAtTheEdgeIsFollowedWhenDriven() {
        // A host that re-sends while hidden: the driven report itself decides.
        controller.onRelativeMove(40, 0);
        controller.onCursorPosition(1919, 540, false, 1);
        frames.runUi();
        frames.settle();
        assertTrue(1919f <= view.x + 480f);
    }

    @Test
    public void aResumedCursorHiddenAtTheEdgeIsFollowedOnTheFirstMoveThoughTheHostSendsNoMore() {
        // sunmeow sends one report when the cursor hides and none while it stays hidden: the
        // resumed stream's first report is all there is, and it arrives before any input.
        controller.onCursorPosition(1919, 540, false, 1);
        frames.runUi();
        float before = view.x;
        frames.settle();
        assertEquals("not driven yet", before, view.x, 0f);
        controller.onRelativeMove(40, 0);
        frames.runUi();
        frames.settle();
        assertTrue(1919f <= view.x + 480f);
    }
}
