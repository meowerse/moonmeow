package com.limelight.meow.cursor;

/**
 * Sees every mouse movement the client sends, so the dead-reckoned cursor estimate is fed by
 * all of them. Three one-line hooks in {@code NvConnection}, one per mouse-movement method.
 *
 * <h2>Why at the connection and not in each input mode</h2>
 * Movement reaches the host from at least eight places: the touch trackpad
 * ({@code TrackpadContext}, including its fling momentum), the gaming touch mode
 * ({@code RelativeTouchContext}), the captured physical mouse and touchpad, absolute mouse
 * mode, the on-screen keyboard's mouse keys, gamepad mouse emulation and the stylus/absolute
 * paths. Each used to be a separate place to remember, and the estimate was fed by exactly two
 * of them — so it drifted in every mode it was not wired into. {@code NvConnection} is the one
 * funnel all of them already go through, and it is where the values are already in the units
 * the host receives.
 *
 * <p>A position the host reports itself (0x3004) supersedes all of this; see
 * {@link HostCursor}. The tap still runs then, because an absolute send tells us where the
 * cursor is one round trip before the host can.
 *
 * <p>Calls arrive on whatever thread sent the input — nearly always the UI thread, but gamepad
 * mouse emulation and trackpad fling are timer driven — so the listener must accept any thread.
 * No allocation here.
 */
public final class CursorInputTap {

    /** Receives every mouse movement sent to the host. Any thread. */
    public interface Listener {
        /** {@code LiSendMouseMoveEvent}: a relative move the host applies in its own pixels. */
        void onRelativeMove(int deltaX, int deltaY);

        /**
         * {@code LiSendMousePositionEvent}: an absolute position against a reference size.
         * The value is already mapped into the uncropped frame by the sender.
         */
        void onAbsolutePosition(int x, int y, int referenceWidth, int referenceHeight);

        /**
         * {@code LiSendMouseMoveAsMousePositionEvent}: a relative move that the library turns
         * into an absolute position, added to the last absolute position it sent.
         */
        void onMoveAsPosition(int deltaX, int deltaY, int referenceWidth, int referenceHeight);

        /**
         * A native touch or pen event: the user is pointing at the screen directly. It carries
         * no mouse position, but a host cursor that moves now is under the finger.
         */
        void onDirectPointing();
    }

    private static volatile Listener listener;

    private CursorInputTap() {
    }

    public static void install(Listener l) {
        listener = l;
    }

    /** Clears only if {@code l} is still installed, so an overlapping {@code Game} cannot. */
    public static void uninstall(Listener l) {
        if (listener == l) {
            listener = null;
        }
    }

    /** Hook in {@code NvConnection.sendMouseMove}. */
    public static void relative(short deltaX, short deltaY) {
        Listener l = listener;
        if (l != null) {
            l.onRelativeMove(deltaX, deltaY);
        }
    }

    /** Hook in {@code NvConnection.sendMousePosition}. */
    public static void absolute(short x, short y, short referenceWidth, short referenceHeight) {
        Listener l = listener;
        if (l != null) {
            l.onAbsolutePosition(x, y, referenceWidth, referenceHeight);
        }
    }

    /** Hook in {@code NvConnection.sendTouchEvent} and {@code sendPenEvent}. */
    public static void touch() {
        Listener l = listener;
        if (l != null) {
            l.onDirectPointing();
        }
    }

    /** Hook in {@code NvConnection.sendMouseMoveAsMousePosition}. */
    public static void moveAsPosition(short deltaX, short deltaY,
                                      short referenceWidth, short referenceHeight) {
        Listener l = listener;
        if (l != null) {
            l.onMoveAsPosition(deltaX, deltaY, referenceWidth, referenceHeight);
        }
    }
}
