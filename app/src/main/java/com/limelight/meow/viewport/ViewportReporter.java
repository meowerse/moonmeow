package com.limelight.meow.viewport;

/**
 * Owns the decision of what to tell the host about the region the user is looking at, and
 * when. Plain Java: the transport and the clock/timer are injected, so the whole state
 * machine is unit tested without Android or a live connection.
 *
 * <p><b>Why this exists.</b> Streaming a 5360x1440 desktop into a 5-8 Mbps encoder spends
 * almost every bit on pixels the user is not looking at. When they pinch into a region, the
 * host can crop to that region before scaling into the encoder and spend the same bitrate on
 * a fraction of the pixels. This class is the client half: it reports the rectangle. It
 * never changes what the client renders.
 *
 * <h2>Degrading to today's behaviour</h2>
 * There is no capability negotiation for the viewport extension. The library answers
 * {@link #LI_UNSUPPORTED} when the host's packet-type table has no viewport entry, which is
 * what stock Sunshine, an older sunmeow and every other host produce. That answer is not an
 * error: it means viewport following is unavailable for this session, so we stop sending
 * and the stream behaves exactly as it does today. Any other failure (notably
 * {@link #LI_NOT_CONNECTED}, the control stream not being up yet) is transient, so the
 * throttle is reset and the current rectangle is retried on the next update rather than
 * being recorded as delivered.
 *
 * <h2>Never leave the host cropped</h2>
 * Three paths send the full frame unconditionally: {@link #onStreamStarted}, so a session
 * never inherits a rectangle; {@link #onStreamStopped}, so disconnecting while zoomed does
 * not leave the next session cropped; and any update that resolves to the whole frame, which
 * is what zooming back out to 1:1 produces naturally — at scale 1 the stream view exactly
 * fills its parent, so the visible fraction is the entire frame.
 */
public final class ViewportReporter {

    /** {@code LiSendViewportEvent} accepted the rectangle. */
    public static final int LI_OK = 0;
    /** The control stream is not connected. Transient. */
    public static final int LI_NOT_CONNECTED = -2;
    /** The host does not implement the viewport extension. Permanent for this session. */
    public static final int LI_UNSUPPORTED = -3;

    /**
     * How long after the last movement the trailing rectangle is delivered. Long enough that
     * an ongoing pinch does not trip it every frame, short enough that the host is not left
     * on a stale rectangle for a perceptible time after the fingers stop.
     */
    public static final long SETTLE_DELAY_MS = 120L;

    /** The wire. Returns the {@code LiSendViewportEvent} result code. */
    public interface Sender {
        int send(int x, int y, int width, int height);
    }

    /** A one-shot timer. Rescheduling replaces any outstanding task. */
    public interface Scheduler {
        void scheduleSettle(long delayMs, Runnable task);
        void cancelSettle();
    }

    private final Sender sender;
    private final Scheduler scheduler;
    private final Runnable settleTask = this::settleNow;

    private boolean enabled;
    private boolean streaming;
    private boolean hostSupported = true;
    private boolean cropped;
    private int hostWidth;
    private int hostHeight;
    private long lastKnownTimeMs;
    private ViewportThrottle throttle = ViewportThrottle.forHostSize(1, 1);

    public ViewportReporter(Sender sender, Scheduler scheduler) {
        if (sender == null || scheduler == null) {
            throw new IllegalArgumentException("sender and scheduler are required");
        }
        this.sender = sender;
        this.scheduler = scheduler;
    }

    /** The user preference. Turning it off mid-session restores the full frame. */
    public void setEnabled(boolean enabled, long nowMs) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (!enabled) {
            scheduler.cancelSettle();
            restoreFullFrame(nowMs);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True while we are still willing to send — the preference is on and the host answered. */
    public boolean isActive() {
        return enabled && streaming && hostSupported;
    }

    /** False once the host has told us it does not understand viewport messages. */
    public boolean isHostSupported() {
        return hostSupported;
    }

    /** The host frame size the rectangles are expressed against. */
    public int hostWidth() {
        return hostWidth;
    }

    public int hostHeight() {
        return hostHeight;
    }

    /**
     * Called once the control stream is up. Resets to the full frame so a session can never
     * start cropped, whatever the previous one left behind.
     */
    public void onStreamStarted(int hostWidth, int hostHeight, long nowMs) {
        this.hostWidth = Math.max(1, hostWidth);
        this.hostHeight = Math.max(1, hostHeight);
        this.throttle = ViewportThrottle.forHostSize(this.hostWidth, this.hostHeight);
        this.hostSupported = true;
        this.streaming = true;
        this.cropped = false;
        this.lastKnownTimeMs = nowMs;
        scheduler.cancelSettle();
        if (!enabled) {
            return;
        }
        deliver(ViewportRect.full(this.hostWidth, this.hostHeight), nowMs);
    }

    /**
     * Called while the connection is still up but on its way down, so the full frame is
     * restored before {@code LiStopConnection}. Sending after that point is not allowed.
     */
    public void onStreamStopped(long nowMs) {
        scheduler.cancelSettle();
        restoreFullFrame(nowMs);
        streaming = false;
        throttle.reset();
    }

    /** The rectangle of the host frame the user can currently see. */
    public void onVisibleRectChanged(ViewportRect rect, long nowMs) {
        lastKnownTimeMs = nowMs;
        if (!isActive() || rect == null) {
            return;
        }
        ViewportRect toSend = throttle.offer(rect, nowMs);
        if (toSend != null) {
            deliver(toSend, nowMs);
        }
        if (throttle.hasPending()) {
            scheduler.scheduleSettle(SETTLE_DELAY_MS, settleTask);
        }
    }

    /**
     * Deliver the trailing rectangle. Normally invoked by the scheduler; exposed so a caller
     * that knows a gesture ended can force it without waiting.
     *
     * <p>The send is stamped with the time of the last movement rather than the time the
     * timer fired. That is the instant the user actually stopped, and it means the rate-limit
     * window for whatever they do next is measured from their gesture rather than from our
     * timer -- which is why this class needs no clock of its own.
     */
    public void settleNow() {
        if (!isActive()) {
            return;
        }
        ViewportRect toSend = throttle.flush(lastKnownTimeMs);
        if (toSend != null) {
            deliver(toSend, lastKnownTimeMs);
        }
    }

    private void restoreFullFrame(long nowMs) {
        if (!cropped || !streaming || !hostSupported || hostWidth <= 0 || hostHeight <= 0) {
            return;
        }
        deliver(ViewportRect.full(hostWidth, hostHeight), nowMs);
    }

    private void deliver(ViewportRect rect, long nowMs) {
        int result = sender.send(rect.x, rect.y, rect.width, rect.height);
        if (result == LI_UNSUPPORTED) {
            // Not a failure: the host simply has no viewport in its packet-type table.
            // Stop sending for the rest of the session and leave the stream alone.
            hostSupported = false;
            cropped = false;
            throttle.reset();
            scheduler.cancelSettle();
            return;
        }
        if (result != LI_OK) {
            // Transient (control stream not up yet). Forget that we sent anything so the
            // current rectangle is offered again rather than being assumed delivered.
            throttle.reset();
            return;
        }
        throttle.markSent(rect, nowMs);
        cropped = !rect.coversAllOf(hostWidth, hostHeight);
    }
}
