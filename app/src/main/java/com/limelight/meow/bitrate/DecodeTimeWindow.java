package com.limelight.meow.bitrate;

/**
 * The renderer's average decode time over its last one-second statistics window, published by
 * a one-line hook in {@code MediaCodecDecoderRenderer} where it flips that window. Read by the
 * receiver reporter on its own thread.
 *
 * <p>The renderer already measures this (enqueue to dequeue per frame) for its performance
 * overlay; the hook only publishes what it computed, so the report and the overlay agree.
 */
public final class DecodeTimeWindow {

    private static volatile int averageMs;

    private DecodeTimeWindow() {
    }

    /** Renderer thread, once per statistics window. */
    public static void publish(long decoderTimeMs, int framesReceived) {
        averageMs = framesReceived > 0
                ? (int) Math.min(Integer.MAX_VALUE, decoderTimeMs / framesReceived) : 0;
    }

    public static int averageMs() {
        return averageMs;
    }

    /** At stream start: nothing measured yet. */
    public static void reset() {
        averageMs = 0;
    }
}
