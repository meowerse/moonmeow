package com.limelight.meow.bitrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ReceiverReporterTest {

    private final List<int[]> sent = new ArrayList<>();
    private int sendResult;
    private long rttInfo = (25L << 32) | 3L;
    private long received;
    private long expected;
    private long bytes;
    private ReceiverReporter reporter;

    @Before
    public void setUp() {
        ReceiverReporter.Stats stats = new ReceiverReporter.Stats() {
            @Override
            public boolean readCounters(int[] out) {
                out[0] = (int) received;
                out[1] = (int) expected;
                out[2] = (int) bytes;
                return true;
            }

            @Override public long rttInfo() { return rttInfo; }
            @Override public int decodeQueueFrames() { return 2; }
            @Override public int averageDecodeMs() { return 6; }
        };
        reporter = new ReceiverReporter(r -> {
            sent.add(new int[] {r.receivedKbps, r.lossPermille, r.rttMs, r.rttVarianceMs,
                    r.decodeQueueFrames, r.avgDecodeMs, r.maxKbps, r.autoBitrate ? 1 : 0,
                    r.intervalMs});
            return sendResult;
        }, stats);
    }

    private void second(long nowMs) {
        received += 900;
        expected += 1000;
        bytes += 1_250_000;
        reporter.tick(nowMs);
    }

    @Test
    public void aReportDescribesTheIntervalWithRealStatistics() {
        reporter.start(0, true, 20000);
        assertTrue(sent.isEmpty());
        second(1000);
        assertEquals(1, sent.size());
        int[] r = sent.get(0);
        assertEquals(10000, r[0]);
        assertEquals(100, r[1]);
        assertEquals(25, r[2]);
        assertEquals(3, r[3]);
        assertEquals(2, r[4]);
        assertEquals(6, r[5]);
        assertEquals("the user's setting is the ceiling", 20000, r[6]);
        assertEquals(1, r[7]);
        assertEquals(1000, r[8]);
    }

    @Test
    public void aHostThatNeverAnswersIsGivenUpOn() {
        reporter.start(0, true, 20000);
        for (int s = 1; s <= 10; s++) {
            second(s * 1000L);
        }
        assertEquals(ReceiverReporter.GIVE_UP_AFTER_REPORTS, sent.size());
        assertFalse(reporter.isRunning());
    }

    @Test
    public void aHostThatAnswersKeepsGettingReports() {
        reporter.start(0, true, 20000);
        second(1000);
        reporter.onApplied(15000, 1100);
        for (int s = 2; s <= 10; s++) {
            second(s * 1000L);
        }
        assertEquals(10, sent.size());
        assertTrue(reporter.isRunning());
        assertEquals(15000, reporter.appliedKbps());
    }

    @Test
    public void aHostWithoutThePacketTypeStopsReportingAtOnce() {
        sendResult = -3;
        reporter.start(0, true, 20000);
        second(1000);
        assertFalse(reporter.isRunning());
        second(2000);
        assertEquals(1, sent.size());
    }

    @Test
    public void noReportGoesOutBeforeThereIsAnRttEstimate() {
        // An unknown RTT would have to go out as 0 ms and become the host's RTT baseline.
        rttInfo = ReceiverReport.RTT_UNKNOWN;
        reporter.start(0, true, 20000);
        second(1000);
        second(2000);
        assertTrue(sent.isEmpty());
        assertTrue("silence without an estimate is not the host's silence", reporter.isRunning());
        rttInfo = (30L << 32) | 4L;
        second(3000);
        assertEquals(1, sent.size());
        // A later dropout of the estimate keeps the last known one.
        rttInfo = ReceiverReport.RTT_UNKNOWN;
        second(4000);
        assertEquals(2, sent.size());
        assertEquals(30, sent.get(1)[2]);
    }

    @Test
    public void transientSendFailuresDoNotCountTowardGivingUp() {
        sendResult = -1;
        reporter.start(0, true, 20000);
        for (int s = 1; s <= 10; s++) {
            second(s * 1000L);
        }
        assertTrue(reporter.isRunning());
        assertFalse(reporter.hostNeverAdapted());
        sendResult = 0;
        for (int s = 11; s <= 15; s++) {
            second(s * 1000L);
        }
        assertFalse(reporter.isRunning());
        assertTrue(reporter.hostNeverAdapted());
    }

    @Test
    public void onlyABitrateHeldForTenSecondsIsRemembered() {
        reporter.start(0, true, 20000);
        reporter.onApplied(15000, 1000);
        reporter.onApplied(9000, 5000);
        reporter.stop(9000);
        assertEquals("15000 was only a waypoint, 9000 not yet held long enough",
                0, reporter.stableKbps());

        reporter.start(20000, true, 20000);
        reporter.onApplied(9000, 20000);
        reporter.stop(20000 + ReceiverReporter.STABLE_MS);
        assertEquals(9000, reporter.stableKbps());
    }

    @Test
    public void aRepeatedAppliedValueDoesNotRestartItsClock() {
        reporter.start(0, true, 20000);
        reporter.onApplied(12000, 0);
        reporter.onApplied(12000, 9000);
        reporter.stop(ReceiverReporter.STABLE_MS);
        assertEquals(12000, reporter.stableKbps());
    }

    @Test
    public void nonsenseAppliedValuesAreIgnored() {
        reporter.onApplied(0, 0);
        reporter.onApplied(-5, 0);
        assertEquals(0, reporter.appliedKbps());
    }
}
