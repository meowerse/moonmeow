package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class HostCursorTest {

    private static final int STREAM_W = 1920;
    private static final int STREAM_H = 1080;

    private HostCursor cursor;

    @Before
    public void setUp() {
        cursor = new HostCursor();
        cursor.onStreamStarted(STREAM_W, STREAM_H);
    }

    @Test
    public void nothingIsKnownAtStreamStartAndDeltasNeedASeed() {
        assertFalse(cursor.isKnown());
        assertFalse(cursor.onRelativeMove(10, 10));
        cursor.seed(100f, 200f);
        assertTrue(cursor.onRelativeMove(10, -20));
        assertEquals(110f, cursor.x(), 0f);
        assertEquals(180f, cursor.y(), 0f);
    }

    @Test
    public void relativeDeltasAreScaledByTheEchoedDesktopExtent() {
        // F3: a 5360-wide desktop in a 1920-wide stream. The host moves its cursor 5360 px
        // for a full-width swipe; the estimate must move 1920 reference px, not 5360.
        cursor.setDesktopExtent(5360, 1440);
        cursor.seed(0f, 540f);
        cursor.onRelativeMove(2680, 0);
        assertEquals(960f, cursor.x(), 0.5f);
    }

    @Test
    public void theEstimateStaysOnTheDesktopNotInTheLetterbox() {
        cursor.setDesktopExtent(5360, 1440);
        cursor.seed(960f, 540f);
        cursor.onRelativeMove(0, -100000);
        // 5360x1440 in 1920x1080 is 1920x515 starting at row 282.
        assertEquals(282f, cursor.y(), 0f);
    }

    @Test
    public void anAbsolutePositionIsExactAndMirrorsTheLibrary() {
        cursor.onAbsolutePosition(1356, 610, 2712, 1220);
        assertTrue(cursor.isKnown());
        assertEquals(1356f / 2711f * STREAM_W, cursor.x(), 0.01f);
        assertEquals(610f / 1219f * STREAM_H, cursor.y(), 0.01f);
    }

    @Test
    public void moveAsPositionStartsFromTheLibrarysCentreAndItsLastAbsolute() {
        // LiSendMouseMoveAsMousePositionEvent starts at the centre of the reference.
        cursor.onMoveAsPosition(100, 0, 2000, 1000);
        assertEquals((1000 + 100) / 1999f * STREAM_W, cursor.x(), 0.01f);
        // Relative moves in between do not change the library's base.
        cursor.onRelativeMove(500, 0);
        cursor.onMoveAsPosition(100, 0, 2000, 1000);
        int base = (short) ((1100 / 1999f) * 2000);
        assertEquals((base + 100) / 1999f * STREAM_W, cursor.x(), 0.01f);
    }

    @Test
    public void hostReportsWinAndDeltasAreThenIgnored() {
        cursor.seed(500f, 500f);
        cursor.onHostPosition(1000, 400, true);
        assertTrue(cursor.isHostReporting());
        assertFalse(cursor.onRelativeMove(50, 50));
        assertEquals(1000f, cursor.x(), 0f);
        cursor.resetEstimate();
        assertTrue("a reported position survives a capture toggle", cursor.isKnown());
    }

    @Test
    public void aCaptureToggleForgetsTheEstimate() {
        cursor.seed(500f, 500f);
        cursor.resetEstimate();
        assertFalse(cursor.isKnown());
    }

    @Test
    public void aNewStreamForgetsEverything() {
        cursor.onHostPosition(10, 10, false);
        cursor.onStreamStarted(STREAM_W, STREAM_H);
        assertFalse(cursor.isKnown());
        assertFalse(cursor.isHostReporting());
        assertTrue(cursor.isVisible());
    }
}
