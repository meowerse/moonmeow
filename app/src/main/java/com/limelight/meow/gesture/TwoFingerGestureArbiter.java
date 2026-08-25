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

    private static final float MIN_USABLE_SPAN_PX = 0.01f;
    private static final float MIN_SCALE_DELTA = 0.1f;
    private static final float MAX_SCALE_DELTA = 10f;

    private final float spanSlopPx;
    private final float translationSlopPx;
    private final float zoomBias;

    private Decision decision = Decision.INACTIVE;
    private boolean disqualified;

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
        if (!(spanSlopPx > 0f) || !(translationSlopPx > 0f) || !(zoomBias > 0f)) {
            throw new IllegalArgumentException(
                    "slops and bias must be positive: spanSlop=" + spanSlopPx
                            + " translationSlop=" + translationSlopPx + " zoomBias=" + zoomBias);
        }
        this.spanSlopPx = spanSlopPx;
        this.translationSlopPx = translationSlopPx;
        this.zoomBias = zoomBias;
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
        initialSpan = prevSpan = 0f;
        initialFocusX = initialFocusY = prevFocusX = prevFocusY = 0f;
        scaleDelta = 1f;
        focusX = focusY = focusDeltaX = focusDeltaY = 0f;
    }

    /**
     * Refuse to arbitrate for the rest of this gesture. Used when a third finger lands:
     * that is one of the existing multi-finger gestures, never an inline pinch.
     * Cleared only by {@link #reset()}.
     *
     * <p>This is <em>not</em> what protects the 3/4/5 finger gestures, and it is worth
     * being explicit about that because it looks as though it is. Latching ZOOM is a
     * <em>consuming</em> decision, so once it has latched a later third finger never gets
     * here at all: {@code InlinePinchZoomController} swallows the pointer-down. What
     * actually protects those gestures is dispatch order — {@code Game.handleMotionEvent}
     * offers every {@code pointerCount > 2} event to {@code handleMultiTouchGesture}
     * <em>before</em> the inline-pinch hook, in every mode where the touch contexts exist —
     * and no amount of extra caution in here can substitute for it. (Mouse mode 4 skips the
     * recognisers altogether, so there is nothing there to protect.) See {@code docs/meow/TOUCHPOINTS.md}.
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

    /** Start arbitration with the two fingers at the given positions. */
    public void beginTwoFinger(float x0, float y0, float x1, float y1) {
        if (disqualified) {
            return;
        }
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
     * @return the current decision, latched once it is SCROLL or ZOOM
     */
    public Decision update(float x0, float y0, float x1, float y1) {
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
            decision = classify(spanChange, translation, spanSlopPx, translationSlopPx, zoomBias);
        }

        return decision;
    }

    public Decision getDecision() {
        return decision;
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
