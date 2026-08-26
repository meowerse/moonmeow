package com.limelight.meow.cursor;

import android.view.View;

import com.limelight.binding.input.capture.InputCaptureProvider;
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
 */
public final class LocalCursorScaler implements ZoomTransformObserver {

    private final View streamView;
    private final InputCaptureProvider captureProvider;

    public LocalCursorScaler(View streamView, InputCaptureProvider captureProvider) {
        if (streamView == null || captureProvider == null) {
            throw new IllegalArgumentException("streamView and captureProvider required");
        }
        this.streamView = streamView;
        this.captureProvider = captureProvider;
    }

    @Override
    public void onZoomTransformChanged() {
        float scale = streamView.getScaleX();
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
