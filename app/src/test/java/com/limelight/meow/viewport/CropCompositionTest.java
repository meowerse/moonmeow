package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

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

/**
 * F1, end to end through the production wiring: a real {@link PanZoomHandler} driving a real
 * {@link StreamViewportBinder}, with a host that honours the crop.
 *
 * <p>Before the compositor, the client kept its own 4x transform on top of a frame the host had
 * already cropped to the 4x view, so the user saw 16x — and a tap addressed the container
 * rather than the point under the finger. {@link #aHonouredCropIsShownAtOneMagnification} and
 * {@link #aTapLandsOnTheDesktopPointUnderTheFinger} fail against that code.
 */
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class CropCompositionTest {

    private static final int STREAM_W = 1920;
    private static final int STREAM_H = 1080;
    private static final int VIEW_W = 1920;
    private static final int VIEW_H = 1080;

    private FrameLayout parent;
    private View streamView;
    private StreamViewportBinder binder;
    private PanZoomHandler panZoom;
    private ViewportReporter reporter;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        parent = new FrameLayout(context);
        streamView = new View(context);
        parent.addView(streamView, new FrameLayout.LayoutParams(VIEW_W, VIEW_H));
        parent.layout(0, 0, VIEW_W, VIEW_H);
        streamView.layout(0, 0, VIEW_W, VIEW_H);

        reporter = new ViewportReporter((x, y, w, h, force) -> ViewportReporter.LI_OK,
                new ViewportReporter.Scheduler() {
                    @Override public void schedule(long delayMs, Runnable task) { }
                    @Override public void cancel() { }
                });
        binder = new StreamViewportBinder(streamView, parent, reporter,
                new Handler(Looper.getMainLooper()));
        panZoom = new PanZoomHandler(context, null, streamView, parent,
                PreferenceConfiguration.readPreferences(context));

        // Exactly what Game.onCreate wires.
        binder.setEnabled(true);
        binder.setTransformSource(panZoom);
        ReferencePointer.install(panZoom);
        panZoom.setZoomTransformObserver(binder);

        binder.onStreamStarted(STREAM_W, STREAM_H);
        drain();
        binder.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0, 0);
        drain();
    }

    @After
    public void tearDown() {
        ReferencePointer.uninstall(panZoom);
        binder.release();
    }

    private static void drain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Zoom 4x about the centre and let the host apply exactly what the client reported. */
    private ViewportRect zoomAndHonour() {
        panZoom.pinchBy(4f, VIEW_W / 2f, VIEW_H / 2f);
        drain();
        ViewportRect visible = binder.computeVisibleHostRect();
        binder.onViewportApplied(visible.x, visible.y, visible.width, visible.height, 0, 0, 0);
        drain();
        return visible;
    }

    @Test
    public void aHonouredCropIsShownAtOneMagnification() {
        ViewportRect visible = zoomAndHonour();
        assertEquals(new ViewportRect(720, 405, 480, 270), visible);

        // The decoded frame IS the 4x view now; it must be presented 1:1 and in place.
        assertEquals(1f, streamView.getScaleX(), 1e-3f);
        assertEquals(1f, streamView.getScaleY(), 1e-3f);
        assertEquals(0f, streamView.getX(), 0.5f);
        // The host even-aligns the crop origin for chroma (row 405 -> 404), so the decoded
        // picture starts one reference row -- four screen rows at 4x -- above the view's top.
        // Presenting it at 0 would shift the whole picture by that row.
        assertEquals(-4f, streamView.getY(), 0.5f);

        // The user's logical zoom is untouched by the composition.
        assertEquals(4f, panZoom.getScaleFactor(), 0f);
    }

    @Test
    public void panningAfterTheCropMovesTheLogicalViewNotTheComposedOne() {
        zoomAndHonour();
        float before = panZoom.getChildX();
        panZoom.panBy(-100f, 0f);
        drain();
        assertEquals(before - 100f, panZoom.getChildX(), 0.01f);
        // Reported rectangle follows the logical pan: 100 view px at 4x is 25 stream px.
        assertEquals(745, binder.computeVisibleHostRect().x);
    }

    @Test
    public void aTapLandsOnTheDesktopPointUnderTheFinger() {
        zoomAndHonour();
        // The visible box is reference (720, 405, 480, 270); the view is the whole container,
        // whose reference size equals the stream size here. A tap at the container's
        // top-left quarter point is reference (720 + 480/4, 405 + 270/4).
        assertEquals(840, ReferencePointer.x(VIEW_W / 4f, VIEW_W));
        assertEquals(472, ReferencePointer.y(VIEW_H / 4f, VIEW_H));
    }

    @Test
    public void aRevocationGoesBackToTheSoftZoomWithoutMovingTheView() {
        zoomAndHonour();
        binder.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0, 0);
        drain();
        assertEquals(4f, streamView.getScaleX(), 0f);
        assertEquals(panZoom.getChildX(), streamView.getX(), 0f);
    }

    @Test
    public void stoppingTheStreamReturnsToTheLogicalTransform() {
        zoomAndHonour();
        binder.onStreamStopped();
        drain();
        assertEquals(4f, streamView.getScaleX(), 0f);
    }
}
