package com.limelight.meow.viewport;

/**
 * Notified whenever the local zoom/pan transform of the stream view changes.
 *
 * <p>Declared here rather than in {@code PanZoomHandler} so the upstream class only gains a
 * field, a setter and two notify lines — see {@code docs/meow/TOUCHPOINTS.md}. Every
 * implementation of this must be cheap: it is called once per input frame during a pinch.
 */
public interface ZoomTransformObserver {
    void onZoomTransformChanged();
}
