package com.limelight.meow.viewport;

/**
 * Which {@link FrameMapping} each host frame was encoded with. Written on the binder's reporter
 * thread straight from the host's echoes (not through the UI thread, which is busiest during a
 * pan), read on the presenter thread once per frame. Plain Java.
 *
 * <p>The host echoes each crop with the first frame that carries it. Every frame from that one
 * on, until the next crop's first frame, shows that crop; frames before it show whatever was in
 * force before. The presenter asks {@link #mappingFor} with the number of the frame it is about
 * to put on screen, so the transform always matches the pixels it is paired with -- no matter
 * whether the echo arrived long before the frame or a moment before.
 *
 * <h2>Concurrency</h2>
 * One writer, one reader. Each change publishes a new immutable {@link Entries} through a
 * volatile field (echoes are a handful a second, so the allocation is not on a hot path); the
 * reader loads the field once per frame and never allocates.
 *
 * <p>An echo that arrives after its first frame was already shown (the control channel stalled
 * behind a retransmission) cannot fix that frame; it applies from the next frame on, and
 * {@link #lateEchoes()} counts it.
 */
public final class CropTimeline {

    /** Crops kept; the oldest is folded into the base once a newer one is on screen. */
    static final int MAX_ENTRIES = 8;

    /** An immutable snapshot. */
    static final class Entries {
        final FrameMapping base;
        final int[] firstFrames;
        final FrameMapping[] mappings;

        Entries(FrameMapping base, int[] firstFrames, FrameMapping[] mappings) {
            this.base = base;
            this.firstFrames = firstFrames;
            this.mappings = mappings;
        }
    }

    private static final Entries EMPTY =
            new Entries(FrameMapping.IDENTITY, new int[0], new FrameMapping[0]);

    private volatile Entries entries = EMPTY;
    /** The newest frame the presenter has shown, or 0. Presenter thread writes. */
    private volatile int lastPresented;
    private volatile int lateEchoes;

    /** A new stream: nothing is cropped yet. The writer's thread. */
    public void reset() {
        entries = EMPTY;
        lastPresented = 0;
        lateEchoes = 0;
    }

    /**
     * The host applied {@code mapping} from {@code firstFrame} on. The writer's thread.
     *
     * @param firstFrame the echo's frame index; 0 (a host without echo v2) means "from the
     *                   next frame shown"
     */
    public void add(int firstFrame, FrameMapping mapping) {
        if (mapping == null) {
            return;
        }
        int shown = lastPresented;
        if (firstFrame == 0) {
            firstFrame = shown + 1;
        } else if (shown != 0 && firstFrame - shown <= 0) {
            lateEchoes++;
        }
        Entries old = entries;
        // Drop what this crop supersedes: anything starting at or after it (a reordered
        // echo), and fold entries that are wholly in the past into the base.
        int keep = 0;
        for (int i = 0; i < old.firstFrames.length; i++) {
            if (old.firstFrames[i] - firstFrame < 0) {
                keep++;
            }
        }
        FrameMapping base = old.base;
        int start = 0;
        while (keep - start >= MAX_ENTRIES
                || (keep - start > 1 && shown != 0 && old.firstFrames[start + 1] - shown <= 0)) {
            base = old.mappings[start];
            start++;
        }
        int count = keep - start + 1;
        int[] frames = new int[count];
        FrameMapping[] mappings = new FrameMapping[count];
        int n = 0;
        for (int i = start; i < old.firstFrames.length && n < count - 1; i++) {
            if (old.firstFrames[i] - firstFrame < 0) {
                frames[n] = old.firstFrames[i];
                mappings[n] = old.mappings[i];
                n++;
            }
        }
        frames[n] = firstFrame;
        mappings[n] = mapping;
        entries = new Entries(base, frames, mappings);
    }

    /**
     * The mapping {@code frame} was encoded with. Presenter thread, allocation-free.
     *
     * @param frame a host frame number; 0 (unknown) gets the newest mapping already on screen
     */
    public FrameMapping mappingFor(int frame) {
        Entries e = entries;
        if (frame == 0) {
            frame = lastPresented;
            if (frame == 0) {
                return e.base;
            }
        }
        FrameMapping result = e.base;
        for (int i = 0; i < e.firstFrames.length; i++) {
            // Wrap-safe: the frame is at or after the crop's first frame.
            if (frame - e.firstFrames[i] >= 0) {
                result = e.mappings[i];
            }
        }
        return result;
    }

    /** The presenter showed {@code frame}. Presenter thread. */
    public void onPresented(int frame) {
        if (frame != 0 && (lastPresented == 0 || frame - lastPresented > 0)) {
            lastPresented = frame;
        }
    }

    /** The newest frame shown, or 0. */
    public int lastPresented() {
        return lastPresented;
    }

    /** Echoes that arrived after the frame they named was already on screen. */
    public int lateEchoes() {
        return lateEchoes;
    }

    /** Crops currently held (for tests). */
    int size() {
        return entries.firstFrames.length;
    }
}
