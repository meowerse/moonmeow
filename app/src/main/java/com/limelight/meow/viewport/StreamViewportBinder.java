package com.limelight.meow.viewport;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;

import com.limelight.LimeLog;

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
     * Must run while the connection is still up — the protocol forbids sending a viewport
     * after {@code LiStopConnection}, and doing so races the ENet peer's destruction.
     * Blocks, bounded, until the uncrop has been handed to the library.
     */
    public void onStreamStopped() {
        MeowViewportBridge.setEchoListener(null);
        live = false;

        if (Looper.myLooper() == handler.getLooper()) {
            // Only reachable when the reporter was given the caller's own looper (tests, or
            // a future caller that wires it that way). Posting and then waiting on the same
            // thread would deadlock; run it here instead, which is the same work in the same
            // order.
            reporter.onStreamStopped();
            quitOwnThread();
            return;
        }

        final CountDownLatch drained = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                reporter.onStreamStopped();
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

        quitOwnThread();
    }

    private void quitOwnThread() {
        if (ownsThread && thread != null) {
            thread.quitSafely();
        }
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

    /** Visible for tests. */
    ViewportReporter reporter() {
        return reporter;
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
     * <p>The platform call is separated from the arithmetic so the arithmetic can be tested:
     * Robolectric has no real window, so {@code getGlobalVisibleRect} there reports the whole
     * view and a test driven through it would pass without ever exercising the clipping.
     */
    private float[] windowInParentCoords(int parentWidth, int parentHeight) {
        scratchVisible.setEmpty();
        scratchOffset.set(0, 0);
        boolean answered = parent.getGlobalVisibleRect(scratchVisible, scratchOffset);
        return windowFromGlobalVisibleRect(answered, scratchVisible, scratchOffset,
                parentWidth, parentHeight, scratchWindow);
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
}
