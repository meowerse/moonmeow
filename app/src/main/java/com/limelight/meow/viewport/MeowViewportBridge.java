package com.limelight.meow.viewport;

/**
 * The one native seam moonmeow adds of its own: the viewport request out, and the host's
 * echo back in.
 *
 * <p><b>JNI hazard (CLAUDE.md).</b> {@link #sendViewport} and {@code nativeInit} bind by
 * static mangled name to {@code Java_com_limelight_meow_viewport_MeowViewportBridge_*} in
 * {@code app/src/main/jni/moonlight-core/meowjni.c}. Renaming or moving this class without
 * renaming those symbols produces a build that succeeds and then dies at first call with
 * {@code UnsatisfiedLinkError}. There is deliberately no {@code FindClass} counterpart:
 * {@code nativeInit} hands the native side its {@code jclass} through the JNI calling
 * convention, so the class identity travels with the mangled name and there is no
 * slash-form string to forget. {@code MeowViewportBridgeContractTest} pins both.
 *
 * <p>The library is loaded exactly the way {@code MoonBridge} loads it. A second
 * {@code loadLibrary} of an already-loaded library is a no-op, so ordering between the two
 * classes does not matter.
 *
 * <p><b>This seam fails safe.</b> A JNI binding failure is the one thing here that cannot
 * be verified without a live stream against a real host, so it is not allowed to be fatal:
 * class initialisation swallows a load or bind failure and records it, and {@link #send}
 * then reports {@link ViewportReporter#LI_LIBRARY_UNAVAILABLE} rather than calling into a
 * symbol that is not there. The reporter treats that as "viewport following is unavailable
 * this session, leave the stream alone". A mangled-name mistake costs the feature, not the
 * user's session.
 *
 * <h2>Why the echo comes back through a static</h2>
 * The native callback is a plain C function pointer in
 * {@code CONNECTION_LISTENER_CALLBACKS} with no context parameter, so there is nothing to
 * carry an instance on. Exactly one stream runs at a time, and {@link StreamViewportBinder}
 * registers itself for the life of that stream and deregisters at teardown. The field is
 * {@code volatile} because the echo arrives on moonlight-common-c's async callback thread
 * while registration happens on the UI thread.
 */
public final class MeowViewportBridge implements ViewportReporter.Sender {

    /** Notified when the host echoes the rectangle it actually applied. */
    public interface EchoListener {
        /**
         * @param x             applied left edge, in negotiated-stream-resolution pixels
         * @param y             applied top edge
         * @param width         applied width
         * @param height        applied height
         * @param desktopWidth  captured desktop width in host pixels, or 0 if not reported
         * @param desktopHeight captured desktop height in host pixels, or 0 if not reported
         */
        void onViewportApplied(int x, int y, int width, int height,
                               int desktopWidth, int desktopHeight);
    }

    private static volatile EchoListener echoListener;

    /** True when the native symbols resolved. False makes {@link #send} a no-op. */
    private static final boolean NATIVE_READY = loadNative();

    private static boolean loadNative() {
        try {
            System.loadLibrary("moonlight-core");
        } catch (UnsatisfiedLinkError | SecurityException ignored) {
            // MoonBridge has almost certainly loaded it already. Fall through and let
            // nativeInit() decide -- if the library really is absent, that throws too.
        }
        try {
            nativeInit();
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            // The symbol did not bind. Everything below degrades to "unavailable" rather
            // than taking the stream down.
            return false;
        }
    }

    /**
     * Hands the native side this class object so it can call {@link #onViewportEcho} from
     * the async callback thread. {@code FindClass} on an attached native thread uses the
     * system class loader and cannot see application classes, which is why this has to be
     * pushed from Java rather than pulled from C.
     */
    private static native void nativeInit();

    /**
     * Report the rectangle of the stream frame the client is displaying.
     *
     * <p>Coordinates are in the negotiated stream resolution, not host desktop pixels --
     * see {@link ViewportReporter} for why that is the only space both ends can compute.
     *
     * <p>May only be called between {@code LiStartConnection} and {@code LiStopConnection}.
     * Values outside the {@code uint16} wire range are clamped on the native side.
     *
     * @return 0 on success, -1 for a zero-sized rectangle, -2 if the control stream is not
     *         connected, -3 if this host's generation has no viewport packet type at all.
     *         <b>0 does not mean the host understood the message</b>; only an echo does.
     */
    public static native int sendViewport(int x, int y, int width, int height);

    /**
     * Called from native code when the host echoes an applied viewport.
     *
     * <p>Runs on moonlight-common-c's async callback thread. Nothing may escape from here:
     * an exception crossing back into JNI would be left pending on a thread shared with
     * rumble, HDR and clipboard delivery.
     */
    static void onViewportEcho(int x, int y, int width, int height,
                               int desktopWidth, int desktopHeight) {
        EchoListener listener = echoListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onViewportApplied(x, y, width, height, desktopWidth, desktopHeight);
        } catch (RuntimeException | Error ignored) {
            // A misbehaving listener must not abort a shared native callback thread.
        }
    }

    /** Registers the listener for the current stream. */
    public static void setEchoListener(EchoListener listener) {
        echoListener = listener;
    }

    /**
     * Deregisters {@code listener}, but only if it is still the registered one.
     *
     * <p>Guards the one overlap this static can suffer: if a second {@code Game} registers
     * before the first tears down — which is what a stream restart through PiP looks like —
     * an unconditional clear would silently deregister the live stream's binder and the new
     * session would never see an echo.
     */
    public static void clearEchoListener(EchoListener listener) {
        if (echoListener == listener) {
            echoListener = null;
        }
    }

    /** True when the native symbols resolved and calls will actually reach the library. */
    public static boolean isNativeReady() {
        return NATIVE_READY;
    }

    @Override
    public int send(int x, int y, int width, int height) {
        if (!NATIVE_READY) {
            return ViewportReporter.LI_LIBRARY_UNAVAILABLE;
        }
        try {
            return sendViewport(x, y, width, height);
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return ViewportReporter.LI_LIBRARY_UNAVAILABLE;
        }
    }
}
