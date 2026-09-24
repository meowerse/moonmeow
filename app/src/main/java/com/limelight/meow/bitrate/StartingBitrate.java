package com.limelight.meow.bitrate;

/**
 * The bitrate a stream is negotiated at when automatic bitrate is on. Pure arithmetic.
 *
 * <p>With automatic bitrate the user's bitrate setting stops being "the bitrate" and becomes the
 * <em>ceiling</em>: it is what the receiver reports advertise as {@code max_kbps}, and the host
 * adapts below it. Where to start inside that range is what this decides. Starting at the
 * ceiling every time means every session on a slow link opens with a burst of loss while the
 * host backs off; starting at the bottom means every session on a fast link opens blurry while
 * it climbs. So a session starts where the last one on the same host settled.
 *
 * <p>The remembered value is bounded on both sides. Above by the ceiling, because the user may
 * have lowered it since. Below by {@link #floorFor}, because one bad session — a DERP relay, a
 * train tunnel — must not pin every later session near the host's minimum: the host only probes
 * up a few percent per clean window, and the floor it derives is a quarter of what we
 * negotiate.
 */
public final class StartingBitrate {

    /** Never start below this, whatever was remembered. */
    static final int MIN_START_KBPS = 2000;

    /**
     * Bits per pixel per frame below which a desktop -- text, thin lines, flat colour -- stops
     * being readable in HEVC. 0.04 is about 5 Mbps at 1080p60 and 10 Mbps at 2160x3840 at 30,
     * which is where small text still holds up; below it the encoder smears glyphs.
     */
    static final double READABLE_BITS_PER_PIXEL = 0.04;

    private StartingBitrate() {
    }

    /**
     * @param automatic      the automatic-bitrate preference
     * @param configuredKbps the user's bitrate setting (the ceiling)
     * @param rememberedKbps the last stable applied bitrate for this host, or 0 if none
     * @param width          negotiated stream width
     * @param height         negotiated stream height
     * @param fps            negotiated frame rate
     * @return the bitrate to negotiate: the setting on a first session or with automatic off,
     *         otherwise the remembered value bounded by the setting above and
     *         {@link #floorFor} below
     */
    public static int choose(boolean automatic, int configuredKbps, int rememberedKbps,
                             int width, int height, int fps) {
        if (!automatic || configuredKbps <= 0 || rememberedKbps <= 0) {
            return configuredKbps;
        }
        return Math.max(floorFor(configuredKbps, width, height, fps),
                Math.min(rememberedKbps, configuredKbps));
    }

    /**
     * The lowest start: a quarter of the setting, the readable floor for this resolution and
     * frame rate, and never under {@link #MIN_START_KBPS} -- but never above the setting.
     */
    static int floorFor(int configuredKbps, int width, int height, int fps) {
        long pixelRate = (long) Math.max(0, width) * Math.max(0, height) * Math.max(0, fps);
        int readable = (int) Math.min(Integer.MAX_VALUE,
                pixelRate * READABLE_BITS_PER_PIXEL / 1000.0);
        int floor = Math.max(MIN_START_KBPS, Math.max(configuredKbps / 4, readable));
        return Math.min(configuredKbps, floor);
    }
}
