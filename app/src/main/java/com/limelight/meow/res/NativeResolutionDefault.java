package com.limelight.meow.res;

/**
 * Picks the stream resolution a fresh install should start at.
 *
 * <p>Upstream ships a fixed {@code 1280x720} default, and every entry in
 * {@code res/values/arrays.xml} is 16:9. A 16:9 stream shown on a phone that is not 16:9
 * cannot fill the screen: the surface is letterboxed against the display, leaving black
 * bars the user cannot zoom away, because they are outside the video surface rather than
 * inside the frame. On a 20:9 phone that is a bar down each side for the whole session.
 *
 * <p>Defaulting to the device's own panel geometry removes those bars by construction --
 * the stream and the screen have the same aspect, so the video fills it. Bitrate is not a
 * separate worry: {@code PreferenceConfiguration.getDefaultBitrate()} derives from the
 * resolution, so a larger default scales its own bandwidth.
 *
 * <p>Pure integer logic with no Android types, so it is unit testable without a device or
 * Robolectric.
 */
public final class NativeResolutionDefault {

    /** Upstream's fixed default, used whenever the panel geometry is unusable. */
    public static final String FALLBACK = "1280x720";

    /** Widest default we will pick on our own. Beyond this, defer to {@link #FALLBACK}. */
    static final int MAX_WIDTH = 3840;

    /** Tallest default we will pick on our own. */
    static final int MAX_HEIGHT = 2160;

    /**
     * Width/height multiple every YUV420 decoder requires.
     *
     * <p>Deliberately 2, not 16. Some hardware reports a 16 alignment, but clamping to it
     * unconditionally would turn 1920x1080 into 1920x1072 -- and 1080 is the most widely
     * decoded height there is, already shipped as a preset. Whether a specific size is
     * actually decodable is a decoder question, answered by
     * {@code PreferenceConfiguration.isResolutionDecodable()} against real
     * {@code VideoCapabilities}; this only guarantees the chroma-subsampling floor.
     */
    static final int ALIGNMENT = 2;

    private NativeResolutionDefault() {
    }

    /**
     * Resolve the default resolution string for a panel.
     *
     * @param displayWidth  panel width in physical pixels, either orientation
     * @param displayHeight panel height in physical pixels, either orientation
     * @param isTelevision  whether the device reports itself as a TV
     * @return a {@code WxH} string, always landscape-normalised
     */
    public static String resolve(int displayWidth, int displayHeight, boolean isTelevision) {
        // Devices report panel dimensions in either orientation. Normalise to the
        // conventional width > height arrangement, matching StreamSettings' own handling.
        final int width = Math.max(displayWidth, displayHeight);
        final int height = Math.min(displayWidth, displayHeight);

        if (width <= 0 || height <= 0) {
            return FALLBACK;
        }

        // TVs report strange values here -- StreamSettings already refuses to offer native
        // resolutions on a TV for the same reason. A TV is also 16:9 in practice, so it has
        // none of the letterboxing this exists to fix.
        if (isTelevision) {
            return FALLBACK;
        }

        // Above 4K, picking native on the user's behalf commits them to a bitrate and a
        // decode load they never asked for. They can still select it by hand.
        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
            return FALLBACK;
        }

        // An odd dimension cannot be expressed in YUV420 and configures to a black stream
        // rather than throwing -- indistinguishable from the bug this change exists to fix.
        // Rounding down costs at most one pixel per axis.
        final int alignedWidth = align(width);
        final int alignedHeight = align(height);
        if (alignedWidth <= 0 || alignedHeight <= 0) {
            return FALLBACK;
        }

        return alignedWidth + "x" + alignedHeight;
    }

    /** Round down to the nearest multiple of {@link #ALIGNMENT}. */
    private static int align(int value) {
        return value - (value % ALIGNMENT);
    }
}
