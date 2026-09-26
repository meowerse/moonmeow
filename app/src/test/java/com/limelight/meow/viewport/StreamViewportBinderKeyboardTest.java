package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.cursor.CursorFollowController;
import com.limelight.meow.cursor.CursorInputTap;
import com.limelight.meow.keyboard.KeyboardVisibleArea;
import com.limelight.meow.stream.MeowStreamBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.PanZoomHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * The binder's side of the keyboard wiring: the keyboard area becomes its bottom obstruction,
 * the host cursor is the area's focus source, and large cursor moves are reported once.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class StreamViewportBinderKeyboardTest {

    private static final int W = 1920;
    private static final int H = 1080;

    private FrameLayout root;
    private FrameLayout parent;
    private StreamViewportBinder binder;
    private CursorFollowController follow;

    /** Frames that never run: this test drives positions, not motion. */
    private static class NoFrames implements CursorFollowController.Frames {
        @Override public long uptimeMillis() { return 0L; }
        @Override public void requestFrame(Choreographer.FrameCallback callback) { }
        @Override public boolean isUiThread() { return true; }
        @Override public void postToUi(Runnable task) { task.run(); }
        @Override public boolean animationsEnabled() { return true; }
        @Override public void postToUiDelayed(Runnable task, long delayMs) { }
    }

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        root = new FrameLayout(context);
        parent = new FrameLayout(context);
        View streamView = new View(context);
        root.addView(parent, new FrameLayout.LayoutParams(W, H));
        parent.addView(streamView, new FrameLayout.LayoutParams(W, H));
        root.layout(0, 0, W, H);
        parent.layout(0, 0, W, H);
        streamView.layout(0, 0, W, H);
        binder = new StreamViewportBinder(streamView, parent, null, new Handler(Looper.getMainLooper()));
        PanZoomHandler panZoom = new PanZoomHandler(context, null, streamView, parent,
                PreferenceConfiguration.readPreferences(context));
        binder.setTransformSource(panZoom);
        panZoom.setZoomTransformObserver(binder);
        follow = new CursorFollowController(binder, panZoom, true, new NoFrames());
        binder.setCursorFollow(follow);
        binder.onStreamStarted(W, H);
        idle();
    }

    @After
    public void tearDown() {
        binder.release();
        CursorInputTap.install(null);
        MeowStreamBridge.setCursorListener(null);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private int obstruction() throws Exception {
        Field f = StreamViewportBinder.class.getDeclaredField("bottomObstructionPx");
        f.setAccessible(true);
        return f.getInt(binder);
    }

    @Test
    public void theKeyboardAreaBecomesTheBottomObstruction() throws Exception {
        KeyboardVisibleArea area = KeyboardVisibleArea.of(parent);
        assertNotNull(area);
        area.publish(0, 0, W, 700);
        assertEquals(H - 700, obstruction());
        area.publish(0, 0, W, H);
        assertEquals(0, obstruction());
    }

    @Test
    public void theAreaRightEdgeBecomesTheRightObstructionAndNarrowsWhatIsVisible() throws Exception {
        binder.onViewportApplied(0, 0, W, H, W, H, 0);
        idle();
        float[] full = new float[4];
        assertTrue(binder.visibleReferenceRect(full));
        KeyboardVisibleArea area = KeyboardVisibleArea.of(parent);
        area.publish(0, 0, W - 200, H);
        Field f = StreamViewportBinder.class.getDeclaredField("rightObstructionPx");
        f.setAccessible(true);
        assertEquals(200, f.getInt(binder));
        float[] narrowed = new float[4];
        assertTrue(binder.visibleReferenceRect(narrowed));
        assertEquals("the visible width loses the side bar", full[2] - 200f, narrowed[2], 1f);
        area.publish(0, 0, W, H);
        assertEquals(0, f.getInt(binder));
        float[] restored = new float[4];
        assertTrue(binder.visibleReferenceRect(restored));
        assertEquals(full[2], restored[2], 1f);
    }

    @Test
    public void theFocusXIsTheHostCursorInContainerPixels() {
        follow.onCursorPosition(1500, 810, true, 1);
        assertEquals(1500f, KeyboardVisibleArea.of(parent).focusX(), 0.5f);
    }

    @Test
    public void theFocusIsTheHostCursorInContainerPixels() {
        KeyboardVisibleArea area = KeyboardVisibleArea.of(parent);
        assertTrue("unknown until the cursor is", Float.isNaN(area.focusY()));
        follow.onCursorPosition(960, 810, true, 1);
        assertEquals(810f, area.focusY(), 0.5f);
    }

    @Test
    public void largeCursorMovesAreReportedOnceEach() {
        KeyboardVisibleArea area = KeyboardVisibleArea.of(parent);
        int[] moved = {0};
        area.setFocusMovedListenerForTest(() -> moved[0]++);
        float[] v = new float[4];
        follow.onCursorPosition(960, 100, true, 1);
        binder.visibleReferenceRect(v);
        binder.visibleReferenceRect(v);
        idle();
        assertEquals("the first position is news, once", 1, moved[0]);
        follow.onCursorPosition(960, 150, true, 2);
        binder.visibleReferenceRect(v);
        idle();
        assertEquals("a small move is not", 1, moved[0]);
        follow.onCursorPosition(960, 900, true, 3);
        binder.visibleReferenceRect(v);
        idle();
        assertEquals("a large one is", 2, moved[0]);
    }

    @Test
    public void theStreamSettlingAfterALiftReReportsWhatIsVisible() throws Exception {
        // The lift moved the container; nothing about what covers the window changed. The
        // binder must recompute: a cursor that was under the keyboard's rows is chased again.
        KeyboardVisibleArea area = KeyboardVisibleArea.of(parent);
        area.publish(0, 0, W, 700);
        idle();
        int[] visibleChecks = {0};
        CursorFollowController counting = new CursorFollowController(binder,
                new com.limelight.meow.gesture.InlinePinchZoomController.ZoomTarget() {
                    @Override public void pinchBy(float s, float fx, float fy) { }
                    @Override public void panBy(float dx, float dy) { }
                    @Override public float getScaleFactor() { return 1f; }
                    @Override public float getChildX() { return 0f; }
                    @Override public float getChildY() { return 0f; }
                }, true, new NoFrames() {
                    @Override public void requestFrame(Choreographer.FrameCallback callback) {
                        // Run the frame at once: the pan target refuses, so it settles.
                        visibleChecks[0]++;
                        callback.doFrame(visibleChecks[0] * 16_666_667L);
                    }
                });
        binder.setCursorFollow(counting);
        counting.onStreamStarted(W, H);
        counting.onCursorPosition(960, 1000, true, 1);
        idle();
        int before = visibleChecks[0];
        parent.setTranslationY(-300f);
        area.onStreamMoved();
        idle();
        assertTrue("the cursor was being followed before", before > 0);
        assertTrue("the move re-armed the follower through onVisibleAreaChanged: " + visibleChecks[0]
                + " vs " + before, visibleChecks[0] > before);
        counting.onStreamStopped();
    }

    @Test
    public void releaseLeavesTheAreaHoldingNothing() throws Exception {
        KeyboardVisibleArea area = KeyboardVisibleArea.of(parent);
        follow.onCursorPosition(960, 810, true, 1);
        binder.release();
        assertTrue(Float.isNaN(area.focusY()));
        int before = obstruction();
        area.publish(0, 0, W, 500);
        assertEquals("no longer listening", before, obstruction());
    }
}
