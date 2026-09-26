package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.gesture.InlinePinchZoomController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The compositor, state by state: every state must present the user's view at exactly one
 * magnification, and the sharp crop must replace the soft zoom on the frame the host named.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class ViewportCompositorTest {

    private static final int VIEW_W = 1920;
    private static final int VIEW_H = 1080;
    private static final int STREAM_W = 1920;
    private static final int STREAM_H = 1080;

    /** A logical transform the test sets directly. */
    private static final class Logical implements InlinePinchZoomController.ZoomTarget {
        float scale = 1f;
        float x;
        float y;

        @Override public void pinchBy(float s, float fx, float fy) { }
        @Override public void panBy(float dx, float dy) { }
        @Override public float getScaleFactor() { return scale; }
        @Override public float getChildX() { return x; }
        @Override public float getChildY() { return y; }
    }

    /** Frames the "decoder" has presented. */
    private static final class Frames implements ViewportCompositor.FrameClock {
        int presentedUpTo;

        @Override
        public boolean hasPresented(int frameIndex) {
            return frameIndex <= presentedUpTo;
        }
    }

    /** Vsyncs the test fires by hand. */
    private static final class Vsync implements ViewportCompositor.VsyncScheduler {
        Runnable pending;
        int requests;

        @Override
        public void requestFrame(Runnable onFrame) {
            pending = onFrame;
            requests++;
        }

        void fire() {
            Runnable run = pending;
            pending = null;
            if (run != null) {
                run.run();
            }
        }
    }

    private View view;
    private Logical logical;
    private Frames frames;
    private Vsync vsync;
    private ViewportCompositor compositor;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        FrameLayout parent = new FrameLayout(context);
        view = new View(context);
        parent.addView(view, new FrameLayout.LayoutParams(VIEW_W, VIEW_H));
        parent.layout(0, 0, VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        view.setPivotX(0);
        view.setPivotY(0);

        logical = new Logical();
        frames = new Frames();
        vsync = new Vsync();
        compositor = new ViewportCompositor(view, logical, frames, vsync);
        compositor.onStreamStarted(STREAM_W, STREAM_H);
    }

    /** Zoom 4x onto the reference box starting at (480, 270), like PanZoomHandler would. */
    private void zoomInto480x270() {
        logical.scale = 4f;
        logical.x = -480f * 4f;
        logical.y = -270f * 4f;
        compositor.onLogicalTransformChanged();
    }

    /** The echo a host sends for the reference box V = (480, 270, 480, 270). */
    private void hostAppliesQuarter(int frameIndex) {
        compositor.onCropApplied(new ViewportRect(480, 270, 480, 270), 0, 0, frameIndex);
    }

    /**
     * The invariant: the decoded pixel that shows reference point r lands where r lands under
     * the logical transform. Checked across the whole frame against the mapping the compositor
     * says is on screen — which the test separately pins to what the "decoder" shows.
     */
    private void assertSingleMagnification(FrameMapping onScreen) {
        assertSame("the compositor must present the mapping the decoder shows",
                onScreen, compositor.presentedMapping());
        double pxPerRef = (double) VIEW_W / STREAM_W;
        for (int f = 0; f <= STREAM_W; f += 192) {
            double screen = view.getX() + f * pxPerRef * view.getScaleX();
            double expected = logical.x + onScreen.toReferenceX(f) * pxPerRef * logical.scale;
            assertEquals("x of decoded column " + f, expected, screen, 0.05);
        }
        for (int f = 0; f <= STREAM_H; f += 108) {
            double screen = view.getY() + f * pxPerRef * view.getScaleY();
            double expected = logical.y + onScreen.toReferenceY(f) * pxPerRef * logical.scale;
            assertEquals("y of decoded row " + f, expected, screen, 0.05);
        }
    }

    @Test
    public void idleUnzoomedIsTheIdentity() {
        assertSingleMagnification(FrameMapping.IDENTITY);
        assertEquals(1f, view.getScaleX(), 0f);
    }

    @Test
    public void midPinchTheFrameOnScreenIsZoomedSoftlyAndImmediately() {
        zoomInto480x270();
        // Nothing from the host yet: the uncropped frame, magnified locally.
        assertSingleMagnification(FrameMapping.IDENTITY);
        assertEquals(4f, view.getScaleX(), 0f);
    }

    @Test
    public void theSharpCropReplacesTheSoftZoomOnTheNamedFrameAndNotBefore() {
        zoomInto480x270();
        frames.presentedUpTo = 99;
        hostAppliesQuarter(100);

        // Frame 100 not on screen yet: still the soft zoom of the uncropped frame.
        vsync.fire();
        assertSingleMagnification(FrameMapping.IDENTITY);
        assertEquals(4f, view.getScaleX(), 0f);

        frames.presentedUpTo = 100;
        vsync.fire();
        FrameMapping crop = compositor.presentedMapping();
        assertFalse(crop.isIdentity());
        assertSingleMagnification(crop);
        // The crop is already the 4x view; it is shown at 1:1, not magnified again (F1).
        assertEquals(1f, view.getScaleX(), 1e-4f);
        assertEquals(0f, view.getX(), 0.05f);
        assertEquals(0, compositor.pendingCount());
    }

    @Test
    public void anEchoV1HostSwapsOnReceipt() {
        zoomInto480x270();
        hostAppliesQuarter(0);
        assertEquals(1f, view.getScaleX(), 1e-4f);
        assertSingleMagnification(compositor.presentedMapping());
    }

    @Test
    public void anEchoThatTrailsItsFrameSwapsAtOnce() {
        zoomInto480x270();
        frames.presentedUpTo = 120;
        hostAppliesQuarter(100);
        assertEquals(1f, view.getScaleX(), 1e-4f);
        assertEquals(0, vsync.requests);
    }

    @Test
    public void panningAfterTheSwapKeepsASingleMagnification() {
        zoomInto480x270();
        hostAppliesQuarter(0);
        FrameMapping crop = compositor.presentedMapping();
        // The user drags; the host has not caught up, so the same crop is panned softly.
        logical.x += 300f;
        logical.y -= 120f;
        compositor.onLogicalTransformChanged();
        assertSingleMagnification(crop);
        // And zooms out a little mid-gesture.
        logical.scale = 3f;
        compositor.onLogicalTransformChanged();
        assertSingleMagnification(crop);
    }

    @Test
    public void aRevocationReturnsToTheIdentityOnItsFrame() {
        zoomInto480x270();
        hostAppliesQuarter(0);
        frames.presentedUpTo = 200;
        compositor.onCropApplied(new ViewportRect(0, 0, STREAM_W, STREAM_H), 0, 0, 201);
        vsync.fire();
        assertEquals(1f, view.getScaleX(), 1e-4f);
        frames.presentedUpTo = 201;
        vsync.fire();
        assertSingleMagnification(FrameMapping.IDENTITY);
        assertEquals(4f, view.getScaleX(), 0f);
    }

    @Test
    public void framesOfAnOlderCropAreNeverShownUnderANewerCropsMapping() {
        zoomInto480x270();
        frames.presentedUpTo = 9;
        hostAppliesQuarter(10);
        compositor.onCropApplied(new ViewportRect(600, 300, 480, 270), 0, 0, 20);
        assertEquals(2, compositor.pendingCount());

        frames.presentedUpTo = 12;
        vsync.fire();
        FrameMapping first = compositor.presentedMapping();
        assertEquals(480.0, first.offsetX, 1.0);
        assertEquals(1, compositor.pendingCount());

        frames.presentedUpTo = 25;
        vsync.fire();
        assertEquals(600.0, compositor.presentedMapping().offsetX, 1.0);
        assertEquals(0, compositor.pendingCount());
    }

    @Test
    public void aReconnectNeverInheritsACrop() {
        zoomInto480x270();
        hostAppliesQuarter(0);
        FrameMapping crop = compositor.presentedMapping();
        compositor.onStreamStopped();
        // The frozen last frame is still the crop.
        assertSingleMagnification(crop);
        compositor.onStreamStarted(STREAM_W, STREAM_H);
        assertSingleMagnification(FrameMapping.IDENTITY);
        assertEquals(4f, view.getScaleX(), 0f);
    }

    @Test
    public void aRotationOrPipResizeRecomputesFromTheNewViewSize() {
        zoomInto480x270();
        hostAppliesQuarter(0);
        FrameMapping crop = compositor.presentedMapping();
        // PiP: the view shrinks and PanZoomHandler rescales the logical transform.
        view.layout(0, 0, VIEW_W / 4, VIEW_H / 4);
        logical.x /= 4f;
        logical.y /= 4f;
        compositor.onLogicalTransformChanged();
        double pxPerRef = (double) (VIEW_W / 4) / STREAM_W;
        double screen = view.getX() + 960 * pxPerRef * view.getScaleX();
        double expected = logical.x + crop.toReferenceX(960) * pxPerRef * logical.scale;
        assertEquals(expected, screen, 0.05);
    }

    @Test
    public void thePendingQueueIsBoundedAndKeepsTheNewest() {
        zoomInto480x270();
        for (int i = 0; i < ViewportCompositor.MAX_PENDING + 3; i++) {
            compositor.onCropApplied(new ViewportRect(100 + i * 20, 270, 480, 270), 0, 0,
                    100 + i);
        }
        assertEquals(ViewportCompositor.MAX_PENDING, compositor.pendingCount());
        frames.presentedUpTo = 1000;
        vsync.fire();
        assertEquals(100 + (ViewportCompositor.MAX_PENDING + 2) * 20,
                compositor.presentedMapping().offsetX, 1.0);
    }

    // ---- per-frame presentation -------------------------------------------------------

    @Test
    public void withATimelineCropsGoToItAndTheViewKeepsTheLogicalTransform() {
        CropTimeline timeline = new CropTimeline();
        compositor.setTimeline(timeline);
        compositor.onStreamStarted(STREAM_W, STREAM_H);
        zoomInto480x270();
        hostAppliesQuarter(40);
        vsync.fire();
        // The view is exactly what PanZoomHandler wrote; the crop is the presenter's business.
        assertEquals(4f, view.getScaleX(), 0f);
        assertEquals(logical.x, view.getX(), 0f);
        assertEquals(0, compositor.pendingCount());
        assertSame(FrameMapping.IDENTITY, timeline.mappingFor(39));
        assertEquals(480.0, timeline.mappingFor(40).offsetX, 1.0);
        assertEquals(0.25, timeline.mappingFor(40).scaleX, 1e-3);
    }

    @Test
    public void anEchoAnsweringARecentRequestIsMappedExactly() {
        SunmeowCropModel host = new SunmeowCropModel(5360, 1440, STREAM_W, STREAM_H);
        CropRequestHistory history = new CropRequestHistory();
        compositor.setRequestHistory(history);
        ViewportRect request = new ViewportRect(611, 377, 673, 379);
        history.record(request);
        int[] source = host.source(request);
        compositor.onCropApplied(host.echo(source), 5360, 1440, 0);
        FrameMapping truth = host.trueMapping(source);
        assertEquals(truth.offsetX, compositor.presentedMapping().offsetX, 1e-9);
        assertEquals(truth.scaleY, compositor.presentedMapping().scaleY, 1e-12);
    }

    @Test
    public void aNewStreamEmptiesTheTimeline() {
        CropTimeline timeline = new CropTimeline();
        compositor.setTimeline(timeline);
        hostAppliesQuarter(40);
        compositor.onStreamStarted(STREAM_W, STREAM_H);
        assertSame(FrameMapping.IDENTITY, timeline.mappingFor(41));
    }
}

