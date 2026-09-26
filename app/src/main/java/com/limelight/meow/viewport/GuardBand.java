package com.limelight.meow.viewport;

/**
 * Decides what crop to ask the host for, given what the user can see. Plain Java, UI thread,
 * allocation only when a new request is made.
 *
 * <h2>Why not just ask for what is visible</h2>
 * Asking for exactly the visible rectangle V makes every pan -- a finger drag, the cursor
 * pushing the view -- reveal pixels the host has not sent: the edges are soft until the next
 * crop has made a round trip and been encoded, and every small movement is a new crop for the
 * host to apply. Asking for V plus a <b>guard band</b> puts the pixels just outside the view
 * in every frame already. Pans are shown sharp from what was received (the compositor presents
 * V inside the applied crop A at one magnification), and the crop only moves when V nears the
 * band's edge.
 *
 * <h2>The size never changes while the zoom does not</h2>
 * The owner's report (2026-09-26) on a 5360x1440 desktop at 6.6x: the picture "weirdly
 * resized, bigger smaller during moving". An earlier revision grew the band with pan speed
 * (10% at rest to 35%) and tightened it 300 ms after the view stopped, so every pan was a run
 * of crops of different sizes, each a different host magnification, each a visible resize at
 * the swap. The request's size is now a function of V's size alone -- that is, of the zoom
 * and the window -- and is recomputed only when V's size changes by more than rounding
 * ({@link #SIZE_TOLERANCE}). Speed only decides <em>where</em> the band is placed (the lead,
 * below), never how big it is, and nothing tightens on settle. With the size fixed, the host's
 * scale factor is fixed, and consecutive crops differ by a translation only.
 *
 * <h2>The margin: {@value #MARGIN} per side</h2>
 * The encoder's pixels are spread over the band too, so V gets {@code 1 / (1 + 2m)} of them
 * per axis: 71% at m = 0.20 (83% at 0.10, 67% at 0.25). That is the worst case, when the
 * encoder is what limits sharpness. On the owner's setup it is not: at 6.6x on a 5360x1440
 * desktop streamed to a 1220x2712 portrait surface the band is about 1140 desktop pixels
 * across for 1220 encoder pixels, so the host still has more than one encoder pixel per
 * desktop pixel and the margin costs no detail at all. What it buys is headroom: V can move
 * 0.2 of its own width before the band's edge shows, and because each new band is placed
 * ahead of the motion (below) up to 0.37 in the direction of travel. A crop takes one
 * round trip plus encode and decode to arrive -- about 100 ms over Tailscale -- so a pan of up
 * to ~3.5 views a second stays inside received pixels (cursor follow's gentle regime is capped
 * at 2.5). 0.25 would stretch that to ~4.5 views a second for 4% less sharpness in the
 * encoder-limited case; 0.20 is the balance, and faster flings show black at the leading edge
 * for a moment, which the owner accepted.
 *
 * <h2>Hysteresis and lead</h2>
 * The current request is kept while V stays inside it with a slack of {@link #INNER_SLACK} of
 * V's size on every side not at the desktop edge. When V reaches that slack, the band is
 * re-placed around V's predicted position {@code V + velocity x lead}, where the lead is the
 * measured crop round trip plus a frame ({@link #setLeadMs}), bounded so V itself is still
 * inside the new band. So a steady pan re-crops in steps of about a third of a view, not once
 * per input frame, and each new band is mostly ahead of the view rather than centred on where
 * it was.
 *
 * <h2>Constant on the host's side too</h2>
 * A constant request in reference pixels is not yet a constant crop: the host maps it into
 * desktop pixels by flooring the near edge and ceiling the far one, then even-aligns
 * ({@code sanitize()}), so on a 5360-wide desktop in a 1220-wide stream the same request width
 * becomes 1140 or 1142 desktop pixels depending on where it sits -- a 0.2% resize at every
 * step. Once the desktop size is known ({@link #setDesktop}) the size is nudged by up to
 * {@value #MAX_EXTENT_NUDGE} reference pixels to one whose host source extent is the same at
 * every position ({@link HostCropPlan#hostAxis}), so the host's scale factor is exactly
 * constant and consecutive crops differ by a translation only.
 *
 * <h2>Rules kept from before</h2>
 * <ul>
 *   <li>The request keeps the aspect ratio of the encode surface, so the host scales it
 *       without letterboxing and no encoder pixel is spent on padding.</li>
 *   <li>It is clamped inside the desktop (shifted, not shrunk, where it can be), and a view
 *       that covers the desktop asks for the whole of it.</li>
 *   <li>Containment works on the part of V that shows desktop, so a view overlapping the
 *       letterbox padding still gets hysteresis. The size is taken from the whole of V, so it
 *       does not change as V slides over the padding.</li>
 * </ul>
 */
public final class GuardBand {

    /** Margin per side, as a fraction of the view. See the class comment for the numbers. */
    static final float MARGIN = 0.20f;
    /** The view must stay this far (a fraction of its size) inside the request. */
    static final float INNER_SLACK = 0.03f;
    /**
     * A view size change within this fraction (or 2 px) is rounding of a pan, not a zoom. The
     * visible rectangle's two edges are rounded separately, so its width wobbles by a pixel.
     */
    static final float SIZE_TOLERANCE = 0.01f;
    private static final int SIZE_TOLERANCE_PX = 2;
    /** The lead used until the crop round trip has been measured. */
    static final long DEFAULT_LEAD_MS = 120L;
    static final long MAX_LEAD_MS = 400L;
    /** How far the size may be nudged to make the host's source extent position-independent. */
    static final int MAX_EXTENT_NUDGE = 4;
    /** Velocity smoothing per update. */
    private static final float VELOCITY_SMOOTHING = 0.5f;
    /** A gap this long between updates means the view was at rest in between. */
    private static final long REST_GAP_MS = 250L;

    private ViewportRect current;

    /** The request size, and the view size and bounds it was computed for. */
    private int sizeW;
    private int sizeH;
    private int keyViewW;
    private int keyViewH;
    private int keyBoundsW;
    private int keyBoundsH;
    private boolean haveSize;

    private long leadMs = DEFAULT_LEAD_MS;

    /** The captured desktop's size, or 0 when not known yet. */
    private int desktopW;
    private int desktopH;

    private long lastMs;
    private float lastCentreX;
    private float lastCentreY;
    /** Reference pixels per millisecond. */
    private float velocityX;
    private float velocityY;
    private boolean haveLast;

    /** Scratch: the part of the view on the desktop as {x, y, w, h}. */
    private final int[] view = new int[4];
    private final int[] size = new int[2];
    private final int[] axis = new int[4];

    /** Forget everything; the next call makes a fresh request. */
    public void reset() {
        current = null;
        haveSize = false;
        haveLast = false;
        velocityX = 0f;
        velocityY = 0f;
    }

    /** The crop last requested, or null. */
    public ViewportRect current() {
        return current;
    }

    /**
     * How far ahead of the view to place a new band: the time a crop takes to reach the screen.
     * Only the placement uses it; the size never does.
     */
    public void setLeadMs(long ms) {
        leadMs = Math.max(0L, Math.min(MAX_LEAD_MS, ms));
    }

    long leadMs() {
        return leadMs;
    }

    /**
     * The captured desktop's size, from the host's echo; 0 when unknown. With it the size is
     * chosen so the host's own crop size does not change as the band moves.
     */
    public void setDesktop(int width, int height) {
        if (width != desktopW || height != desktopH) {
            desktopW = Math.max(0, width);
            desktopH = Math.max(0, height);
            haveSize = false;
        }
    }

    /**
     * The view changed.
     *
     * @param visible      what the user sees, reference pixels
     * @param nowMs        a monotonic clock
     * @param bounds       the desktop inside the frame (content box), or the whole frame
     * @param streamWidth  encode surface width, for the aspect ratio
     * @param streamHeight encode surface height
     * @return the rectangle to ask the host for, or null to keep the current request
     */
    public ViewportRect onVisible(ViewportRect visible, long nowMs, ViewportRect bounds,
                                  int streamWidth, int streamHeight) {
        if (visible == null || bounds == null || !onDesktop(visible, bounds)) {
            return null;
        }
        updateVelocity(nowMs);
        return decide(visible, bounds, streamWidth, streamHeight);
    }

    /**
     * Motion stopped. The request is not tightened -- its size belongs to the zoom -- but it is
     * offered again: that is how a request the library could not deliver is retried, and when
     * the host already has it the library drops it as a duplicate.
     *
     * @return the rectangle to ask for (the current one, or a new one if V is no longer inside
     *         it), or null when there is none
     */
    public ViewportRect onSettled(ViewportRect visible, ViewportRect bounds,
                                  int streamWidth, int streamHeight) {
        if (visible == null || bounds == null || !onDesktop(visible, bounds)) {
            return null;
        }
        velocityX = 0f;
        velocityY = 0f;
        ViewportRect moved = decide(visible, bounds, streamWidth, streamHeight);
        return moved != null ? moved : current;
    }

    /**
     * The part of the view that shows desktop, into {@link #view}. Containment works on this,
     * not on the raw view: at low zoom over a letterboxed desktop the view overlaps the
     * padding, no request (which is clamped to the desktop) can contain it, and hysteresis
     * would never hold.
     */
    private boolean onDesktop(ViewportRect v, ViewportRect bounds) {
        int left = Math.max(v.x, bounds.x);
        int top = Math.max(v.y, bounds.y);
        int right = Math.min(v.x + v.width, bounds.x + bounds.width);
        int bottom = Math.min(v.y + v.height, bounds.y + bounds.height);
        if (right <= left || bottom <= top) {
            return false;
        }
        view[0] = left;
        view[1] = top;
        view[2] = right - left;
        view[3] = bottom - top;
        return true;
    }

    private void updateVelocity(long nowMs) {
        float cx = view[0] + view[2] / 2f;
        float cy = view[1] + view[3] / 2f;
        if (haveLast && nowMs > lastMs) {
            long dt = nowMs - lastMs;
            if (dt >= REST_GAP_MS) {
                velocityX = 0f;
                velocityY = 0f;
            } else {
                float instantX = (cx - lastCentreX) / dt;
                float instantY = (cy - lastCentreY) / dt;
                velocityX = velocityX * VELOCITY_SMOOTHING + instantX * (1f - VELOCITY_SMOOTHING);
                velocityY = velocityY * VELOCITY_SMOOTHING + instantY * (1f - VELOCITY_SMOOTHING);
            }
        }
        haveLast = true;
        lastMs = nowMs;
        lastCentreX = cx;
        lastCentreY = cy;
    }

    /** Allocates only when it returns a new request. */
    private ViewportRect decide(ViewportRect visible, ViewportRect bounds,
                                int streamWidth, int streamHeight) {
        boolean resized = updateSize(visible, bounds, streamWidth, streamHeight);
        ViewportRect keep = current;
        if (!resized && keep != null && contains(keep, bounds)) {
            return null;
        }
        int x;
        int y;
        if (view[2] >= bounds.width && view[3] >= bounds.height) {
            x = bounds.x;
            y = bounds.y;
        } else {
            x = place(view[0], view[2], sizeW, velocityX, bounds.x, bounds.width);
            y = place(view[1], view[3], sizeH, velocityY, bounds.y, bounds.height);
        }
        if (keep != null && keep.x == x && keep.y == y
                && keep.width == sizeW && keep.height == sizeH) {
            return null;
        }
        current = new ViewportRect(x, y, sizeW, sizeH);
        return current;
    }

    /**
     * Recomputes the request size when the view's size (the zoom) or the desktop box changed
     * by more than rounding.
     *
     * @return whether the size changed
     */
    private boolean updateSize(ViewportRect visible, ViewportRect bounds,
                               int streamWidth, int streamHeight) {
        if (haveSize && bounds.width == keyBoundsW && bounds.height == keyBoundsH
                && sameSize(visible.width, keyViewW) && sameSize(visible.height, keyViewH)) {
            return false;
        }
        sizeFor(visible.width, visible.height, view, bounds, streamWidth, streamHeight,
                MARGIN, size);
        size[0] = stableExtent(size[0], bounds.x, bounds.width, desktopW);
        size[1] = stableExtent(size[1], bounds.y, bounds.height, desktopH);
        boolean changed = !haveSize || size[0] != sizeW || size[1] != sizeH;
        sizeW = size[0];
        sizeH = size[1];
        keyViewW = visible.width;
        keyViewH = visible.height;
        keyBoundsW = bounds.width;
        keyBoundsH = bounds.height;
        haveSize = true;
        return changed;
    }

    /**
     * Of the extents within {@link #MAX_EXTENT_NUDGE} of {@code extent}, the nearest (larger
     * first) whose host source extent is the same wherever the band sits along the desktop;
     * {@code extent} itself when the desktop is unknown or none is.
     */
    private int stableExtent(int extent, int boundsStart, int boundsLength, int capture) {
        if (capture <= 0 || extent >= boundsLength) {
            return extent;
        }
        for (int nudge = 0; nudge <= MAX_EXTENT_NUDGE; nudge++) {
            if (hostExtentIsStable(extent + nudge, boundsStart, boundsLength, capture)) {
                return extent + nudge;
            }
            if (nudge > 0 && hostExtentIsStable(extent - nudge, boundsStart, boundsLength,
                    capture)) {
                return extent - nudge;
            }
        }
        return extent;
    }

    private boolean hostExtentIsStable(int extent, int boundsStart, int boundsLength,
                                       int capture) {
        if (extent < 1 || extent > boundsLength) {
            return false;
        }
        int first = -1;
        for (int start = boundsStart; start + extent <= boundsStart + boundsLength; start++) {
            if (!HostCropPlan.hostAxis(start, extent, boundsStart, boundsLength, capture,
                    axis, 0)) {
                return false;
            }
            if (first < 0) {
                first = axis[2];
            } else if (axis[2] != first) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSize(int now, int then) {
        return Math.abs(now - then) <= Math.max(SIZE_TOLERANCE_PX, then * SIZE_TOLERANCE);
    }

    /**
     * The left (top) edge of a band of {@code size} around a view spanning
     * {@code [start, start + length)}: centred on where the view will be after the lead, kept
     * so the view is inside it with the slack, then kept inside the desktop.
     */
    private int place(int start, int length, int size, float velocity, int boundsStart,
                      int boundsLength) {
        float lead = velocity * leadMs;
        float ideal = start + length / 2f + lead - size / 2f;
        float slack = length * INNER_SLACK;
        float lowest = start + length + slack - size;
        float highest = start - slack;
        if (lowest <= highest) {
            ideal = Math.max(lowest, Math.min(ideal, highest));
        } else {
            ideal = start + length / 2f - size / 2f;
        }
        float clamped = Math.max(boundsStart, Math.min(ideal, boundsStart + boundsLength - size));
        return Math.round(clamped);
    }

    /** The view inside R with slack on every side that is not at the desktop's edge. */
    private boolean contains(ViewportRect r, ViewportRect bounds) {
        int vx = view[0];
        int vy = view[1];
        int vw = view[2];
        int vh = view[3];
        int slackX = Math.round(vw * INNER_SLACK);
        int slackY = Math.round(vh * INNER_SLACK);
        boolean left = vx - r.x >= slackX || r.x <= bounds.x;
        boolean top = vy - r.y >= slackY || r.y <= bounds.y;
        boolean right = (r.x + r.width) - (vx + vw) >= slackX
                || r.x + r.width >= bounds.x + bounds.width;
        boolean bottom = (r.y + r.height) - (vy + vh) >= slackY
                || r.y + r.height >= bounds.y + bounds.height;
        return vx >= r.x && vy >= r.y && vx + vw <= r.x + r.width
                && vy + vh <= r.y + r.height && left && top && right && bottom;
    }

    /**
     * The request size for a view of {@code viewW x viewH}: grown by the margin, at the surface
     * aspect, no larger than the desktop. A view whose desktop part covers the desktop asks for
     * the whole desktop.
     *
     * @param onDesktop the desktop part of the view, {x, y, w, h}
     * @param out       {width, height}
     */
    static void sizeFor(int viewW, int viewH, int[] onDesktop, ViewportRect bounds,
                        int streamWidth, int streamHeight, float margin, int[] out) {
        if (onDesktop[2] >= bounds.width && onDesktop[3] >= bounds.height) {
            out[0] = bounds.width;
            out[1] = bounds.height;
            return;
        }
        float w = viewW * (1f + 2f * margin);
        float h = viewH * (1f + 2f * margin);
        float aspect = streamHeight > 0 ? (float) streamWidth / streamHeight : w / h;
        if (w / h < aspect) {
            w = h * aspect;
        } else {
            h = w / aspect;
        }
        out[0] = Math.max(1, Math.min(Math.round(w), bounds.width));
        out[1] = Math.max(1, Math.min(Math.round(h), bounds.height));
    }
}
