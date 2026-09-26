package com.limelight.meow.viewport;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * What the JVM can check of the presenter: it builds, hands the decoder a surface, and the
 * binder switches the compositor to per-frame presentation with it. The per-frame decision
 * is {@link FrameSelectorTest} and {@link FrameLayerGeometryTest}; the transaction itself
 * needs SurfaceFlinger and is verified on the device (docs/meow/TOUCHPOINTS.md).
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class SurfaceFramePresenterTest {

    @Test
    public void theBinderHandsTheDecoderThePresentersSurfaceAndComposesPerFrame() {
        Context context = ApplicationProvider.getApplicationContext();
        FrameLayout parent = new FrameLayout(context);
        SurfaceView view = new SurfaceView(context);
        parent.addView(view, new FrameLayout.LayoutParams(1220, 2712));
        StreamViewportBinder binder = new StreamViewportBinder(view, parent,
                new ViewportReporter((x, y, w, h, force) -> ViewportReporter.LI_OK,
                        new ViewportReporter.Scheduler() {
                            @Override public void schedule(long delayMs, Runnable task) { }
                            @Override public void cancel() { }
                        }),
                new android.os.Handler(Looper.getMainLooper()));
        binder.setEnabled(true);
        binder.setTransformSource(new com.limelight.meow.gesture.InlinePinchZoomController
                .ZoomTarget() {
            @Override public void pinchBy(float s, float fx, float fy) { }
            @Override public void panBy(float dx, float dy) { }
            @Override public float getScaleFactor() { return 1f; }
            @Override public float getChildX() { return 0f; }
            @Override public float getChildY() { return 0f; }
        });
        android.view.Surface fallback = new android.view.Surface(
                new android.graphics.SurfaceTexture(0));
        android.view.Surface target = binder.decoderSurface(fallback, 1220, 2712, false);
        assertNotNull(target);
        assertTrue("the decoder renders into the presenter's reader", target != fallback);
        assertTrue(binder.compositor().presentsPerFrame());
        assertTrue("asked again: the same surface, not a second reader",
                target == binder.decoderSurface(fallback, 1220, 2712, false));
        binder.setFrameRate(60f);
        binder.onStreamStarted(1220, 2712);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // An echo goes straight into the presenter's timeline, on the reporter thread.
        binder.onViewportApplied(0, 0, 1220, 2712, 0, 0, 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        binder.onViewportApplied(300, 1200, 259, 327, 5360, 1440, 90);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(binder.frameTimeline().mappingFor(90).scaleX < 0.5);
        assertTrue(binder.frameTimeline().mappingFor(89).isIdentity());

        // Giving up: the decoder goes back to the view's surface and crops swap on the view.
        binder.onPresenterFailed();
        assertTrue(DecoderSurfaceSwitch.pending() == fallback);
        assertTrue(binder.frameTimeline() == null);
        assertTrue(!binder.compositor().presentsPerFrame());
        DecoderSurfaceSwitch.reset();
        binder.release();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void withCropReportingOffTheDecoderKeepsTheViewsOwnSurface() {
        Context context = ApplicationProvider.getApplicationContext();
        FrameLayout parent = new FrameLayout(context);
        SurfaceView view = new SurfaceView(context);
        parent.addView(view, new FrameLayout.LayoutParams(1220, 2712));
        StreamViewportBinder binder = new StreamViewportBinder(view, parent,
                new ViewportReporter((x, y, w, h, force) -> ViewportReporter.LI_OK,
                        new ViewportReporter.Scheduler() {
                            @Override public void schedule(long delayMs, Runnable task) { }
                            @Override public void cancel() { }
                        }),
                new android.os.Handler(Looper.getMainLooper()));
        binder.setEnabled(false);
        binder.setTransformSource(new com.limelight.meow.gesture.InlinePinchZoomController
                .ZoomTarget() {
            @Override public void pinchBy(float s, float fx, float fy) { }
            @Override public void panBy(float dx, float dy) { }
            @Override public float getScaleFactor() { return 1f; }
            @Override public float getChildX() { return 0f; }
            @Override public float getChildY() { return 0f; }
        });
        android.view.Surface fallback = new android.view.Surface(
                new android.graphics.SurfaceTexture(0));
        assertTrue("the host never crops: nothing to pair",
                fallback == binder.decoderSurface(fallback, 1220, 2712, false));
        binder.release();
    }
}
