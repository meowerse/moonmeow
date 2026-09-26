package com.limelight.meow.cursor;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

import com.limelight.meow.gesture.InlinePinchZoomController;
import com.limelight.meow.stream.MeowStreamBridge;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the host cursor visible whenever the user is zoomed in. UI thread, except the two
 * listener interfaces, which accept any thread. The full behaviour table, and why each rule
 * is what it is, is {@code docs/meow/cursor-follow-ux.md}.
 *
 * <h2>The model</h2>
 * In the pointer modes (touch trackpad natural and gaming, physical touchpad, mouse, local
 * cursor) the cursor is the user's point of interest, so view manipulation is built around it:
 * <ul>
 *   <li><b>Zoom anchors on the cursor.</b> A pinch keeps the cursor at the same screen position
 *       instead of the fingers' midpoint. {@code PanZoomHandler} still applies the pinch about
 *       the fingers; the correction is applied in the same UI message, before anything draws,
 *       so every zoom entry point (inline pinch, the explicit Pan/Zoom mode, anything later) is
 *       anchored without a hook of its own.</li>
 *   <li><b>A manual pan carries the cursor.</b> It keeps its screen position and the host
 *       pointer is moved with the view (one absolute position per transform change). "Free
 *       look" was the alternative; it is exactly the state the user reported as the cursor
 *       being lost, and the next pointer motion snapping the view back undoes the pan anyway.
 *       </li>
 *   <li><b>Everything else</b> — a cursor move, a host teleport, a jump to the other monitor,
 *       rotation, PiP or split-screen resize, the soft keyboard, a reconnect, a capture or mode
 *       switch — arms the follower, which eases the view until the cursor is inside a comfort
 *       margin: gently while it is on screen, fast while it is off screen.</li>
 * </ul>
 * In the direct-touch modes (multi-touch, and "normal mouse" where a tap clicks under the
 * finger) the finger is the pointer: zoom anchors on the fingers as it always did, a pan does
 * not move the host pointer, and the follower only edge-scrolls when the pointer itself is
 * taken to the very edge.
 *
 * <h2>Where the cursor comes from</h2>
 * {@link HostCursor}. With a host that reports it (0x3004, subscribed once the host has proven
 * it is a meow host) the reports are the truth and are never overridden, except that a report
 * older than a position the client itself just sent is ignored for a round trip.
 *
 * <p>Without reports, the estimate is dead-reckoned from every movement the client sends
 * ({@link CursorInputTap}), which drifts: the host applies pointer acceleration we cannot see.
 * So <b>while zoomed in, the client owns the pointer</b> (and unzoomed too against a proven
 * meow host that does not report): relative movement is not sent as relative at all but
 * replayed as absolute positions ({@link #interceptRelative}), clamped to the visible part of
 * the desktop, and pushing past the edge scrolls the view by the overshoot at once. The host then has no acceleration to apply, the estimate is
 * exact by construction, and the cursor cannot leave the screen — it pushes the view instead.
 * Whenever the estimate is only a guess (stream start, after unzoomed relative travel, after a
 * capture toggle) the first zoomed-in input or zoom-in re-syncs it by placing the host pointer
 * at a known point inside the view. Unzoomed, nothing changes: relative input stays relative,
 * with the host's own acceleration, because the whole desktop is on screen anyway.
 *
 * <p>No allocation on the per-event or per-frame paths, apart from a {@code MeowFollow} line
 * (at most four a second, built only when it will be written). Inputs from other threads are
 * folded into atomics and drained on the UI thread by one reused runnable; a native touch
 * reported off the UI thread (no sender does that today) is posted as a small task.
 */
public final class CursorFollowController
        implements CursorInputTap.Listener, MeowStreamBridge.CursorListener {

    /** Margin after relative movement, as a fraction of the visible size. */
    static final float COMFORT_MARGIN = 0.15f;
    /** Margin in the direct-touch modes and after absolute pointing: edge-scroll only. */
    static final float EDGE_MARGIN = 0.04f;
    /** How long an absolute input keeps the edge margin in force. */
    static final long ABSOLUTE_INPUT_WINDOW_MS = 500L;
    /**
     * A cursor the host hides while the user is moving it -- it was visible, and pointer input
     * went out within this long of the report -- is still followed: it is where the user is
     * looking. The emulator run against sunmeow PR #20 had the host report the cursor hidden
     * at the desktop's right edge mid-swipe, and the follower stopped short of it. A cursor
     * that was already hidden (a game, a video) is never chased, however much the mouse moves.
     */
    static final long POINTER_INPUT_WINDOW_MS = 500L;
    /** After the client moves the pointer, host reports older than this may be stale. */
    static final long HOST_REPORT_GRACE_MS = 300L;
    /** Zoom factors this close to 1 count as unzoomed. */
    static final float UNZOOMED = 1.001f;
    /** Longest frame step integrated, so a stalled UI thread does not jump the view. */
    private static final float MAX_FRAME_SECONDS = 0.05f;
    /**
     * After the host proves it is a meow host, how long to wait for its first 0x3004 report
     * before treating it as one that does not report. A reporting host answers the
     * subscription at once; until then the client must not move the pointer on a guess.
     */
    public static final long FIRST_REPORT_WAIT_MS = 1500L;
    /**
     * The pointer sprite hangs down and right of its hotspot; a pointer the client keeps on
     * screen stays this many screen pixels inside the right and bottom edges so it is seen.
     */
    static final float POINTER_SPRITE_PX = 24f;
    /** Screen pixels a touch contact travels before it counts as a drag the view follows. */
    static final float TOUCH_SLOP_PX = 24f;
    private static final long NEVER = Long.MIN_VALUE / 2;

    /** What the controller needs from the view side. Implemented by the viewport binder. */
    public interface ViewportView {
        /**
         * The visible part of the reference frame as {x, y, width, height}, under the user's
         * logical transform and above the soft keyboard. UI thread, no allocation.
         *
         * @return false before the views are laid out or the stream has started
         */
        boolean visibleReferenceRect(float[] out);

        /** The desktop inside the frame as {left, top, right, bottom}, reference pixels. */
        void contentBounds(float[] out);

        /**
         * The logical transform as {originX, originY, parentPxPerReferenceX,
         * parentPxPerReferenceY, zoom}: a reference point r is at parent pixel
         * {@code origin + r * pxPerReference}.
         */
        boolean transform(float[] out);

        /**
         * The box the view is shown in, {left, top, right, bottom} in parent pixels, ignoring
         * the soft keyboard and docked overlays (they come and go while typing).
         *
         * @return false before the views are laid out or the stream has started
         */
        boolean window(float[] out);
    }

    /** Sends an absolute host pointer position. Production: {@code NvConnection}. */
    public interface PointerSink {
        void sendPosition(short x, short y, short referenceWidth, short referenceHeight);
    }

    /** Clock, vsync and UI-thread access. Production uses {@link Choreographer}; tests pump. */
    public interface Frames {
        long uptimeMillis();

        void requestFrame(Choreographer.FrameCallback callback);

        boolean isUiThread();

        void postToUi(Runnable task);

        /** False when the user has turned animations off: move the view in one step. */
        boolean animationsEnabled();

        void postToUiDelayed(Runnable task, long delayMs);
    }

    /** Whether touch input is in a direct-touch mode (the finger is the pointer). */
    public interface TouchMode {
        boolean isDirectTouch();
    }

    private final ViewportView view;
    private final InlinePinchZoomController.ZoomTarget panTarget;
    private final Frames frames;
    private final HostCursor cursor = new HostCursor();
    private final boolean enabled;
    /** {@link AutoCursorZoom}: start zoomed when the desktop is a strip in the view. */
    private boolean autoZoom;
    /** The user set the zoom themselves this stream (a pinch, a restored zoom): hands off. */
    private boolean userZoomed;

    private PointerSink sink;
    private TouchMode touchMode = () -> false;
    private final FollowLog log;

    /** Written by the stream lifecycle, which may run on the teardown worker. */
    private volatile boolean streamStarted;
    private boolean armed;
    private boolean frameRequested;
    private long lastFrameNanos;
    private float velocityX;
    private float velocityY;
    private volatile long lastAbsoluteInputMs = Long.MIN_VALUE / 2;
    /** When the client last sent pointer movement of any kind. Any thread writes it. */
    private volatile long lastPointerInputMs = Long.MIN_VALUE / 2;
    /**
     * The host's cursor is hidden, but where the user drove it: keep following. Decided when a
     * report is drained, so a follow in progress runs to its end. UI thread.
     */
    private boolean followHidden;
    /** Where {@link #followHidden} was decided; a hidden cursor that moves off it drops it. */
    private float followHiddenX;
    private float followHiddenY;
    /** The host reported its cursor visible at least once this stream. UI thread. */
    private boolean seenVisible;
    /** The "sent as relative, host reports" line is written once per stream. */
    private boolean loggedHostReportingInput;
    private long ignoreHostReportsUntilMs = NEVER;
    /** When this stream's host proved it is a meow host (subscribed), or NEVER. */
    private volatile long hostProvenAtMs = NEVER;
    private int streamWidth = 1;
    private int streamHeight = 1;

    /** The logical transform at the last change we saw: origin, px/ref and zoom. */
    private final float[] lastTransform = new float[5];
    private boolean haveTransform;
    /** Set while the controller itself moves the view, so it does not react to its own pans. */
    private boolean moving;
    /** Scratch for the transform after a pan, to tell a refused pan. UI thread. */
    private final float[] afterPan = new float[5];
    /** Set while the controller itself sends an absolute position. */
    private boolean placing;
    /** Whether that placement should arm the follower (only a move the user made). */
    private boolean placingArms;

    private final float[] visible = new float[4];
    private final float[] bounds = new float[4];
    private final float[] transform = new float[5];
    private final float[] scratchWindow = new float[4];
    /** What auto zoom last measured: content w/h, window w/h, screen px per desktop px x/y. */
    private final float[] measuredGeometry = new float[6];
    /** The current touch contact, UI thread: where it went down, and whether it is a drag. */
    private float touchDownX;
    private float touchDownY;
    private boolean touchDragging;

    // Cross-thread inbox, drained on the UI thread.
    private static final long NO_POSITION = -1L;
    private final AtomicLong pendingHostPosition = new AtomicLong(NO_POSITION);
    private final AtomicInteger pendingRelativeX = new AtomicInteger();
    private final AtomicInteger pendingRelativeY = new AtomicInteger();
    private final AtomicBoolean drainPosted = new AtomicBoolean();
    private final Runnable drain = this::drainInbox;

    private final Choreographer.FrameCallback onFrame = this::onFrame;
    private final Runnable subscribe = this::subscribeIfEnabled;

    public CursorFollowController(ViewportView view,
                                  InlinePinchZoomController.ZoomTarget panTarget,
                                  boolean enabled) {
        this(view, panTarget, enabled, new AndroidFrames());
    }

    public CursorFollowController(ViewportView view,
                                  InlinePinchZoomController.ZoomTarget panTarget,
                                  boolean enabled, Frames frames) {
        this(view, panTarget, enabled, frames, new FollowLog());
    }

    /** Test seam: inject the diagnostics log (plain JVM tests have no android.util.Log). */
    CursorFollowController(ViewportView view, InlinePinchZoomController.ZoomTarget panTarget,
                           boolean enabled, Frames frames, FollowLog log) {
        if (view == null || panTarget == null || frames == null) {
            throw new IllegalArgumentException("view, panTarget and frames are required");
        }
        this.view = view;
        this.panTarget = panTarget;
        this.enabled = enabled;
        this.frames = frames;
        this.log = log;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Where the controller may place the host pointer. Without it, it never does. */
    public void setPointerSink(PointerSink sink) {
        this.sink = sink;
    }

    /** Auto cursor zoom on or off ({@link AutoCursorZoom#isEnabled}). Before the stream. */
    public void setAutoZoom(boolean on) {
        this.autoZoom = on;
    }

    /** How to tell the direct-touch modes from the pointer modes. Read when needed. */
    public void setTouchMode(TouchMode mode) {
        this.touchMode = mode != null ? mode : () -> false;
    }

    /** The cursor model. UI thread. */
    public HostCursor cursor() {
        return cursor;
    }

    /**
     * Sends the 0x3004 subscription. Runs off the UI thread (it may block on ENet), on the
     * thread that learns the host is a meow host. Only subscribes when following is on.
     */
    public Runnable subscribeTask() {
        return subscribe;
    }

    private void subscribeIfEnabled() {
        if (enabled) {
            hostProvenAtMs = frames.uptimeMillis();
            int result = MeowStreamBridge.subscribeCursor(true);
            log.state("host proven (viewport echo); cursor subscription sent, result " + result);
        }
    }

    /**
     * The client may move the host pointer on its own judgement: never over a host's own
     * reports, and not while a proven meow host has not yet had the chance to send its first.
     */
    private boolean mayOwnPointer() {
        if (cursor.isHostReporting()) {
            return false;
        }
        long proven = hostProvenAtMs;
        return proven == NEVER || frames.uptimeMillis() - proven >= FIRST_REPORT_WAIT_MS;
    }

    /** UI thread. Starts listening for this stream. */
    public void onStreamStarted(int streamWidth, int streamHeight) {
        this.streamWidth = Math.max(1, streamWidth);
        this.streamHeight = Math.max(1, streamHeight);
        cursor.onStreamStarted(streamWidth, streamHeight);
        pendingHostPosition.set(NO_POSITION);
        pendingRelativeX.set(0);
        pendingRelativeY.set(0);
        armed = false;
        haveTransform = view.transform(lastTransform);
        // A zoom already in place (rememberZoomPan restored it) is the user's choice.
        userZoomed = haveTransform && lastTransform[4] > UNZOOMED;
        followHidden = false;
        seenVisible = false;
        loggedHostReportingInput = false;
        lastPointerInputMs = NEVER;
        lastAbsoluteInputMs = NEVER;
        java.util.Arrays.fill(measuredGeometry, -1f);
        ignoreHostReportsUntilMs = NEVER;
        hostProvenAtMs = NEVER;
        streamStarted = true;
        if (enabled) {
            CursorInputTap.install(this);
            MeowStreamBridge.setCursorListener(this);
        }
        log.state("stream start " + streamWidth + "x" + streamHeight + ": follow "
                + (enabled ? "on" : "OFF") + ", touch mode "
                + (touchMode.isDirectTouch() ? "direct" : "pointer")
                + ", sink " + (sink != null) + ", zoom " + (haveTransform ? lastTransform[4] : -1f)
                + ", auto zoom " + (autoZoom ? (userZoomed ? "on, user zoom kept" : "on") : "off"));
        autoZoomIfStrip("stream start");
    }

    /** Any thread. Stops listening; the view is left where it is. */
    public void onStreamStopped() {
        streamStarted = false;
        CursorInputTap.uninstall(this);
        MeowStreamBridge.clearCursorListener(this);
    }

    /** UI thread. The desktop size from the viewport echo, for scaling relative deltas. */
    public void onDesktopExtent(int desktopWidth, int desktopHeight) {
        cursor.setDesktopExtent(desktopWidth, desktopHeight);
        // The echo also told the binder where the desktop sits in the frame: now the strip
        // can be measured. Every later echo lands here too; the latch in autoZoomIfStrip
        // makes those free, since the geometry it measures has not changed.
        autoZoomIfStrip("desktop extent");
    }

    /**
     * {@link AutoCursorZoom}: when the desktop fills only a strip of the window, zoom so it
     * fills the window, centred on the cursor (or on the desktop's middle when the cursor is
     * unknown; the first move then puts the pointer in the middle of the view). Does nothing
     * once the user has zoomed this stream. UI thread.
     */
    private void autoZoomIfStrip(String why) {
        if (!enabled || !autoZoom || userZoomed || !streamStarted
                || !view.transform(transform) || !view.window(scratchWindow)) {
            return;
        }
        view.contentBounds(bounds);
        float zoom = transform[4];
        float pxX = transform[2] / zoom;
        float pxY = transform[3] / zoom;
        float windowW = scratchWindow[2] - scratchWindow[0];
        float windowH = scratchWindow[3] - scratchWindow[1];
        float contentW = (bounds[2] - bounds[0]) * pxX;
        float contentH = (bounds[3] - bounds[1]) * pxY;
        float perDesktopX = pxX * cursor.desktopToReferenceX();
        float perDesktopY = pxY * cursor.desktopToReferenceY();
        // Measure once per geometry: only a new desktop box, a new desktop size or a resize
        // re-zooms, never an echo that changed nothing (which would re-centre the view under
        // the user on every crop) nor a target the handler cannot reach.
        if (measuredGeometry[0] == contentW && measuredGeometry[1] == contentH
                && measuredGeometry[2] == windowW && measuredGeometry[3] == windowH
                && measuredGeometry[4] == perDesktopX && measuredGeometry[5] == perDesktopY) {
            return;
        }
        measuredGeometry[0] = contentW;
        measuredGeometry[1] = contentH;
        measuredGeometry[2] = windowW;
        measuredGeometry[3] = windowH;
        measuredGeometry[4] = perDesktopX;
        measuredGeometry[5] = perDesktopY;
        float target = AutoCursorZoom.targetZoom(contentW, contentH, windowW, windowH,
                perDesktopX, perDesktopY);
        if (Math.abs(target - zoom) < 0.01f * target) {
            return;
        }
        float centreX = scratchWindow[0] + windowW / 2f;
        float centreY = scratchWindow[1] + windowH / 2f;
        float atX = cursor.isKnown() ? cursor.x() : (bounds[0] + bounds[2]) / 2f;
        float atY = cursor.isKnown() ? cursor.y() : (bounds[1] + bounds[3]) / 2f;
        moving = true;
        try {
            panTarget.pinchBy(target / zoom, centreX, centreY);
            if (view.transform(transform)) {
                panTarget.panBy(centreX - (transform[0] + atX * transform[2]),
                        centreY - (transform[1] + atY * transform[3]));
            }
        } finally {
            moving = false;
        }
        remember();
        log.state("auto zoom " + zoom + "->" + target + " (" + why + "): desktop "
                + Math.round(bounds[2] - bounds[0]) + "x" + Math.round(bounds[3] - bounds[1])
                + " ref in a " + Math.round(windowW) + "x" + Math.round(windowH)
                + " window, centred on " + (cursor.isKnown() ? "cursor " + describeCursor()
                        : "the desktop"));
        arm();
    }

    /**
     * UI thread. Pointer capture toggled: the host cursor may have been moved by input we did
     * not send, so the estimate starts over. A host-reported position survives.
     */
    public void resetEstimate() {
        cursor.resetEstimate();
        ensureVisible();
    }

    /**
     * UI thread. Something changed what is on screen without moving the cursor (soft keyboard,
     * mouse-mode switch): bring the cursor back into the comfort area if it has left it.
     */
    public void ensureVisible() {
        arm();
    }

    // ---- view changes, UI thread -------------------------------------------------------

    /**
     * {@code PanZoomHandler} changed the logical transform (a pinch, a pan, a resize), or the
     * visible area changed. Anchors zoom on the cursor and carries it through pans in the
     * pointer modes; always re-checks visibility.
     */
    public void onViewTransformChanged() {
        if (!view.transform(transform)) {
            return;
        }
        if (!haveTransform || moving || !enabled || !streamStarted) {
            remember();
            return;
        }

        float oldZoom = lastTransform[4];
        float newZoom = transform[4];
        boolean resized = Math.abs(transform[2] / newZoom - lastTransform[2] / oldZoom) > 1e-4f
                || Math.abs(transform[3] / newZoom - lastTransform[3] / oldZoom) > 1e-4f;
        boolean zoomed = newZoom != oldZoom;
        boolean panned = transform[0] != lastTransform[0] || transform[1] != lastTransform[1];

        if (zoomed && !resized) {
            // Anything that zooms other than the controller itself is the user.
            userZoomed = true;
        }
        boolean direct = touchMode.isDirectTouch();
        if (!resized && !direct && cursor.isVisible()) {
            if (zoomed) {
                anchorZoomOnCursor(newZoom);
            } else if (panned) {
                carryCursorThroughPan();
            }
        }
        remember();
        if ((zoomed || resized) && log.activityAllowed(FollowLog.VIEW, frames.uptimeMillis())) {
            view.visibleReferenceRect(visible);
            log.activity(FollowLog.VIEW, "view " + (resized ? "resized" : "zoom " + oldZoom + "->" + newZoom)
                    + " (" + (direct ? "direct: fingers anchor" : "pointer: cursor anchor")
                    + ") cursor " + describeCursor() + " visible " + describeVisible());
        }
        // A resize changes what is on screen under a still cursor: bring it back. After a
        // zoom or pan the cursor kept its screen position, so only a cursor that is somehow
        // off screen (the view clamped at a desktop edge) needs the follower; pulling an
        // on-screen cursor out of the margin band now would drift the view under the fingers
        // mid-gesture. In the direct-touch modes the user may zoom away from the last tap on
        // purpose, so the view is never chased there.
        if (!direct && (resized || cursorOffScreen())) {
            arm();
        }
        if (resized) {
            // A rotation: measure the strip again (the user's zoom, once set, is kept).
            autoZoomIfStrip("resize");
        }
    }

    private boolean cursorOffScreen() {
        return cursor.isKnown() && view.visibleReferenceRect(visible)
                && (cursor.x() < visible[0] || cursor.x() > visible[0] + visible[2]
                    || cursor.y() < visible[1] || cursor.y() > visible[1] + visible[3]);
    }

    private void remember() {
        if (view.transform(lastTransform)) {
            haveTransform = true;
        }
    }

    /** Keep the cursor at its screen position through a zoom, or place it if unknown. */
    private void anchorZoomOnCursor(float newZoom) {
        if (!cursor.isKnown()) {
            // Nothing to anchor on. Zooming in is where the user wants to work, so put the
            // pointer in the middle of the new view, which also makes the position exact.
            if (newZoom > UNZOOMED && mayOwnPointer() && view.visibleReferenceRect(visible)) {
                place(visible[0] + visible[2] / 2f, visible[1] + visible[3] / 2f, false);
            }
            return;
        }
        float before = lastTransform[0] + cursor.x() * lastTransform[2];
        float beforeY = lastTransform[1] + cursor.y() * lastTransform[3];
        float after = transform[0] + cursor.x() * transform[2];
        float afterY = transform[1] + cursor.y() * transform[3];
        moveView(before - after, beforeY - afterY);

        if (!cursor.isExact() && newZoom > UNZOOMED && mayOwnPointer()
                && view.visibleReferenceRect(visible) && view.transform(transform)) {
            // Anchored on a dead-reckoned guess. Make it true: put the host pointer where the
            // estimate says, inside the view, so the cursor the user sees is where the zoom went.
            place(clampX(cursor.x()), clampY(cursor.y()), false);
        }
    }

    private float clampX(float x) {
        return Math.max(Math.max(visible[0], cursor.boundsLeft()), Math.min(x, rightLimit()));
    }

    private float clampY(float y) {
        return Math.max(Math.max(visible[1], cursor.boundsTop()), Math.min(y, bottomLimit()));
    }

    /**
     * How far right the client may put the pointer: a sprite's width inside the visible edge
     * while the view can still scroll further, so the pointer is seen and pushes the view; the
     * desktop's last pixel once the view is against the desktop's edge, so the panel, tray
     * and corner there stay reachable.
     */
    private float rightLimit() {
        float visibleRight = visible[0] + visible[2];
        if (visibleRight < cursor.boundsRight() - 0.5f) {
            return visibleRight - edgeInset(transform[2]);
        }
        return cursor.boundsRight() - 1f;
    }

    private float bottomLimit() {
        float visibleBottom = visible[1] + visible[3];
        if (visibleBottom < cursor.boundsBottom() - 0.5f) {
            return visibleBottom - edgeInset(transform[3]);
        }
        return cursor.boundsBottom() - 1f;
    }

    /** Reference pixels to keep a placed pointer's sprite on screen. */
    private static float edgeInset(float pxPerReference) {
        return pxPerReference > 0f ? Math.max(1f, POINTER_SPRITE_PX / pxPerReference) : 1f;
    }

    /** The view moved under a still cursor: move the host pointer with the view. */
    private void carryCursorThroughPan() {
        if (!cursor.isKnown() || transform[2] <= 0f || transform[3] <= 0f) {
            return;
        }
        float screenX = lastTransform[0] + cursor.x() * lastTransform[2];
        float screenY = lastTransform[1] + cursor.y() * lastTransform[3];
        place((screenX - transform[0]) / transform[2], (screenY - transform[1]) / transform[3],
                false);
    }

    /** Pans the logical view by parent pixels, without reacting to it as a user pan. */
    private void moveView(float dx, float dy) {
        if (dx == 0f && dy == 0f) {
            return;
        }
        moving = true;
        try {
            panTarget.panBy(dx, dy);
        } finally {
            moving = false;
        }
    }

    // ---- placing the host pointer ------------------------------------------------------

    /**
     * Puts the host pointer at reference point (x, y), clamped to the desktop. The position
     * goes out through the normal send path, so the estimate hears it via the tap and is exact.
     *
     * @return false when there is nowhere to send it
     */
    private boolean place(float x, float y, boolean armAfter) {
        PointerSink out = sink;
        if (out == null) {
            return false;
        }
        float px = Math.max(cursor.boundsLeft(), Math.min(x, cursor.boundsRight() - 1f));
        float py = Math.max(cursor.boundsTop(), Math.min(y, cursor.boundsBottom() - 1f));
        // A reference as fine as a short allows, so the pointer lands on the desktop pixel
        // meant and not on the nearest multiple of the stream-to-desktop ratio.
        int fine = Math.max(1, Math.min(4, 32766 / Math.max(streamWidth, streamHeight)));
        int refW = streamWidth * fine;
        int refH = streamHeight * fine;
        placing = true;
        placingArms = armAfter;
        try {
            out.sendPosition((short) Math.round(px / streamWidth * (refW - 1)),
                    (short) Math.round(py / streamHeight * (refH - 1)),
                    (short) refW, (short) refH);
        } finally {
            placing = false;
            placingArms = false;
        }
        // The position went out quantised to the reference grid; keep the exact target as the
        // estimate, or every small move gains or loses a fraction of a step (slow motion
        // wobbles by up to a third at 2.8 desktop pixels per reference pixel).
        cursor.placedAt(px, py);
        if (cursor.isHostReporting()) {
            ignoreHostReportsUntilMs = frames.uptimeMillis() + HOST_REPORT_GRACE_MS;
        }
        return true;
    }

    /**
     * {@code NvConnection.sendMouseMove}, UI thread: while zoomed in against a host that does
     * not report its cursor, replay the move as an absolute position inside the view.
     *
     * @return true when the move was sent that way and must not also go out as relative
     */
    boolean interceptRelative(int deltaX, int deltaY) {
        if (!enabled || !streamStarted || sink == null || !mayOwnPointer()
                || !frames.isUiThread() || !view.transform(transform)
                || transform[4] <= UNZOOMED
                || !view.visibleReferenceRect(visible)) {
            if (enabled && streamStarted && cursor.isHostReporting()) {
                // Expected with a reporting host: its reports move the cursor model. Said once.
                if (!loggedHostReportingInput) {
                    loggedHostReportingInput = true;
                    log.state("relative moves go out as relative: the host reports its cursor");
                }
            } else if (enabled && streamStarted && view.transform(transform)
                    && transform[4] > UNZOOMED
                    && log.activityAllowed(FollowLog.INPUT, frames.uptimeMillis())) {
                log.activity(FollowLog.INPUT, "relative move sent as relative: sink "
                        + (sink != null)
                        + ", host reporting " + cursor.isHostReporting()
                        + ", waiting for first report "
                        + (!cursor.isHostReporting() && !mayOwnPointer())
                        + ", ui thread " + frames.isUiThread() + ", cursor " + describeCursor());
            }
            return false;
        }
        float left = Math.max(visible[0], cursor.boundsLeft());
        float top = Math.max(visible[1], cursor.boundsTop());
        float right = rightLimit();
        float bottom = bottomLimit();
        if (!(right > left) || !(bottom > top)) {
            return false;
        }

        float x;
        float y;
        if (cursor.isExact()) {
            x = cursor.x();
            y = cursor.y();
        } else if (cursor.isKnown()) {
            // A guess from unzoomed travel: the best place to re-sync is where it points.
            x = cursor.x();
            y = cursor.y();
        } else {
            x = visible[0] + visible[2] / 2f;
            y = visible[1] + visible[3] / 2f;
        }
        float wantX = x + deltaX * cursor.desktopToReferenceX();
        float wantY = y + deltaY * cursor.desktopToReferenceY();
        // Past the visible edge: scroll the view by the overshoot in this same event, so the
        // cursor keeps moving at finger speed with the desktop sliding under it. Pinning it at
        // the edge while the follower eases after it is what reads as "druggy". The pan is
        // clamped to the desktop by PanZoomHandler; whatever it could not do stays clamped.
        float overX = wantX < left ? wantX - left : (wantX > right ? wantX - right : 0f);
        float overY = wantY < top ? wantY - top : (wantY > bottom ? wantY - bottom : 0f);
        if ((overX != 0f || overY != 0f) && transform[4] > UNZOOMED) {
            moveView(-overX * transform[2], -overY * transform[3]);
            remember();
            if (view.visibleReferenceRect(visible) && view.transform(transform)) {
                left = Math.max(visible[0], cursor.boundsLeft());
                top = Math.max(visible[1], cursor.boundsTop());
                right = rightLimit();
                bottom = bottomLimit();
            }
        }
        x = Math.max(left, Math.min(wantX, right));
        y = Math.max(top, Math.min(wantY, bottom));
        return place(x, y, true);
    }

    // ---- inputs, any thread ------------------------------------------------------------

    @Override
    public void onCursorPosition(int x, int y, boolean visible, int seq) {
        pendingHostPosition.set(((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16)
                | (visible ? 1L : 0L));
        scheduleDrain();
    }

    @Override
    public boolean onRelativeMove(int deltaX, int deltaY) {
        lastPointerInputMs = frames.uptimeMillis();
        if (frames.isUiThread()) {
            if (interceptRelative(deltaX, deltaY)) {
                return true;
            }
            applyRelative(deltaX, deltaY);
            return false;
        }
        pendingRelativeX.addAndGet(deltaX);
        pendingRelativeY.addAndGet(deltaY);
        scheduleDrain();
        return false;
    }

    @Override
    public void onAbsolutePosition(final int x, final int y,
                                   final int referenceWidth, final int referenceHeight) {
        if (!placing) {
            lastPointerInputMs = frames.uptimeMillis();
        }
        if (frames.isUiThread()) {
            applyAbsolute(x, y, referenceWidth, referenceHeight, false);
        } else {
            // No sender does this off the UI thread today; correct rather than fast.
            frames.postToUi(() -> applyAbsolute(x, y, referenceWidth, referenceHeight, false));
        }
    }

    @Override
    public void onMoveAsPosition(final int deltaX, final int deltaY,
                                 final int referenceWidth, final int referenceHeight) {
        lastPointerInputMs = frames.uptimeMillis();
        if (frames.isUiThread()) {
            applyAbsolute(deltaX, deltaY, referenceWidth, referenceHeight, true);
        } else {
            frames.postToUi(() -> applyAbsolute(deltaX, deltaY, referenceWidth, referenceHeight,
                    true));
        }
    }

    @Override
    public void onDirectPointing(final byte eventType, final float fractionX,
                                 final float fractionY) {
        // Any thread. The finger is the pointer here: edge-scroll only (the margin), and the
        // view follows it when it is dragged into the edge band.
        lastAbsoluteInputMs = frames.uptimeMillis();
        if (Float.isNaN(fractionX) || Float.isNaN(fractionY)) {
            return;
        }
        lastPointerInputMs = lastAbsoluteInputMs;
        if (frames.isUiThread()) {
            applyTouchPoint(eventType, fractionX, fractionY);
        } else {
            frames.postToUi(() -> applyTouchPoint(eventType, fractionX, fractionY));
        }
    }

    /**
     * A touch that lands in the edge band is a tap, not a request to scroll: panning between
     * its down and its up would re-map the lift through the new view, and the host would see
     * the contact slide. So a contact is followed only once it has travelled past the slop,
     * which is also what makes it a drag. A hovering pen has no contact and is followed.
     */
    private void applyTouchPoint(byte eventType, float fractionX, float fractionY) {
        if (!streamStarted || cursor.isHostReporting()) {
            // A reporting host says where its pointer went (if touch moved it at all).
            return;
        }
        float x = fractionX * streamWidth;
        float y = fractionY * streamHeight;
        // The estimate always takes the point: a tap is where the host pointer went, and what
        // the keyboard opening or the next mouse move must keep in view.
        cursor.onTouchPoint(x, y);
        if (eventType == CursorInputTap.TOUCH_DOWN) {
            touchDownX = x;
            touchDownY = y;
            touchDragging = false;
            return;
        }
        if (eventType == CursorInputTap.TOUCH_MOVE && !touchDragging) {
            float scale = view.transform(transform) ? transform[2] : 1f;
            float dx = (x - touchDownX) * scale;
            float dy = (y - touchDownY) * scale;
            if (dx * dx + dy * dy < TOUCH_SLOP_PX * TOUCH_SLOP_PX) {
                return;
            }
            touchDragging = true;
        }
        arm();
    }

    private String describeCursor() {
        if (!cursor.isKnown()) {
            return "unknown";
        }
        return Math.round(cursor.x()) + "," + Math.round(cursor.y())
                + (cursor.isHostReporting() ? " (host)" : cursor.isExact() ? " (exact)" : " (guess)")
                + (cursor.isVisible() ? "" : " hidden");
    }

    private String describeVisible() {
        return Math.round(visible[0]) + "," + Math.round(visible[1]) + " "
                + Math.round(visible[2]) + "x" + Math.round(visible[3]);
    }

    private void scheduleDrain() {
        if (drainPosted.compareAndSet(false, true)) {
            frames.postToUi(drain);
        }
    }

    private void drainInbox() {
        drainPosted.set(false);
        long packed = pendingHostPosition.getAndSet(NO_POSITION);
        if (packed != NO_POSITION && streamStarted) {
            long wait = ignoreHostReportsUntilMs - frames.uptimeMillis();
            if (wait > 0) {
                // Possibly older than the position we just sent -- but possibly the host's
                // correction of it (clamped into a monitor, say). Hold the latest and take it
                // once the window has passed, unless a newer report replaces it first.
                pendingHostPosition.compareAndSet(NO_POSITION, packed);
                if (drainPosted.compareAndSet(false, true)) {
                    frames.postToUiDelayed(drain, wait);
                }
            } else {
                boolean first = !cursor.isHostReporting();
                boolean wasVisible = !first && cursor.isVisible();
                boolean driven = frames.uptimeMillis() - lastPointerInputMs
                        <= POINTER_INPUT_WINDOW_MS;
                cursor.onHostPosition((int) (packed >>> 32) & 0xFFFF,
                        (int) (packed >>> 16) & 0xFFFF, (packed & 1L) != 0);
                followHidden = followsHidden(wasVisible, driven);
                if (first) {
                    log.state("first host cursor report (0x3004): " + describeCursor());
                }
                arm();
            }
        }
        int dx = pendingRelativeX.getAndSet(0);
        int dy = pendingRelativeY.getAndSet(0);
        if (dx != 0 || dy != 0) {
            applyRelative(dx, dy);
        }
    }

    // ---- UI thread ---------------------------------------------------------------------

    /**
     * Whether the cursor just reported hidden is still followed. Only where the user drove it:
     * <ul>
     *   <li>it was visible and went hidden within {@link #POINTER_INPUT_WINDOW_MS} of pointer
     *       input (the emulator host hid it at the desktop edge mid-swipe);</li>
     *   <li>before any visible report this stream, it is pinned against the desktop's edge
     *       while the user drives it -- a resumed session whose first report is the cursor
     *       still hidden where it was left. Once the host has shown its cursor, a hidden one
     *       at the edge is a game's (a confined pointer) and is not chased;</li>
     *   <li>a later hidden report at the point it was decided at keeps it; one that has moved
     *       off that point drops it, however slowly it crept: a game that hides the cursor and
     *       lets it wander is not chased (row 20).</li>
     * </ul>
     * Reports that arrive between two drains are coalesced to the newest, so a visible report
     * squeezed between hidden ones can be missed; the next drive decides again.
     */
    private boolean followsHidden(boolean wasVisible, boolean driven) {
        if (cursor.isVisible()) {
            seenVisible = true;
            return false;
        }
        float x = cursor.x();
        float y = cursor.y();
        // Anchored where it was decided, never moved by a report that only "stayed".
        boolean stayed = followHidden && Math.abs(x - followHiddenX) <= 1f
                && Math.abs(y - followHiddenY) <= 1f;
        if (stayed) {
            return true;
        }
        // A follow already decided slides along the edge the user drives it against (a
        // diagonal swipe into the right edge moves y), for a host that re-sends while hidden.
        boolean slid = followHidden && driven
                && ((onVerticalEdge(followHiddenX) && onVerticalEdge(x)
                        && Math.abs(x - followHiddenX) <= 1f)
                    || (onHorizontalEdge(followHiddenY) && onHorizontalEdge(y)
                        && Math.abs(y - followHiddenY) <= 1f));
        // Only a hide against the desktop edge is the compositor's doing (KWin reports no
        // cursor once the user pushes it off the captured edge); a hide mid-desktop is an
        // application capturing the pointer (a game, a video player) and is never chased.
        boolean atEdge = onVerticalEdge(x) || onHorizontalEdge(y);
        boolean pinned = !seenVisible && atEdge;
        if (slid || (driven && atEdge && (wasVisible || pinned))) {
            followHiddenX = x;
            followHiddenY = y;
            return true;
        }
        return false;
    }

    /** Against the desktop's left or right edge: the last desktop pixel, in reference px. */
    private boolean onVerticalEdge(float x) {
        float band = Math.max(1f, cursor.desktopToReferenceX()) + 1f;
        return x <= cursor.boundsLeft() + band - 1f || x >= cursor.boundsRight() - band;
    }

    private boolean onHorizontalEdge(float y) {
        float band = Math.max(1f, cursor.desktopToReferenceY()) + 1f;
        return y <= cursor.boundsTop() + band - 1f || y >= cursor.boundsBottom() - band;
    }

    /**
     * A relative move against a reporting host whose cursor is hidden. The host sends one
     * report when its cursor hides and none while it stays hidden (sunmeow's coalescer:
     * "moved while hidden: nothing the client can use"), so a resumed session whose first
     * report is the cursor hidden at the edge would never get a driven report to decide on.
     * The move itself decides: before the host has shown its cursor this stream, a hidden
     * cursor pinned against the desktop edge is followed when the user pushes into that edge.
     *
     * <p>Only a push into the edge, and never the content origin: sunmeow publishes (0, 0) for
     * a capture that has not yet seen a visible cursor, which lands on the desktop's top-left
     * corner -- inside both edge bands. Without these guards the first move of an ordinary
     * session that starts with a hidden cursor (a fullscreen game) panned there.
     */
    private void onDrivenWhileHostReports(int deltaX, int deltaY) {
        if (followHidden || seenVisible || cursor.isVisible() || !cursor.isKnown()) {
            return;
        }
        float x = cursor.x();
        float y = cursor.y();
        if (Math.abs(x - cursor.boundsLeft()) < 0.5f && Math.abs(y - cursor.boundsTop()) < 0.5f) {
            return;  // The host never saw this cursor: a placeholder, not a position.
        }
        boolean left = onVerticalEdge(x) && x < (cursor.boundsLeft() + cursor.boundsRight()) / 2f;
        boolean top = onHorizontalEdge(y) && y < (cursor.boundsTop() + cursor.boundsBottom()) / 2f;
        boolean pushes = (deltaX > 0 && onVerticalEdge(x) && !left)
                || (deltaX < 0 && left)
                || (deltaY > 0 && onHorizontalEdge(y) && !top)
                || (deltaY < 0 && top);
        if (pushes) {
            followHidden = true;
            followHiddenX = x;
            followHiddenY = y;
            arm();
        }
    }

    private void applyRelative(int deltaX, int deltaY) {
        if (!streamStarted) {
            return;
        }
        if (cursor.isHostReporting()) {
            // With host reports, the host's own answer (a round trip later) moves the cursor.
            onDrivenWhileHostReports(deltaX, deltaY);
            return;
        }
        if (!cursor.isKnown() && view.visibleReferenceRect(visible)) {
            // Unzoomed and nothing known: guess the middle of what the user sees. Zooming in
            // later re-syncs it (anchorZoomOnCursor), so the guess never decides visibility.
            cursor.seed(visible[0] + visible[2] / 2f, visible[1] + visible[3] / 2f);
        }
        if (cursor.onRelativeMove(deltaX, deltaY)) {
            arm();
        }
    }

    private void applyAbsolute(int a, int b, int referenceWidth, int referenceHeight,
                               boolean relativeToLastPosition) {
        if (!streamStarted) {
            return;
        }
        if (relativeToLastPosition) {
            cursor.onMoveAsPosition(a, b, referenceWidth, referenceHeight);
        } else {
            cursor.onAbsolutePosition(a, b, referenceWidth, referenceHeight);
            if (!placing) {
                lastAbsoluteInputMs = frames.uptimeMillis();
            }
        }
        if (cursor.isHostReporting() && !placing) {
            ignoreHostReportsUntilMs = frames.uptimeMillis() + HOST_REPORT_GRACE_MS;
        }
        if (!placing || placingArms) {
            // A zoom anchor or a pan carry keeps the cursor where the user sees it; arming
            // here would drift the view under the fingers mid-gesture.
            arm();
        }
    }

    private void arm() {
        if (!enabled || !streamStarted) {
            return;
        }
        armed = true;
        if (!frameRequested) {
            frameRequested = true;
            lastFrameNanos = 0L;
            frames.requestFrame(onFrame);
        }
    }

    /** One vsync of following. */
    void onFrame(long frameTimeNanos) {
        frameRequested = false;
        if (!armed || !enabled || !streamStarted || !cursor.isKnown()
                || (!cursor.isVisible() && !followHidden)
                || !view.visibleReferenceRect(visible) || !view.transform(transform)) {
            // Any stop, not only a settle, ends a hidden follow: a latch left behind would pull
            // the view to a stale point on the next unrelated re-check (keyboard, resize).
            settled();
            return;
        }
        view.contentBounds(bounds);

        float dt = lastFrameNanos == 0L ? 1f / 60f
                : Math.min(MAX_FRAME_SECONDS, (frameTimeNanos - lastFrameNanos) / 1e9f);
        lastFrameNanos = frameTimeNanos;

        // The margin follows the input that last moved the cursor, not the touch mode: a tap,
        // a hover or a pen puts the cursor under the user's finger or pointer, so only the
        // very edge scrolls; relative motion (trackpad, mouse, gamepad) and the host's own
        // moves get the comfort margin.
        boolean pointing = frames.uptimeMillis() - lastAbsoluteInputMs <= ABSOLUTE_INPUT_WINDOW_MS;
        float margin = pointing ? EDGE_MARGIN : COMFORT_MARGIN;
        float needX = CursorFollowMotion.remaining(visible[0], visible[2], cursor.x(),
                bounds[0], bounds[2], margin);
        float needY = CursorFollowMotion.remaining(visible[1], visible[3], cursor.y(),
                bounds[1], bounds[3], margin);
        if (needX == 0f && needY == 0f) {
            settled();
            return;
        }

        float stepX;
        float stepY;
        if (!frames.animationsEnabled()) {
            // "Remove animations": no motion at all, just the result.
            stepX = needX;
            stepY = needY;
        } else {
            boolean offScreen = cursorOffScreen();
            velocityX = CursorFollowMotion.velocity(needX, visible[2], velocityX, dt, offScreen);
            velocityY = CursorFollowMotion.velocity(needY, visible[3], velocityY, dt, offScreen);
            stepX = CursorFollowMotion.step(needX, velocityX, dt);
            stepY = CursorFollowMotion.step(needY, velocityY, dt);
        }

        if (log.activityAllowed(FollowLog.PAN, frames.uptimeMillis())) {
            log.activity(FollowLog.PAN, "follow pan " + stepX + "," + stepY + " of " + needX + "," + needY
                    + " (margin " + margin + ") cursor " + describeCursor()
                    + " visible " + describeVisible());
        }
        // Moving the visible rectangle right means moving the content left.
        float originX = transform[0];
        float originY = transform[1];
        moveView(-stepX * transform[2], -stepY * transform[3]);
        if (view.transform(afterPan) && Math.abs(afterPan[0] - originX) < 0.01f
                && Math.abs(afterPan[1] - originY) < 0.01f) {
            // The view refused the pan: it is already as far as it goes (an overlay such as
            // the PC keyboard covers the rows the cursor is on). Asking again every vsync
            // would never get further and would never stop; settle and wait for a change.
            remember();
            settled();
            return;
        }
        remember();

        if (Math.abs(needX - stepX) <= CursorFollowMotion.SETTLE_PX
                && Math.abs(needY - stepY) <= CursorFollowMotion.SETTLE_PX) {
            settled();
            return;
        }
        frameRequested = true;
        frames.requestFrame(onFrame);
    }

    /**
     * The view reached the cursor. A follow of a hidden cursor ends here: sunmeow sends
     * nothing while it stays hidden, so a latch kept past this point would pull the view back
     * to a stale point on every later re-check (keyboard, rotation, a user pan).
     */
    private void settled() {
        if (!cursor.isVisible()) {
            followHidden = false;
        }
        disarm();
    }

    private void disarm() {
        armed = false;
        velocityX = 0f;
        velocityY = 0f;
    }

    /** Production clock, vsync and UI thread. Created on the UI thread. */
    private static final class AndroidFrames implements Frames {
        private final Choreographer choreographer = Choreographer.getInstance();
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public void requestFrame(Choreographer.FrameCallback callback) {
            choreographer.postFrameCallback(callback);
        }

        @Override
        public boolean isUiThread() {
            return Looper.myLooper() == handler.getLooper();
        }

        @Override
        public void postToUi(Runnable task) {
            handler.post(task);
        }

        @Override
        public boolean animationsEnabled() {
            return ValueAnimator.areAnimatorsEnabled();
        }

        @Override
        public void postToUiDelayed(Runnable task, long delayMs) {
            handler.postDelayed(task, delayMs);
        }
    }
}
