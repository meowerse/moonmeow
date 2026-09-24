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

    private StartingBitrate() {
    }

    /**
     * @param automatic      the automatic-bitrate preference
     * @param configuredKbps the user's bitrate setting (the ceiling)
     * @param rememberedKbps the last stable applied bitrate for this host, or 0 if none
     * @return the bitrate to negotiate
     */
    public static int choose(boolean automatic, int configuredKbps, int rememberedKbps) {
        if (!automatic || configuredKbps <= 0 || rememberedKbps <= 0) {
            return configuredKbps;
        }
        return Math.max(floorFor(configuredKbps), Math.min(rememberedKbps, configuredKbps));
    }

    /** The lowest start for a ceiling: a quarter of it, but never under {@link #MIN_START_KBPS}. */
    static int floorFor(int configuredKbps) {
        return Math.min(configuredKbps, Math.max(MIN_START_KBPS, configuredKbps / 4));
    }
}
