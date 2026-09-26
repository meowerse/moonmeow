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
 * in every frame already. Small pans are shown sharp from what was received (the compositor
 * presents V inside the applied crop A at one magnification), and the crop only changes when
 * V nears the band's edge or the zoom changes enough to matter.
 *
 * <h2>The trade</h2>
 * The encoder's pixels are spread over the band too, so V gets {@code 1 / (1 + 2m)} of them
 * per axis for a margin m. At rest the margin is {@link #REST_MARGIN} (10%, 83% of the
 * sharpness of an exact crop); while the view is moving it grows with speed up to
 * {@link #MAX_MARGIN}, because then a crop that keeps up matters more than the last bit of
 * detail; once motion stops ({@link #onSettled}) it is tightened back.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>The request keeps the aspect ratio of the encode surface, so the host scales it
 *       without letterboxing and no encoder pixel is spent on padding.</li>
 *   <li>It is clamped inside the desktop (shifted, not shrunk, where it can be), and a view
 *       that covers the desktop asks for the whole of it.</li>
 *   <li>The logic works on the part of V that shows desktop, so a view overlapping the
 *       letterbox padding still gets hysteresis.</li>
 *   <li>Hysteresis: the current request is kept while V is inside it with a slack of
 *       {@link #INNER_SLACK} of V's size on every side not at the desktop edge, and while it
 *       is no more than {@link #OVERSIZE_TOLERANCE} times the size the margin calls for.</li>
 * </ul>
 */
public final class GuardBand {

    static final float REST_MARGIN = 0.10f;
    static final float MAX_MARGIN = 0.35f;
    /** Margin added per view-width per second of pan speed. */
    static final float MARGIN_PER_SPEED = 0.1f;
    static final float INNER_SLACK = 0.02f;
    static final float OVERSIZE_TOLERANCE = 1.25f;
    /**
     * At rest a request may be this much larger than the rest target before it is tightened.
     * Each tightening is a visible re-sharpen and a burst of bits, so only a clear gain is
     * worth one.
     */
    static final float REST_TIGHTEN = 1.15f;
    /** Pan speed smoothing per update. */
    private static final float SPEED_SMOOTHING = 0.5f;

    private ViewportRect current;
    private long lastMs;
    private float lastCentreX;
    private float lastCentreY;
    private float speed;
    private boolean haveLast;
    /** Scratch: the part of the view on the desktop, and the target, as {x, y, w, h}. */
    private final int[] view = new int[4];
    private final int[] target = new int[4];

    /** Forget everything; the next call makes a fresh request. */
    public void reset() {
        current = null;
        haveLast = false;
        speed = 0f;
    }

    /** The crop last requested, or null. */
    public ViewportRect current() {
        return current;
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
        updateSpeed(nowMs);
        return decide(bounds, streamWidth, streamHeight, marginForSpeed(), false);
    }

    /**
     * Motion stopped: tighten the band back to the rest margin if it grew while moving.
     *
     * @return the rectangle to ask for: a tighter one, or the current one again. Re-offering
     *         the current request is how a request the library could not deliver is retried;
     *         when the host already has it the library drops it as a duplicate.
     */
    public ViewportRect onSettled(ViewportRect visible, ViewportRect bounds,
                                  int streamWidth, int streamHeight) {
        if (visible == null || bounds == null || !onDesktop(visible, bounds)) {
            return null;
        }
        speed = 0f;
        ViewportRect tighter = decide(bounds, streamWidth, streamHeight, REST_MARGIN, true);
        return tighter != null ? tighter : current;
    }

    private float marginForSpeed() {
        return Math.min(MAX_MARGIN, REST_MARGIN + speed * MARGIN_PER_SPEED);
    }

    /**
     * The part of the view that shows desktop, into {@link #view}. The band logic works on
     * this, not on the raw view: at low zoom over a letterboxed desktop the view overlaps the
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

    private void updateSpeed(long nowMs) {
        float cx = view[0] + view[2] / 2f;
        float cy = view[1] + view[3] / 2f;
        if (haveLast && nowMs > lastMs) {
            float dt = (nowMs - lastMs) / 1000f;
            float distance = (float) Math.hypot(cx - lastCentreX, cy - lastCentreY);
            float instant = distance / Math.max(1, view[2]) / dt;
            speed = speed * SPEED_SMOOTHING + instant * (1f - SPEED_SMOOTHING);
        }
        haveLast = true;
        lastMs = nowMs;
        lastCentreX = cx;
        lastCentreY = cy;
    }

    /** Allocates only when it returns a new request. */
    private ViewportRect decide(ViewportRect bounds, int streamWidth, int streamHeight,
                                float margin, boolean settling) {
        expand(view, bounds, streamWidth, streamHeight, margin, target);
        ViewportRect keep = current;
        if (keep != null && contains(keep, bounds)) {
            float limit = settling ? REST_TIGHTEN : OVERSIZE_TOLERANCE;
            if (keep.width <= target[2] * limit && keep.height <= target[3] * limit) {
                return null;
            }
        }
        if (keep != null && keep.x == target[0] && keep.y == target[1]
                && keep.width == target[2] && keep.height == target[3]) {
            return null;
        }
        current = new ViewportRect(target[0], target[1], target[2], target[3]);
        return current;
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

    /** The view (x, y, w, h) grown by the margin, at the surface aspect, clamped into the desktop. */
    static void expand(int[] v, ViewportRect bounds, int streamWidth, int streamHeight,
                       float margin, int[] out) {
        if (v[2] >= bounds.width && v[3] >= bounds.height) {
            out[0] = bounds.x;
            out[1] = bounds.y;
            out[2] = bounds.width;
            out[3] = bounds.height;
            return;
        }
        float w = v[2] * (1f + 2f * margin);
        float h = v[3] * (1f + 2f * margin);
        float aspect = streamHeight > 0 ? (float) streamWidth / streamHeight : w / h;
        if (w / h < aspect) {
            w = h * aspect;
        } else {
            h = w / aspect;
        }
        w = Math.min(w, bounds.width);
        h = Math.min(h, bounds.height);
        float cx = v[0] + v[2] / 2f;
        float cy = v[1] + v[3] / 2f;
        float x = Math.max(bounds.x, Math.min(cx - w / 2f, bounds.x + bounds.width - w));
        float y = Math.max(bounds.y, Math.min(cy - h / 2f, bounds.y + bounds.height - h));
        int ix = Math.round(x);
        int iy = Math.round(y);
        out[0] = ix;
        out[1] = iy;
        out[2] = Math.min(Math.round(w), bounds.x + bounds.width - ix);
        out[3] = Math.min(Math.round(h), bounds.y + bounds.height - iy);
    }
}
