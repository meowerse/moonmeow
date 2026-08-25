package com.limelight.meow.viewport;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;

/**
 * Wires the local zoom/pan transform to {@link ViewportReporter}.
 *
 * <p>This is the only class in the feature that touches Android views, and it is kept
 * deliberately thin: read four numbers off the stream view, work out the window they are
 * seen through, hand both to {@link ViewportGeometry}. Every decision — what counts as a
 * change, how often to send, what to do when the host says "unsupported" — lives in the
 * plain-Java classes beside it.
 *
 * <p>Everything here runs on the UI thread: {@code PanZoomHandler} is driven from touch
 * dispatch, {@code connectionStarted()} hops to the UI thread before calling in, and
 * {@code stopConnection()} is already on it. The {@link Handler} passed in must be bound to
 * that same thread.
 */
public final class StreamViewportBinder implements ZoomTransformObserver {

    private final View streamView;
    private final View parent;
    private final ViewportReporter reporter;

    private final Rect scratchVisible = new Rect();
    private final Point scratchOffset = new Point();

    public StreamViewportBinder(View streamView, View parent, Handler handler) {
        this(streamView, parent,
                new ViewportReporter(new MeowViewportBridge(), new HandlerSettleScheduler(handler)));
    }

    StreamViewportBinder(View streamView, View parent, ViewportReporter reporter) {
        if (streamView == null || parent == null || reporter == null) {
            throw new IllegalArgumentException("streamView, parent and reporter are required");
        }
        this.streamView = streamView;
        this.parent = parent;
        this.reporter = reporter;
    }

    public ViewportReporter reporter() {
        return reporter;
    }

    public void setEnabled(boolean enabled) {
        reporter.setEnabled(enabled, now());
    }

    /**
     * @param hostWidth  width of the frame the host is encoding, in host pixels
     * @param hostHeight height of the frame the host is encoding, in host pixels
     */
    public void onStreamStarted(int hostWidth, int hostHeight) {
        reporter.onStreamStarted(hostWidth, hostHeight, now());
    }

    /** Must run while the connection is still up — the protocol forbids sending after stop. */
    public void onStreamStopped() {
        reporter.onStreamStopped(now());
    }

    @Override
    public void onZoomTransformChanged() {
        if (!reporter.isActive()) {
            return;
        }
        ViewportRect rect = computeVisibleHostRect();
        if (rect != null) {
            reporter.onVisibleRectChanged(rect, now());
        }
    }

    /**
     * The rectangle of the host frame currently on screen, or null before the views have been
     * laid out.
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
                reporter.hostWidth(), reporter.hostHeight());
    }

    /**
     * The part of the parent the user can actually see, in the parent's own coordinates.
     *
     * <p>Usually the whole parent, but not always: in FILL scale mode {@code StreamContainer}
     * measures itself <em>larger</em> than the screen so the video fills it, and the overflow
     * is off-screen. Treating the parent box as visible there would report a viewport wider
     * than anything on screen, which is the opposite of what this feature is for. Falls back
     * to the full parent box whenever the platform cannot answer.
     */
    private float[] windowInParentCoords(int parentWidth, int parentHeight) {
        float[] whole = { 0f, 0f, parentWidth, parentHeight };
        scratchVisible.setEmpty();
        scratchOffset.set(0, 0);
        if (!parent.getGlobalVisibleRect(scratchVisible, scratchOffset)) {
            return whole;
        }
        float left = scratchVisible.left - scratchOffset.x;
        float top = scratchVisible.top - scratchOffset.y;
        float right = scratchVisible.right - scratchOffset.x;
        float bottom = scratchVisible.bottom - scratchOffset.y;
        if (!(right > left) || !(bottom > top)) {
            return whole;
        }
        return new float[] {
                Math.max(0f, left),
                Math.max(0f, top),
                Math.min(parentWidth, right),
                Math.min(parentHeight, bottom)
        };
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }
}
