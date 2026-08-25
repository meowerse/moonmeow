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
    private final float[] scratchWindow = new float[4];

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

    public void setEnabled(boolean enabled) {
        reporter.setEnabled(enabled, now());
    }

    /**
     * @param hostWidth  width of the frame the host is encoding, in host pixels
     * @param hostHeight height of the frame the host is encoding, in host pixels
     */
    public void onStreamStarted(int hostWidth, int hostHeight) {
        reporter.onStreamStarted(hostWidth, hostHeight, now());
        // The reset above is unconditional, and the transform may already be zoomed: with
        // rememberZoomPan on, setInitialZoomAndPan runs from a streamContainer.post() in
        // onCreate, hundreds of milliseconds before the connection is up, so its notify was
        // discarded by the isActive() guard. Read the live transform back now or the host
        // stays uncropped until the user next touches the screen.
        onZoomTransformChanged();
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
        out[0] = Math.max(0f, left);
        out[1] = Math.max(0f, top);
        out[2] = Math.min(parentWidth, right);
        out[3] = Math.min(parentHeight, bottom);
        return out;
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }
}
