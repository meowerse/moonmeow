package com.limelight.meow.viewport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

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
 * The view-reading half of the feature. The arithmetic itself is covered by
 * {@link ViewportGeometryTest} and the state machine by {@link ViewportReporterTest}; what
 * matters here is that the right numbers are read off the right views, that the work is
 * handed to the reporter's thread rather than done on the caller's, and that the binder
 * stays inert when it should.
 *
 * <p>The handler is injected and bound to Robolectric's paused main looper, so every test
 * controls exactly when the posted work runs. In production that handler belongs to a
 * private {@code HandlerThread} — the whole point being that none of this happens on the UI
 * thread.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class StreamViewportBinderTest {

    private static final int STREAM_W = 5360;
    private static final int STREAM_H = 1440;
    private static final int VIEW_W = 2400;
    private static final int VIEW_H = 644;

    private static final class FakeSender implements ViewportReporter.Sender {
        final List<ViewportRect> sent = new ArrayList<>();
        int result = ViewportReporter.LI_OK;

        final List<Boolean> forced = new ArrayList<>();

        @Override
        public int send(int x, int y, int width, int height, boolean force) {
            sent.add(new ViewportRect(x, y, width, height));
            forced.add(force);
            return result;
        }

        ViewportRect last() {
            return sent.isEmpty() ? null : sent.get(sent.size() - 1);
        }
    }

    /** Captures the deadline task so a test can decide when the probe times out. */
    private static final class CapturingScheduler implements ViewportReporter.Scheduler {
        Runnable task;

        @Override
        public void schedule(long delayMs, Runnable task) {
            this.task = task;
        }

        @Override
        public void cancel() {
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
        binder = newBinder(streamView);
    }

    @After
    public void tearDown() {
        MeowViewportBridge.setEchoListener(null);
    }

    private StreamViewportBinder newBinder(View view) {
        return new StreamViewportBinder(view, parent, reporter,
                new Handler(Looper.getMainLooper()));
    }

    private void drain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Start the stream and let the host answer, so the feature is live. */
    private void startSupported() {
        binder.setEnabled(true);
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
        binder.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0);
        drain();
    }

    // --- reading the views --------------------------------------------------------------

    @Test
    public void anUntransformedViewReportsTheWholeFrame() {
        startSupported();
        assertEquals(ViewportRect.full(STREAM_W, STREAM_H), binder.computeVisibleHostRect());
    }

    @Test
    public void theScaleAndOffsetOfTheStreamViewAreWhatIsRead() {
        startSupported();
        streamView.setScaleX(4f);
        streamView.setScaleY(4f);
        streamView.setX(-VIEW_W * 3f);   // panned hard right, as constrainToBounds clamps it
        streamView.setY(-VIEW_H * 3f);

        ViewportRect rect = binder.computeVisibleHostRect();
        assertNotNull(rect);
        assertEquals(STREAM_W, rect.x + rect.width);
        assertEquals(STREAM_W / 4, rect.width);
        assertEquals(STREAM_H / 4, rect.height);
    }

    @Test
    public void aViewWithNoLayoutYetReportsNothingRatherThanGuessing() {
        startSupported();
        StreamViewportBinder early = newBinder(new View(context));
        assertNull(early.computeVisibleHostRect());
    }

    // --- the reporting path -------------------------------------------------------------

    @Test
    public void nothingReachesTheWireOnTheCallersThread() {
        // The whole reason the handler exists: LiSendViewportEvent can block for up to
        // ~10ms on ENet backpressure, and this is called from inside touch dispatch.
        startSupported();
        sender.sent.clear();
        streamView.setScaleX(2f);
        streamView.setScaleY(2f);
        streamView.setX(-VIEW_W / 2f);
        streamView.setY(-VIEW_H / 2f);

        binder.onZoomTransformChanged();
        assertTrue("the send must be posted, not performed inline", sender.sent.isEmpty());

        drain();
        assertEquals(1, sender.sent.size());
        assertEquals(STREAM_W / 2, sender.last().width);
    }

    @Test
    public void everyTransformChangeReachesTheWireOnceTheHostIsSupported() {
        // No throttle of our own -- the library coalesces. See ViewportReporter.
        startSupported();
        sender.sent.clear();
        for (int i = 1; i <= 5; i++) {
            streamView.setX(-i);
            binder.onZoomTransformChanged();
        }
        drain();
        assertEquals(5, sender.sent.size());
    }

    @Test
    public void anInactiveBinderDoesNotEvenLookAtTheViews() {
        // The observer is called once per input frame; when the feature is off or the host
        // does not support it, that has to cost nothing.
        streamView.setScaleX(4f);
        binder.onZoomTransformChanged();
        drain();
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void aHostThatNeverEchoesSilencesLaterTransformChanges() {
        binder.setEnabled(true);
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
        scheduler.fire();   // first deadline: retry
        scheduler.fire();   // second deadline: give up
        int afterProbes = sender.sent.size();

        streamView.setScaleX(4f);
        streamView.setScaleY(4f);
        binder.onZoomTransformChanged();
        drain();

        assertEquals(afterProbes, sender.sent.size());
    }

    @Test
    public void theHostsEchoIsForwardedToTheReporterOnItsOwnThread() {
        binder.setEnabled(true);
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
        assertEquals(ViewportReporter.HostSupport.PROBING, reporter.hostSupport());

        // Arrives on the library's async callback thread in production.
        binder.onViewportApplied(0, 0, STREAM_W, STREAM_H, 3840, 2160);
        assertEquals("must not be applied inline",
                ViewportReporter.HostSupport.PROBING, reporter.hostSupport());

        drain();
        assertEquals(ViewportReporter.HostSupport.SUPPORTED, reporter.hostSupport());
        assertEquals(3840, reporter.desktopWidth());
    }

    @Test
    public void theBinderRegistersItselfForEchoesAtStreamStartAndDeregistersAtStop() {
        binder.setEnabled(true);
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
        MeowViewportBridge.onViewportEcho(0, 0, STREAM_W, STREAM_H, 0, 0);
        drain();
        assertEquals(ViewportReporter.HostSupport.SUPPORTED, reporter.hostSupport());

        binder.onStreamStopped();
        drain();
        // A late echo after teardown must not reach anything.
        MeowViewportBridge.onViewportEcho(0, 0, 10, 10, 0, 0);
        drain();
        assertEquals(ViewportReporter.HostSupport.UNSUPPORTED, reporter.hostSupport());
    }

    @Test
    public void stoppingDrainsTheUncropBeforeReturning() {
        // Sending a viewport after LiStopConnection races the ENet peer's destruction, so
        // onStreamStopped() must not return with the uncrop still sitting in a queue.
        startSupported();
        streamView.setScaleX(4f);
        binder.onZoomTransformChanged();
        drain();
        int before = sender.sent.size();

        binder.onStreamStopped();

        assertEquals("the uncrop must have been sent before onStreamStopped() returned",
                before + 1, sender.sent.size());
        assertEquals(ViewportRect.full(STREAM_W, STREAM_H), sender.last());
    }

    @Test
    public void stoppingWaitsForTheUncropWhenTheReporterIsOnItsOwnThread() throws Exception {
        // The production shape: the reporter really is on another thread, so onStreamStopped()
        // has to block on it. If it returned early, Game would call conn.stop() -- and
        // LiStopConnection tears down the ENet peer that the queued send is about to touch.
        android.os.HandlerThread worker = new android.os.HandlerThread("viewport-test");
        worker.start();
        try {
            FakeSender workerSender = new FakeSender();
            ViewportReporter workerReporter =
                    new ViewportReporter(workerSender, new CapturingScheduler());
            StreamViewportBinder threaded = new StreamViewportBinder(streamView, parent,
                    workerReporter, new Handler(worker.getLooper()));

            threaded.setEnabled(true);
            threaded.onStreamStarted(STREAM_W, STREAM_H);
            threaded.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0);

            threaded.onStreamStopped();

            // No sleeping, no polling: if the drain worked, the uncrop is already recorded.
            assertTrue("nothing was ever sent", !workerSender.sent.isEmpty());
            assertEquals("the last thing on the wire must be the uncrop",
                    ViewportRect.full(STREAM_W, STREAM_H), workerSender.last());
        } finally {
            worker.quitSafely();
        }
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
                0, 0, 3000, VIEW_H, 300f, 0f, 2700f, VIEW_H, STREAM_W, STREAM_H);
        assertEquals(Math.round(0.1f * STREAM_W), rect.x);
        assertEquals(Math.round(0.8f * STREAM_W), rect.width);
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

    @Test
    public void aWindowEntirelyOutsideTheParentFallsBackRatherThanInverting() {
        float[] out = new float[4];
        StreamViewportBinder.windowFromGlobalVisibleRect(
                true, new android.graphics.Rect(3000, 0, 4000, VIEW_H),
                new android.graphics.Point(0, 0), VIEW_W, VIEW_H, out);
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

        binder.setEnabled(true);
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();

        assertEquals("the probe goes first", ViewportRect.full(STREAM_W, STREAM_H),
                sender.sent.get(0));
        assertEquals("and nothing else until the host answers", 1, sender.sent.size());

        binder.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0);
        drain();
        assertEquals("the restored crop must reach the host",
                STREAM_W / 4, sender.last().width);
    }

    @Test
    public void aStreamStartingUnzoomedSendsOnlyTheProbe() {
        startSupported();
        assertEquals(1, sender.sent.size());
        assertEquals(ViewportRect.full(STREAM_W, STREAM_H), sender.last());
    }

    @Test
    public void constructorRejectsNulls() {
        Handler handler = new Handler(Looper.getMainLooper());
        try {
            new StreamViewportBinder(null, parent, reporter, handler);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new StreamViewportBinder(streamView, null, reporter, handler);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
