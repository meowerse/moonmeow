package com.limelight.meow.stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.After;
import org.junit.Test;

/**
 * There is no native library on the JVM, so this exercises exactly what a mangled-name
 * mismatch looks like at runtime: every call must degrade to "unavailable", never throw.
 */
public class MeowStreamBridgeTest {

    @After
    public void tearDown() {
        MeowStreamBridge.setCursorListener(null);
        MeowStreamBridge.setBitrateListener(null);
    }

    @Test
    public void withoutTheLibraryEverySendReportsUnavailable() {
        assertFalse(MeowStreamBridge.isNativeReady());
        assertEquals(MeowStreamBridge.LI_LIBRARY_UNAVAILABLE, MeowStreamBridge.subscribeCursor(true));
        assertEquals(MeowStreamBridge.LI_LIBRARY_UNAVAILABLE,
                MeowStreamBridge.sendReport(true, 1000, 5000, 3, 20, 4, 1, 6, 20000));
    }

    @Test
    public void withoutTheLibraryStatsAreLeftUntouched() {
        int[] out = {7, 8, 9};
        assertFalse(MeowStreamBridge.readVideoNetworkStats(out));
        assertArrayEquals(new int[] {7, 8, 9}, out);
    }

    @Test
    public void callbacksReachTheRegisteredListeners() {
        final int[] seen = new int[5];
        MeowStreamBridge.setCursorListener((x, y, visible, seq) -> {
            seen[0] = x;
            seen[1] = y;
            seen[2] = visible ? 1 : 0;
            seen[3] = seq;
        });
        MeowStreamBridge.setBitrateListener(kbps -> seen[4] = kbps);

        MeowStreamBridge.onCursorPosition(640, 360, true, 65535);
        MeowStreamBridge.onBitrateApplied(12000);

        assertArrayEquals(new int[] {640, 360, 1, 65535, 12000}, seen);
    }

    @Test
    public void aThrowingListenerCannotEscapeIntoTheNativeThread() {
        MeowStreamBridge.setCursorListener((x, y, visible, seq) -> {
            throw new IllegalStateException("boom");
        });
        MeowStreamBridge.setBitrateListener(kbps -> {
            throw new AssertionError("boom");
        });
        MeowStreamBridge.onCursorPosition(1, 2, true, 3);
        MeowStreamBridge.onBitrateApplied(4);
    }

    @Test
    public void clearingSomeoneElsesListenerLeavesTheLiveOneRegistered() {
        final int[] calls = new int[1];
        MeowStreamBridge.CursorListener stale = (x, y, visible, seq) -> { };
        MeowStreamBridge.CursorListener live = (x, y, visible, seq) -> calls[0]++;
        MeowStreamBridge.setCursorListener(live);
        MeowStreamBridge.clearCursorListener(stale);
        MeowStreamBridge.onCursorPosition(1, 1, true, 1);
        assertEquals(1, calls[0]);

        MeowStreamBridge.clearCursorListener(live);
        MeowStreamBridge.onCursorPosition(1, 1, true, 2);
        assertEquals(1, calls[0]);
    }
}
