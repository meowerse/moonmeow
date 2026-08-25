package com.limelight.meow.viewport;

/**
 * Owns the decision of what to tell the host about the region the user is looking at, and
 * when. Plain Java: the transport and the timer are injected, so the whole state machine is
 * unit tested without Android or a live connection.
 *
 * <p><b>Why this exists.</b> Streaming a 5360x1440 desktop into a 5-8 Mbps encoder spends
 * almost every bit on pixels the user is not looking at. When they pinch into a region, the
 * host can crop to that region before scaling into the encoder and spend the same bitrate
 * on a fraction of the pixels. This class is the client half: it reports the rectangle. It
 * never changes what the client renders.
 *
 * <h2>Coordinate space: the stream frame, not the desktop</h2>
 * Rectangles are in the <b>negotiated stream resolution</b>, uncropped, with (0,0) at its
 * top-left. Not host desktop pixels. The client is never told the host's desktop size —
 * {@code serverinfo} does not carry it — so the stream frame is the only coordinate system
 * both ends can compute, and it is the same reference space
 * {@code LiSendMousePositionEvent} already uses for absolute positioning. The host maps the
 * rectangle into its own desktop pixels, undoing its letterbox padding, and answers in the
 * same stream space. Older revisions of {@code Limelight.h} described the wire as desktop
 * pixels; that was never implementable and has been corrected.
 *
 * <h2>Capability detection: the echo, and only the echo</h2>
 * {@code LiSendViewportEvent} returning 0 proves nothing. Its packet-type table is chosen
 * from the host's advertised app version alone, and stock Sunshine advertises a version
 * that selects the encrypted Gen 7 table — which has a viewport entry. So against a host
 * that has never heard of this extension, the call succeeds and a reliable control packet
 * really does go out. Reading the return value as capability detection means talking to
 * stock Sunshine at up to 20 packets a second for the whole session.
 *
 * <p>The only truthful signal is the host's echo ({@code ConnListenerSetViewport}): a host
 * that implements viewport following must answer every understood request with the
 * rectangle it applied. So this class <b>probes</b>. At stream start it sends one full-frame
 * rectangle and starts a deadline. Until an echo arrives nothing further goes out — the
 * user's zoom is remembered, not transmitted. If the deadline passes without an echo the
 * probe is retried once; if that fails too, the host is recorded as not supporting the
 * extension and the feature latches off for the rest of the session. A non-supporting host
 * therefore sees {@value #PROBE_ATTEMPTS} packets in total, not a stream of them.
 *
 * <h2>Coalescing lives in the library, not here</h2>
 * {@code LiSendViewportEvent} already rate limits to one message per 50 ms, drops
 * rectangles identical to the last one sent, retries a failed send on its next tick, and
 * flushes the trailing rectangle once the caller stops moving. Duplicating any of that here
 * only added latency — an earlier revision of this class delayed a below-threshold final
 * rectangle from 50 ms to 120 ms for no benefit. The JNI call is cheap and is made off the
 * UI thread (see {@link StreamViewportBinder}), so every update is simply offered to the
 * library.
 *
 * <h2>Never leave the host cropped</h2>
 * {@link #onStreamStopped} sends the full frame unconditionally while the connection is
 * still up, so disconnecting while zoomed cannot leave the host cropped. It is
 * unconditional rather than guarded on a "currently cropped" flag because that flag would
 * have to be inferred from asynchronous echoes, and being wrong once is unrecoverable —
 * there is no session left to correct it. A redundant uncrop costs one packet that the
 * library drops as a duplicate anyway. The library additionally flushes this rectangle
 * during {@code LiStopConnection} teardown, ignoring its own rate limit, so a terminal
 * uncrop sent inside the coalescing window is still delivered.
 *
 * <h2>The preference takes effect on the next stream</h2>
 * {@link #setEnabled} exists and works, but nothing routes to it mid-session: the
 * preference is read once in {@code Game.onCreate} and there is no path from the in-stream
 * menu to Settings. Toggling the preference therefore applies to the next stream, not the
 * running one.
 */
public final class ViewportReporter {

    /** {@code LiSendViewportEvent} accepted the rectangle. Says nothing about the host. */
    public static final int LI_OK = 0;
    /** The rectangle was degenerate. Should not happen: {@link ViewportRect} clamps. */
    public static final int LI_INVALID_RECT = -1;
    /** The control stream is not connected. Transient. */
    public static final int LI_NOT_CONNECTED = -2;
    /** This host's generation has no viewport packet type at all. Permanent. */
    public static final int LI_NO_PACKET_TYPE = -3;
    /** The native symbol did not bind. Permanent, and not a library return code. */
    public static final int LI_LIBRARY_UNAVAILABLE = -100;

    /**
     * How long to wait for the host's echo before assuming it did not understand.
     *
     * <p>Covers a control-stream round trip plus the host's own scheduling. Generous on
     * purpose: the cost of waiting is that viewport following engages a moment late, while
     * the cost of being impatient is latching the feature off against a host that does
     * support it.
     */
    public static final long ECHO_DEADLINE_MS = 2000L;

    /** How many full-frame probes a host gets before it is written off. */
    public static final int PROBE_ATTEMPTS = 2;

    /** Host support, as far as we can tell. */
    public enum HostSupport {
        /** A probe is outstanding. Nothing but probes goes on the wire in this state. */
        PROBING,
        /** The host echoed. Viewport following is live. */
        SUPPORTED,
        /** No echo, or the library refused outright. Latched off for this session. */
        UNSUPPORTED
    }

    /** The wire. Returns the {@code LiSendViewportEvent} result code. */
    public interface Sender {
        int send(int x, int y, int width, int height);
    }

    /** A one-shot timer. Rescheduling replaces any outstanding task. */
    public interface Scheduler {
        void schedule(long delayMs, Runnable task);
        void cancel();
    }

    private final Sender sender;
    private final Scheduler scheduler;
    private final Runnable deadlineTask = this::onEchoDeadline;

    private boolean enabled;
    private boolean streaming;
    private HostSupport support = HostSupport.UNSUPPORTED;
    private int probesSent;

    private int streamWidth;
    private int streamHeight;
    private int desktopWidth;
    private int desktopHeight;
    private ViewportReferenceFrame referenceFrame;
    private ViewportRect appliedRect;

    /** The most recent rectangle the user is looking at, held while PROBING. */
    private ViewportRect deferredRect;
    /** The rectangle the outstanding probe carried, so its duplicate can be skipped. */
    private ViewportRect probeRect;

    public ViewportReporter(Sender sender, Scheduler scheduler) {
        if (sender == null || scheduler == null) {
            throw new IllegalArgumentException("sender and scheduler are required");
        }
        this.sender = sender;
        this.scheduler = scheduler;
    }

    /**
     * The user preference. Takes effect on the next stream — see the class note. Turning it
     * off while a stream is somehow still running restores the full frame first.
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (!enabled) {
            scheduler.cancel();
            if (streaming && support == HostSupport.SUPPORTED) {
                deliverFullFrame();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True while rectangles the user produces are actually being transmitted. */
    public boolean isActive() {
        return enabled && streaming && support == HostSupport.SUPPORTED;
    }

    /** True while we are willing to keep the feature alive — probing counts. */
    public boolean isLive() {
        return enabled && streaming && support != HostSupport.UNSUPPORTED;
    }

    public HostSupport hostSupport() {
        return support;
    }

    /** The negotiated stream width the rectangles are expressed against. */
    public int streamWidth() {
        return streamWidth;
    }

    public int streamHeight() {
        return streamHeight;
    }

    /** Captured desktop width in host pixels, or 0 until the host reports it. */
    public int desktopWidth() {
        return desktopWidth;
    }

    public int desktopHeight() {
        return desktopHeight;
    }

    /** Where the desktop sits inside the stream frame, or null until the host reports it. */
    public ViewportReferenceFrame referenceFrame() {
        return referenceFrame;
    }

    /** The last rectangle the host said it applied, or null if it has not said. */
    public ViewportRect appliedRect() {
        return appliedRect;
    }

    /**
     * Called once the control stream is up. Resets to the full frame — so a session can
     * never inherit a rectangle — and starts probing for host support.
     *
     * @param streamWidth  negotiated stream width, {@code Game.displayWidth}
     * @param streamHeight negotiated stream height, {@code Game.displayHeight}
     */
    public void onStreamStarted(int streamWidth, int streamHeight) {
        this.streamWidth = Math.max(1, streamWidth);
        this.streamHeight = Math.max(1, streamHeight);
        this.desktopWidth = 0;
        this.desktopHeight = 0;
        this.referenceFrame = null;
        this.appliedRect = null;
        this.deferredRect = null;
        this.probeRect = null;
        this.probesSent = 0;
        this.streaming = true;
        this.support = HostSupport.PROBING;
        scheduler.cancel();
        if (!enabled) {
            this.support = HostSupport.UNSUPPORTED;
            return;
        }
        sendProbe();
    }

    /**
     * Called while the connection is still up but on its way down. Sending after
     * {@code LiStopConnection} is not allowed, so this must run before it.
     */
    public void onStreamStopped() {
        scheduler.cancel();
        if (streaming && support == HostSupport.SUPPORTED) {
            deliverFullFrame();
        }
        streaming = false;
        support = HostSupport.UNSUPPORTED;
        deferredRect = null;
    }

    /**
     * The host's echo of the rectangle it actually applied. This is the capability signal.
     *
     * <p>Every value here is host-supplied and arrives over the network (CLAUDE.md §8), so
     * it is validated rather than trusted: a rectangle outside the stream frame or a
     * desktop size that cannot be reconciled with the negotiated resolution is recorded as
     * "unknown" instead of being used. The echo still counts as proof of support — a host
     * that answers at all understood the message.
     *
     * @param x             applied left edge, in stream pixels
     * @param y             applied top edge, in stream pixels
     * @param width         applied width, in stream pixels
     * @param height        applied height, in stream pixels
     * @param desktopWidth  captured desktop width in host pixels, or 0 if not reported
     * @param desktopHeight captured desktop height in host pixels, or 0 if not reported
     */
    public void onViewportApplied(int x, int y, int width, int height,
                                  int desktopWidth, int desktopHeight) {
        if (!streaming || !enabled || support == HostSupport.UNSUPPORTED) {
            // Late echo from a stream that has already ended, or from one we gave up on.
            return;
        }

        boolean wasProbing = support == HostSupport.PROBING;
        support = HostSupport.SUPPORTED;
        scheduler.cancel();

        this.appliedRect = sanitizeApplied(x, y, width, height);
        adoptDesktopExtent(desktopWidth, desktopHeight);

        if (wasProbing && deferredRect != null) {
            ViewportRect toSend = deferredRect;
            deferredRect = null;
            // The probe carried a rectangle too. If the user never moved, it is the same
            // one, and repeating it would be a JNI call the library only drops again.
            if (!toSend.equals(probeRect)) {
                deliver(toSend);
            }
        }
    }

    /** The rectangle of the stream frame the user can currently see. */
    public void onVisibleRectChanged(ViewportRect rect) {
        if (rect == null || !isLive()) {
            return;
        }
        if (support == HostSupport.PROBING) {
            // Remember it, but do not put it on the wire: we have no evidence yet that
            // anything is listening, and a host that is not must not be talked at.
            deferredRect = rect;
            return;
        }
        deliver(rect);
    }

    /** The probe deadline expired. Retry, or write the host off. */
    void onEchoDeadline() {
        if (!streaming || !enabled || support != HostSupport.PROBING) {
            return;
        }
        if (probesSent < PROBE_ATTEMPTS) {
            sendProbe();
            return;
        }
        markUnsupported();
    }

    private void sendProbe() {
        probesSent++;
        probeRect = ViewportRect.full(streamWidth, streamHeight);
        transmit(probeRect);
        // Arm the deadline unless that send latched the feature off. Notably it is armed
        // even when the send failed transiently (LI_NOT_CONNECTED), because the retry this
        // deadline schedules is exactly what that case needs.
        if (support == HostSupport.PROBING) {
            scheduler.schedule(ECHO_DEADLINE_MS, deadlineTask);
        }
    }

    private void deliverFullFrame() {
        // Prefer the host's own content box when we know it: outside that box is padding,
        // and asking for padding is how a request gets refused.
        ViewportRect full = referenceFrame != null
                ? referenceFrame.fullContent()
                : ViewportRect.full(streamWidth, streamHeight);
        transmit(full);
    }

    private void deliver(ViewportRect rect) {
        ViewportRect toSend = rect;
        if (referenceFrame != null) {
            toSend = referenceFrame.clamp(rect);
            if (toSend == null) {
                // The user is looking only at padding. The host would refuse this and
                // stream the whole desktop; say so ourselves rather than making it guess.
                toSend = referenceFrame.fullContent();
            }
        }
        transmit(toSend);
    }

    private void transmit(ViewportRect rect) {
        int result = sender.send(rect.x, rect.y, rect.width, rect.height);
        if (result == LI_NO_PACKET_TYPE || result == LI_LIBRARY_UNAVAILABLE) {
            // Nothing to send to, or nothing to send with. Permanent for this session.
            markUnsupported();
            return;
        }
        // LI_NOT_CONNECTED and LI_INVALID_RECT are transient and already handled elsewhere:
        // the library retries what it can and the next transform produces another
        // rectangle. Nothing is recorded as delivered in any case, because delivery is not
        // something this class can observe -- only the echo is.
    }

    private void markUnsupported() {
        support = HostSupport.UNSUPPORTED;
        deferredRect = null;
        scheduler.cancel();
    }

    /**
     * Validates an echoed rectangle against the stream frame. Returns null rather than a
     * clamped guess: an echo we cannot make sense of is not evidence about where the host
     * is cropped.
     */
    private ViewportRect sanitizeApplied(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        if (x < 0 || y < 0 || x + width > streamWidth || y + height > streamHeight) {
            return null;
        }
        return new ViewportRect(x, y, width, height);
    }

    /**
     * Adopts the host's reported desktop size, if it is usable. A size that produces no
     * content area inside the stream frame is discarded — the client keeps sending
     * unclamped rectangles, which the host clamps itself.
     */
    private void adoptDesktopExtent(int width, int height) {
        if (width <= 0 || height <= 0 || width > ViewportRect.MAX_COORD
                || height > ViewportRect.MAX_COORD) {
            return;
        }
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(width, height, streamWidth, streamHeight);
        if (frame == null) {
            return;
        }
        this.desktopWidth = width;
        this.desktopHeight = height;
        this.referenceFrame = frame;
    }
}
