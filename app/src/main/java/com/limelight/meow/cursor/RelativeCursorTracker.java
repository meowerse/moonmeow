package com.limelight.meow.cursor;

/**
 * Dead-reckons where the host cursor is while the pointer is captured.
 *
 * <h2>Why guessing is the only option here</h2>
 * In the captured-pointer ("relative") mouse modes the client sends <em>deltas</em> and never
 * learns where the host actually put the cursor: the host draws it into the video and the
 * protocol carries no position back. Cursor-follow needs a position, so it accumulates the
 * deltas it sent. That is an estimate, and this class exists so the estimate is at least a
 * tested one rather than arithmetic buried in a touch handler.
 *
 * <h2>What it is wrong about, deliberately</h2>
 * <ul>
 *   <li><b>Host pointer acceleration.</b> The host may move its cursor further than the delta
 *       we sent. Nothing on the client can observe that, so the estimate drifts.
 *   <li><b>Other input.</b> Somebody touching the physical mouse on the host desktop moves the
 *       real cursor and this estimate does not follow.
 * </ul>
 * Both errors are bounded in practice by {@link #accumulate}'s clamp: the cursor cannot leave
 * the frame, so a run to any edge resynchronises that axis exactly. The cost of being wrong is
 * a crop that pans to slightly the wrong place, which the next few events correct — not a lost
 * click, because this class never influences what is sent to the host.
 *
 * <h2>Coordinate space</h2>
 * Stream-frame pixels, the same space as {@link CursorFollowPlanner} and
 * {@code LiSendMousePositionEvent}. Deltas arrive in <em>view</em> pixels, so callers on the
 * absolute-mouse path must convert with {@link #scaleDelta} first — see its note.
 *
 * <p>Plain Java, no Android types, single-threaded (the UI thread's input dispatch).
 */
public final class RelativeCursorTracker {

    private static final int UNSEEDED = -1;

    private int hostX = UNSEEDED;
    private int hostY = UNSEEDED;

    /** True once a starting position is known and {@link #hostX()} means something. */
    public boolean isSeeded() {
        return hostX != UNSEEDED;
    }

    /**
     * Establishes the starting position. Ignored once seeded, so the caller can offer its
     * best guess on every event without having to remember whether it already did.
     */
    public void seed(int hostX, int hostY, int hostWidth, int hostHeight) {
        if (isSeeded()) {
            return;
        }
        this.hostX = clamp(hostX, hostWidth);
        this.hostY = clamp(hostY, hostHeight);
    }

    /**
     * Applies one movement, in stream-frame pixels, and clamps to the frame.
     *
     * <p>Does nothing until {@link #seed} has been called: an unseeded tracker has no origin
     * to add to, and picking one here would hide the caller's failure to supply one.
     *
     * @return true if the tracker holds a usable position afterwards
     */
    public boolean accumulate(int deltaX, int deltaY, int hostWidth, int hostHeight) {
        if (!isSeeded()) {
            return false;
        }
        hostX = clamp(hostX + deltaX, hostWidth);
        hostY = clamp(hostY + deltaY, hostHeight);
        return true;
    }

    /** Forgets the position, so the next {@link #seed} takes effect. */
    public void reset() {
        hostX = UNSEEDED;
        hostY = UNSEEDED;
    }

    public int hostX() {
        return hostX;
    }

    public int hostY() {
        return hostY;
    }

    /**
     * Converts a delta expressed against one reference size into stream-frame pixels.
     *
     * <p>Needed on the absolute-mouse path, where {@code LiSendMouseMoveAsMousePositionEvent}
     * takes the delta in the caller's reference frame — {@code streamContainer}'s pixel size —
     * and normalises it against that same reference before sending. So a delta of {@code n}
     * container pixels moves the host cursor by {@code n / referenceSize} of the frame, which
     * is {@code n * hostSize / referenceSize} stream pixels. Adding the raw container delta to
     * a stream-pixel accumulator, as this code used to, mis-scales the estimate by exactly the
     * ratio between the phone's stream view and the negotiated stream width.
     *
     * <p>Rounds away from zero so a small delta is never swallowed: a pointer that moves is
     * always seen to move, otherwise a slow drag across the screen would accumulate nothing.
     */
    public static int scaleDelta(int delta, int referenceSize, int hostSize) {
        if (delta == 0 || referenceSize <= 0 || hostSize <= 0) {
            return 0;
        }
        float scaled = delta * (hostSize / (float) referenceSize);
        int rounded = Math.round(scaled);
        if (rounded == 0) {
            return delta > 0 ? 1 : -1;
        }
        return rounded;
    }

    private static int clamp(int value, int size) {
        int max = Math.max(0, size);
        return Math.max(0, Math.min(value, max));
    }
}
