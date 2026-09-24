package com.limelight.shadows;

import com.limelight.meow.stream.MeowStreamBridge;
import com.limelight.meow.stream.MeowStreamBridgeAccess;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * {@link ShadowMoonBridge} plus a fake meow host behind the mouse and touch natives: the host
 * integrates what the client sends into a cursor on a desktop the size of the stream, and —
 * when {@link #reporting} is on — answers with 0x3004 positions through
 * {@link MeowStreamBridge}, exactly as the library's callback would. Lets a test drive real
 * {@code MotionEvent}s through {@code Game} and check the view follows the host cursor.
 */
@Implements(value = com.limelight.nvstream.jni.MoonBridge.class, isInAndroidSdk = false)
public class ShadowMoonBridgeWithHost extends ShadowMoonBridge {

    public static int desktopWidth = 1920;
    public static int desktopHeight = 1080;
    public static float cursorX;
    public static float cursorY;
    public static boolean reporting;
    public static int sends;
    private static float libraryFractionX;
    private static float libraryFractionY;
    private static int seq;

    public static void reset(boolean reportPositions) {
        cursorX = desktopWidth / 2f;
        cursorY = desktopHeight / 2f;
        libraryFractionX = 0.5f;
        libraryFractionY = 0.5f;
        reporting = reportPositions;
        sends = 0;
        seq = 0;
    }

    private static void moved() {
        sends++;
        cursorX = Math.max(0, Math.min(cursorX, desktopWidth - 1));
        cursorY = Math.max(0, Math.min(cursorY, desktopHeight - 1));
        if (reporting) {
            MeowStreamBridgeAccess.cursor((int) cursorX, (int) cursorY, ++seq);
        }
    }

    @Implementation
    protected static void sendMouseMove(short deltaX, short deltaY) {
        cursorX += deltaX;
        cursorY += deltaY;
        moved();
    }

    @Implementation
    protected static void sendMousePosition(short x, short y, short referenceWidth,
                                            short referenceHeight) {
        libraryFractionX = Math.max(0, Math.min(x, referenceWidth - 1)) / (float) (referenceWidth - 1);
        libraryFractionY = Math.max(0, Math.min(y, referenceHeight - 1)) / (float) (referenceHeight - 1);
        cursorX = libraryFractionX * desktopWidth;
        cursorY = libraryFractionY * desktopHeight;
        moved();
    }

    @Implementation
    protected static void sendMouseMoveAsMousePosition(short deltaX, short deltaY,
                                                       short referenceWidth, short referenceHeight) {
        short oldX = (short) (libraryFractionX * referenceWidth);
        short oldY = (short) (libraryFractionY * referenceHeight);
        sendMousePosition((short) Math.max(0, Math.min(oldX + deltaX, referenceWidth)),
                (short) Math.max(0, Math.min(oldY + deltaY, referenceHeight)),
                referenceWidth, referenceHeight);
    }

    @Implementation
    protected static int sendTouchEvent(byte eventType, int pointerId, float x, float y,
                                        float pressure, float contactAreaMajor,
                                        float contactAreaMinor, short rotation) {
        // A host that moves its pointer to the touch, as a desktop compositor does.
        cursorX = x * desktopWidth;
        cursorY = y * desktopHeight;
        moved();
        return 0;
    }
}
