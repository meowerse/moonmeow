package com.limelight.meow.cursor;

import com.limelight.meow.viewport.ViewportReferenceFrame;

/**
 * Where the host cursor is, in the uncropped reference frame (negotiated-stream pixels, the
 * same space as the viewport and 0x3004). Plain Java, single-threaded (the UI thread).
 *
 * <h2>Two sources, one answer</h2>
 * <ul>
 *   <li><b>The host's own report</b> (0x3004 POSITION). Once one arrives this session the
 *       model is in host mode: reported positions are the truth, and relative deltas are
 *       ignored, because only the host knows its pointer acceleration and edge clamping.</li>
 *   <li><b>Dead reckoning</b>, for hosts that do not report. Every movement the client sends
 *       is replayed here (see {@link CursorInputTap}). Absolute positions are exact. Relative
 *       deltas are in the host's <em>desktop</em> pixels, so they are scaled into reference
 *       pixels by the desktop extent the viewport echo carries — without it a 5360-wide desktop
 *       in a 1920-wide stream over-reads every delta 2.8x, which is how the old estimate ran
 *       off the view. Host acceleration is still unknown, so the estimate drifts and is
 *       corrected by the next absolute position or edge.</li>
 * </ul>
 *
 * <p>{@code LiSendMouseMoveAsMousePositionEvent} (absolute mouse mode, gaming touch mode with
 * absolute mouse) is mirrored exactly: the library adds the delta to the last absolute position
 * <em>it</em> sent, starting at the centre, not to wherever relative moves took the cursor.
 */
public final class HostCursor {

    private int streamWidth = 1;
    private int streamHeight = 1;

    /** Where relative deltas may move the cursor: the desktop image inside the frame. */
    private float boundsLeft;
    private float boundsTop;
    private float boundsRight = 1;
    private float boundsBottom = 1;
    /** Reference pixels per host desktop pixel. */
    private float desktopToReferenceX = 1f;
    private float desktopToReferenceY = 1f;

    private boolean known;
    /** The position is the host's, not a guess: reported, or set by an absolute send. */
    private boolean exact;
    private boolean hostReporting;
    private boolean visible = true;
    private float x;
    private float y;

    /** The library's own absolute position, as the fraction it keeps. Starts at the centre. */
    private float libraryFractionX = 0.5f;
    private float libraryFractionY = 0.5f;

    public void onStreamStarted(int streamWidth, int streamHeight) {
        this.streamWidth = Math.max(1, streamWidth);
        this.streamHeight = Math.max(1, streamHeight);
        boundsLeft = 0;
        boundsTop = 0;
        boundsRight = this.streamWidth;
        boundsBottom = this.streamHeight;
        desktopToReferenceX = 1f;
        desktopToReferenceY = 1f;
        known = false;
        exact = false;
        hostReporting = false;
        visible = true;
        libraryFractionX = 0.5f;
        libraryFractionY = 0.5f;
    }

    /**
     * The captured desktop size from the viewport echo, or 0/0 when the host did not report it
     * (relative deltas are then taken as reference pixels, the only guess available).
     */
    public void setDesktopExtent(int desktopWidth, int desktopHeight) {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(desktopWidth, desktopHeight, streamWidth, streamHeight);
        if (frame == null) {
            return;
        }
        boundsLeft = frame.contentX;
        boundsTop = frame.contentY;
        boundsRight = frame.contentX + frame.contentWidth;
        boundsBottom = frame.contentY + frame.contentHeight;
        desktopToReferenceX = (float) frame.contentWidth / desktopWidth;
        desktopToReferenceY = (float) frame.contentHeight / desktopHeight;
    }

    /** 0x3004 from the host. From now on the host is the only source of truth this session. */
    public void onHostPosition(int x, int y, boolean visible) {
        hostReporting = true;
        known = true;
        exact = true;
        this.visible = visible;
        this.x = clamp(x, 0, streamWidth);
        this.y = clamp(y, 0, streamHeight);
    }

    /**
     * A relative move the host applies in desktop pixels.
     *
     * @return true when the estimate moved
     */
    public boolean onRelativeMove(int deltaX, int deltaY) {
        if (hostReporting || !known) {
            return false;
        }
        x = clamp(x + deltaX * desktopToReferenceX, boundsLeft, boundsRight);
        y = clamp(y + deltaY * desktopToReferenceY, boundsTop, boundsBottom);
        // The host applies its own pointer acceleration and we cannot see it: a guess now.
        exact = false;
        return true;
    }

    /** An absolute position the client sent, against its reference size. */
    public void onAbsolutePosition(int x, int y, int referenceWidth, int referenceHeight) {
        if (referenceWidth <= 1 || referenceHeight <= 1) {
            return;
        }
        // LiSendMousePositionEvent: clamp to [0, ref - 1] and keep it as a fraction of ref - 1.
        libraryFractionX = clamp(x, 0, referenceWidth - 1) / (float) (referenceWidth - 1);
        libraryFractionY = clamp(y, 0, referenceHeight - 1) / (float) (referenceHeight - 1);
        adoptLibraryPosition();
    }

    /** {@code LiSendMouseMoveAsMousePositionEvent}, mirrored. */
    public void onMoveAsPosition(int deltaX, int deltaY, int referenceWidth, int referenceHeight) {
        if (referenceWidth <= 1 || referenceHeight <= 1) {
            return;
        }
        int oldX = (short) (libraryFractionX * referenceWidth);
        int oldY = (short) (libraryFractionY * referenceHeight);
        onAbsolutePosition((int) clamp(oldX + deltaX, 0, referenceWidth),
                (int) clamp(oldY + deltaY, 0, referenceHeight), referenceWidth, referenceHeight);
    }

    private void adoptLibraryPosition() {
        // Taken even in host mode: this is exactly where we just put the cursor, a round trip
        // before the host can say so.
        known = true;
        exact = true;
        x = libraryFractionX * streamWidth;
        y = libraryFractionY * streamHeight;
    }

    /** Start dead reckoning from {@code (x, y)} if nothing better is known yet. */
    public void seed(float x, float y) {
        if (known) {
            return;
        }
        known = true;
        exact = false;
        this.x = clamp(x, boundsLeft, boundsRight);
        this.y = clamp(y, boundsTop, boundsBottom);
    }

    /**
     * Forget the dead-reckoned position (pointer capture toggled: the host cursor may have been
     * moved by something we did not see). A host-reported position is kept.
     */
    public void resetEstimate() {
        if (!hostReporting) {
            known = false;
            exact = false;
        }
    }

    /**
     * The client just placed the pointer at (x, y): keep that exact position rather than the
     * library's quantised copy of it. Reference pixels, already clamped by the caller.
     */
    public void placedAt(float x, float y) {
        known = true;
        exact = true;
        this.x = x;
        this.y = y;
    }

    /**
     * True when the position is the host's own: reported over 0x3004, or set by an absolute
     * position the client sent. False while it is a dead-reckoned guess.
     */
    public boolean isExact() {
        return known && exact;
    }

    /** Relative deltas to reference pixels on each axis: {x, y}. */
    public float desktopToReferenceX() {
        return desktopToReferenceX;
    }

    public float desktopToReferenceY() {
        return desktopToReferenceY;
    }

    /** The desktop inside the frame, where the cursor can be: {left, top, right, bottom}. */
    public float boundsLeft() {
        return boundsLeft;
    }

    public float boundsTop() {
        return boundsTop;
    }

    public float boundsRight() {
        return boundsRight;
    }

    public float boundsBottom() {
        return boundsBottom;
    }

    public boolean isKnown() {
        return known;
    }

    public boolean isHostReporting() {
        return hostReporting;
    }

    public boolean isVisible() {
        return visible;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
