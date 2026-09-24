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
    }

    /** Pans the fake view the way PanZoomHandler would. */
    private final class Pan implements InlinePinchZoomController.ZoomTarget {
        @Override public void pinchBy(float s, float fx, float fy) { }
        @Override public void panBy(float dx, float dy) {
            view.x -= dx / 4f;
            view.y -= dy / 4f;
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
        controller = new CursorFollowController(view, new Pan(), true, frames);
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
                new CursorFollowController(view, new Pan(), false, frames);
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
        controller.onDirectPointing();
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
        // Clamped just inside the right edge of the visible box (720..1200), at 4x precision.
        float x = controller.cursor().x();
        assertTrue("x " + x, x <= 1200f && x >= 1198f);
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
    public void aStaleHostReportJustAfterTheClientMovedThePointerIsIgnored() {
        withSink();
        controller.onCursorPosition(900, 500, true, 1);
        frames.runUi();
        controller.onAbsolutePosition(1100, 600, 1920, 1080);
        // The host's report of the old position arrives a moment later.
        controller.onCursorPosition(900, 500, true, 2);
        frames.runUi();
        assertEquals(1100f / 1919f * 1920f, controller.cursor().x(), 0.01f);
        frames.now += CursorFollowController.HOST_REPORT_GRACE_MS + 1;
        controller.onCursorPosition(905, 500, true, 3);
        frames.runUi();
        assertEquals(905f, controller.cursor().x(), 0f);
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
}
