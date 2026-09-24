package com.limelight.meow.bitrate;

import android.content.Context;

import com.limelight.R;

/**
 * The applied-bitrate line on the performance overlay. One hook in
 * {@code MediaCodecDecoderRenderer}, where the overlay text is assembled once a second.
 *
 * <p>Static because the renderer knows nothing about the session; {@link BitrateSession}
 * publishes into it and clears it at stream end. Shown only once the host has said what it
 * applied — before that there is nothing true to show.
 */
public final class BitrateOverlay {

    private static volatile int appliedKbps;
    private static volatile boolean automatic;

    private BitrateOverlay() {
    }

    static void publish(int kbps, boolean automaticBitrate) {
        automatic = automaticBitrate;
        appliedKbps = kbps;
    }

    static void clear() {
        appliedKbps = 0;
    }

    /** Appends the line, if there is one, in the overlay's own layout. Renderer thread. */
    public static void append(StringBuilder text, Context context, boolean lite) {
        int kbps = appliedKbps;
        if (kbps <= 0 || context == null) {
            return;
        }
        text.append(lite ? "\t" : "\n");
        text.append(context.getString(automatic
                        ? R.string.perf_overlay_meow_applied_bitrate_auto
                        : R.string.perf_overlay_meow_applied_bitrate_host,
                kbps / 1000f));
    }
}
