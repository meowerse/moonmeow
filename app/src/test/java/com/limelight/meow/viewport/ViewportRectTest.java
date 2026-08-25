package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ViewportRectTest {

    @Test
    public void clampsCoordinatesIntoTheUint16WireRange() {
        // 70000 truncated to uint16 is 4464 -- a plausible-looking rectangle in the wrong
        // place. Clamping turns a silent misplacement into an obvious edge case.
        ViewportRect rect = new ViewportRect(70000, -5, 99999, 0);
        assertEquals(ViewportRect.MAX_COORD, rect.x);
        assertEquals(0, rect.y);
        assertEquals(ViewportRect.MAX_COORD, rect.width);
        assertEquals(1, rect.height);
    }

    @Test
    public void widthAndHeightAreNeverZeroBecauseTheProtocolRejectsThat() {
        ViewportRect rect = new ViewportRect(10, 10, 0, -3);
        assertEquals(1, rect.width);
        assertEquals(1, rect.height);
    }

    @Test
    public void fullIsTheWholeStreamFrameAtTheOrigin() {
        ViewportRect full = ViewportRect.full(5360, 1440);
        assertEquals(0, full.x);
        assertEquals(0, full.y);
        assertEquals(5360, full.width);
        assertEquals(1440, full.height);
    }

    @Test
    public void valueEquality() {
        assertEquals(new ViewportRect(1, 2, 3, 4), new ViewportRect(1, 2, 3, 4));
        assertEquals(new ViewportRect(1, 2, 3, 4).hashCode(), new ViewportRect(1, 2, 3, 4).hashCode());
        assertNotEquals(new ViewportRect(1, 2, 3, 4), new ViewportRect(1, 2, 3, 5));
    }
}
