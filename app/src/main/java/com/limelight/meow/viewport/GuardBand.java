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
    /** At rest a request may be this much larger than the rest target before it is tightened. */
    static final float REST_TIGHTEN = 1.06f;
    /** Pan speed smoothing per update. */
    private static final float SPEED_SMOOTHING = 0.5f;

    private ViewportRect current;
    private long lastMs;
    private float lastCentreX;
    private float lastCentreY;
    private float speed;
    private boolean haveLast;

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
        if (visible == null || bounds == null) {
            return null;
        }
        updateSpeed(visible, nowMs);
        return decide(visible, bounds, streamWidth, streamHeight, marginForSpeed(), false);
    }

    /**
     * Motion stopped: tighten the band back to the rest margin if it grew while moving.
     *
     * @return the tighter rectangle to ask for, or null when the current one is already right
     */
    public ViewportRect onSettled(ViewportRect visible, ViewportRect bounds,
                                  int streamWidth, int streamHeight) {
        if (visible == null || bounds == null) {
            return null;
        }
        speed = 0f;
        return decide(visible, bounds, streamWidth, streamHeight, REST_MARGIN, true);
    }

    private float marginForSpeed() {
        return Math.min(MAX_MARGIN, REST_MARGIN + speed * MARGIN_PER_SPEED);
    }

    private void updateSpeed(ViewportRect v, long nowMs) {
        float cx = v.x + v.width / 2f;
        float cy = v.y + v.height / 2f;
        if (haveLast && nowMs > lastMs) {
            float dt = (nowMs - lastMs) / 1000f;
            float distance = (float) Math.hypot(cx - lastCentreX, cy - lastCentreY);
            float instant = distance / Math.max(1, v.width) / dt;
            speed = speed * SPEED_SMOOTHING + instant * (1f - SPEED_SMOOTHING);
        }
        haveLast = true;
        lastMs = nowMs;
        lastCentreX = cx;
        lastCentreY = cy;
    }

    private ViewportRect decide(ViewportRect v, ViewportRect bounds, int streamWidth,
                                int streamHeight, float margin, boolean settling) {
        ViewportRect target = expand(v, bounds, streamWidth, streamHeight, margin);
        ViewportRect keep = current;
        if (keep != null && contains(keep, v, bounds)) {
            float limit = settling ? REST_TIGHTEN : OVERSIZE_TOLERANCE;
            if (keep.width <= target.width * limit && keep.height <= target.height * limit) {
                return null;
            }
        }
        if (target.equals(keep)) {
            return null;
        }
        current = target;
        return target;
    }

    /** V inside R with slack on every side that is not at the desktop's edge. */
    private static boolean contains(ViewportRect r, ViewportRect v, ViewportRect bounds) {
        int slackX = Math.round(v.width * INNER_SLACK);
        int slackY = Math.round(v.height * INNER_SLACK);
        boolean left = v.x - r.x >= slackX || r.x <= bounds.x;
        boolean top = v.y - r.y >= slackY || r.y <= bounds.y;
        boolean right = (r.x + r.width) - (v.x + v.width) >= slackX
                || r.x + r.width >= bounds.x + bounds.width;
        boolean bottom = (r.y + r.height) - (v.y + v.height) >= slackY
                || r.y + r.height >= bounds.y + bounds.height;
        return v.x >= r.x && v.y >= r.y && v.x + v.width <= r.x + r.width
                && v.y + v.height <= r.y + r.height && left && top && right && bottom;
    }

    /** V grown by the margin, matched to the surface aspect, clamped into the desktop. */
    static ViewportRect expand(ViewportRect v, ViewportRect bounds, int streamWidth,
                               int streamHeight, float margin) {
        if (v.width >= bounds.width && v.height >= bounds.height) {
            return bounds;
        }
        float w = v.width * (1f + 2f * margin);
        float h = v.height * (1f + 2f * margin);
        float aspect = streamHeight > 0 ? (float) streamWidth / streamHeight : w / h;
        if (w / h < aspect) {
            w = h * aspect;
        } else {
            h = w / aspect;
        }
        w = Math.min(w, bounds.width);
        h = Math.min(h, bounds.height);
        float cx = v.x + v.width / 2f;
        float cy = v.y + v.height / 2f;
        float x = Math.max(bounds.x, Math.min(cx - w / 2f, bounds.x + bounds.width - w));
        float y = Math.max(bounds.y, Math.min(cy - h / 2f, bounds.y + bounds.height - h));
        int ix = Math.round(x);
        int iy = Math.round(y);
        int iw = Math.min(Math.round(w), bounds.x + bounds.width - ix);
        int ih = Math.min(Math.round(h), bounds.y + bounds.height - iy);
        return new ViewportRect(ix, iy, iw, ih);
    }
}
