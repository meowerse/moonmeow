package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;

import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.meow.gesture.InlinePinchZoomController;

import org.junit.Test;

/**
 * The scaler must forward the user's logical zoom. Once the host crops, the stream view's own
 * scale is the logical zoom times the crop factor — around 1 at any zoom — and reading it
 * would make the cursor look unzoomed exactly when the user is zoomed in.
 */
public class LocalCursorScalerTest {

    private static final class Zoom implements InlinePinchZoomController.ZoomTarget {
        float scale = 1f;

        @Override public void pinchBy(float s, float fx, float fy) { }
        @Override public void panBy(float dx, float dy) { }
        @Override public float getScaleFactor() { return scale; }
        @Override public float getChildX() { return 0f; }
        @Override public float getChildY() { return 0f; }
    }

    private static final class Capture extends InputCaptureProvider {
        float lastScale = -1f;

        @Override
        public void onZoomScaleChanged(float scale) {
            lastScale = scale;
        }
    }

    @Test
    public void theLogicalZoomIsForwarded() {
        Zoom zoom = new Zoom();
        Capture capture = new Capture();
        LocalCursorScaler scaler = new LocalCursorScaler(zoom, capture);

        scaler.refresh();
        assertEquals(1f, capture.lastScale, 0f);

        zoom.scale = 4f;
        scaler.onZoomTransformChanged();
        assertEquals(4f, capture.lastScale, 0f);
    }

    @Test
    public void nonsenseFallsBackToOne() {
        Zoom zoom = new Zoom();
        Capture capture = new Capture();
        zoom.scale = Float.NaN;
        new LocalCursorScaler(zoom, capture).onZoomTransformChanged();
        assertEquals(1f, capture.lastScale, 0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void bothCollaboratorsAreRequired() {
        new LocalCursorScaler(null, new Capture());
    }
}
