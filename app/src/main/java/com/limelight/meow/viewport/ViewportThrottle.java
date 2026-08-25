package com.limelight.meow.viewport;

/**
 * Decides which viewport updates actually reach the wire.
 *
 * <p>A pinch produces one transform per input frame — 120 to 240 per second on a modern
 * phone — and every one of them changes the visible rectangle by a pixel or two. Sending
 * each as a control-stream packet would put a sustained several-hundred-packets-per-second
 * load on the same connection that carries input, on a link the user has already told us is
 * 5-8 Mbps. This class is the filter.
 *
 * <p>Three rules, in order:
 * <ol>
 *   <li><b>Identical rectangles are dropped.</b> Panning against a bound produces a long run
 *       of these.</li>
 *   <li><b>Insignificant rectangles are deferred, not dropped.</b> A change smaller than
 *       {@code minEdgeDelta} on every edge is held as pending so {@link #flush} still
 *       delivers the exact final rectangle. Dropping it outright would leave the host
 *       cropped a few pixels away from where the user actually stopped.</li>
 *   <li><b>Sends are rate limited</b> to one per {@link #MIN_INTERVAL_MS}. Anything arriving
 *       inside that window becomes pending.</li>
 * </ol>
 *
 * <p>{@link #flush} is the trailing edge and deliberately ignores the rate limit: it exists
 * precisely so that a gesture which stops mid-window still lands. Without it the host would
 * stay cropped to whatever rectangle happened to win the last tick, which is the one failure
 * mode that is visibly wrong rather than merely suboptimal.
 *
 * <p>Plain Java with an injected clock — no {@code SystemClock}, no {@code Handler} — so the
 * whole decision table is unit tested without Android.
 */
public final class ViewportThrottle {

    /** Minimum spacing between two sends, in milliseconds. */
    public static final long MIN_INTERVAL_MS = 50L;

    /**
     * A change below this many host pixels on every edge is not worth a packet on its own.
     * Expressed as a fraction of the host dimension so it scales with the desktop:
     * 1/200 of 5360 is 27px, which is well under a line of text.
     */
    public static final int EDGE_DELTA_DIVISOR = 200;

    private final int minEdgeDelta;

    private ViewportRect lastSent;
    private long lastSentAtMs;
    private boolean haveSent;
    private ViewportRect pending;

    public ViewportThrottle(int minEdgeDelta) {
        this.minEdgeDelta = Math.max(1, minEdgeDelta);
    }

    /** Derives the significance threshold from the host frame size. */
    public static ViewportThrottle forHostSize(int hostWidth, int hostHeight) {
        int smaller = Math.max(1, Math.min(hostWidth, hostHeight));
        return new ViewportThrottle(Math.max(1, smaller / EDGE_DELTA_DIVISOR));
    }

    /**
     * Offer the rectangle the user is currently looking at.
     *
     * @return the rectangle to send now, or null if it was dropped or deferred
     */
    public ViewportRect offer(ViewportRect candidate, long nowMs) {
        if (candidate == null) {
            return null;
        }
        if (haveSent && candidate.equals(lastSent)) {
            pending = null;
            return null;
        }
        if (haveSent && candidate.maxEdgeDelta(lastSent) < minEdgeDelta) {
            pending = candidate;
            return null;
        }
        if (haveSent && nowMs - lastSentAtMs < MIN_INTERVAL_MS) {
            pending = candidate;
            return null;
        }
        return commit(candidate, nowMs);
    }

    /**
     * The trailing edge: deliver whatever the last offer settled on.
     *
     * @return the rectangle to send now, or null if there is nothing outstanding
     */
    public ViewportRect flush(long nowMs) {
        ViewportRect outstanding = pending;
        pending = null;
        if (outstanding == null) {
            return null;
        }
        if (haveSent && outstanding.equals(lastSent)) {
            return null;
        }
        return commit(outstanding, nowMs);
    }

    /** True when a rectangle is waiting for {@link #flush}. */
    public boolean hasPending() {
        return pending != null;
    }

    /** The rectangle most recently handed out for sending, or null if none yet. */
    public ViewportRect lastSent() {
        return haveSent ? lastSent : null;
    }

    /** Forget everything. Used between streaming sessions. */
    public void reset() {
        lastSent = null;
        pending = null;
        haveSent = false;
        lastSentAtMs = 0L;
    }

    /**
     * Record a rectangle as sent without going through {@link #offer}. Used for the
     * unconditional full-frame sends at stream start and stream end, so the next offer is
     * compared against what the host was actually told.
     */
    public void markSent(ViewportRect rect, long nowMs) {
        if (rect != null) {
            commit(rect, nowMs);
        }
    }

    private ViewportRect commit(ViewportRect rect, long nowMs) {
        lastSent = rect;
        lastSentAtMs = nowMs;
        haveSent = true;
        pending = null;
        return rect;
    }
}
