package com.limelight.meow.viewport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The echo and the frame it names race each other on independent paths; the gate must say
 * "on screen" exactly once the first frame at or after the target has been presented,
 * whichever arrived first.
 */
public class DecodedFrameGateTest {

    @Before
    @After
    public void clear() {
        DecodedFrameGate.reset();
    }

    private static void queue(int first, int last, long ptsBase) {
        for (int f = first; f <= last; f++) {
            DecodedFrameGate.onFrameQueued(f, ptsBase + f * 16_667L);
        }
    }

    private static long pts(int frame) {
        return 1_000_000L + frame * 16_667L;
    }

    @Test
    public void frameZeroMeansNothingToWaitFor() {
        assertTrue(DecodedFrameGate.hasPresented(0));
    }

    @Test
    public void notPresentedUntilTheTargetFrameIsHandedToTheDisplay() {
        queue(1, 10, 1_000_000L);
        DecodedFrameGate.onFramePresented(pts(6));
        assertFalse(DecodedFrameGate.hasPresented(7));
        DecodedFrameGate.onFramePresented(pts(7));
        assertTrue(DecodedFrameGate.hasPresented(7));
        assertTrue(DecodedFrameGate.hasPresented(3));
    }

    @Test
    public void anEchoThatOvertakesItsFrameWaitsForTheFrameToBeQueued() {
        queue(1, 5, 1_000_000L);
        DecodedFrameGate.onFramePresented(pts(5));
        // Echo names frame 8, not queued yet.
        assertFalse(DecodedFrameGate.hasPresented(8));
        queue(6, 8, 1_000_000L);
        assertFalse(DecodedFrameGate.hasPresented(8));
        DecodedFrameGate.onFramePresented(pts(8));
        assertTrue(DecodedFrameGate.hasPresented(8));
    }

    @Test
    public void aLostTargetFrameIsCoveredByTheNextOne() {
        // Frame 8 never reached the decoder (lost, not recovered); 9 carries the same crop.
        queue(1, 7, 1_000_000L);
        DecodedFrameGate.onFrameQueued(9, pts(9));
        DecodedFrameGate.onFramePresented(pts(7));
        assertFalse(DecodedFrameGate.hasPresented(8));
        DecodedFrameGate.onFramePresented(pts(9));
        assertTrue(DecodedFrameGate.hasPresented(8));
    }

    @Test
    public void aTargetThatLeftTheRingLongAgoIsOnScreen() {
        queue(1, DecodedFrameGate.RING_SIZE * 3, 1_000_000L);
        DecodedFrameGate.onFramePresented(pts(DecodedFrameGate.RING_SIZE * 3));
        assertTrue(DecodedFrameGate.hasPresented(5));
    }

    @Test
    public void nothingCountsBeforeTheFirstPresentedFrame() {
        queue(1, 3, 1_000_000L);
        assertFalse(DecodedFrameGate.hasPresented(1));
    }

    @Test
    public void resetForgetsTheOldStream() {
        queue(1, 50, 1_000_000L);
        DecodedFrameGate.onFramePresented(pts(50));
        DecodedFrameGate.reset();
        assertFalse(DecodedFrameGate.hasPresented(10));
    }
}
