package com.limelight.meow.cursor;

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
 * Keeps the host cursor on the phone's screen while zoomed in, in every input mode, by panning
 * the user's view after the <em>cursor</em> — never after the finger. UI thread, except the
 * two listener interfaces, which accept any thread.
 *
 * <h2>Where the cursor comes from</h2>
 * {@link HostCursor}: the host's own 0x3004 reports once it sends them (subscribed after the
 * host has proven it is a meow host, see {@link #subscribeTask()}), else an estimate fed by
 * every movement the client sends ({@link CursorInputTap}). Both paths end in the same place,
 * so touch trackpad, gaming touch, physical touchpad, captured mouse (relative and absolute
 * mode), local cursor, absolute touch and multi-touch all follow identically — and a cursor
 * moved on the host itself (its own mouse, an app warping it) follows too, which no client-side
 * guess can.
 *
 * <h2>How the view moves</h2>
 * A cursor change arms the follower; each vsync ({@link Choreographer}) moves the view one
 * {@link CursorFollowMotion} step toward keeping the cursor inside a comfort margin, and the
 * follower disarms once it is there. Panning goes through {@code PanZoomHandler.panBy}, so the
 * new view reaches the host as a viewport update and the crop follows. Only cursor changes arm
 * it: a user who pans away from a still cursor is left where they panned.
 *
 * <p>The margin depends on how the cursor last moved. After a relative move (trackpad, mouse)
 * it is a comfortable {@link #COMFORT_MARGIN} of the view. After an absolute one (a tap, a
 * hover, the local cursor) the cursor is under the user's finger or pointer by construction, so
 * the view only scrolls when it is within {@link #EDGE_MARGIN} of the edge — a tap near a
 * screen edge must not slide the desktop out from under it.
 *
 * <p>No allocation on the per-event or per-frame paths. Inputs from other threads are folded
 * into atomics and drained on the UI thread by one reused runnable.
 */
public final class CursorFollowController
        implements CursorInputTap.Listener, MeowStreamBridge.CursorListener {

    /** Margin after relative movement, as a fraction of the visible size. */
    static final float COMFORT_MARGIN = 0.15f;
    /** Margin after absolute movement: edge-scroll only. */
    static final float EDGE_MARGIN = 0.04f;
    /** How long an absolute input keeps the edge margin in force. */
    static final long ABSOLUTE_INPUT_WINDOW_MS = 500L;
    /** Longest frame step integrated, so a stalled UI thread does not jump the view. */
    private static final float MAX_FRAME_SECONDS = 0.05f;

    /** What the controller needs from the view side. Implemented by the viewport binder. */
    public interface ViewportView {
        /**
         * The visible part of the reference frame as {x, y, width, height}, under the user's
         * logical transform. UI thread, no allocation.
         *
         * @return false before the views are laid out or the stream has started
         */
        boolean visibleReferenceRect(float[] out);

        /** The desktop inside the frame as {left, top, right, bottom}, reference pixels. */
        void contentBounds(float[] out);

        /** Parent pixels per reference pixel on each axis, as {x, y}, at the logical zoom. */
        boolean viewPixelsPerReference(float[] out);
    }

    /** Clock, vsync and UI-thread access. Production uses {@link Choreographer}; tests pump. */
    public interface Frames {
        long uptimeMillis();

        void requestFrame(Choreographer.FrameCallback callback);

        boolean isUiThread();

        void postToUi(Runnable task);
    }

    private final ViewportView view;
    private final InlinePinchZoomController.ZoomTarget panTarget;
    private final Frames frames;
    private final HostCursor cursor = new HostCursor();

    private final boolean enabled;
    /** Written by the stream lifecycle, which may run on the teardown worker. */
    private volatile boolean streamStarted;
    private boolean armed;
    private boolean frameRequested;
    private long lastFrameNanos;
    private volatile long lastAbsoluteInputMs = Long.MIN_VALUE / 2;

    private final float[] visible = new float[4];
    private final float[] bounds = new float[4];
    private final float[] pixelsPerReference = new float[2];

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
        if (view == null || panTarget == null || frames == null) {
            throw new IllegalArgumentException("view, panTarget and frames are required");
        }
        this.view = view;
        this.panTarget = panTarget;
        this.enabled = enabled;
        this.frames = frames;
    }

    public boolean isEnabled() {
        return enabled;
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
            MeowStreamBridge.subscribeCursor(true);
        }
    }

    /** UI thread. Starts listening for this stream. */
    public void onStreamStarted(int streamWidth, int streamHeight) {
        cursor.onStreamStarted(streamWidth, streamHeight);
        pendingHostPosition.set(NO_POSITION);
        pendingRelativeX.set(0);
        pendingRelativeY.set(0);
        armed = false;
        streamStarted = true;
        if (enabled) {
            CursorInputTap.install(this);
            MeowStreamBridge.setCursorListener(this);
        }
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
    }

    /**
     * UI thread. Pointer capture toggled: the host cursor may have been moved by input we did
     * not send, so the estimate starts over. A host-reported position survives.
     */
    public void resetEstimate() {
        cursor.resetEstimate();
    }

    // ---- inputs, any thread ------------------------------------------------------------

    @Override
    public void onCursorPosition(int x, int y, boolean visible, int seq) {
        pendingHostPosition.set(((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16)
                | (visible ? 1L : 0L));
        scheduleDrain();
    }

    @Override
    public void onRelativeMove(int deltaX, int deltaY) {
        if (frames.isUiThread()) {
            applyRelative(deltaX, deltaY);
            return;
        }
        pendingRelativeX.addAndGet(deltaX);
        pendingRelativeY.addAndGet(deltaY);
        scheduleDrain();
    }

    @Override
    public void onAbsolutePosition(final int x, final int y,
                                   final int referenceWidth, final int referenceHeight) {
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
        if (frames.isUiThread()) {
            applyAbsolute(deltaX, deltaY, referenceWidth, referenceHeight, true);
        } else {
            frames.postToUi(() -> applyAbsolute(deltaX, deltaY, referenceWidth, referenceHeight,
                    true));
        }
    }

    @Override
    public void onDirectPointing() {
        // Any thread; it only selects the margin, so the volatile store is all it needs.
        lastAbsoluteInputMs = frames.uptimeMillis();
    }

    private void scheduleDrain() {
        if (drainPosted.compareAndSet(false, true)) {
            frames.postToUi(drain);
        }
    }

    private void drainInbox() {
        drainPosted.set(false);
        long packed = pendingHostPosition.getAndSet(NO_POSITION);
        if (packed != NO_POSITION) {
            cursor.onHostPosition((int) (packed >>> 32) & 0xFFFF, (int) (packed >>> 16) & 0xFFFF,
                    (packed & 1L) != 0);
            arm();
        }
        int dx = pendingRelativeX.getAndSet(0);
        int dy = pendingRelativeY.getAndSet(0);
        if (dx != 0 || dy != 0) {
            applyRelative(dx, dy);
        }
    }

    // ---- UI thread ---------------------------------------------------------------------

    private void applyRelative(int deltaX, int deltaY) {
        if (!streamStarted || cursor.isHostReporting()) {
            // With host reports, the host's own answer (a round trip later) moves the cursor.
            return;
        }
        if (!cursor.isKnown() && view.visibleReferenceRect(visible)) {
            // Best available guess: the middle of what the user can see. Capture usually
            // starts with the pointer there, and the edge corrects any error.
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
            lastAbsoluteInputMs = frames.uptimeMillis();
        }
        arm();
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
        if (!armed || !enabled || !streamStarted || !cursor.isKnown() || !cursor.isVisible()) {
            armed = false;
            return;
        }
        if (!view.visibleReferenceRect(visible) || !view.viewPixelsPerReference(pixelsPerReference)) {
            armed = false;
            return;
        }
        view.contentBounds(bounds);

        float dt = lastFrameNanos == 0L ? 1f / 60f
                : Math.min(MAX_FRAME_SECONDS, (frameTimeNanos - lastFrameNanos) / 1e9f);
        lastFrameNanos = frameTimeNanos;

        float margin = frames.uptimeMillis() - lastAbsoluteInputMs <= ABSOLUTE_INPUT_WINDOW_MS
                ? EDGE_MARGIN : COMFORT_MARGIN;
        float needX = CursorFollowMotion.remaining(visible[0], visible[2], cursor.x(),
                bounds[0], bounds[2], margin);
        float needY = CursorFollowMotion.remaining(visible[1], visible[3], cursor.y(),
                bounds[1], bounds[3], margin);
        if (needX == 0f && needY == 0f) {
            armed = false;
            return;
        }
        float stepX = CursorFollowMotion.step(needX, visible[2], dt);
        float stepY = CursorFollowMotion.step(needY, visible[3], dt);

        // Moving the visible rectangle right means moving the content left.
        panTarget.panBy(-stepX * pixelsPerReference[0], -stepY * pixelsPerReference[1]);

        if (Math.abs(needX - stepX) <= CursorFollowMotion.SETTLE_PX
                && Math.abs(needY - stepY) <= CursorFollowMotion.SETTLE_PX) {
            armed = false;
            return;
        }
        frameRequested = true;
        frames.requestFrame(onFrame);
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
    }
}
