package com.limelight.meow.viewport;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;

import com.limelight.LimeLog;
import com.limelight.meow.bitrate.BitrateSession;
import com.limelight.meow.cursor.CursorFollowController;
import com.limelight.meow.gesture.InlinePinchZoomController;

import java.util.ArrayList;
import java.util.List;
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
public final class StreamViewportBinder implements ZoomTransformObserver,
        MeowViewportBridge.EchoListener, CursorFollowController.ViewportView {

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
    private final int[] scratchLocation = new int[2];

    /**
     * Mirrors {@code reporter.isLive()} for the UI thread, so a gesture does not have to
     * touch reporter state that lives on another thread. Only ever written from the
     * reporter's thread; a stale read costs one wasted post, never correctness.
     */
    private volatile boolean live;

    /**
     * True between {@link #onStreamStarted} and {@link #onStreamStopped}/{@link #release}.
     *
     * <p>Separate from {@link #live} on purpose. {@code live} answers "is the host listening
     * to our viewport messages", which is a property of the <em>host</em>; cursor-follow only
     * needs to know that the negotiated stream size is known, which is a property of
     * <em>this</em> client. Conflating the two is what made cursor-follow silently dead
     * against every host that does not implement the viewport extension.
     */
    private volatile boolean streamStarted;

    /**
     * Mirror of {@code reporter.referenceFrame()} for the UI thread. The reporter's own field
     * is written on the reporter's thread and read nowhere else; cursor-follow runs on the UI
     * thread, so it reads this copy instead of reaching across.
     */
    private volatile ViewportReferenceFrame contentFrame;

    /** Negotiated stream size, written on the UI thread before any rectangle is computed. */
    private volatile int streamWidth = 1;
    private volatile int streamHeight = 1;

    /** Follows the host cursor while zoomed. Null until {@link #setCursorFollow}. */
    private CursorFollowController cursorFollow;

    /** Automatic bitrate. Null until {@link #setBitrateSession}. */
    private BitrateSession bitrateSession;

    /** Once per stream: the host answered a probe, so it runs the meow extensions. */
    private final List<Runnable> hostProvenTasks = new ArrayList<>();
    private boolean hostProvenAnnounced;

    /** Posts the host's echo to the UI thread, where the compositor lives. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * The user's logical zoom over the reference frame ({@code PanZoomHandler}). Once the host
     * crops, the stream view carries the <em>presented</em> transform instead, so the visible
     * rectangle must never be read back off the view. Null only in tests that drive the view
     * directly and never compose. UI thread.
     */
    private InlinePinchZoomController.ZoomTarget transformSource;

    /** Composes the logical transform with the host's crop. UI thread; null until wired. */
    private ViewportCompositor compositor;

    /** Scratch for {@link #logicalTransform}. UI thread. */
    private final float[] scratchTransform = new float[3];

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
     * Wires the user's logical transform and starts composing it with the host's crop. Call
     * once, on the UI thread, before the stream starts.
     */
    public void setTransformSource(InlinePinchZoomController.ZoomTarget source) {
        setTransformSource(source, source != null ? new ViewportCompositor(streamView, source) : null);
    }

    /** Test seam: inject the compositor. */
    void setTransformSource(InlinePinchZoomController.ZoomTarget source,
                            ViewportCompositor compositor) {
        this.transformSource = source;
        this.compositor = compositor;
    }

    /**
     * Probe the host for the meow extensions even with crop reporting off, so features that
     * are gated on a proven host still find out. Before the stream starts.
     */
    public void setCapabilityProbe(final boolean probe) {
        post(() -> reporter.setCapabilityProbe(probe));
    }

    /**
     * Runs {@code task} on the reporter's thread the first time each stream's host proves it
     * is a meow host (its viewport echo). That thread may block on ENet, which is what the
     * cursor subscription and receiver reports need. Before the stream starts.
     */
    public void addHostProvenTask(Runnable task) {
        synchronized (hostProvenTasks) {
            hostProvenTasks.add(task);
        }
    }

    /**
     * Attaches the cursor follower: the binder drives its lifecycle, hands it the desktop
     * extent from each echo and subscribes to host cursor reports once the host is proven.
     * UI thread, before the stream starts.
     */
    public void setCursorFollow(CursorFollowController controller) {
        this.cursorFollow = controller;
        if (controller != null) {
            // Insets reach the stream container whenever the soft keyboard opens or closes;
            // pass them on untouched and re-check what is visible.
            parent.setOnApplyWindowInsetsListener((v, insets) -> {
                onVisibleAreaChanged();
                return v.onApplyWindowInsets(insets);
            });
        }
        if (controller != null && controller.isEnabled()) {
            addHostProvenTask(controller.subscribeTask());
        }
    }

    /**
     * Attaches automatic bitrate: the binder drives its lifecycle and starts its receiver
     * reports once the host is proven. UI thread, before the stream starts.
     */
    public void setBitrateSession(BitrateSession session) {
        this.bitrateSession = session;
        if (session != null) {
            addHostProvenTask(session.startTask());
        }
    }

    /** The compositor, or null when no transform source is wired. UI thread. */
    public ViewportCompositor compositor() {
        return compositor;
    }

    /**
     * @param streamWidth  negotiated stream width in host pixels ({@code Game.displayWidth})
     * @param streamHeight negotiated stream height in host pixels
     */
    public void onStreamStarted(final int streamWidth, final int streamHeight) {
        this.streamWidth = Math.max(1, streamWidth);
        this.streamHeight = Math.max(1, streamHeight);
        this.streamStarted = true;
        this.contentFrame = null;
        if (compositor != null) {
            compositor.onStreamStarted(this.streamWidth, this.streamHeight);
        }
        if (cursorFollow != null) {
            cursorFollow.onStreamStarted(this.streamWidth, this.streamHeight);
        }
        if (bitrateSession != null) {
            bitrateSession.onStreamStarted();
        }
        MeowViewportBridge.setEchoListener(this);
        post(() -> {
            hostProvenAnnounced = false;
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
        streamStarted = false;
        final ViewportCompositor presenting = compositor;
        if (presenting != null) {
            mainHandler.post(presenting::onStreamStopped);
        }
        if (cursorFollow != null) {
            cursorFollow.onStreamStopped();
        }
        if (bitrateSession != null) {
            // Blocks, bounded, so no receiver report is in flight at LiStopConnection.
            bitrateSession.onStreamStopped();
        }

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
        streamStarted = false;
        if (cursorFollow != null) {
            cursorFollow.onStreamStopped();
        }
        if (bitrateSession != null) {
            bitrateSession.release();
        }
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
        // PanZoomHandler has just written the logical transform to the view; replace it with
        // the presented one before anything draws. Unconditional: composition is about what
        // the decoder shows, not about whether we are still reporting to the host.
        if (compositor != null) {
            compositor.onLogicalTransformChanged();
        }
        if (cursorFollow != null) {
            cursorFollow.onViewTransformChanged();
        }
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
                                  final int desktopWidth, final int desktopHeight,
                                  final int frameIndex) {
        post(() -> {
            boolean accepted =
                    reporter.onViewportApplied(x, y, width, height, desktopWidth, desktopHeight);
            live = reporter.isLive();
            contentFrame = reporter.referenceFrame();
            if (accepted) {
                // Composed from what the reporter validated, so a rectangle outside the
                // stream frame never reaches the view: appliedRect() is null for it and the
                // compositor keeps what it has.
                forwardCrop(reporter.appliedRect(), reporter.desktopWidth(),
                        reporter.desktopHeight(), frameIndex);
                announceHostProven();
            }
        });
    }

    /** Reporter thread: run the host-proven tasks once per stream. */
    private void announceHostProven() {
        if (hostProvenAnnounced || !reporter.isHostProven()) {
            return;
        }
        hostProvenAnnounced = true;
        Runnable[] tasks;
        synchronized (hostProvenTasks) {
            tasks = hostProvenTasks.toArray(new Runnable[0]);
        }
        for (Runnable task : tasks) {
            try {
                task.run();
            } catch (RuntimeException e) {
                LimeLog.warning("Viewport: host-proven task failed: " + e);
            }
        }
    }

    /** Reporter thread: hand the applied crop to the compositor on the UI thread. */
    private void forwardCrop(final ViewportRect applied, final int desktopWidth,
                             final int desktopHeight, final int frameIndex) {
        final ViewportCompositor presenting = compositor;
        final CursorFollowController follower = cursorFollow;
        if ((presenting == null || applied == null) && follower == null) {
            return;
        }
        mainHandler.post(() -> {
            if (!streamStarted) {
                return;
            }
            if (presenting != null && applied != null) {
                presenting.onCropApplied(applied, desktopWidth, desktopHeight, frameIndex);
            }
            if (follower != null) {
                follower.onDesktopExtent(desktopWidth, desktopHeight);
            }
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
        float[] transform = logicalTransform();

        return ViewportGeometry.visibleHostRect(
                transform[1], transform[2],
                viewWidth * transform[0], viewHeight * transform[0],
                window[0], window[1], window[2], window[3],
                streamWidth, streamHeight);
    }

    /**
     * {scale, x, y} of the reference frame in the parent under the user's logical transform.
     * Read from the transform source when wired; the view's own properties are only the
     * logical transform while nothing composes onto them.
     */
    private float[] logicalTransform() {
        InlinePinchZoomController.ZoomTarget source = transformSource;
        if (source != null) {
            scratchTransform[0] = source.getScaleFactor();
            scratchTransform[1] = source.getChildX();
            scratchTransform[2] = source.getChildY();
        } else {
            scratchTransform[0] = streamView.getScaleX();
            scratchTransform[1] = streamView.getX();
            scratchTransform[2] = streamView.getY();
        }
        return scratchTransform;
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
        int[] loc = scratchLocation;
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
        // The soft keyboard covers the bottom of the window without resizing it here (the
        // stream window is fullscreen): what is under it is not visible.
        return windowFromLocationInWindow(loc[0], loc[1], parentWidth, parentHeight,
                decorWidth, Math.max(1, decorHeight - imeBottomInset(decor)), scratchWindow);
    }

    /** Height of the soft keyboard over the window, or 0. No allocation (a single type). */
    private static int imeBottomInset(View decor) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || decor == null) {
            return 0;
        }
        android.view.WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) {
            return 0;
        }
        return insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
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

    // ---- CursorFollowController.ViewportView ---------------------------------------------

    /**
     * The visible part of the reference frame as {x, y, width, height}, from the logical
     * transform and the window the parent is seen through. UI thread, no allocation.
     */
    @Override
    public boolean visibleReferenceRect(float[] out) {
        int viewWidth = streamView.getWidth();
        int viewHeight = streamView.getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        if (!streamStarted || viewWidth <= 0 || viewHeight <= 0
                || parentWidth <= 0 || parentHeight <= 0) {
            return false;
        }
        float[] window = windowInParentCoords(parentWidth, parentHeight);
        float[] transform = logicalTransform();
        float childWidth = viewWidth * transform[0];
        float childHeight = viewHeight * transform[0];
        if (!(childWidth > 0f) || !(childHeight > 0f)) {
            return false;
        }
        float left = Math.max(window[0], transform[1]);
        float top = Math.max(window[1], transform[2]);
        float right = Math.min(window[2], transform[1] + childWidth);
        float bottom = Math.min(window[3], transform[2] + childHeight);
        if (!(right > left) || !(bottom > top)) {
            return false;
        }
        out[0] = (left - transform[1]) / childWidth * streamWidth;
        out[1] = (top - transform[2]) / childHeight * streamHeight;
        out[2] = (right - left) / childWidth * streamWidth;
        out[3] = (bottom - top) / childHeight * streamHeight;
        return true;
    }

    /** The desktop inside the frame, {left, top, right, bottom}; the whole frame if unknown. */
    @Override
    public void contentBounds(float[] out) {
        ViewportReferenceFrame frame = contentFrame;
        if (frame != null) {
            out[0] = frame.contentX;
            out[1] = frame.contentY;
            out[2] = frame.contentX + frame.contentWidth;
            out[3] = frame.contentY + frame.contentHeight;
        } else {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = streamWidth;
            out[3] = streamHeight;
        }
    }

    /** {originX, originY, parentPxPerReferenceX, parentPxPerReferenceY, zoom}. */
    @Override
    public boolean transform(float[] out) {
        float[] t = logicalTransform();
        out[0] = t[1];
        out[1] = t[2];
        out[2] = streamView.getWidth() * t[0] / streamWidth;
        out[3] = streamView.getHeight() * t[0] / streamHeight;
        out[4] = t[0];
        return out[2] > 0f && out[3] > 0f;
    }

    /**
     * The window stopped matching what the parent shows (the soft keyboard opened or
     * closed): report the new visible rectangle and bring the cursor back above it. Any
     * thread; the work runs on the UI thread.
     */
    void onVisibleAreaChanged() {
        mainHandler.post(() -> {
            if (!streamStarted) {
                return;
            }
            onZoomTransformChanged();
            if (cursorFollow != null) {
                cursorFollow.ensureVisible();
            }
        });
    }
}
