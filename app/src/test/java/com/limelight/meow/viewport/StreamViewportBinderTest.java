package com.limelight.meow.viewport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The view-reading half of the feature. The arithmetic itself is covered by
 * {@link ViewportGeometryTest}; what matters here is that the right numbers are read off the
 * right views, and that the binder stays inert when it should.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class StreamViewportBinderTest {

    private static final int HOST_W = 5360;
    private static final int HOST_H = 1440;
    private static final int VIEW_W = 2400;
    private static final int VIEW_H = 644;

    private static final class FakeSender implements ViewportReporter.Sender {
        final List<ViewportRect> sent = new ArrayList<>();
        int result = ViewportReporter.LI_OK;

        @Override
        public int send(int x, int y, int width, int height) {
            sent.add(new ViewportRect(x, y, width, height));
            return result;
        }

        ViewportRect last() {
            return sent.isEmpty() ? null : sent.get(sent.size() - 1);
        }
    }

    /** Captures the settle task so a test can decide when the trailing edge fires. */
    private static final class CapturingScheduler implements ViewportReporter.Scheduler {
        Runnable task;

        @Override
        public void scheduleSettle(long delayMs, Runnable task) {
            this.task = task;
        }

        @Override
        public void cancelSettle() {
            task = null;
        }

        void fire() {
            Runnable t = task;
            task = null;
            if (t != null) {
                t.run();
            }
        }
    }

    private Context context;
    private FrameLayout parent;
    private View streamView;
    private FakeSender sender;
    private ViewportReporter reporter;
    private CapturingScheduler scheduler;
    private StreamViewportBinder binder;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        parent = new FrameLayout(context);
        streamView = new View(context);
        parent.addView(streamView, new FrameLayout.LayoutParams(VIEW_W, VIEW_H));

        parent.layout(0, 0, VIEW_W, VIEW_H);
        streamView.layout(0, 0, VIEW_W, VIEW_H);
        streamView.setPivotX(0);
        streamView.setPivotY(0);

        sender = new FakeSender();
        scheduler = new CapturingScheduler();
        reporter = new ViewportReporter(sender, scheduler);
        binder = new StreamViewportBinder(streamView, parent, reporter);
    }

    private void start() {
        binder.setEnabled(true);
        binder.onStreamStarted(HOST_W, HOST_H);
    }

    @Test
    public void anUntransformedViewReportsTheWholeDesktop() {
        start();
        assertEquals(ViewportRect.full(HOST_W, HOST_H), binder.computeVisibleHostRect());
    }

    @Test
    public void theScaleAndOffsetOfTheStreamViewAreWhatIsRead() {
        start();
        streamView.setScaleX(4f);
        streamView.setScaleY(4f);
        streamView.setX(-VIEW_W * 3f);   // panned hard right, as constrainToBounds clamps it
        streamView.setY(-VIEW_H * 3f);

        ViewportRect rect = binder.computeVisibleHostRect();
        assertNotNull(rect);
        assertEquals(HOST_W, rect.x + rect.width);
        assertEquals(HOST_W / 4, rect.width);
        assertEquals(HOST_H / 4, rect.height);
    }

    @Test
    public void aViewWithNoLayoutYetReportsNothingRatherThanGuessing() {
        start();
        View unlaidOut = new View(context);
        StreamViewportBinder early = new StreamViewportBinder(unlaidOut, parent, reporter);
        assertNull(early.computeVisibleHostRect());
    }

    @Test
    public void aTransformChangeReachesTheWire() {
        start();
        sender.sent.clear();
        // The full-frame send at stream start starts the rate-limit window, so let it lapse
        // -- otherwise this would exercise the throttle rather than the binder.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(ViewportThrottle.MIN_INTERVAL_MS + 1));
        streamView.setScaleX(2f);
        streamView.setScaleY(2f);
        streamView.setX(-VIEW_W / 2f);
        streamView.setY(-VIEW_H / 2f);

        binder.onZoomTransformChanged();

        assertEquals(1, sender.sent.size());
        assertEquals(HOST_W / 2, sender.last().width);
    }

    @Test
    public void aTransformChangeInsideTheRateLimitStillLandsOnTheSettle() {
        start();
        sender.sent.clear();
        streamView.setScaleX(2f);
        streamView.setScaleY(2f);
        streamView.setX(-VIEW_W / 2f);
        streamView.setY(-VIEW_H / 2f);

        binder.onZoomTransformChanged();
        assertTrue("throttled inside the rate-limit window", sender.sent.isEmpty());

        scheduler.fire();
        assertEquals(HOST_W / 2, sender.last().width);
    }

    @Test
    public void anInactiveBinderDoesNotEvenLookAtTheViews() {
        // The observer is called once per input frame; when the feature is off or the host
        // does not support it, that has to cost nothing.
        streamView.setScaleX(4f);
        binder.onZoomTransformChanged();
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void anUnsupportedHostSilencesLaterTransformChanges() {
        sender.result = ViewportReporter.LI_UNSUPPORTED;
        start();
        int afterProbe = sender.sent.size();

        sender.result = ViewportReporter.LI_OK;
        streamView.setScaleX(4f);
        streamView.setScaleY(4f);
        binder.onZoomTransformChanged();

        assertEquals(afterProbe, sender.sent.size());
    }

    // --- the window the parent is seen through -----------------------------------------
    //
    // Driven through the extracted helper rather than through a laid-out hierarchy on
    // purpose: Robolectric has no real window, so getGlobalVisibleRect reports the whole
    // view there and a test routed through it would pass without the clipping ever running.

    @Test
    public void anUnclippedParentIsVisibleInFull() {
        float[] out = new float[4];
        StreamViewportBinder.windowFromGlobalVisibleRect(
                true, new android.graphics.Rect(0, 0, VIEW_W, VIEW_H),
                new android.graphics.Point(0, 0), VIEW_W, VIEW_H, out);
        assertArrayEquals(new float[] { 0f, 0f, VIEW_W, VIEW_H }, out, 0.001f);
    }

    @Test
    public void aParentLargerThanItsWindowReportsOnlyWhatIsOnScreen() {
        // FILL scale mode: StreamContainer measures itself 3000px wide inside a 2400px
        // screen and is centred, so 300px hangs off each side. getGlobalVisibleRect answers
        // with the on-screen slice in screen coordinates (0..2400) and an offset of -300,
        // meaning the visible part starts 300px into the parent.
        float[] out = new float[4];
        StreamViewportBinder.windowFromGlobalVisibleRect(
                true, new android.graphics.Rect(0, 0, VIEW_W, VIEW_H),
                new android.graphics.Point(-300, 0), 3000, VIEW_H, out);
        assertArrayEquals(new float[] { 300f, 0f, 2700f, VIEW_H }, out, 0.001f);
    }

    @Test
    public void thatWindowIsWhatNarrowsTheReportedViewport() {
        ViewportRect rect = ViewportGeometry.visibleHostRect(
                0, 0, 3000, VIEW_H, 300f, 0f, 2700f, VIEW_H, HOST_W, HOST_H);
        assertEquals(Math.round(0.1f * HOST_W), rect.x);
        assertEquals(Math.round(0.8f * HOST_W), rect.width);
    }

    @Test
    public void aPlatformThatCannotAnswerFallsBackToTheWholeParent() {
        float[] out = new float[4];
        StreamViewportBinder.windowFromGlobalVisibleRect(
                false, new android.graphics.Rect(10, 10, 20, 20),
                new android.graphics.Point(5, 5), VIEW_W, VIEW_H, out);
        assertArrayEquals(new float[] { 0f, 0f, VIEW_W, VIEW_H }, out, 0.001f);
    }

    @Test
    public void aDegenerateAnswerFallsBackToTheWholeParent() {
        float[] out = new float[4];
        StreamViewportBinder.windowFromGlobalVisibleRect(
                true, new android.graphics.Rect(0, 0, 0, 0),
                new android.graphics.Point(0, 0), VIEW_W, VIEW_H, out);
        assertArrayEquals(new float[] { 0f, 0f, VIEW_W, VIEW_H }, out, 0.001f);
    }

    @Test
    public void aWindowReachingOutsideTheParentIsClampedToIt() {
        float[] out = new float[4];
        StreamViewportBinder.windowFromGlobalVisibleRect(
                true, new android.graphics.Rect(0, 0, 9000, 9000),
                new android.graphics.Point(100, 100), VIEW_W, VIEW_H, out);
        assertArrayEquals(new float[] { 0f, 0f, VIEW_W, VIEW_H }, out, 0.001f);
    }

    // --- a session that starts already zoomed -------------------------------------------

    @Test
    public void aStreamStartingOnARestoredZoomReportsTheCropNotJustTheFullFrame() {
        // rememberZoomPan restores the transform from a streamContainer.post() in onCreate,
        // long before the connection is up, so that notify is discarded. Without a readback
        // at stream start the host stays uncropped until the user next touches the screen.
        streamView.setScaleX(4f);
        streamView.setScaleY(4f);
        streamView.setX(-VIEW_W * 3f);
        streamView.setY(-VIEW_H * 3f);

        start();

        assertEquals(ViewportRect.full(HOST_W, HOST_H), sender.sent.get(0));
        scheduler.fire();
        assertEquals("the restored crop must reach the host", HOST_W / 4, sender.last().width);
    }

    @Test
    public void aStreamStartingUnzoomedSendsOnlyTheFullFrame() {
        start();
        scheduler.fire();
        assertEquals(1, sender.sent.size());
        assertEquals(ViewportRect.full(HOST_W, HOST_H), sender.last());
    }

    @Test
    public void constructorRejectsNulls() {
        try {
            new StreamViewportBinder(null, parent, reporter);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new StreamViewportBinder(streamView, null, reporter);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new StreamViewportBinder(streamView, parent, (ViewportReporter) null);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
