package com.limelight.meow.viewport;

import android.media.MediaCodec;
import android.view.Surface;

import com.limelight.LimeLog;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Moves the decoder's output to another surface mid-stream, from the renderer's own thread.
 * {@link SurfaceFramePresenter} uses it to give up: if it cannot put frames on screen, the
 * decoder goes back to the stream view's own surface and crops swap on the view's transform,
 * rather than the stream staying black.
 *
 * <p>Static because the renderer is upstream code and its hook must stay one line: the render
 * loop calls {@link #apply} once per iteration, which is a single atomic read when nothing is
 * pending. {@code MediaCodec.setOutputSurface} is valid at any time while the codec is
 * configured with a surface.
 */
public final class DecoderSurfaceSwitch {

    private static final AtomicReference<Surface> PENDING = new AtomicReference<>();

    private DecoderSurfaceSwitch() {
    }

    /** Any thread: switch the decoder to {@code surface} at its next loop. */
    public static void request(Surface surface) {
        PENDING.set(surface);
    }

    /** The surface waiting to be switched to, or null. For tests. */
    static Surface pending() {
        return PENDING.get();
    }

    /** A new stream: nothing pending from the old one. */
    public static void reset() {
        PENDING.set(null);
    }

    /** Renderer thread, once per loop iteration. */
    public static void apply(MediaCodec codec) {
        Surface target = PENDING.getAndSet(null);
        if (target == null || codec == null) {
            return;
        }
        try {
            codec.setOutputSurface(target);
            LimeLog.info("Viewport: decoder output moved back to the view's surface");
        } catch (RuntimeException e) {
            LimeLog.warning("Viewport: could not move the decoder output: " + e);
        }
    }
}
