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

    /**
     * The fake desktop. By default it is the size of the stream and fills it, with no pointer
     * acceleration, so {@link #cursorX}/{@link #cursorY} are also reference coordinates.
     * {@link #configure} gives it a real layout: a desktop letterboxed into the stream (as
     * Sunshine pads to preserve aspect) and pointer acceleration on relative motion (as
     * libinput's adaptive profile or Windows' pointer precision apply) -- the two things the
     * client cannot see.
     */
    public static int desktopWidth = 1920;
    public static int desktopHeight = 1080;
    private static int streamWidth = 1920;
    private static int streamHeight = 1080;
    private static float contentX;
    private static float contentY;
    private static float contentWidth = 1920;
    private static float contentHeight = 1080;
    private static float acceleration = 1f;

    /** Host cursor in DESKTOP pixels. With the default layout, also reference pixels. */
    public static float cursorX;
    public static float cursorY;
    public static boolean reporting;
    public static int sends;
    private static float libraryFractionX;
    private static float libraryFractionY;
    private static int seq;

    public static void reset(boolean reportPositions) {
        configure(1920, 1080, 1920, 1080, 1f);
        reporting = reportPositions;
    }

    /** A desktop of the given size, letterboxed into the stream, with relative acceleration. */
    public static void configure(int desktopW, int desktopH, int streamW, int streamH,
                                 float relativeAcceleration) {
        desktopWidth = desktopW;
        desktopHeight = desktopH;
        streamWidth = streamW;
        streamHeight = streamH;
        float scalar = Math.min((float) streamW / desktopW, (float) streamH / desktopH);
        contentWidth = (int) (desktopW * scalar);
        contentHeight = (int) (desktopH * scalar);
        contentX = (streamW - contentWidth) / 2f;
        contentY = (streamH - contentHeight) / 2f;
        acceleration = relativeAcceleration;
        cursorX = desktopW / 2f;
        cursorY = desktopH / 2f;
        libraryFractionX = 0.5f;
        libraryFractionY = 0.5f;
        sends = 0;
        seq = 0;
    }

    /** Where the host cursor is in the uncropped reference frame (stream pixels). */
    public static float referenceX() {
        return contentX + cursorX * contentWidth / desktopWidth;
    }

    public static float referenceY() {
        return contentY + cursorY * contentHeight / desktopHeight;
    }

    private static void moved() {
        sends++;
        cursorX = Math.max(0, Math.min(cursorX, desktopWidth - 1));
        cursorY = Math.max(0, Math.min(cursorY, desktopHeight - 1));
        if (reporting) {
            MeowStreamBridgeAccess.cursor(Math.round(referenceX()), Math.round(referenceY()),
                    ++seq);
        }
    }

    @Implementation
    protected static void sendMouseMove(short deltaX, short deltaY) {
        cursorX += deltaX * acceleration;
        cursorY += deltaY * acceleration;
        moved();
    }

    /** An absolute position: a fraction of the reference frame, padding included. */
    private static void positionAt(float fractionX, float fractionY) {
        cursorX = (fractionX * streamWidth - contentX) * desktopWidth / contentWidth;
        cursorY = (fractionY * streamHeight - contentY) * desktopHeight / contentHeight;
        moved();
    }

    @Implementation
    protected static void sendMousePosition(short x, short y, short referenceWidth,
                                            short referenceHeight) {
        libraryFractionX = Math.max(0, Math.min(x, referenceWidth - 1)) / (float) (referenceWidth - 1);
        libraryFractionY = Math.max(0, Math.min(y, referenceHeight - 1)) / (float) (referenceHeight - 1);
        positionAt(libraryFractionX, libraryFractionY);
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
        positionAt(x, y);
        return 0;
    }
}
