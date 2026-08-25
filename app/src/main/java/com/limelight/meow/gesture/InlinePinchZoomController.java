package com.limelight.meow.gesture;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * Makes pinch-to-zoom work inline, without the modal Pan/Zoom toggle.
 *
 * <p>Before this existed, zooming meant opening the game menu (or tapping the overlay
 * button), flipping {@code Game.isPanZoomMode}, zooming, and flipping it back — and
 * while the mode was on, the mouse was gone. Chrome Remote Desktop has no such mode:
 * one finger moves the cursor, two fingers translating scroll, two fingers changing
 * distance zoom, all at once. This class provides the missing third case.
 *
 * <p>Routing, per gesture:
 * <ul>
 *   <li>fewer than two fingers &rarr; not our business, pass through</li>
 *   <li>three or more fingers &rarr; one of the existing multi-finger gestures, pass through
 *       and stay out of the way for the rest of the gesture. A third finger arriving
 *       <em>during</em> a zoom <b>ends</b> the zoom: the recognisers run first (see the
 *       ordering precondition below) and every one of their exits dispatches a synthetic
 *       {@code ACTION_CANCEL}, which reaches {@link #handle} and clears the latch. Three
 *       fingers during a zoom therefore open the keyboard, which is what a user pressing
 *       three fingers down is asking for.
 *       <p>The pause-and-rebaseline path below (see {@code needsRebaseline}) survives only
 *       where the multi-finger recognisers are skipped -- mouse mode 4, "touch mouse
 *       disabled", where the touch contexts do not exist. It is kept because that mode is
 *       precisely where zooming the local view is the only thing touch is still for.</li>
 *   <li>two fingers &rarr; ask {@link TwoFingerGestureArbiter}. SCROLL passes through to the
 *       existing trackpad handling; ZOOM is consumed and drives the {@link ZoomTarget}.</li>
 * </ul>
 *
 * <p>Once a gesture is latched to ZOOM it is consumed until every finger is up, even after
 * the second finger leaves. That last part is deliberate: the finger still on the glass
 * would otherwise resume driving the cursor and jerk it across the screen. The existing
 * trackpad code guards the same hazard with its own post-scroll transition timeout.
 *
 * <p><b>Ordering precondition.</b> Latching ZOOM is a <em>consuming</em> decision: from
 * that point on every {@code ACTION_POINTER_DOWN} is swallowed, so anything downstream of
 * this hook never sees a third finger land. The caller must therefore offer every
 * {@code pointerCount > 2} event to the 3/4/5 finger recognisers <em>before</em> this
 * controller, which is exactly what {@code Game.handleMotionEvent} does <em>in every mode
 * where the touch contexts exist</em>. The one exception is mouse mode 4, where the
 * recognisers are skipped entirely and this hook does get first refusal again -- harmless,
 * because 3/4/5 finger gestures have never worked in that mode, upstream included. That is safe for
 * zoom because those recognisers only act on {@code ACTION_POINTER_DOWN} /
 * {@code ACTION_POINTER_UP} / {@code ACTION_UP}, and zoom is driven entirely by
 * {@code ACTION_MOVE}, which they never handle. Reversing the two is the bug this
 * ordering exists to prevent -- see {@code docs/meow/TOUCHPOINTS.md}.
 *
 * <p>The event routing lives in {@link #handle} which takes plain numbers, so it is unit
 * tested on the JVM without fabricating {@link MotionEvent}s.
 */
public final class InlinePinchZoomController {

    /**
     * Where a resolved pinch/pan goes. An interface rather than a direct reference so the
     * routing can be tested without a View; the real implementation is
     * {@code com.limelight.utils.PanZoomHandler}, which owns the single copy of the zoom
     * transform used by both this path and the explicit Pan/Zoom mode.
     */
    public interface ZoomTarget {
        /** Scale by {@code scaleDelta} about the given focus point, in view coordinates. */
        void pinchBy(float scaleDelta, float focusX, float focusY);

        /** Translate the zoomed view by the given pixel delta. */
        void panBy(float dx, float dy);
    }

    /**
     * Ceiling on the slop we derive from the platform, and the one number in here that is
     * load bearing rather than a matter of taste.
     *
     * <p>A gesture is ambiguous while it starts, so the touch contexts see the pointers go
     * down before we know it is a pinch. They begin emitting scroll to the host as soon as
     * they confirm a move, and their thresholds are fixed pixel counts that do not scale
     * with display density: {@code RelativeTouchContext.TAP_MOVEMENT_THRESHOLD} is 20px
     * (its {@code TAP_DISTANCE_THRESHOLD} is 25px) and {@code TrackpadContext}'s is 30px.
     * We must therefore latch below 20px of span change, or a pinch that starts from rest
     * briefly scrolls the remote desktop before we take over. See
     * {@link TwoFingerGestureArbiter#DEFAULT_SPAN_SLOP_PX} for the case this does
     * <em>not</em> cover — the contexts' accumulated-path test, which no displacement
     * bound can close.
     *
     * <p>{@code ViewConfiguration#getScaledTouchSlop()} does scale with density: 8dp is
     * 26px on the Poco X7 Pro (520dpi) and 32px at 640dpi, which would break the invariant
     * outright. Hence the cap.
     */
    private static final float MAX_SLOP_PX = 18f;

    private final TwoFingerGestureArbiter arbiter;
    private final ZoomTarget target;
    private final Runnable onZoomBegin;
    private final Runnable onZoomEnd;

    private boolean zooming;
    private boolean needsRebaseline;

    /**
     * @param onZoomBegin run once when a gesture latches to zoom, so the caller can cancel
     *                    whatever the in-flight touch contexts were about to send
     * @param onZoomEnd   run when a zooming gesture ends
     */
    public InlinePinchZoomController(Context context, ZoomTarget target,
                                     Runnable onZoomBegin, Runnable onZoomEnd) {
        this(new TwoFingerGestureArbiter(
                        touchSlop(context), touchSlop(context), TwoFingerGestureArbiter.DEFAULT_ZOOM_BIAS),
                target, onZoomBegin, onZoomEnd);
    }

    public InlinePinchZoomController(TwoFingerGestureArbiter arbiter, ZoomTarget target,
                                     Runnable onZoomBegin, Runnable onZoomEnd) {
        if (arbiter == null || target == null || onZoomBegin == null || onZoomEnd == null) {
            throw new IllegalArgumentException("arbiter, target and callbacks are required");
        }
        this.arbiter = arbiter;
        this.target = target;
        this.onZoomBegin = onZoomBegin;
        this.onZoomEnd = onZoomEnd;
    }

    /**
     * Turns the platform's touch slop into the slop we actually arbitrate with. Separated
     * from {@link #touchSlop(Context)} and made {@code static} so the cap can be pinned by
     * a test at densities this machine will never report — which is the whole point of it.
     *
     * @param platformSlopPx {@code ViewConfiguration#getScaledTouchSlop()}, or a
     *                       non-positive value if it is unavailable
     */
    static float effectiveSlopPx(float platformSlopPx) {
        float base = platformSlopPx > 0f ? platformSlopPx : TwoFingerGestureArbiter.DEFAULT_SPAN_SLOP_PX;
        return Math.min(base, MAX_SLOP_PX);
    }

    private static float touchSlop(Context context) {
        if (context == null) {
            return effectiveSlopPx(0f);
        }
        return effectiveSlopPx(ViewConfiguration.get(context).getScaledTouchSlop());
    }

    /** True while a gesture is being consumed as a zoom. */
    public boolean isZooming() {
        return zooming;
    }

    /** The arbiter this controller was built with. Exposed so the slop actually used in
     *  production — which is {@link #MAX_SLOP_PX}-capped, not the arbiter's default — can
     *  be asserted by tests. */
    public TwoFingerGestureArbiter getArbiter() {
        return arbiter;
    }

    /**
     * @return true if the event was consumed as an inline zoom and the caller must not
     *         process it any further
     */
    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        if (pointerCount >= 2) {
            return handle(event.getActionMasked(), pointerCount,
                    event.getX(0), event.getY(0), event.getX(1), event.getY(1));
        }
        return handle(event.getActionMasked(), pointerCount, 0f, 0f, 0f, 0f);
    }

    /**
     * Drop any latched zoom without running {@link #onZoomEnd}. For callers that tear the
     * gesture surface out from under an in-flight gesture — {@code Game.applyMouseMode}
     * rebuilds the touch contexts — where no further events from the old gesture will
     * arrive to clear the latch naturally.
     */
    public void reset() {
        zooming = false;
        needsRebaseline = false;
        arbiter.reset();
    }

    /**
     * Android-free core of {@link #onTouchEvent}. The {@code actionMasked} values are
     * {@link MotionEvent}'s compile time constants, so this stays callable from a plain
     * JVM unit test.
     */
    public boolean handle(int actionMasked, int pointerCount,
                          float x0, float y0, float x1, float y1) {
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                zooming = false;
                needsRebaseline = false;
                arbiter.reset();
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (zooming) {
                    // A third finger landing mid-zoom is not a gesture we recognise. Hold
                    // still rather than guessing, and re-baseline before moving again so
                    // the view cannot jump when we are back to two fingers.
                    needsRebaseline = true;
                    return true;
                }
                if (pointerCount == 2) {
                    arbiter.beginTwoFinger(x0, y0, x1, y1);
                } else {
                    arbiter.disqualify();
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (!zooming) {
                    if (pointerCount != 2) {
                        return false;
                    }
                    if (arbiter.update(x0, y0, x1, y1) != TwoFingerGestureArbiter.Decision.ZOOM) {
                        return false;
                    }
                    zooming = true;
                    needsRebaseline = false;
                    onZoomBegin.run();
                    applyCurrentFrame();
                    return true;
                }
                if (pointerCount == 2) {
                    if (needsRebaseline) {
                        // Fingers changed since the last frame we acted on. Take the new
                        // positions as the reference and emit nothing for this frame.
                        arbiter.beginTwoFinger(x0, y0, x1, y1);
                        needsRebaseline = false;
                    } else {
                        arbiter.update(x0, y0, x1, y1);
                        applyCurrentFrame();
                    }
                }
                // With a single finger left we keep swallowing the gesture but hold still,
                // so the lingering finger cannot jerk the cursor on its way up.
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (zooming) {
                    needsRebaseline = true;
                    return true;
                }
                if (pointerCount == 2) {
                    // Back to one finger without ever deciding; stay eligible to re-arm.
                    arbiter.endTwoFinger();
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasZooming = zooming;
                zooming = false;
                needsRebaseline = false;
                arbiter.reset();
                if (wasZooming) {
                    onZoomEnd.run();
                    return true;
                }
                return false;
            }

            default:
                return zooming;
        }
    }

    private void applyCurrentFrame() {
        float scaleDelta = arbiter.getScaleDelta();
        if (scaleDelta != 1f) {
            target.pinchBy(scaleDelta, arbiter.getFocusX(), arbiter.getFocusY());
        }
        float dx = arbiter.getFocusDeltaX();
        float dy = arbiter.getFocusDeltaY();
        if (dx != 0f || dy != 0f) {
            target.panBy(dx, dy);
        }
    }
}
