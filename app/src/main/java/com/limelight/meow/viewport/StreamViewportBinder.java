package com.limelight.meow.viewport;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;

import com.limelight.LimeLog;
import com.limelight.meow.cursor.CursorFollowPlan;
import com.limelight.meow.cursor.CursorFollowPlanner;
import com.limelight.utils.PanZoomHandler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Wires the local zoom/pan transform to {@link ViewportReporter}, and owns the thread the
 * reporter runs on.
 *
 * <p>This is the only class in the feature that touches Android views, and it is kept
 * deliberately thin: read four numbers off the stream view, work out the window they are
 * seen through, hand both to {@link ViewportGeometry}. Every decision — what counts as a
 * change, how often to send, what to do when the host says nothing — lives in the plain-Java
 * classes beside it.
 *
 * <h2>Threads, and why there is one of our own</h2>
 * {@code PanZoomHandler.constrainToBounds()} is driven from touch dispatch, so
 * {@link #onZoomTransformChanged()} runs on the UI thread inside a gesture. The JNI call it
 * ultimately causes reaches {@code sendMessageEnet}, which takes the ENet mutex and, on
 * reliable-packet backpressure, sleeps in 1 ms steps up to ten times. Backpressure is the
 * <em>expected</em> case on the 5-8 Mbps link this feature exists for, so doing that
 * synchronously would mean up to ~200 ms of blocked UI thread per second during a pinch —
 * jank in exactly the gesture that drives the feature. Every other input path in this app
 * enqueues to a sender thread for the same reason.
 *
 * <p>So: the UI thread does nothing but read the transform and post an immutable
 * {@link ViewportRect}. The reporter, the JNI call and the probe deadline all live on a
 * private {@link HandlerThread}, which makes the reporter single-threaded and lock-free.
 * The host's echo arrives on moonlight-common-c's async callback thread and is posted onto
 * the same handler, so it too is serialised with everything else.
 *
 * <p>{@link #onStreamStopped()} is the one place that blocks, and it has to: the terminal
 * uncrop must reach the wire before {@code LiStopConnection}, because sending after that is
 * a use-after-free rather than merely a lost packet. It is bounded, it happens once, and it
 * is on a teardown path that already does network I/O.
 */
public final class StreamViewportBinder
        implements ZoomTransformObserver, MeowViewportBridge.EchoListener {

    /**
     * How long {@link #onStreamStopped()} waits for the uncrop to reach the library. Long
     * enough to cover the ENet backpressure sleep the library may do (10 ms) many times
     * over; short enough that a wedged sender thread cannot hold up teardown.
     */
    static final long STOP_DRAIN_TIMEOUT_MS = 250L;

    private final View streamView;
    private final View parent;
    private final ViewportReporter reporter;
    private final Handler handler;
    private final HandlerThread thread;
    private final boolean ownsThread;

    // UI thread only. computeVisibleHostRect() reads View properties, which may only be
    // read there, so every caller of it -- and therefore of these -- is on the UI thread.
    // Nothing on the reporter's thread may touch them.
    private final Rect scratchVisible = new Rect();
    private final Point scratchOffset = new Point();
    private final float[] scratchWindow = new float[4];

    /**
     * Mirrors {@code reporter.isLive()} for the UI thread, so a gesture does not have to
     * touch reporter state that lives on another thread. Only ever written from the
     * reporter's thread; a stale read costs one wasted post, never correctness.
     */
    private volatile boolean live;

    /** Negotiated stream size, written on the UI thread before any rectangle is computed. */
    private volatile int streamWidth = 1;
    private volatile int streamHeight = 1;

    private final CursorFollowPlanner cursorPlanner = new CursorFollowPlanner();

    public StreamViewportBinder(View streamView, View parent) {
        this(streamView, parent, null, null);
    }

    /** Test seam: inject the reporter and a handler on the calling thread. */
    StreamViewportBinder(View streamView, View parent, ViewportReporter reporter,
                         Handler handler) {
        if (streamView == null || parent == null) {
            throw new IllegalArgumentException("streamView and parent are required");
        }
        this.streamView = streamView;
        this.parent = parent;

        if (handler != null) {
            this.thread = null;
            this.ownsThread = false;
            this.handler = handler;
        } else {
            this.thread = new HandlerThread("meow-viewport");
            this.thread.start();
            this.ownsThread = true;
            this.handler = new Handler(this.thread.getLooper());
        }

        this.reporter = reporter != null
                ? reporter
                : new ViewportReporter(new MeowViewportBridge(),
                        new HandlerDeadlineScheduler(this.handler));
    }

    public void setEnabled(final boolean enabled) {
        post(() -> reporter.setEnabled(enabled));
    }

    /**
     * @param streamWidth  negotiated stream width in host pixels ({@code Game.displayWidth})
     * @param streamHeight negotiated stream height in host pixels
     */
    public void onStreamStarted(final int streamWidth, final int streamHeight) {
        this.streamWidth = Math.max(1, streamWidth);
        this.streamHeight = Math.max(1, streamHeight);
        MeowViewportBridge.setEchoListener(this);
        post(() -> {
            reporter.onStreamStarted(streamWidth, streamHeight);
            live = reporter.isLive();
        });

        // The reset above is unconditional, and the transform may already be zoomed: with
        // rememberZoomPan on, setInitialZoomAndPan runs from a streamContainer.post() in
        // onCreate, hundreds of milliseconds before the connection is up, so its notify was
        // discarded. Read the live transform back now or the host stays uncropped until the
        // user next touches the screen.
        //
        // Posted directly rather than through onZoomTransformChanged(), because `live` is
        // written on the reporter's thread and has not caught up yet; the handler queue is
        // what guarantees this lands after the reset above.
        final ViewportRect restored = computeVisibleHostRect();
        if (restored != null) {
            post(() -> {
                reporter.onVisibleRectChanged(restored);
                live = reporter.isLive();
            });
        }
    }

    /**
     * Sends the terminal uncrop and blocks, bounded, until it has reached the library.
     *
     * <p>Must run while the connection is still up and strictly before
     * {@code LiStopConnection}: the protocol forbids sending a viewport after that, and
     * doing so races the ENet peer's destruction rather than merely losing a packet.
     *
     * <p><b>Do not call this on the UI thread.</b> {@code Game.stopConnection()} calls it
     * from the same worker thread that then calls {@code conn.stop()} — the one whose
     * comment says stop "may take a few hundred ms to do some network I/O… let it run in a
     * separate thread to keep things smooth for the UI". The ordering that matters (uncrop
     * before stop) is preserved by both running on that thread in sequence, and the UI
     * thread never waits.
     *
     * <p>Idempotent, and safe to call after {@link #release()}: the post simply fails once
     * the looper has quit.
     */
    public void onStreamStopped() {
        MeowViewportBridge.clearEchoListener(this);
        live = false;

        if (Looper.myLooper() == handler.getLooper()) {
            // Only reachable when the reporter was given the caller's own looper (tests, or
            // a future caller that wires it that way). Posting and then waiting on the same
            // thread would deadlock; run it here instead, which is the same work in the same
            // order.
            stopReporter();
            return;
        }

        final CountDownLatch drained = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                stopReporter();
            } finally {
                drained.countDown();
            }
        });
        if (posted) {
            try {
                if (!drained.await(STOP_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    LimeLog.warning("Viewport: uncrop did not drain before teardown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Releases the handler thread and the echo registration. Idempotent, safe from any
     * thread, and safe whether or not a stream ever connected.
     *
     * <p>This is separate from {@link #onStreamStopped()} because that only runs once
     * {@code connectionStarted()} has fired — {@code Game.stopConnection()} is guarded on
     * {@code connecting || connected}. A handshake that fails, or a user who backs out
     * while connecting, would otherwise leave this thread alive for the life of the
     * process and the echo listener pointing at a dead binder that holds the Activity.
     * On the flaky link this feature targets that is not a rare path. {@code Game.onDestroy}
     * calls this unconditionally.
     */
    public void release() {
        MeowViewportBridge.clearEchoListener(this);
        live = false;
        if (ownsThread && thread != null) {
            thread.quitSafely();
        }
    }

    /** Runs on the reporter's thread. */
    private void stopReporter() {
        reporter.onStreamStopped();
        // Also cleared on the UI thread before this was posted, but a transform update
        // already queued ahead of this one may have written it back to true in between.
        live = false;
    }

    @Override
    public void onZoomTransformChanged() {
        if (!live) {
            return;
        }
        final ViewportRect rect = computeVisibleHostRect();
        if (rect == null) {
            return;
        }
        post(() -> {
            reporter.onVisibleRectChanged(rect);
            live = reporter.isLive();
        });
    }

    /** The host's echo. Arrives on the library's async callback thread. */
    @Override
    public void onViewportApplied(final int x, final int y, final int width, final int height,
                                  final int desktopWidth, final int desktopHeight) {
        post(() -> {
            reporter.onViewportApplied(x, y, width, height, desktopWidth, desktopHeight);
            live = reporter.isLive();
        });
    }

    private void post(Runnable task) {
        // post() returns false once the looper is quitting, which is exactly the window
        // after onStreamStopped(). Dropping the work is correct there: there is no
        // connection left to report to.
        handler.post(task);
    }

    /**
     * The rectangle of the stream frame currently on screen, or null before the views have
     * been laid out.
     */
    ViewportRect computeVisibleHostRect() {
        int viewWidth = streamView.getWidth();
        int viewHeight = streamView.getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || parentWidth <= 0 || parentHeight <= 0) {
            return null;
        }

        float[] window = windowInParentCoords(parentWidth, parentHeight);

        return ViewportGeometry.visibleHostRect(
                streamView.getX(), streamView.getY(),
                viewWidth * streamView.getScaleX(), viewHeight * streamView.getScaleY(),
                window[0], window[1], window[2], window[3],
                streamWidth, streamHeight);
    }

    /**
     * The part of the parent the user can actually see, in the parent's own coordinates.
     *
     * <p>Usually the whole parent, but not always: in FILL scale mode {@code StreamContainer}
     * measures itself <em>larger</em> than the screen so the video fills it, and the overflow
     * is off-screen. Treating the parent box as visible there would report a viewport wider
     * than anything on screen, which is the opposite of what this feature is for.
     *
     * <p>Uses {@code getLocationInWindow} + decor size rather than {@code getGlobalVisibleRect}:
     * the latter is unreliable under Robolectric (always reports whole view) and can leave
     * 1-pixel slivers after zoom due to rounding when the decor viewport clips the parent.
     * The decor viewport is the window (0,0) .. (decorWidth,decorHeight) in window coords;
     * intersecting it with the parent's window rect gives the visible window in parent coords.
     * Works in both landscape and portrait: when parent height > width (portrait phone) the
     * same intersection allows a tall narrow crop.
     *
     * <p>The platform calls are separated from the arithmetic so the arithmetic can be tested.
     */
    private float[] windowInParentCoords(int parentWidth, int parentHeight) {
        int[] loc = new int[2];
        parent.getLocationInWindow(loc);
        View decor = parent.getRootView();
        int decorWidth = decor != null ? decor.getWidth() : 0;
        int decorHeight = decor != null ? decor.getHeight() : 0;
        if (decorWidth <= 0 || decorHeight <= 0) {
            // Decor not laid out yet (early onCreate or Robolectric fallback): fall back to
            // globalVisibleRect path which at least yields full parent, matching previous
            // fail-open behaviour.
            scratchVisible.setEmpty();
            scratchOffset.set(0, 0);
            boolean answered = parent.getGlobalVisibleRect(scratchVisible, scratchOffset);
            return windowFromGlobalVisibleRect(answered, scratchVisible, scratchOffset,
                    parentWidth, parentHeight, scratchWindow);
        }
        return windowFromLocationInWindow(loc[0], loc[1], parentWidth, parentHeight,
                decorWidth, decorHeight, scratchWindow);
    }

    /**
     * Converts what {@link View#getGlobalVisibleRect(Rect, Point)} reported into a window in
     * the parent's own coordinates, falling back to the whole parent box whenever the
     * platform cannot answer or answers with something degenerate.
     *
     * <p>{@code globalOffset} is documented as the offset to subtract from the global
     * rectangle to get view-local coordinates, which is the whole conversion.
     *
     * @param out a four-element buffer, filled with {left, top, right, bottom}
     */
    static float[] windowFromLocationInWindow(int locX, int locY,
                                                int parentWidth, int parentHeight,
                                                int decorWidth, int decorHeight,
                                                float[] out) {
        out[0] = 0f;
        out[1] = 0f;
        out[2] = parentWidth;
        out[3] = parentHeight;
        if (decorWidth <= 0 || decorHeight <= 0 || parentWidth <= 0 || parentHeight <= 0) {
            return out;
        }
        // Decor viewport in window coords is (0,0)-(decorWidth,decorHeight).
        // Parent rect in window coords is (locX,locY)-(locX+parentWidth, locY+parentHeight).
        // Intersection gives visible parent rect in window coords; convert to parent coords by subtracting loc.
        float winLeft = Math.max((float) locX, 0f);
        float winTop = Math.max((float) locY, 0f);
        float winRight = Math.min((float) locX + parentWidth, (float) decorWidth);
        float winBottom = Math.min((float) locY + parentHeight, (float) decorHeight);
        if (!(winRight > winLeft) || !(winBottom > winTop)) {
            return out;
        }
        float left = winLeft - locX;
        float top = winTop - locY;
        float right = winRight - locX;
        float bottom = winBottom - locY;
        if (!(right > left) || !(bottom > top)) {
            return out;
        }
        float clampedLeft = Math.max(0f, left);
        float clampedTop = Math.max(0f, top);
        float clampedRight = Math.min(parentWidth, right);
        float clampedBottom = Math.min(parentHeight, bottom);
        if (!(clampedRight > clampedLeft) || !(clampedBottom > clampedTop)) {
            return out;
        }
        out[0] = clampedLeft;
        out[1] = clampedTop;
        out[2] = clampedRight;
        out[3] = clampedBottom;
        return out;
    }

    static float[] windowFromGlobalVisibleRect(boolean answered, Rect globalVisible,
                                               Point globalOffset,
                                               int parentWidth, int parentHeight,
                                               float[] out) {
        out[0] = 0f;
        out[1] = 0f;
        out[2] = parentWidth;
        out[3] = parentHeight;
        if (!answered) {
            return out;
        }
        float left = globalVisible.left - globalOffset.x;
        float top = globalVisible.top - globalOffset.y;
        float right = globalVisible.right - globalOffset.x;
        float bottom = globalVisible.bottom - globalOffset.y;
        if (!(right > left) || !(bottom > top)) {
            return out;
        }
        float clampedLeft = Math.max(0f, left);
        float clampedTop = Math.max(0f, top);
        float clampedRight = Math.min(parentWidth, right);
        float clampedBottom = Math.min(parentHeight, bottom);
        if (!(clampedRight > clampedLeft) || !(clampedBottom > clampedTop)) {
            // The reported region lies entirely outside the parent box, which means the two
            // coordinate spaces disagree about something. Claim the whole parent rather than
            // an inverted window.
            return out;
        }
        out[0] = clampedLeft;
        out[1] = clampedTop;
        out[2] = clampedRight;
        out[3] = clampedBottom;
        return out;
    }

    /**
     * Cursor-follow entry point. Called on the UI thread from {@code Game.updateMousePosition}
     * and from the absolute-touch path. If the cursor is within the edge margin or outside
     * the visible crop, pans the crop so the cursor sits on the margin line.
     *
     * <p>Uses {@link CursorFollowPlanner} (12% edge margin, pure Java) and
     * {@link ViewportGeometry#hostPointFromView} / {@link ViewportGeometry#viewDeltaForHostDelta}
     * for the coordinate math. The actual pan goes through {@link PanZoomHandler#panBy},
     * which calls {@code constrainToBounds} and notifies this binder via the existing
     * {@link com.limelight.meow.viewport.ZoomTransformObserver} path on the HandlerThread.
     *
     * @param viewX      cursor X in parent (streamContainer) pixels
     * @param viewY      cursor Y in parent pixels
     * @param panHandler the handler that owns the transform
     * @return true if a pan was performed
     */
    public boolean handleCursorViewPosition(float viewX, float viewY, PanZoomHandler panHandler) {
        if (!live || panHandler == null) {
            return false;
        }
        ViewportRect visible = computeVisibleHostRect();
        if (visible == null) {
            return false;
        }
        float childX = streamView.getX();
        float childY = streamView.getY();
        float childW = streamView.getWidth() * streamView.getScaleX();
        float childH = streamView.getHeight() * streamView.getScaleY();
        if (!(childW > 0f) || !(childH > 0f)) {
            return false;
        }
        int[] hostPt = ViewportGeometry.hostPointFromView(viewX, viewY, childX, childY, childW, childH,
                streamWidth, streamHeight);
        int cursorX = hostPt[0];
        int cursorY = hostPt[1];

        // Bounds for the planner: the desktop content box if known, else the full stream.
        int boundsX = 0;
        int boundsY = 0;
        int boundsW = streamWidth;
        int boundsH = streamHeight;
        ViewportReferenceFrame frame = reporter.referenceFrame();
        if (frame != null) {
            boundsX = frame.contentX;
            boundsY = frame.contentY;
            boundsW = frame.contentWidth;
            boundsH = frame.contentHeight;
        }

        CursorFollowPlan plan = cursorPlanner.plan(visible, cursorX, cursorY, boundsX, boundsY, boundsW, boundsH);
        if (!plan.isMove()) {
            return false;
        }
        float dxView = ViewportGeometry.viewDeltaForHostDelta(plan.dx, childW, streamWidth);
        float dyView = ViewportGeometry.viewDeltaForHostDelta(plan.dy, childH, streamHeight);
        if (dxView == 0f && dyView == 0f) {
            return false;
        }
        panHandler.panBy(dxView, dyView);
        return true;
    }
}
