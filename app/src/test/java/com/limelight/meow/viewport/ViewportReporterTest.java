package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The state machine, exercised without Android or a connection.
 *
 * <p>The scenario throughout is the one the feature exists for: a 5360x1440 two-monitor
 * desktop negotiated at 1920x516. Sunshine letterboxes that into 1920x515 with one row of
 * padding, which is what {@link ViewportReferenceFrame} reconstructs from the echo.
 */
public class ViewportReporterTest {

    private static final int STREAM_W = 1920;
    private static final int STREAM_H = 516;
    private static final int DESKTOP_W = 5360;
    private static final int DESKTOP_H = 1440;

    /** Records every rectangle handed to the wire and answers with a scripted result code. */
    private static final class FakeSender implements ViewportReporter.Sender {
        final List<ViewportRect> sent = new ArrayList<>();
        int result = ViewportReporter.LI_OK;

        final List<Boolean> forced = new ArrayList<>();

        @Override
        public int send(int x, int y, int width, int height, boolean force) {
            sent.add(new ViewportRect(x, y, width, height));
            forced.add(force);
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
        public void schedule(long delayMs, Runnable task) {
            this.delayMs = delayMs;
            this.task = task;
        }

        @Override
        public void cancel() {
            cancels++;
            task = null;
        }

        boolean armed() {
            return task != null;
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

    private void startSupportedStream() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        echoFullContent();
    }

    /** The echo a sunmeow host sends for an uncropped 5360x1440 desktop at 1920x516. */
    private void echoFullContent() {
        ViewportReferenceFrame frame =
                ViewportReferenceFrame.of(DESKTOP_W, DESKTOP_H, STREAM_W, STREAM_H);
        assertNotNull(frame);
        ViewportRect full = frame.fullContent();
        reporter.onViewportApplied(full.x, full.y, full.width, full.height,
                DESKTOP_W, DESKTOP_H);
    }

    // ---- construction -------------------------------------------------------------

    @Test(expected = IllegalArgumentException.class)
    public void aSenderIsRequired() {
        new ViewportReporter(null, scheduler);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSchedulerIsRequired() {
        new ViewportReporter(sender, null);
    }

    // ---- the preference -----------------------------------------------------------

    @Test
    public void nothingIsSentWhileThePreferenceIsOff() {
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        reporter.onVisibleRectChanged(new ViewportRect(100, 100, 400, 200));
        assertTrue(sender.sent.isEmpty());
        assertEquals(ViewportReporter.HostSupport.UNSUPPORTED, reporter.hostSupport());
    }

    @Test
    public void enablingDoesNotProbeUntilTheNextStreamStarts() {
        // The preference is read once in Game.onCreate and there is no route back to
        // Settings mid-stream, so this is documentation of an intentional limit, not a bug.
        reporter.setEnabled(true);
        assertTrue(sender.sent.isEmpty());
    }

    // ---- capability probing -------------------------------------------------------

    @Test
    public void aStreamStartProbesWithTheFullFrameAndArmsTheDeadline() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        assertEquals(1, sender.sent.size());
        assertEquals(new ViewportRect(0, 0, STREAM_W, STREAM_H), sender.last());
        assertTrue(scheduler.armed());
        assertEquals(ViewportReporter.ECHO_DEADLINE_MS, scheduler.delayMs);
        assertEquals(ViewportReporter.HostSupport.PROBING, reporter.hostSupport());
    }

    @Test
    public void nothingElseGoesOnTheWireWhileTheProbeIsOutstanding() {
        // This is the whole point of probing: against a host that has never heard of the
        // extension, LiSendViewportEvent returns 0 and really does transmit. A client that
        // trusted that would talk to stock Sunshine 20 times a second for the session.
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        for (int i = 0; i < 50; i++) {
            reporter.onVisibleRectChanged(new ViewportRect(i, 0, 400, 200));
        }
        assertEquals("only the probe", 1, sender.sent.size());
    }

    @Test
    public void anUnansweredProbeIsRetriedOnceAndThenTheHostIsWrittenOff() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        assertEquals(1, sender.sent.size());

        scheduler.fire();
        assertEquals("the retry", 2, sender.sent.size());
        assertEquals(ViewportReporter.HostSupport.PROBING, reporter.hostSupport());

        scheduler.fire();
        assertEquals("no third packet", ViewportReporter.PROBE_ATTEMPTS, sender.sent.size());
        assertEquals(ViewportReporter.HostSupport.UNSUPPORTED, reporter.hostSupport());
        assertFalse(reporter.isLive());
    }

    @Test
    public void theRetryProbeIsForcedSoTheLibraryCannotDeduplicateItAway() {
        // The retry carries the same rectangle as the first probe by construction, and
        // LiSendViewportEvent drops a rectangle the host already has -- returning 0 having
        // sent nothing. Without the bypass the retry would be theatre, and the race it
        // exists for (a probe landing before the host published its capture geometry)
        // would be permanent.
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        scheduler.fire();
        assertEquals(2, sender.sent.size());
        assertEquals("both probes must bypass the library's dedup",
                java.util.Arrays.asList(true, true), sender.forced);
    }

    @Test
    public void ordinaryRectanglesAreNotForced() {
        // The rate limit and the redundant-rectangle drop are exactly what we want on a
        // gesture path; only probes may bypass them.
        startSupportedStream();
        sender.forced.clear();
        reporter.onVisibleRectChanged(new ViewportRect(100, 0, 400, 200));
        assertEquals(java.util.Arrays.asList(false), sender.forced);
    }

    @Test
    public void theTerminalUncropIsNotForcedBecauseTheLibraryFlushesItAtTeardown() {
        // stopControlStream() calls flushFinalViewportEvent(), which ignores the rate limit,
        // so the uncrop lands even if it was coalesced. Forcing here would only add a packet.
        startSupportedStream();
        sender.forced.clear();
        reporter.onStreamStopped();
        assertEquals(java.util.Arrays.asList(false), sender.forced);
    }

    @Test
    public void aHostThatIsWrittenOffNeverHearsFromUsAgainThisSession() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        scheduler.fire();
        scheduler.fire();
        int afterProbes = sender.sent.size();

        for (int i = 0; i < 100; i++) {
            reporter.onVisibleRectChanged(new ViewportRect(i, 0, 400, 200));
        }
        reporter.onStreamStopped();
        assertEquals(afterProbes, sender.sent.size());
    }

    @Test
    public void aLateEchoAfterTheHostWasWrittenOffIsIgnored() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        scheduler.fire();
        scheduler.fire();
        echoFullContent();
        assertEquals(ViewportReporter.HostSupport.UNSUPPORTED, reporter.hostSupport());
        assertFalse(reporter.isActive());
    }

    @Test
    public void anEchoMakesTheHostSupportedAndCancelsTheDeadline() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        echoFullContent();
        assertEquals(ViewportReporter.HostSupport.SUPPORTED, reporter.hostSupport());
        assertFalse(scheduler.armed());
        assertTrue(reporter.isActive());
    }

    @Test
    public void aRectangleDeferredDuringProbingIsSentAsSoonAsTheHostAnswers() {
        // Otherwise a user who is already zoomed in when the stream starts sees nothing
        // happen until they next move a finger.
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        reporter.onVisibleRectChanged(new ViewportRect(960, 0, 480, 300));
        assertEquals(1, sender.sent.size());

        echoFullContent();
        assertEquals(2, sender.sent.size());
        assertEquals(new ViewportRect(960, 0, 480, 300), sender.last());
    }

    @Test
    public void onlyTheLastDeferredRectangleSurvivesTheProbeWindow() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        reporter.onVisibleRectChanged(new ViewportRect(10, 0, 400, 200));
        reporter.onVisibleRectChanged(new ViewportRect(20, 0, 400, 200));
        reporter.onVisibleRectChanged(new ViewportRect(30, 0, 400, 200));
        echoFullContent();
        assertEquals(2, sender.sent.size());
        assertEquals(new ViewportRect(30, 0, 400, 200), sender.last());
    }

    // ---- library refusals ---------------------------------------------------------

    @Test
    public void aHostGenerationWithNoViewportPacketTypeLatchesTheFeatureOff() {
        sender.result = ViewportReporter.LI_NO_PACKET_TYPE;
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        assertEquals(ViewportReporter.HostSupport.UNSUPPORTED, reporter.hostSupport());
        assertFalse(scheduler.armed());
        assertEquals("one probe, then silence", 1, sender.sent.size());
    }

    @Test
    public void aMissingNativeSymbolLatchesTheFeatureOff() {
        sender.result = ViewportReporter.LI_LIBRARY_UNAVAILABLE;
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        assertEquals(ViewportReporter.HostSupport.UNSUPPORTED, reporter.hostSupport());
        assertEquals(1, sender.sent.size());
    }

    @Test
    public void aTransientSendFailureStillArmsTheDeadlineSoTheProbeIsRetried() {
        // LI_NOT_CONNECTED at stream start means the control stream lost a race with the
        // connectionStarted callback. Retrying is exactly right; giving up is not.
        sender.result = ViewportReporter.LI_NOT_CONNECTED;
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        assertEquals(ViewportReporter.HostSupport.PROBING, reporter.hostSupport());
        assertTrue(scheduler.armed());

        sender.result = ViewportReporter.LI_OK;
        scheduler.fire();
        assertEquals(2, sender.sent.size());
        echoFullContent();
        assertEquals(ViewportReporter.HostSupport.SUPPORTED, reporter.hostSupport());
    }

    // ---- reporting ----------------------------------------------------------------

    @Test
    public void everyRectangleIsOfferedToTheLibraryOnceTheHostIsSupported() {
        // No throttle of our own: LiSendViewportEvent already rate limits to 50ms, drops
        // duplicates and flushes the trailing rectangle. Adding a second layer here only
        // delayed the final rectangle.
        startSupportedStream();
        int before = sender.sent.size();
        for (int i = 0; i < 10; i++) {
            reporter.onVisibleRectChanged(new ViewportRect(100 + i, 0, 400, 200));
        }
        assertEquals(before + 10, sender.sent.size());
    }

    @Test
    public void aNullRectangleIsIgnored() {
        startSupportedStream();
        int before = sender.sent.size();
        reporter.onVisibleRectChanged(null);
        assertEquals(before, sender.sent.size());
    }

    // ---- the echo's desktop extent ------------------------------------------------

    @Test
    public void theHostsDesktopSizeIsAdoptedFromTheEcho() {
        startSupportedStream();
        assertEquals(DESKTOP_W, reporter.desktopWidth());
        assertEquals(DESKTOP_H, reporter.desktopHeight());
        assertNotNull(reporter.referenceFrame());
    }

    @Test
    public void anEchoWithoutADesktopExtentLeavesTheReferenceFrameUnknown() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        reporter.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0);
        assertEquals(ViewportReporter.HostSupport.SUPPORTED, reporter.hostSupport());
        assertEquals(0, reporter.desktopWidth());
        assertNull(reporter.referenceFrame());
    }

    @Test
    public void withoutAReferenceFrameRectanglesGoOutUnclamped() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        reporter.onViewportApplied(0, 0, STREAM_W, STREAM_H, 0, 0);
        reporter.onVisibleRectChanged(new ViewportRect(0, 0, STREAM_W, STREAM_H));
        assertEquals(new ViewportRect(0, 0, STREAM_W, STREAM_H), sender.last());
    }

    @Test
    public void onceTheReferenceFrameIsKnownRectanglesAreClampedIntoTheContentArea() {
        // The padding rows map to no desktop at all. Asking for them makes the host refuse
        // the request and stream the whole desktop -- the opposite of what was wanted.
        startSupportedStream();
        ViewportReferenceFrame frame = reporter.referenceFrame();
        assertNotNull(frame);

        reporter.onVisibleRectChanged(new ViewportRect(0, 0, STREAM_W, STREAM_H));
        assertEquals(frame.fullContent(), sender.last());
    }

    @Test
    public void aRectangleEntirelyInThePaddingBecomesTheFullContentArea() {
        startSupportedStream();
        ViewportReferenceFrame frame = reporter.referenceFrame();
        assertNotNull(frame);
        int belowContent = frame.contentY + frame.contentHeight;
        if (belowContent >= STREAM_H) {
            // This negotiation has no padding to test with; the 1920x1080 case does.
            return;
        }
        reporter.onVisibleRectChanged(
                new ViewportRect(0, belowContent, STREAM_W, STREAM_H - belowContent));
        assertEquals(frame.fullContent(), sender.last());
    }

    @Test
    public void aNonsensicalEchoedRectangleIsNotRecordedButStillProvesSupport() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        // Wider than the stream frame: a host bug, or a hostile peer. Either way it is not
        // evidence about where the crop is.
        reporter.onViewportApplied(0, 0, STREAM_W * 4, STREAM_H, DESKTOP_W, DESKTOP_H);
        assertEquals(ViewportReporter.HostSupport.SUPPORTED, reporter.hostSupport());
        assertNull(reporter.appliedRect());
    }

    @Test
    public void anAbsurdDesktopExtentIsDiscardedRatherThanUsed() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        reporter.onViewportApplied(0, 0, STREAM_W, STREAM_H, -5, 999999);
        assertEquals(0, reporter.desktopWidth());
        assertNull(reporter.referenceFrame());
    }

    // ---- never leave the host cropped ---------------------------------------------

    @Test
    public void stoppingWhileZoomedUncropsTheHost() {
        startSupportedStream();
        reporter.onVisibleRectChanged(new ViewportRect(960, 100, 400, 200));
        reporter.onStreamStopped();
        assertEquals(reporter.referenceFrame().fullContent(), sender.last());
    }

    @Test
    public void theUncropIsUnconditionalRatherThanGuessedFromAsynchronousEchoes() {
        // Being wrong about "am I cropped?" once is unrecoverable: there is no session left
        // to correct it. The library drops a redundant rectangle anyway.
        startSupportedStream();
        int before = sender.sent.size();
        reporter.onStreamStopped();
        assertEquals(before + 1, sender.sent.size());
    }

    @Test
    public void stoppingAnUnsupportedStreamSendsNothing() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(STREAM_W, STREAM_H);
        scheduler.fire();
        scheduler.fire();
        int before = sender.sent.size();
        reporter.onStreamStopped();
        assertEquals(before, sender.sent.size());
    }

    @Test
    public void nothingIsSentAfterTheStreamStops() {
        startSupportedStream();
        reporter.onStreamStopped();
        int after = sender.sent.size();
        reporter.onVisibleRectChanged(new ViewportRect(10, 10, 100, 100));
        reporter.onViewportApplied(0, 0, 100, 100, DESKTOP_W, DESKTOP_H);
        assertEquals(after, sender.sent.size());
        assertFalse(reporter.isActive());
    }

    @Test
    public void turningThePreferenceOffMidStreamUncropsFirst() {
        startSupportedStream();
        reporter.onVisibleRectChanged(new ViewportRect(960, 100, 400, 200));
        reporter.setEnabled(false);
        assertEquals(reporter.referenceFrame().fullContent(), sender.last());
        assertFalse(reporter.isActive());
    }

    // ---- session isolation --------------------------------------------------------

    @Test
    public void aNewStreamNeverInheritsThePreviousOnesHostState() {
        startSupportedStream();
        reporter.onVisibleRectChanged(new ViewportRect(960, 100, 400, 200));
        reporter.onStreamStopped();

        sender.sent.clear();
        reporter.onStreamStarted(1280, 720);
        assertEquals(ViewportReporter.HostSupport.PROBING, reporter.hostSupport());
        assertEquals(0, reporter.desktopWidth());
        assertNull(reporter.referenceFrame());
        assertNull(reporter.appliedRect());
        assertEquals(new ViewportRect(0, 0, 1280, 720), sender.last());
        assertEquals(1280, reporter.streamWidth());
        assertEquals(720, reporter.streamHeight());
    }

    @Test
    public void aDegenerateStreamSizeIsClampedRatherThanDividedBy() {
        reporter.setEnabled(true);
        reporter.onStreamStarted(0, -4);
        assertEquals(1, reporter.streamWidth());
        assertEquals(1, reporter.streamHeight());
        assertEquals(new ViewportRect(0, 0, 1, 1), sender.last());
    }
}
