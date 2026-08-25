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
    public void fullCoversTheWholeFrame() {
        assertTrue(ViewportRect.full(5360, 1440).coversAllOf(5360, 1440));
    }

    @Test
    public void aCroppedRectangleDoesNotCoverTheFrame() {
        assertFalse(new ViewportRect(100, 0, 5260, 1440).coversAllOf(5360, 1440));
        assertFalse(new ViewportRect(0, 0, 5359, 1440).coversAllOf(5360, 1440));
    }

    @Test
    public void maxEdgeDeltaMeasuresTheWorstMovingEdge() {
        ViewportRect a = new ViewportRect(100, 100, 200, 200);
        // right edge moves 300 -> 340, everything else moves less
        ViewportRect b = new ViewportRect(100, 105, 240, 195);
        assertEquals(40, a.maxEdgeDelta(b));
    }

    @Test
    public void maxEdgeDeltaAgainstNullIsUnboundedSoTheFirstSendAlwaysHappens() {
        assertEquals(Integer.MAX_VALUE, new ViewportRect(0, 0, 10, 10).maxEdgeDelta(null));
    }

    @Test
    public void valueEquality() {
        assertEquals(new ViewportRect(1, 2, 3, 4), new ViewportRect(1, 2, 3, 4));
        assertEquals(new ViewportRect(1, 2, 3, 4).hashCode(), new ViewportRect(1, 2, 3, 4).hashCode());
        assertNotEquals(new ViewportRect(1, 2, 3, 4), new ViewportRect(1, 2, 3, 5));
    }
}
