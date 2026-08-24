package com.limelight.meow.gesture;

/**
 * Decides whether a two finger gesture is a <em>scroll</em> (both fingers translating
 * together) or a <em>pinch</em> (the fingers changing distance from each other).
 *
 * <p>The two gestures are genuinely ambiguous when they start: every real pinch drags
 * its centroid around a little, and every real two finger scroll lets the fingers
 * drift apart a little. We therefore watch both quantities from the moment the second
 * finger lands and commit to whichever crosses its slop first; if both cross in the
 * same frame, the larger one wins. Once committed the decision is <em>latched</em>
 * for the rest of the gesture, because flip-flopping between scrolling the remote
 * desktop and zooming the local view is far worse than picking the wrong one once.
 *
 * <p>This class is deliberately plain Java with no Android dependencies: gesture
 * disambiguation is a pure function over pointer positions and is unit tested
 * directly on the JVM (see {@code CLAUDE.md} §5.3).
 */
public final class TwoFingerGestureArbiter {

    public enum Decision {
        /** Not tracking a two finger gesture (yet, or any more). */
        INACTIVE,
        /** Two fingers are down but neither interpretation has crossed its slop. */
        UNDECIDED,
        /** Committed: the fingers are translating together. Leave it to the caller's
         *  existing scroll handling. */
        SCROLL,
        /** Committed: the fingers are changing distance. Drive zoom/pan. */
        ZOOM
    }

    /**
     * Span change (in pixels) that must accumulate before a gesture can be read as a pinch.
     *
     * <p>This value has an invariant attached to it: it must stay below the point at which
     * the touch contexts confirm a move and start emitting scroll to the host, which is
     * 20px for {@code RelativeTouchContext} and 30px for {@code TrackpadContext}. A
     * symmetric pinch moves each finger by half the span change and an anchored pinch
     * moves one finger by the whole span change, so latching below 20px of span change is
     * what stops <em>a pinch that starts from rest</em> from briefly scrolling the remote
     * desktop on its way in.
     * {@code InlinePinchZoomController} enforces the ceiling; see its {@code MAX_SLOP_PX}.
     *
     * <p>Be precise about what that does <em>not</em> cover. The contexts' second move
     * test is a path integral, not a displacement test — {@code RelativeTouchContext}
     * accumulates {@code distanceMoved} against a 25px {@code TAP_DISTANCE_THRESHOLD}
     * (and {@code TrackpadContext} against 30px). This arbiter bounds displacement while
     * undecided, and no displacement bound bounds a path length: a gesture that wanders
     * with the fingers together and only then pinches can still cross the contexts'
     * accumulated threshold first, and leak one scroll frame before the latch. Closing
     * that completely means withholding two finger events while undecided and replaying
     * them on a SCROLL latch, which is a much larger change than the residual leak
     * justifies. Lowering this constant cannot close it.
     *
     * <p>Note that the slop only decides <em>when</em> we commit, not <em>what</em> we
     * commit to — that is {@link #classify}'s dominance rule — so erring low costs
     * accuracy far less than erring high costs stray input.
     */
    public static final float DEFAULT_SPAN_SLOP_PX = 18f;

    /** Centroid travel (in pixels) that must accumulate before a gesture reads as a scroll. */
    public static final float DEFAULT_TRANSLATION_SLOP_PX = 18f;

    /**
     * Tie break weight applied to the translation when both slops are crossed in the
     * same frame. 1.0 is neutral; values above 1 favour scroll, below 1 favour zoom.
     */
    public static final float DEFAULT_ZOOM_BIAS = 1.0f;

    /**
     * How long after the second finger lands the ZOOM latch is held back, in milliseconds.
     *
     * <p>This exists because latching ZOOM is <em>consuming</em>: {@code
     * InlinePinchZoomController} cancels the in-flight touch contexts and swallows every
     * later pointer-down for the rest of the gesture. The 3/4/5 finger gestures (soft
     * keyboard, full keyboard, game menu) are recognised further down {@code Game}'s
     * dispatch, so anything we swallow they never see.
     *
     * <p>The ordering normally protects them: a third finger arrives as
     * {@code ACTION_POINTER_DOWN} with {@code pointerCount == 3}, which
     * {@link #disqualify()}s us before any of this matters. But fingers in a multi-finger
     * tap do not land in the same frame — at 120-240Hz there are several {@code
     * ACTION_MOVE}s between the second contact and the third — and a multi-finger tap
     * whose fingers converge as they land can push the span past
     * {@link #DEFAULT_SPAN_SLOP_PX} inside that gap. Without this dwell the gesture
     * latches ZOOM first and the user gets a zoom instead of the keyboard, intermittently.
     *
     * <p>40ms is chosen at the <em>low</em> end deliberately. The dwell is not free: while
     * we are undecided the events pass through to the touch contexts, and those confirm a
     * move at 20px (see {@link #DEFAULT_SPAN_SLOP_PX}), which is only 2px above where we
     * would otherwise have latched. Every extra millisecond of dwell is therefore paid for
     * by a real pinch in leaked scroll, on every pinch, whereas the multi-finger benefit
     * saturates quickly because most multi-finger taps land well inside 40ms. Erring short
     * costs a rare stolen gesture; erring long costs stray input on a common one.
     *
     * <p>Time, not a frame count: three frames is 12.5ms at 240Hz and 25ms at 120Hz, so a
     * frame count that covers the gap on one device misses it on another.
     */
    public static final long DEFAULT_ZOOM_LATCH_DWELL_MS = 40L;

    private static final float MIN_USABLE_SPAN_PX = 0.01f;
    private static final float MIN_SCALE_DELTA = 0.1f;
    private static final float MAX_SCALE_DELTA = 10f;

    private final float spanSlopPx;
    private final float translationSlopPx;
    private final float zoomBias;
    private final long zoomLatchDwellMs;

    private Decision decision = Decision.INACTIVE;
    private boolean disqualified;

    /**
     * {@code eventTime} of the {@code ACTION_POINTER_DOWN} that started arbitration. A
     * caller-supplied millisecond stamp rather than a clock read in here, so this class
     * stays a pure function of its inputs and testable on a bare JVM.
     */
    private long twoFingerDownTimeMs;

    private float initialSpan;
    private float initialFocusX;
    private float initialFocusY;

    private float prevSpan;
    private float prevFocusX;
    private float prevFocusY;

    private float scaleDelta = 1f;
    private float focusX;
    private float focusY;
    private float focusDeltaX;
    private float focusDeltaY;

    public TwoFingerGestureArbiter() {
        this(DEFAULT_SPAN_SLOP_PX, DEFAULT_TRANSLATION_SLOP_PX, DEFAULT_ZOOM_BIAS);
    }

    public TwoFingerGestureArbiter(float spanSlopPx, float translationSlopPx, float zoomBias) {
        this(spanSlopPx, translationSlopPx, zoomBias, DEFAULT_ZOOM_LATCH_DWELL_MS);
    }

    /**
     * @param zoomLatchDwellMs see {@link #DEFAULT_ZOOM_LATCH_DWELL_MS}. Zero disables the
     *                         dwell, which is only ever appropriate in a test that is
     *                         deliberately isolating the slop from the timing.
     */
    public TwoFingerGestureArbiter(float spanSlopPx, float translationSlopPx, float zoomBias,
                                   long zoomLatchDwellMs) {
        if (!(spanSlopPx > 0f) || !(translationSlopPx > 0f) || !(zoomBias > 0f)) {
            throw new IllegalArgumentException(
                    "slops and bias must be positive: spanSlop=" + spanSlopPx
                            + " translationSlop=" + translationSlopPx + " zoomBias=" + zoomBias);
        }
        if (zoomLatchDwellMs < 0L) {
            throw new IllegalArgumentException(
                    "zoom latch dwell must not be negative: " + zoomLatchDwellMs);
        }
        this.spanSlopPx = spanSlopPx;
        this.translationSlopPx = translationSlopPx;
        this.zoomBias = zoomBias;
        this.zoomLatchDwellMs = zoomLatchDwellMs;
    }

    /**
     * The whole decision, as a pure function. Exposed so the truth table can be tested
     * without simulating a stream of pointer positions.
     *
     * @param spanChange       absolute change in finger separation since the gesture started
     * @param translation      distance the centroid has travelled since the gesture started
     */
    public static Decision classify(float spanChange, float translation,
                                    float spanSlopPx, float translationSlopPx, float zoomBias) {
        boolean spanCrossed = spanChange >= spanSlopPx;
        boolean translationCrossed = translation >= translationSlopPx;

        if (!spanCrossed && !translationCrossed) {
            return Decision.UNDECIDED;
        }
        if (spanCrossed && !translationCrossed) {
            return Decision.ZOOM;
        }
        if (!spanCrossed) {
            return Decision.SCROLL;
        }
        // Both crossed in the same frame: whichever moved further describes the gesture better.
        return spanChange > translation * zoomBias ? Decision.ZOOM : Decision.SCROLL;
    }

    /** Forget everything, including any disqualification. Call when a gesture ends. */
    public void reset() {
        decision = Decision.INACTIVE;
        disqualified = false;
        twoFingerDownTimeMs = 0L;
        initialSpan = prevSpan = 0f;
        initialFocusX = initialFocusY = prevFocusX = prevFocusY = 0f;
        scaleDelta = 1f;
        focusX = focusY = focusDeltaX = focusDeltaY = 0f;
    }

    /**
     * Refuse to arbitrate for the rest of this gesture. Used when a third finger lands:
     * that is one of the existing multi-finger gestures, never an inline pinch.
     * Cleared only by {@link #reset()}.
     */
    public void disqualify() {
        decision = Decision.INACTIVE;
        disqualified = true;
    }

    /** Stop tracking, but stay eligible to re-arm if two fingers come back down. */
    public void endTwoFinger() {
        if (!disqualified) {
            decision = Decision.INACTIVE;
        }
    }

    /**
     * Start arbitration with the two fingers at the given positions.
     *
     * @param eventTimeMs {@code MotionEvent.getEventTime()} of the pointer-down that
     *                    brought the second finger down. Starts the
     *                    {@link #DEFAULT_ZOOM_LATCH_DWELL_MS} window; passed in rather
     *                    than read from a clock here so this class stays Android-free.
     */
    public void beginTwoFinger(float x0, float y0, float x1, float y1, long eventTimeMs) {
        if (disqualified) {
            return;
        }
        twoFingerDownTimeMs = eventTimeMs;
        initialSpan = prevSpan = span(x0, y0, x1, y1);
        initialFocusX = prevFocusX = focusX = (x0 + x1) * 0.5f;
        initialFocusY = prevFocusY = focusY = (y0 + y1) * 0.5f;
        scaleDelta = 1f;
        focusDeltaX = focusDeltaY = 0f;
        decision = Decision.UNDECIDED;
    }

    /**
     * Feed a new pair of pointer positions.
     *
     * <p>Also refreshes {@link #getScaleDelta()} and the focus accessors, which describe
     * the change since the <em>previous</em> update — never since the start of the
     * gesture. Reporting per frame deltas is what keeps the slop that was consumed
     * during arbitration from being applied as one visible jump at the moment we latch.
     *
     * @param eventTimeMs {@code MotionEvent.getEventTime()} for this frame, on the same
     *                    timebase as the one given to {@link #beginTwoFinger}
     * @return the current decision, latched once it is SCROLL or ZOOM
     */
    public Decision update(float x0, float y0, float x1, float y1, long eventTimeMs) {
        if (decision == Decision.INACTIVE) {
            scaleDelta = 1f;
            focusDeltaX = focusDeltaY = 0f;
            return decision;
        }

        float span = span(x0, y0, x1, y1);
        float fx = (x0 + x1) * 0.5f;
        float fy = (y0 + y1) * 0.5f;

        scaleDelta = clampScaleDelta(prevSpan > MIN_USABLE_SPAN_PX ? span / prevSpan : 1f);
        focusDeltaX = fx - prevFocusX;
        focusDeltaY = fy - prevFocusY;
        focusX = fx;
        focusY = fy;

        prevSpan = span;
        prevFocusX = fx;
        prevFocusY = fy;

        if (decision == Decision.UNDECIDED) {
            float spanChange = Math.abs(span - initialSpan);
            float translation = distance(fx - initialFocusX, fy - initialFocusY);
            Decision candidate =
                    classify(spanChange, translation, spanSlopPx, translationSlopPx, zoomBias);
            // ZOOM is the consuming decision, so it is the only one that can steal a
            // 3/4/5 finger gesture that is still landing. Hold it back until the dwell
            // has passed; SCROLL keeps latching immediately because latching it changes
            // nothing about what the caller does with the events -- they pass through
            // either way. See DEFAULT_ZOOM_LATCH_DWELL_MS.
            if (candidate != Decision.ZOOM || hasDwelled(eventTimeMs)) {
                decision = candidate;
            }
        }

        return decision;
    }

    /**
     * Whether enough time has passed since the second finger landed for a ZOOM latch to
     * be safe. Subtraction rather than comparison so it stays correct across the wrap of
     * the monotonic uptime clock {@code MotionEvent.getEventTime()} is drawn from.
     */
    private boolean hasDwelled(long eventTimeMs) {
        return eventTimeMs - twoFingerDownTimeMs >= zoomLatchDwellMs;
    }

    public Decision getDecision() {
        return decision;
    }

    /** The configured ZOOM latch dwell. Exposed so tests can drive it without guessing. */
    public long getZoomLatchDwellMs() {
        return zoomLatchDwellMs;
    }

    /** The configured span slop. Exposed so tests can pin it through the production path. */
    public float getSpanSlopPx() {
        return spanSlopPx;
    }

    /** The configured translation slop. Exposed so tests can pin it through the production path. */
    public float getTranslationSlopPx() {
        return translationSlopPx;
    }

    public boolean isDisqualified() {
        return disqualified;
    }

    /** Ratio of the current finger separation to the previous one. */
    public float getScaleDelta() {
        return scaleDelta;
    }

    public float getFocusX() {
        return focusX;
    }

    public float getFocusY() {
        return focusY;
    }

    public float getFocusDeltaX() {
        return focusDeltaX;
    }

    public float getFocusDeltaY() {
        return focusDeltaY;
    }

    private static float clampScaleDelta(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 1f;
        }
        return Math.max(MIN_SCALE_DELTA, Math.min(value, MAX_SCALE_DELTA));
    }

    private static float span(float x0, float y0, float x1, float y1) {
        return distance(x1 - x0, y1 - y0);
    }

    private static float distance(float dx, float dy) {
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
