package com.limelight.meow.viewport;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Tells {@link SurfaceFramePresenter} which decoded frame a buffer it received is. Plain Java,
 * lock-free, allocation-free.
 *
 * <h2>Why it is needed</h2>
 * The presenter receives decoded frames from an {@code ImageReader}, and an {@code Image}
 * carries one number: the timestamp the renderer released it with. That is
 * {@code releaseOutputBuffer(index, renderTimestampNs)}'s argument, or the frame's
 * presentation time in nanoseconds for {@code releaseOutputBuffer(index, true)} -- not the
 * host frame number the echo names. The renderer knows both at the moment it releases, so it
 * reports them with one-line hooks:
 * <ul>
 *   <li>{@link #onOutput} where a dequeued buffer's index and presentation time are both in
 *       hand (the renderer's balanced mode queues bare indices for its Choreographer thread
 *       and loses the time);</li>
 *   <li>{@link #onRelease} immediately before each rendering release, with the index and the
 *       render timestamp.</li>
 * </ul>
 * {@link #lookup} then finds a buffer's presentation time from its timestamp, and
 * {@link DecodedFrameGate#frameForPts} its host frame number.
 *
 * <h2>Concurrency</h2>
 * Releases happen on two threads (the renderer's and its Choreographer thread), so each claims
 * its own ring slot with an atomic counter and writes it seqlock-style: the sequence word is
 * cleared, the fields written, then the sequence set. A reader accepts a slot only when it
 * reads the same non-zero sequence before and after the fields. The index table is written
 * before the index is handed to the releasing thread (directly, or through the renderer's
 * blocking queue), which orders it.
 *
 * <p>Static because the renderer is upstream code and the hooks must stay one line each.
 * {@link #reset} runs at every stream start.
 */
public final class FrameStamps {

    /** Output buffer indices are small (a codec has a few dozen buffers at most). */
    static final int INDEX_SLOTS = 256;
    static final int RING_SIZE = 64;
    private static final int RING_MASK = RING_SIZE - 1;

    /** Not a presentation time: nothing recorded. */
    public static final long NONE = Long.MIN_VALUE;

    private static final AtomicLongArray PTS_BY_INDEX = new AtomicLongArray(INDEX_SLOTS);

    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final AtomicLongArray SEQUENCE = new AtomicLongArray(RING_SIZE);
    private static final AtomicLongArray RENDER_TS = new AtomicLongArray(RING_SIZE);
    private static final AtomicLongArray PTS = new AtomicLongArray(RING_SIZE);
    private static final AtomicLongArray RELEASED_AT = new AtomicLongArray(RING_SIZE);

    static {
        reset();
    }

    private FrameStamps() {
    }

    /** A dequeued output buffer: its index and presentation time. Renderer thread. */
    public static void onOutput(int bufferIndex, long presentationTimeUs) {
        if (bufferIndex >= 0 && bufferIndex < INDEX_SLOTS) {
            PTS_BY_INDEX.set(bufferIndex, presentationTimeUs);
        }
    }

    /**
     * The buffer at {@code bufferIndex} is about to be rendered with
     * {@code renderTimestampNs}. Any rendering thread.
     */
    public static void onRelease(int bufferIndex, long renderTimestampNs) {
        if (bufferIndex < 0 || bufferIndex >= INDEX_SLOTS) {
            return;
        }
        // Taken, not read: a release path that never recorded this use of the index must not
        // inherit the presentation time of an earlier one.
        long pts = PTS_BY_INDEX.getAndSet(bufferIndex, NONE);
        if (pts == NONE) {
            return;
        }
        int claim = NEXT.getAndIncrement();
        int slot = claim & RING_MASK;
        SEQUENCE.set(slot, 0L);
        RENDER_TS.set(slot, renderTimestampNs);
        PTS.set(slot, pts);
        RELEASED_AT.set(slot, System.nanoTime());
        // Never 0, so a written slot is never mistaken for an empty one.
        SEQUENCE.set(slot, (claim & 0xFFFFFFFFL) | (1L << 32));
    }

    /**
     * Finds the release of the buffer an {@code Image} carrying {@code timestampNs} came from.
     * Matches either timestamp a release can give the buffer: the explicit render time, or the
     * presentation time in nanoseconds.
     *
     * @param out filled with {presentation time in us, System.nanoTime() at the release}
     * @return false when no recorded release matches
     */
    public static boolean lookup(long timestampNs, long[] out) {
        for (int i = 0; i < RING_SIZE; i++) {
            long sequence = SEQUENCE.get(i);
            if (sequence == 0L) {
                continue;
            }
            long renderTs = RENDER_TS.get(i);
            long pts = PTS.get(i);
            long releasedAt = RELEASED_AT.get(i);
            if (SEQUENCE.get(i) != sequence) {
                continue;
            }
            if (renderTs == timestampNs || pts * 1000L == timestampNs) {
                out[0] = pts;
                out[1] = releasedAt;
                return true;
            }
        }
        return false;
    }

    /** Forget everything. Called at stream start. */
    public static void reset() {
        for (int i = 0; i < INDEX_SLOTS; i++) {
            PTS_BY_INDEX.set(i, NONE);
        }
        for (int i = 0; i < RING_SIZE; i++) {
            SEQUENCE.set(i, 0L);
        }
    }
}
