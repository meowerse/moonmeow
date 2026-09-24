package com.limelight.meow.stream;

/**
 * The native seam for the two control-stream extensions that ride beside the viewport
 * message: the host cursor (0x3004) and the receiver report / applied bitrate (0x3005).
 * The wire format lives in {@code moonlight-common-c/docs/meow-protocol.md}.
 *
 * <p><b>JNI hazard (CLAUDE.md).</b> Every native method here binds by static mangled name to
 * {@code Java_com_limelight_meow_stream_MeowStreamBridge_*} in
 * {@code app/src/main/jni/moonlight-core/meowjni.c}, and the two callbacks are resolved there
 * by name and descriptor. There is no {@code FindClass}: {@code nativeInit} hands the native
 * side its {@code jclass}, so there is no slash-form string for a package move to leave
 * stale. {@code MeowStreamBridgeContractTest} derives every name from this class object.
 *
 * <p><b>Fails safe</b>, exactly like {@code MeowViewportBridge}: a load or bind failure is
 * recorded at class initialisation and every call then reports
 * {@link #LI_LIBRARY_UNAVAILABLE} instead of throwing, so a JNI mistake costs the feature and
 * never the session.
 *
 * <p><b>Capability.</b> Neither message announces itself. A return of 0 from a send means the
 * library queued it, not that the host understood it. Callers only send once the host has
 * proven it is a meow host (a viewport echo arrived); see {@code MeowHostSession}.
 *
 * <p>Callbacks arrive on moonlight-common-c's async callback thread through static listener
 * slots, because the native callbacks are bare C function pointers with no context. As with
 * the echo listener, deregistration is compare-and-clear so an overlapping {@code Game}
 * (a restart through PiP) cannot deregister the live session.
 */
public final class MeowStreamBridge {

    /** Not a library return code: the native symbols did not bind. */
    public static final int LI_LIBRARY_UNAVAILABLE = -100;

    /** Host cursor positions (0x3004 POSITION). */
    public interface CursorListener {
        /**
         * @param x       cursor hotspot in the uncropped reference frame (negotiated stream
         *                resolution, including the host's aspect padding)
         * @param y       likewise
         * @param visible false while the host cursor is hidden
         * @param seq     the host's per-message counter; wraps at 65536 and may skip
         */
        void onCursorPosition(int x, int y, boolean visible, int seq);
    }

    /** Encoder bitrate changes (0x3005 APPLIED). */
    public interface BitrateListener {
        /** @param kbps the bitrate the host's encoder now runs at; never 0 */
        void onBitrateApplied(int kbps);
    }

    private static volatile CursorListener cursorListener;
    private static volatile BitrateListener bitrateListener;

    private static final boolean NATIVE_READY = loadNative();

    private MeowStreamBridge() {
    }

    private static boolean loadNative() {
        try {
            System.loadLibrary("moonlight-core");
        } catch (UnsatisfiedLinkError | SecurityException ignored) {
            // MoonBridge has almost certainly loaded it already; nativeInit() decides.
        }
        try {
            nativeInit();
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return false;
        }
    }

    private static native void nativeInit();

    private static native int sendCursorSubscribe(boolean subscribe);

    private static native int sendReceiverReport(boolean autoBitrate, int intervalMs,
                                                 int receivedKbps, int lossPermille,
                                                 int rttMs, int rttVarianceMs,
                                                 int decodeQueueFrames, int avgDecodeMs,
                                                 int maxKbps);

    private static native void getVideoNetworkStats(int[] out);

    /** True when the native symbols resolved. */
    public static boolean isNativeReady() {
        return NATIVE_READY;
    }

    /**
     * {@code LiSendCursorSubscribe}. May block up to ~10 ms under ENet backpressure, so never
     * call it on the UI thread. Only between {@code LiStartConnection} and
     * {@code LiStopConnection}.
     *
     * @return 0 when sent, -1/-2/-3 as documented in {@code Limelight.h}, or
     *         {@link #LI_LIBRARY_UNAVAILABLE}
     */
    public static int subscribeCursor(boolean subscribe) {
        if (!NATIVE_READY) {
            return LI_LIBRARY_UNAVAILABLE;
        }
        try {
            return sendCursorSubscribe(subscribe);
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return LI_LIBRARY_UNAVAILABLE;
        }
    }

    /**
     * {@code LiSendReceiverReport}. Same threading rules as {@link #subscribeCursor}.
     * Negative values are clamped to 0 natively, and the 16-bit fields saturate.
     */
    public static int sendReport(boolean autoBitrate, int intervalMs, int receivedKbps,
                                 int lossPermille, int rttMs, int rttVarianceMs,
                                 int decodeQueueFrames, int avgDecodeMs, int maxKbps) {
        if (!NATIVE_READY) {
            return LI_LIBRARY_UNAVAILABLE;
        }
        try {
            return sendReceiverReport(autoBitrate, intervalMs, receivedKbps, lossPermille,
                    rttMs, rttVarianceMs, decodeQueueFrames, avgDecodeMs, maxKbps);
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return LI_LIBRARY_UNAVAILABLE;
        }
    }

    /**
     * {@code LiGetMeowVideoNetworkStats}: fills {@code out[0..2]} with packets received,
     * packets expected and bytes received. Free-running uint32 counters carried in ints;
     * take differences with unsigned arithmetic. Leaves {@code out} untouched and returns
     * false when the library is unavailable.
     */
    public static boolean readVideoNetworkStats(int[] out) {
        if (!NATIVE_READY || out == null || out.length < 3) {
            return false;
        }
        try {
            getVideoNetworkStats(out);
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return false;
        }
    }

    /** Called from meowjni.c on the library's callback thread. Nothing may escape. */
    static void onCursorPosition(int x, int y, boolean visible, int seq) {
        CursorListener listener = cursorListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onCursorPosition(x, y, visible, seq);
        } catch (RuntimeException | Error ignored) {
            // Must not leave an exception pending on a shared native callback thread.
        }
    }

    /** Called from meowjni.c on the library's callback thread. Nothing may escape. */
    static void onBitrateApplied(int kbps) {
        BitrateListener listener = bitrateListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onBitrateApplied(kbps);
        } catch (RuntimeException | Error ignored) {
            // As above.
        }
    }

    public static void setCursorListener(CursorListener listener) {
        cursorListener = listener;
    }

    /** Clears the slot only if {@code listener} is still the registered one. */
    public static void clearCursorListener(CursorListener listener) {
        if (cursorListener == listener) {
            cursorListener = null;
        }
    }

    public static void setBitrateListener(BitrateListener listener) {
        bitrateListener = listener;
    }

    /** Clears the slot only if {@code listener} is still the registered one. */
    public static void clearBitrateListener(BitrateListener listener) {
        if (bitrateListener == listener) {
            bitrateListener = null;
        }
    }
}
