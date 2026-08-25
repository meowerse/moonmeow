package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ViewportThrottleTest {

    private static final int HOST_W = 5360;
    private static final int HOST_H = 1440;

    private static ViewportRect rect(int x, int width) {
        return new ViewportRect(x, 0, width, HOST_H);
    }

    @Test
    public void theFirstRectangleAlwaysGoesOut() {
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        assertEquals(rect(0, 1000), throttle.offer(rect(0, 1000), 0L));
    }

    @Test
    public void anIdenticalRectangleIsDroppedNotDeferred() {
        // Panning against a bound produces a long run of these; none of them is worth a
        // packet, and none of them should keep the settle timer alive either.
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        throttle.offer(rect(0, 1000), 0L);
        assertNull(throttle.offer(rect(0, 1000), 500L));
        assertFalse(throttle.hasPending());
    }

    @Test
    public void aChangeInsideTheRateLimitIsDeferred() {
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        throttle.offer(rect(0, 1000), 0L);
        assertNull(throttle.offer(rect(900, 1000), 10L));
        assertTrue(throttle.hasPending());
        assertEquals(rect(900, 1000), throttle.flush(20L));
    }

    @Test
    public void aChangeAfterTheRateLimitGoesOutImmediately() {
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        throttle.offer(rect(0, 1000), 0L);
        assertEquals(rect(900, 1000), throttle.offer(rect(900, 1000), ViewportThrottle.MIN_INTERVAL_MS));
    }

    @Test
    public void aOnePixelJitterIsNotWorthAPacketButIsStillDelivered() {
        // The threshold at 1440 tall is 7px, so a 1px move is below it. Deferring rather
        // than dropping is what stops the host settling a few pixels off where the user
        // actually stopped.
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        throttle.offer(rect(0, 1000), 0L);
        assertNull(throttle.offer(rect(1, 1000), 10_000L));
        assertTrue(throttle.hasPending());
        assertEquals(rect(1, 1000), throttle.flush(10_001L));
    }

    @Test
    public void theThresholdScalesWithTheHostFrame() {
        // 1440 / 200 = 7
        ViewportThrottle big = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        big.offer(rect(0, 1000), 0L);
        assertNull(big.offer(rect(6, 1000), 1000L));
        assertNotNull(big.offer(rect(7, 1000), 2000L));
    }

    @Test
    public void aTinyHostStillGetsAThresholdOfAtLeastOnePixel() {
        ViewportThrottle tiny = ViewportThrottle.forHostSize(10, 10);
        tiny.offer(new ViewportRect(0, 0, 10, 10), 0L);
        assertNotNull(tiny.offer(new ViewportRect(1, 0, 9, 10), 1000L));
    }

    @Test
    public void flushIgnoresTheRateLimitBecauseItIsTheTrailingEdge() {
        // The whole point of the settle is to land the final rectangle promptly. Applying
        // the rate limit here would leave the host on a stale crop.
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        throttle.offer(rect(0, 1000), 0L);
        throttle.offer(rect(900, 1000), 1L);
        assertEquals(rect(900, 1000), throttle.flush(2L));
    }

    @Test
    public void flushWithNothingOutstandingSendsNothing() {
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        assertNull(throttle.flush(0L));
        throttle.offer(rect(0, 1000), 0L);
        assertNull(throttle.flush(1L));
    }

    @Test
    public void flushDoesNotResendWhatAlreadyWentOut() {
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        throttle.offer(rect(0, 1000), 0L);
        throttle.offer(rect(900, 1000), 1L);          // deferred
        throttle.offer(rect(0, 1000), 2L);            // back to what was already sent
        assertFalse(throttle.hasPending());
        assertNull(throttle.flush(3L));
    }

    @Test
    public void resetForgetsEverythingSoTheNextSessionStartsClean() {
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        throttle.offer(rect(0, 1000), 0L);
        throttle.offer(rect(900, 1000), 1L);
        throttle.reset();
        assertFalse(throttle.hasPending());
        assertNull(throttle.lastSent());
        assertEquals(rect(0, 1000), throttle.offer(rect(0, 1000), 2L));
    }

    @Test
    public void markSentMakesLaterOffersCompareAgainstWhatTheHostWasTold() {
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        throttle.markSent(ViewportRect.full(HOST_W, HOST_H), 0L);
        assertEquals(ViewportRect.full(HOST_W, HOST_H), throttle.lastSent());
        assertNull(throttle.offer(ViewportRect.full(HOST_W, HOST_H), 1000L));
    }

    @Test
    public void aPinchAtTwoHundredHertzProducesAtMostTwentyPacketsPerSecond() {
        // The load this class exists to prevent: one transform per input frame for a second.
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        int sends = 0;
        for (int frame = 0; frame < 200; frame++) {
            long now = frame * 5L;
            if (throttle.offer(rect(frame * 10, 2000), now) != null) {
                sends++;
            }
        }
        assertTrue("sent " + sends + " packets in one second", sends <= 21);
        assertTrue("sent " + sends + " packets in one second", sends >= 19);
    }

    @Test
    public void nullOffersAreIgnored() {
        ViewportThrottle throttle = ViewportThrottle.forHostSize(HOST_W, HOST_H);
        assertNull(throttle.offer(null, 0L));
        assertFalse(throttle.hasPending());
    }
}
