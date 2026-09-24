package com.limelight.meow.viewport;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Tells the UI thread when the decoder has put a given host frame on screen, so the crop
 * compensation can swap on exactly that frame. Plain Java, lock-free, allocation-free.
 *
 * <h2>Why it is needed</h2>
 * The host's viewport echo names the first frame encoded with the new crop
 * ({@code frame_index}, the same number the decoder sees as {@code frameNumber}). Swapping the
 * presented transform when the echo <em>arrives</em> is wrong in both directions: the echo can
 * overtake that frame (then the old picture is shown under the new transform) or trail it
 * (then the new picture is magnified twice until the echo lands). Either is a visible jump.
 *
 * <h2>How the frame is followed through the decoder</h2>
 * {@code MediaCodec} identifies output buffers by presentation timestamp, not by host frame
 * number. So the renderer reports both ends with one-line hooks:
 * <ul>
 *   <li>{@link #onFrameQueued} on the submit thread, pairing each frame number with the
 *       timestamp it was queued under, into a small ring;</li>
 *   <li>{@link #onFramePresented} on the output thread, with the timestamp of each frame it
 *       hands to the display.</li>
 * </ul>
 * The UI thread asks {@link #hasPresented} once per vsync while a swap is pending: find the
 * first queued frame at or after the target, and compare its timestamp with the latest one
 * presented. Timestamps are strictly increasing (the renderer bumps duplicates), so "at or
 * after" is a plain comparison.
 *
 * <h2>Why a ring and not a single slot</h2>
 * The echo and the frame race each other on independent paths (control stream vs. video
 * stream plus decode), so by the time a target is known its frame may already have been
 * queued. The ring remembers the last {@value #RING_SIZE} frames — over a second at 60 FPS —
 * which is ample for that race. A target older than everything in the ring is long on screen
 * and is reported as presented.
 *
 * <h2>Concurrency</h2>
 * One writer per method (the renderer's submit and output threads), any number of readers.
 * Each slot is a pair of {@link AtomicLongArray} entries written pts-then-frame after
 * invalidating the frame; a reader accepts a slot only if it reads the same valid frame word
 * before and after the timestamp. No locks, no allocation on either decoder thread.
 *
 * <p>Static because the renderer is upstream code and the hooks must stay one line each.
 * Exactly one stream renders at a time; {@link #reset} runs at every stream start.
 */
public final class DecodedFrameGate {

    static final int RING_SIZE = 128;
    private static final int MASK = RING_SIZE - 1;

    /** Slot word: 0 = empty; otherwise bit 32 set and the frame number in the low 32 bits. */
    private static final AtomicLongArray FRAMES = new AtomicLongArray(RING_SIZE);
    private static final AtomicLongArray PTS = new AtomicLongArray(RING_SIZE);
    private static final long VALID = 1L << 32;

    /** Submit thread only. */
    private static int writeIndex;

    private static volatile long lastPresentedPtsUs = Long.MIN_VALUE;

    private DecodedFrameGate() {
    }

    /**
     * Renderer submit thread, once per picture-data decode unit, with the timestamp it is
     * queued to the codec under.
     */
    public static void onFrameQueued(int frameNumber, long presentationTimeUs) {
        int slot = writeIndex;
        writeIndex = (slot + 1) & MASK;
        FRAMES.set(slot, 0L);
        PTS.set(slot, presentationTimeUs);
        FRAMES.set(slot, VALID | (frameNumber & 0xFFFFFFFFL));
    }

    /** Renderer output thread, for every frame handed to the display. */
    public static void onFramePresented(long presentationTimeUs) {
        if (presentationTimeUs > lastPresentedPtsUs) {
            lastPresentedPtsUs = presentationTimeUs;
        }
    }

    /** Forget everything. Called at stream start, before any frame of the new stream. */
    public static void reset() {
        for (int i = 0; i < RING_SIZE; i++) {
            FRAMES.set(i, 0L);
        }
        lastPresentedPtsUs = Long.MIN_VALUE;
    }

    /**
     * Whether a frame numbered {@code frameIndex} or later has been handed to the display.
     *
     * @param frameIndex host frame number; 0 means "no frame to wait for" and is always true
     */
    public static boolean hasPresented(int frameIndex) {
        if (frameIndex == 0) {
            return true;
        }
        long presented = lastPresentedPtsUs;
        if (presented == Long.MIN_VALUE) {
            return false;
        }

        // The first frame queued at or after the target is the first frame carrying the new
        // crop: the target itself, or -- when the target was lost on the network, dropped
        // before the decoder or has already left the ring -- the next one after it, which
        // the host encoded with the same crop.
        long targetPts = Long.MAX_VALUE;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < RING_SIZE; i++) {
            long frame = FRAMES.get(i);
            if (frame == 0L) {
                continue;
            }
            long pts = PTS.get(i);
            if (FRAMES.get(i) != frame) {
                // Rewritten while we read it. The new occupant is newer than anything we
                // could be waiting for, and the next vsync will read it cleanly.
                continue;
            }
            // Wrap-safe: a frame at or after the target has a non-negative distance.
            int distance = (int) frame - frameIndex;
            if (distance >= 0 && distance < bestDistance) {
                bestDistance = distance;
                targetPts = pts;
            }
        }
        // Nothing at or after the target has been queued yet: keep waiting.
        return targetPts != Long.MAX_VALUE && presented >= targetPts;
    }
}
