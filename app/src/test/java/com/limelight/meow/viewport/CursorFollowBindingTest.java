package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.utils.PanZoomHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Cursor-follow, wired to the real {@link PanZoomHandler} so the whole loop is exercised:
 * transform in, plan, {@code panBy}, {@code constrainToBounds}, transform out.
 *
 * <h2>The regression these exist for</h2>
 * Cursor-follow shipped gated on {@code reporter.isLive()}, which is true only while the
 * <em>host</em> is answering viewport messages. Panning the local view sends nothing and needs
 * no host cooperation, so against any host without the extension — and against any user who
 * had the viewport preference off — the feature was silently inert. That is what "it exists
 * but doesn't work" meant. {@link #panningHappensEvenWhenTheHostNeverEchoes()} and
 * {@link #panningHappensWithTheViewportPreferenceOff()} fail on the old gate.
 */
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class CursorFollowBindingTest {

    private static final int STREAM_W = 1920;
    private static final int STREAM_H = 1080;
    private static final int VIEW_W = 1920;
    private static final int VIEW_H = 1080;

    /** Accepts everything; this suite is about the view, not the wire. */
    private static final class FakeSender implements ViewportReporter.Sender {
        @Override
        public int send(int x, int y, int width, int height, boolean force) {
            return ViewportReporter.LI_OK;
        }
    }

    /** Holds the probe deadline so a test can decide when the host is written off. */
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

    private FrameLayout parent;
    private View streamView;
    private StreamViewportBinder binder;
    private CapturingScheduler scheduler;
    private ViewportReporter reporter;
    private PanZoomHandler panZoom;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        parent = new FrameLayout(context);
        streamView = new View(context);
        parent.addView(streamView, new FrameLayout.LayoutParams(VIEW_W, VIEW_H));
        parent.layout(0, 0, VIEW_W, VIEW_H);
        streamView.layout(0, 0, VIEW_W, VIEW_H);

        scheduler = new CapturingScheduler();
        reporter = new ViewportReporter(new FakeSender(), scheduler);
        binder = new StreamViewportBinder(streamView, parent, reporter,
                new Handler(Looper.getMainLooper()));

        // PanZoomHandler sets the pivot to (0,0) itself, which is the geometry
        // ViewportGeometry assumes. Only pinchBy/panBy are used, so the Game is not needed.
        panZoom = new PanZoomHandler(context, null, streamView, parent,
                PreferenceConfiguration.readPreferences(context));
        panZoom.setZoomTransformObserver(binder);
    }

    private void drain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Stream up, host answers: the feature is live in every sense. */
    private void startWithSupportingHost() {
        binder.setEnabled(true);
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
        binder.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0);
        drain();
    }

    /** Stream up, host never answers: exactly what stock Sunshine looks like. */
    private void startWithSilentHost() {
        binder.setEnabled(true);
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
        for (int i = 0; i <= ViewportReporter.PROBE_ATTEMPTS; i++) {
            scheduler.fire();
            drain();
        }
        assertEquals("the host must have been written off for this test to mean anything",
                ViewportReporter.HostSupport.UNSUPPORTED, reporter.hostSupport());
    }

    private void zoomTo4x() {
        panZoom.pinchBy(4f, VIEW_W / 2f, VIEW_H / 2f);
        drain();
    }

    // --- the regression -------------------------------------------------------------------

    @Test
    public void panningHappensEvenWhenTheHostNeverEchoes() {
        startWithSilentHost();
        zoomTo4x();
        float before = streamView.getX();

        assertTrue("cursor-follow is client side and must not need host support",
                binder.handleCursorViewPosition(VIEW_W - 10f, VIEW_H / 2f, panZoom));
        assertTrue("the view must actually have moved", streamView.getX() < before);
    }

    @Test
    public void panningHappensWithTheViewportPreferenceOff() {
        // Preference off: the reporter must stay silent, but the crop must still chase.
        binder.setEnabled(false);
        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
        zoomTo4x();
        float before = streamView.getX();

        assertTrue(binder.handleCursorViewPosition(VIEW_W - 10f, VIEW_H / 2f, panZoom));
        assertTrue(streamView.getX() < before);
    }

    // --- the behaviour it is supposed to have ---------------------------------------------

    @Test
    public void aCursorNearTheRightEdgeScrollsTheContentLeft() {
        startWithSupportingHost();
        zoomTo4x();
        ViewportRect before = binder.computeVisibleHostRect();

        assertTrue(binder.handleCursorViewPosition(VIEW_W - 1f, VIEW_H / 2f, panZoom));
        drain();

        ViewportRect after = binder.computeVisibleHostRect();
        assertTrue("the crop must move to the right in host space", after.x > before.x);
        assertEquals("only the axis the cursor is near may move", before.y, after.y);
    }

    @Test
    public void aCursorInTheMiddleMovesNothing() {
        startWithSupportingHost();
        zoomTo4x();
        float before = streamView.getX();

        assertFalse(binder.handleCursorViewPosition(VIEW_W / 2f, VIEW_H / 2f, panZoom));
        assertEquals(before, streamView.getX(), 0.001f);
    }

    @Test
    public void anUnzoomedStreamNeverPans() {
        startWithSupportingHost();
        assertFalse(binder.handleCursorViewPosition(VIEW_W - 1f, VIEW_H - 1f, panZoom));
    }

    @Test
    public void nothingHappensBeforeTheStreamStarts() {
        // No negotiated stream size yet, so any host coordinate we computed would be fiction.
        zoomTo4x();
        assertFalse(binder.handleCursorViewPosition(VIEW_W - 1f, VIEW_H / 2f, panZoom));
    }

    @Test
    public void nothingHappensAfterTheStreamStops() {
        startWithSupportingHost();
        zoomTo4x();
        binder.onStreamStopped();
        drain();
        assertFalse(binder.handleCursorViewPosition(VIEW_W - 1f, VIEW_H / 2f, panZoom));
    }

    @Test
    public void cursorFollowCanBeTurnedOffWithoutSilencingTheReporter() {
        startWithSupportingHost();
        zoomTo4x();
        binder.setCursorFollowEnabled(false);

        assertFalse(binder.handleCursorViewPosition(VIEW_W - 1f, VIEW_H / 2f, panZoom));
        assertTrue("the wire half must be unaffected", reporter.isLive());
    }

    @Test
    public void aNullPanTargetIsIgnoredRatherThanThrowing() {
        startWithSupportingHost();
        zoomTo4x();
        assertFalse(binder.handleCursorViewPosition(VIEW_W - 1f, VIEW_H / 2f, null));
    }

    // --- the dead-reckoned (captured pointer) entry point ----------------------------------

    @Test
    public void aHostSpaceCursorAtTheLeftEdgePansTheCropLeft() {
        startWithSupportingHost();
        zoomTo4x();
        ViewportRect before = binder.computeVisibleHostRect();

        assertTrue(binder.handleCursorHostPosition(before.x - 200, before.y + before.height / 2,
                panZoom));
        drain();

        ViewportRect after = binder.computeVisibleHostRect();
        assertTrue("the crop must chase a cursor that left it", after.x < before.x);
    }

    @Test
    public void aHostSpaceCursorInsideTheCropMovesNothing() {
        startWithSupportingHost();
        zoomTo4x();
        ViewportRect visible = binder.computeVisibleHostRect();
        assertFalse(binder.handleCursorHostPosition(visible.x + visible.width / 2,
                visible.y + visible.height / 2, panZoom));
    }

    @Test
    public void theHostSpaceEntryPointAlsoWorksWithoutHostSupport() {
        startWithSilentHost();
        zoomTo4x();
        float before = streamView.getX();
        assertTrue(binder.handleCursorHostPosition(0, STREAM_H / 2, panZoom));
        assertTrue(streamView.getX() > before);
    }
}
