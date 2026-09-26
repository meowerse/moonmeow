package com.limelight.meow.viewport;

/**
 * The per-frame decision of {@link SurfaceFramePresenter}, without Android types: which crop
 * mapping a received buffer is shown with, and whether it is shown at all. One thread (the
 * presenter's), allocation-free.
 *
 * <p>A buffer is named by the timestamp it was released with ({@link FrameStamps}), which
 * gives its presentation time, which gives its host frame number
 * ({@link DecodedFrameGate#frameForPts}), which gives the crop it was encoded with
 * ({@link CropTimeline}). A buffer that cannot be named keeps the mapping of the last one that
 * could: it is the neighbour of a frame whose crop is known, and a crop lasts many frames.
 *
 * <p>A buffer older than the one already on screen is dropped. The renderer releases frames
 * from two threads (its own, and its Choreographer thread for frames it queued), so an older
 * frame can arrive after a newer one; showing it would step the picture back for a frame.
 */
public final class FrameSelector {

    private final CropTimeline timeline;
    private final long[] stamp = new long[2];

    private long lastPts = FrameStamps.NONE;
    private FrameMapping lastMapping = FrameMapping.IDENTITY;
    private long releasedAt = FrameStamps.NONE;
    private int unnamed;
    private int olderDropped;

    public FrameSelector(CropTimeline timeline) {
        this.timeline = timeline;
    }

    /**
     * @param timestampNs the buffer's timestamp
     * @return the mapping to show it with, or null to drop it
     */
    public FrameMapping select(long timestampNs) {
        releasedAt = FrameStamps.NONE;
        if (!FrameStamps.lookup(timestampNs, stamp)) {
            unnamed++;
            return lastMapping;
        }
        long pts = stamp[0];
        if (lastPts != FrameStamps.NONE && pts < lastPts) {
            olderDropped++;
            return null;
        }
        lastPts = pts;
        releasedAt = stamp[1];
        int frame = DecodedFrameGate.frameForPts(pts);
        if (frame == 0) {
            unnamed++;
            return lastMapping;
        }
        lastMapping = timeline.mappingFor(frame);
        timeline.onPresented(frame);
        return lastMapping;
    }

    /** {@link System#nanoTime()} at the release of the last selected buffer, or NONE. */
    public long releasedAt() {
        return releasedAt;
    }

    /** Buffers that could not be named since the last {@link #resetCounts}. */
    public int unnamed() {
        return unnamed;
    }

    /** Buffers dropped for being older than the one on screen, since the last reset. */
    public int olderDropped() {
        return olderDropped;
    }

    public void resetCounts() {
        unnamed = 0;
        olderDropped = 0;
    }

    /** A new stream: its presentation times start over. */
    public void reset() {
        lastPts = FrameStamps.NONE;
        lastMapping = FrameMapping.IDENTITY;
        releasedAt = FrameStamps.NONE;
        resetCounts();
    }
}
