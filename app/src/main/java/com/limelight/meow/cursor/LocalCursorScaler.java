package com.limelight.meow.cursor;

import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.meow.gesture.InlinePinchZoomController;
import com.limelight.meow.viewport.ZoomTransformObserver;

/**
 * Bridges zoom changes to the local cursor's enlarged/normal switch.
 *
 * <p>Host-drawn cursor is untouched. Only the Android system pointer overlay
 * ({@code AndroidPointerIconCaptureProvider} / {@code AndroidNativePointerCaptureProvider})
 * is affected, when {@code checkbox_enlarge_cursor_at_low_zoom} is on.
 *
 * <p>Implements {@link ZoomTransformObserver} so it can be registered alongside
 * {@code StreamViewportBinder} via {@code PanZoomHandler.addZoomTransformObserver}.
 *
 * <p>Reads the user's <em>logical</em> zoom, not the stream view's scale: once the host crops,
 * the view carries the presented transform, whose scale is the logical zoom times the host's
 * crop factor and says nothing about how far the user has zoomed in.
 */
public final class LocalCursorScaler implements ZoomTransformObserver {

    private final InlinePinchZoomController.ZoomTarget zoom;
    private final InputCaptureProvider captureProvider;

    public LocalCursorScaler(InlinePinchZoomController.ZoomTarget zoom,
                             InputCaptureProvider captureProvider) {
        if (zoom == null || captureProvider == null) {
            throw new IllegalArgumentException("zoom and captureProvider required");
        }
        this.zoom = zoom;
        this.captureProvider = captureProvider;
    }

    @Override
    public void onZoomTransformChanged() {
        float scale = zoom.getScaleFactor();
        // Fallback to 1.0 if view not laid out yet or scale is nonsense; policy handles it.
        if (!(scale > 0f) || !Float.isFinite(scale)) {
            scale = 1.0f;
        }
        captureProvider.onZoomScaleChanged(scale);
    }

    /** Call once after enabling the enlarge preference so initial state is correct. */
    public void refresh() {
        onZoomTransformChanged();
    }
}
