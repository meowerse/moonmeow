package com.limelight.meow.viewport;

import com.limelight.meow.gesture.InlinePinchZoomController;

/**
 * Maps an absolute pointer position on the stream container into the uncropped reference
 * frame, through the user's view V. Used at the two absolute-position send sites (touch taps in
 * {@code AbsoluteTouchContext}, and the absolute mouse / local cursor path in
 * {@code Game.updateMousePosition}) as a one-line hook each.
 *
 * <h2>Why</h2>
 * Those sites send {@code (x, y)} in stream-container pixels with the container size as the
 * reference, which is right only while the stream is unzoomed. Under a local zoom the
 * container shows a sub-rectangle V of the frame, so a tap at the container's centre means the
 * centre of V, not of the frame. The host maps positions against the uncropped frame and does
 * no remapping of its own (it cannot: it does not know V), so the client must.
 *
 * <p>The mapping uses only the <em>logical</em> transform (the user's zoom and pan over the
 * reference frame), never the presented one, so it is right whether or not the host is
 * cropping and whatever frame is on screen. It is separable per axis, which is what lets each
 * coordinate be mapped in its own call with no allocation and no out-parameter.
 *
 * <p>The result stays in the caller's units — container pixels over the container size — so
 * the send call keeps its reference size unchanged. Unzoomed, both methods return their input.
 *
 * <p>Static because {@code AbsoluteTouchContext} is upstream code that sees only its target
 * view. Installed for the life of a stream's {@code Game}, removed compare-and-clear so an
 * overlapping {@code Game} (a restart through PiP) cannot uninstall the live one. UI thread.
 */
public final class ReferencePointer {

    private static volatile InlinePinchZoomController.ZoomTarget transform;

    private ReferencePointer() {
    }

    public static void install(InlinePinchZoomController.ZoomTarget logicalTransform) {
        transform = logicalTransform;
    }

    public static void uninstall(InlinePinchZoomController.ZoomTarget logicalTransform) {
        if (transform == logicalTransform) {
            transform = null;
        }
    }

    /**
     * @param containerX pointer x in stream-container pixels, already clamped to the container
     * @param containerWidth the reference width the caller sends with the position
     */
    public static short x(float containerX, int containerWidth) {
        InlinePinchZoomController.ZoomTarget t = transform;
        if (t == null) {
            return (short) containerX;
        }
        return (short) map(containerX, t.getChildX(), t.getScaleFactor(), containerWidth);
    }

    /** As {@link #x}, for the vertical axis. */
    public static short y(float containerY, int containerHeight) {
        InlinePinchZoomController.ZoomTarget t = transform;
        if (t == null) {
            return (short) containerY;
        }
        return (short) map(containerY, t.getChildY(), t.getScaleFactor(), containerHeight);
    }

    /**
     * The pure mapping: undo the logical pan and zoom, and clamp to the reference range.
     * {@code (position - origin) / scale} is where the point falls on the unscaled frame, whose
     * box is exactly the container.
     */
    static float map(float position, float origin, float scale, int size) {
        if (!(scale > 0f) || Float.isNaN(position) || Float.isNaN(origin)) {
            return position;
        }
        float mapped = (position - origin) / scale;
        return Math.max(0f, Math.min(mapped, (float) Math.max(0, size)));
    }
}
