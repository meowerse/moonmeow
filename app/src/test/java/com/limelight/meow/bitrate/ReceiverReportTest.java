package com.limelight.meow.bitrate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReceiverReportTest {

    private static int[] counters(long received, long expected, long bytes) {
        return new int[] {(int) received, (int) expected, (int) bytes};
    }

    @Test
    public void goodputAndLossOverOneSecond() {
        ReceiverReport r = new ReceiverReport();
        r.setNetwork(counters(1000, 1000, 1_000_000), counters(1990, 2000, 2_250_000), 1000);
        // 1.25 MB in a second is 10000 kbps; 10 of 1000 lost is 10 permille.
        assertEquals(10000, r.receivedKbps);
        assertEquals(10, r.lossPermille);
        assertEquals(1000, r.intervalMs);
    }

    @Test
    public void countersThatWrapAreStillDifferencedCorrectly() {
        ReceiverReport r = new ReceiverReport();
        long base = 0xFFFFFF00L;
        r.setNetwork(counters(base, base, base), counters(base + 500, base + 500, base + 125_000),
                1000);
        assertEquals(1000, r.receivedKbps);
        assertEquals(0, r.lossPermille);
    }

    @Test
    public void moreReceivedThanExpectedIsNotNegativeLoss() {
        ReceiverReport r = new ReceiverReport();
        r.setNetwork(counters(0, 0, 0), counters(105, 100, 0), 1000);
        assertEquals(0, r.lossPermille);
    }

    @Test
    public void totalLossIsCappedAtAThousand() {
        ReceiverReport r = new ReceiverReport();
        r.setNetwork(counters(0, 0, 0), counters(0, 400, 0), 1000);
        assertEquals(1000, r.lossPermille);
    }

    @Test
    public void nothingExpectedIsNoLossAndAZeroIntervalIsNoGoodput() {
        ReceiverReport r = new ReceiverReport();
        r.setNetwork(counters(0, 0, 0), counters(0, 0, 5000), 0);
        assertEquals(0, r.lossPermille);
        assertEquals(0, r.receivedKbps);
    }

    @Test
    public void rttInfoIsSplitIntoRttAndVariance() {
        ReceiverReport r = new ReceiverReport();
        r.setRtt((42L << 32) | 7L);
        assertEquals(42, r.rttMs);
        assertEquals(7, r.rttVarianceMs);
    }
}
