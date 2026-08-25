package com.limelight.meow.viewport;

/**
 * The one native entry point moonmeow adds of its own.
 *
 * <p><b>JNI hazard (CLAUDE.md).</b> {@link #sendViewport} binds by static mangled name to
 * {@code Java_com_limelight_meow_viewport_MeowViewportBridge_sendViewport} in
 * {@code app/src/main/jni/moonlight-core/meowjni.c}. Renaming or moving this class without
 * renaming that symbol produces a build that succeeds and then dies at first call with
 * {@code UnsatisfiedLinkError}. There is no {@code FindClass} counterpart because nothing
 * calls back into Java from that file.
 *
 * <p>The library is loaded exactly the way {@code MoonBridge} loads it. A second
 * {@code loadLibrary} of an already-loaded library is a no-op, so ordering between the two
 * classes does not matter.
 *
 * <p><b>This seam fails safe.</b> A JNI binding failure is the one thing here that cannot be
 * verified without a live stream against a real host, so it is not allowed to be fatal:
 * class initialisation swallows a load failure, and {@link #send} converts a missing symbol
 * into {@link ViewportReporter#LI_UNSUPPORTED}, which the reporter already handles as
 * "viewport following is unavailable, leave the stream alone". A mangled-name mistake
 * therefore costs the feature, not the user's session.
 */
public final class MeowViewportBridge implements ViewportReporter.Sender {

    static {
        try {
            System.loadLibrary("moonlight-core");
        } catch (Throwable ignored) {
            // MoonBridge has almost certainly loaded it already; if neither can, send()
            // below degrades to "host unsupported" rather than taking the stream down.
        }
    }

    /**
     * Report the rectangle of the host desktop the client is displaying.
     *
     * <p>May only be called between {@code LiStartConnection} and {@code LiStopConnection}.
     * Values outside the {@code uint16} wire range are clamped on the native side.
     *
     * @return 0 on success, -1 for a zero-sized rectangle, -2 if the control stream is not
     *         connected, -3 if the host does not implement the viewport extension
     */
    public static native int sendViewport(int x, int y, int width, int height);

    @Override
    public int send(int x, int y, int width, int height) {
        try {
            return sendViewport(x, y, width, height);
        } catch (UnsatisfiedLinkError e) {
            // The symbol did not bind. Report it as "host does not support viewports" so the
            // reporter shuts the feature down for the session and the stream is untouched.
            return ViewportReporter.LI_UNSUPPORTED;
        }
    }
}
