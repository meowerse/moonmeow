package com.limelight.meow.stream;

/** Test access to the callbacks meowjni.c invokes, for fake hosts in other packages. */
public final class MeowStreamBridgeAccess {

    private MeowStreamBridgeAccess() {
    }

    /** As the library delivers a 0x3004 POSITION for a visible cursor. */
    public static void cursor(int x, int y, int seq) {
        MeowStreamBridge.onCursorPosition(x, y, true, seq & 0xFFFF);
    }

    /** A 0x3004 POSITION with its visibility flag. */
    public static void cursor(int x, int y, boolean visible, int seq) {
        MeowStreamBridge.onCursorPosition(x, y, visible, seq & 0xFFFF);
    }

    /** As the library delivers a 0x3005 APPLIED. */
    public static void bitrateApplied(int kbps) {
        MeowStreamBridge.onBitrateApplied(kbps);
    }
}
