package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ViewportReporterTest {

    private static final int HOST_W = 5360;
    private static final int HOST_H = 1440;

    /** Records every rectangle handed to the wire and answers with a scripted result code. */
    private static final class FakeSender implements ViewportReporter.Sender {
        final List<ViewportRect> sent = new ArrayList<>();
        int result = ViewportReporter.LI_OK;

        @Override
        public int send(int x, int y, int width, int height) {
            sent.add(new ViewportRect(x, y, width, height));
            return result;
        }

        ViewportRect last() {
            return sent.isEmpty() ? null : sent.get(sent.size() - 1);
        }
    }

    private static final class FakeScheduler implements ViewportReporter.Scheduler {
        Runnable task;
        long delayMs = -1;
        int cancels;

        @Override
        public void scheduleSettle(long delayMs, Runnable task) {
            this.delayMs = delayMs;
            this.task = task;
        }

        @Override
        public void cancelSettle() {
            cancels++;
            task = null;
        }

        void fire() {
            Runnable t = task;
            task = null;
            if (t != null) {
                t.run();
            }
        }
    }

    private FakeSender sender;
    private FakeScheduler scheduler;
    private ViewportReporter reporter;

    @Before
    public void setUp() {
        sender = new FakeSender();
        scheduler = new FakeScheduler();
        reporter = new ViewportReporter(sender, scheduler);
    }

    private static ViewportRect zoomed() {
        return new ViewportRect(1000, 200, 1340, 360);
    }

    // --- disabled by default ------------------------------------------------------------

    @Test
    public void aDisabledReporterNeverTouchesTheWire() {
        reporter.onStreamStarted(HOST_W, HOST_H, 0L);
        reporter.onVisibleRectChanged(zoomed(), 100L);
        reporter.settleNow();
        reporter.onStreamStopped(200L);
        assertTrue(sender.sent.isEmpty());
        assertFalse(reporter.isActive());
    }

    // --- lifecycle ----------------------------------------------------------------------

    @Test
    public void startingAStreamUncropsTheHostBeforeAnythingElse() {
        reporter.setEnabled(true, 0L);
        reporter.onStreamStarted(HOST_W, HOST_H, 0L);
        assertEquals(1, sender.sent.size());
        assertEquals(ViewportRect.full(HOST_W, HOST_H), sender.last());
    }

    @Test
    public void stoppingWhileCroppedRestoresTheFullDesktop() {
        // A client that disconnects zoomed must not leave the next session cropped.
        startEnabled();
        reporter.onVisibleRectChanged(zoomed(), 1000L);
        assertEquals(zoomed(), sender.last());

        reporter.onStreamStopped(2000L);
        assertEquals(ViewportRect.full(HOST_W, HOST_H), sender.last());
    }

    @Test
    public void stoppingWhileAlreadyUncroppedSendsNothingExtra() {
        startEnabled();
        int afterStart = sender.sent.size();
        reporter.onStreamStopped(2000L);
        assertEquals(afterStart, sender.sent.size());
    }

    @Test
    public void nothingIsSentAfterTheStreamStops() {
        startEnabled();
        reporter.onStreamStopped(1000L);
        int afterStop = sender.sent.size();
        reporter.onVisibleRectChanged(zoomed(), 2000L);
        reporter.settleNow();
        assertEquals(afterStop, sender.sent.size());
    }

    @Test
    public void aSecondSessionStartsFromTheFullDesktop() {
        startEnabled();
        reporter.onVisibleRectChanged(zoomed(), 1000L);
        reporter.onStreamStopped(2000L);
        sender.sent.clear();

        reporter.onStreamStarted(HOST_W, HOST_H, 3000L);
        assertEquals(ViewportRect.full(HOST_W, HOST_H), sender.last());
    }

    // --- zooming back out ---------------------------------------------------------------

    @Test
    public void returningToOneToOneUncropsTheHost() {
        startEnabled();
        reporter.onVisibleRectChanged(zoomed(), 1000L);
        reporter.onVisibleRectChanged(ViewportRect.full(HOST_W, HOST_H), 2000L);
        assertEquals(ViewportRect.full(HOST_W, HOST_H), sender.last());
    }

    @Test
    public void turningThePreferenceOffMidSessionUncropsTheHost() {
        startEnabled();
        reporter.onVisibleRectChanged(zoomed(), 1000L);
        reporter.setEnabled(false, 2000L);
        assertEquals(ViewportRect.full(HOST_W, HOST_H), sender.last());
        assertFalse(reporter.isActive());
    }

    // --- host that does not implement the extension --------------------------------------

    @Test
    public void anUnsupportedHostSilencesTheFeatureForTheWholeSession() {
        // Stock Sunshine, an older sunmeow, anything else: -3 is not an error, it means the
        // stream behaves exactly as it does today.
        sender.result = ViewportReporter.LI_UNSUPPORTED;
        reporter.setEnabled(true, 0L);
        reporter.onStreamStarted(HOST_W, HOST_H, 0L);
        assertEquals(1, sender.sent.size());
        assertFalse(reporter.isHostSupported());
        assertFalse(reporter.isActive());

        sender.result = ViewportReporter.LI_OK;
        reporter.onVisibleRectChanged(zoomed(), 1000L);
        reporter.settleNow();
        assertEquals("no further probing after -3", 1, sender.sent.size());
    }

    @Test
    public void anUnsupportedHostIsNotUncroppedOnStopBecauseItWasNeverCropped() {
        sender.result = ViewportReporter.LI_UNSUPPORTED;
        startEnabled();
        int afterStart = sender.sent.size();
        reporter.onStreamStopped(1000L);
        assertEquals(afterStart, sender.sent.size());
    }

    @Test
    public void aTransientFailureIsRetriedRatherThanAssumedDelivered() {
        // -2 means the control stream was not up yet. The rectangle was never queued, so it
        // must not be recorded as the host's current state.
        reporter.setEnabled(true, 0L);
        sender.result = ViewportReporter.LI_NOT_CONNECTED;
        reporter.onStreamStarted(HOST_W, HOST_H, 0L);
        assertTrue(reporter.isActive());

        sender.result = ViewportReporter.LI_OK;
        reporter.onVisibleRectChanged(zoomed(), 10L);
        assertEquals(zoomed(), sender.last());
    }

    // --- throttling and the settle -------------------------------------------------------

    @Test
    public void rapidUpdatesAreCoalescedAndTheLastOneStillLands() {
        startEnabled();
        sender.sent.clear();

        for (int frame = 0; frame < 10; frame++) {
            reporter.onVisibleRectChanged(new ViewportRect(frame * 100, 0, 1340, HOST_H), 1000L + frame * 5L);
        }
        assertTrue("coalesced", sender.sent.size() < 10);
        assertTrue("a settle is pending", scheduler.task != null);
        assertEquals(ViewportReporter.SETTLE_DELAY_MS, scheduler.delayMs);

        scheduler.fire();
        assertEquals(new ViewportRect(900, 0, 1340, HOST_H), sender.last());
    }

    @Test
    public void theSettleIsCancelledWhenTheStreamStops() {
        startEnabled();
        reporter.onVisibleRectChanged(new ViewportRect(0, 0, 1340, HOST_H), 1000L);
        reporter.onVisibleRectChanged(new ViewportRect(50, 0, 1340, HOST_H), 1002L);
        int before = scheduler.cancels;
        reporter.onStreamStopped(1003L);
        assertTrue(scheduler.cancels > before);
    }

    @Test
    public void aSettleThatFiresWithNothingOutstandingSendsNothing() {
        startEnabled();
        int afterStart = sender.sent.size();
        reporter.settleNow();
        assertEquals(afterStart, sender.sent.size());
    }

    @Test
    public void aNullRectangleIsIgnored() {
        startEnabled();
        int afterStart = sender.sent.size();
        reporter.onVisibleRectChanged(null, 1000L);
        assertEquals(afterStart, sender.sent.size());
    }

    @Test
    public void hostDimensionsAreClampedSoTheyCanNeverBeZero() {
        reporter.setEnabled(true, 0L);
        reporter.onStreamStarted(0, -4, 0L);
        assertEquals(1, reporter.hostWidth());
        assertEquals(1, reporter.hostHeight());
    }

    @Test
    public void aSenderAndSchedulerAreRequired() {
        try {
            new ViewportReporter(null, scheduler);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new ViewportReporter(sender, null);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private void startEnabled() {
        reporter.setEnabled(true, 0L);
        reporter.onStreamStarted(HOST_W, HOST_H, 0L);
    }
}
