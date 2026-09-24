package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.cursor.CursorFollowController;
import com.limelight.meow.cursor.CursorFollowMotion;
import com.limelight.meow.cursor.CursorInputTap;
import com.limelight.meow.stream.MeowStreamBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.utils.PanZoomHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Cursor-follow wired exactly as {@code Game.onCreate} wires it — a real {@link PanZoomHandler},
 * the {@link StreamViewportBinder} and the {@link CursorFollowController} — with vsyncs pumped
 * by hand. The assertion throughout is the user-visible one: after following settles, the host
 * cursor is inside what the user can see.
 */
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class CursorFollowBindingTest {

    private static final int STREAM_W = 1920;
    private static final int STREAM_H = 1080;
    private static final int VIEW_W = 1920;
    private static final int VIEW_H = 1080;

    /** Records rectangles on the wire; the host always answers so the feature is live. */
    private final List<ViewportRect> sent = new ArrayList<>();

    /** Vsyncs and clock under test control. */
    private static final class PumpedFrames implements CursorFollowController.Frames {
        Choreographer.FrameCallback pending;
        long nowMs = 10_000L;
        long frameNanos = 1_000_000_000L;

        @Override public long uptimeMillis() { return nowMs; }
        @Override public void requestFrame(Choreographer.FrameCallback callback) { pending = callback; }
        @Override public boolean isUiThread() { return true; }
        @Override public void postToUi(Runnable task) { task.run(); }
        @Override public boolean animationsEnabled() { return true; }

        /** Runs one vsync, if one was asked for. */
        void frame() {
            Choreographer.FrameCallback callback = pending;
            pending = null;
            frameNanos += 16_666_667L;
            nowMs += 17;
            if (callback != null) {
                callback.doFrame(frameNanos);
            }
        }

        /** Runs vsyncs until the follower stops asking; returns how many ran. */
        int settle() {
            int frames = 0;
            while (pending != null && frames < 600) {
                Choreographer.FrameCallback callback = pending;
                pending = null;
                frameNanos += 16_666_667L;
                nowMs += 17;
                callback.doFrame(frameNanos);
                frames++;
            }
            return frames;
        }
    }

    private FrameLayout parent;
    private View streamView;
    private StreamViewportBinder binder;
    private PanZoomHandler panZoom;
    private PumpedFrames frames;
    private CursorFollowController follow;
    private ViewportReporter.Scheduler scheduler;
    private Runnable deadline;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        parent = new FrameLayout(context);
        streamView = new View(context);
        parent.addView(streamView, new FrameLayout.LayoutParams(VIEW_W, VIEW_H));
        parent.layout(0, 0, VIEW_W, VIEW_H);
        streamView.layout(0, 0, VIEW_W, VIEW_H);

        scheduler = new ViewportReporter.Scheduler() {
            @Override public void schedule(long delayMs, Runnable task) { deadline = task; }
            @Override public void cancel() { deadline = null; }
        };
        ViewportReporter reporter = new ViewportReporter((x, y, w, h, force) -> {
            sent.add(new ViewportRect(x, y, w, h));
            return ViewportReporter.LI_OK;
        }, scheduler);
        binder = new StreamViewportBinder(streamView, parent, reporter,
                new Handler(Looper.getMainLooper()));
        panZoom = new PanZoomHandler(context, null, streamView, parent,
                PreferenceConfiguration.readPreferences(context));
        frames = new PumpedFrames();
    }

    @After
    public void tearDown() {
        binder.release();
        CursorInputTap.install(null);
        MeowStreamBridge.setCursorListener(null);
    }

    private static void drain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** As Game.onCreate, then the stream starts. */
    private void wire(boolean followEnabled, boolean viewportEnabled) {
        binder.setEnabled(viewportEnabled);
        binder.setTransformSource(panZoom);
        panZoom.setZoomTransformObserver(binder);
        follow = new CursorFollowController(binder, panZoom, followEnabled, frames);
        // What NvConnection does with a position: the tap hears it.
        follow.setPointerSink(CursorInputTap::absolute);
        binder.setCursorFollow(follow);
        binder.setCapabilityProbe(follow.isEnabled());
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
    }

    private void hostEchoes() {
        binder.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0, 0);
        drain();
    }

    private void zoomTo4x() {
        panZoom.pinchBy(4f, VIEW_W / 2f, VIEW_H / 2f);
        drain();
    }

    private void assertCursorVisible(float x, float y) {
        float[] v = new float[4];
        assertTrue(binder.visibleReferenceRect(v));
        assertTrue("cursor x " + x + " outside " + v[0] + "+" + v[2],
                x >= v[0] && x <= v[0] + v[2]);
        assertTrue("cursor y " + y + " outside " + v[1] + "+" + v[3],
                y >= v[1] && y <= v[1] + v[3]);
    }

    @Test
    public void theViewFollowsTheHostReportedCursorAndTellsTheHost() {
        wire(true, true);
        hostEchoes();
        zoomTo4x();
        int before = sent.size();

        // The host moves its own cursor to the far right of the desktop.
        MeowStreamBridge.setCursorListener(follow);
        follow.onCursorPosition(1800, 900, true, 1);
        assertTrue(frames.settle() > 1);
        drain();

        assertCursorVisible(1800, 900);
        assertTrue("the pan must reach the host so the crop follows", sent.size() > before);
    }

    @Test
    public void followingIsSmoothNotAJump() {
        wire(true, true);
        hostEchoes();
        zoomTo4x();
        float start = panZoom.getChildX();
        follow.onCursorPosition(1800, 540, true, 1);
        Choreographer.FrameCallback callback = frames.pending;
        frames.pending = null;
        callback.doFrame(frames.frameNanos += 16_666_667L);
        float oneFrame = start - panZoom.getChildX();
        // The cursor is off screen, so the fast regime -- but acceleration-limited from rest:
        // the first frame moves at most FAST accel x 480 ref px / 60^2, x4 view px.
        float cap = CursorFollowMotion.FAST_VIEWS_PER_SECOND_SQUARED * 480f / 3600f * 4f;
        assertTrue("moved " + oneFrame, oneFrame > 0f && oneFrame <= cap + 0.5f);
    }

    @Test
    public void panningHappensEvenWhenTheHostNeverEchoes() {
        // Stock Sunshine: no echo, no 0x3004. Dead reckoning from what the client sends.
        wire(true, true);
        deadline.run();
        drain();
        deadline.run();
        drain();
        zoomTo4x();
        CursorInputTap.absolute((short) 1900, (short) 540, (short) 1920, (short) 1080);
        frames.settle();
        assertCursorVisible(follow.cursor().x(), follow.cursor().y());
    }

    @Test
    public void panningHappensWithTheViewportPreferenceOff() {
        wire(true, false);
        zoomTo4x();
        CursorInputTap.absolute((short) 1900, (short) 540, (short) 1920, (short) 1080);
        frames.settle();
        assertCursorVisible(follow.cursor().x(), follow.cursor().y());
    }

    @Test
    public void withFollowingOffTheViewStaysPut() {
        wire(false, true);
        zoomTo4x();
        float before = panZoom.getChildX();
        CursorInputTap.absolute((short) 1900, (short) 540, (short) 1920, (short) 1080);
        follow.onCursorPosition(1800, 540, true, 1);
        frames.settle();
        assertEquals(before, panZoom.getChildX(), 0f);
    }

    @Test
    public void unzoomedThereIsNothingToFollow() {
        wire(true, true);
        follow.onCursorPosition(1900, 1000, true, 1);
        assertTrue(frames.settle() <= 1);
        assertEquals(0f, panZoom.getChildX(), 0f);
    }

    @Test
    public void aHiddenCursorIsNotFollowed() {
        wire(true, true);
        zoomTo4x();
        float before = panZoom.getChildX();
        follow.onCursorPosition(1900, 540, false, 1);
        frames.settle();
        assertEquals(before, panZoom.getChildX(), 0f);
    }

    @Test
    public void aUserPanIsKeptAndCarriesTheCursorWithIt() {
        wire(true, true);
        zoomTo4x();
        follow.onCursorPosition(960, 540, true, 1);
        frames.settle();
        panZoom.panBy(-1000f, 0f);
        drain();
        float panned = panZoom.getChildX();
        frames.settle();
        assertEquals("the pan is not undone", panned, panZoom.getChildX(), 0f);
        // The cursor kept its screen position: 1000 view px at 4x is 250 reference px.
        assertEquals(1210f, follow.cursor().x(), 0.6f);
        assertCursorVisible(follow.cursor().x(), follow.cursor().y());
    }

    @Test
    public void aTapNearTheEdgeDoesNotSlideTheDesktopAway() {
        wire(true, true);
        zoomTo4x();
        float before = panZoom.getChildX();
        // Visible reference box is 720..1200; a tap 7% in from the right edge.
        CursorInputTap.absolute((short) 1166, (short) 540, (short) 1920, (short) 1080);
        frames.settle();
        assertEquals("an absolute input only edge-scrolls", before, panZoom.getChildX(), 0f);

        // At the very edge it does scroll.
        CursorInputTap.absolute((short) 1199, (short) 540, (short) 1920, (short) 1080);
        frames.settle();
        assertTrue(panZoom.getChildX() < before);
    }

    @Test
    public void theHostIsSubscribedOnceItProvesItself() {
        wire(true, true);
        final int[] runs = new int[1];
        binder.addHostProvenTask(() -> runs[0]++);
        hostEchoes();
        hostEchoes();
        assertEquals("once per stream", 1, runs[0]);
    }

    @Test
    public void relativeDeltasFromAnyModeMoveTheEstimate() {
        wire(true, true);
        hostEchoes();
        zoomTo4x();
        // Trackpad, gaming touch, captured mouse and gamepad all send through here, one
        // event per display frame. Zoomed in with no host reports, the client owns the
        // pointer: it never leaves the view, and pushes the view along instead.
        for (int i = 0; i < 40; i++) {
            assertTrue("sent as an absolute position instead",
                    CursorInputTap.relative((short) 10, (short) 0));
            assertCursorVisible(follow.cursor().x(), follow.cursor().y());
            frames.frame();
        }
        frames.settle();
        assertTrue("x " + follow.cursor().x(), follow.cursor().x() > 960f + 300f);
        assertTrue("the estimate is exact: the host was told the position",
                follow.cursor().isExact());
        assertCursorVisible(follow.cursor().x(), follow.cursor().y());
    }

    @Test
    public void aStoppedStreamStopsListening() {
        wire(true, true);
        zoomTo4x();
        binder.onStreamStopped();
        drain();
        float before = panZoom.getChildX();
        CursorInputTap.absolute((short) 1919, (short) 540, (short) 1920, (short) 1080);
        frames.settle();
        assertEquals(before, panZoom.getChildX(), 0f);
        assertFalse(follow.cursor().isHostReporting());
    }
}
