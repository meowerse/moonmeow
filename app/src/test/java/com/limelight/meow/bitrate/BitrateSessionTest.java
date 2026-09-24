package com.limelight.meow.bitrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.stream.MeowStreamBridgeAccess;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The session end to end on its real thread: negotiation from memory, reports only after the
 * host is proven, the overlay, and remembering where the session settled.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class BitrateSessionTest {

    private Context context;
    private final List<ReceiverReport> sent = new ArrayList<>();
    private BitrateSession session;
    private final int[] counters = new int[3];

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(BitrateMemory.FILE, Context.MODE_PRIVATE).edit().clear()
                .commit();
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.release();
        }
    }

    private BitrateSession session(boolean automatic) {
        session = new BitrateSession(context, "host-a", automatic, r -> {
            ReceiverReport copy = new ReceiverReport();
            copy.autoBitrate = r.autoBitrate;
            copy.maxKbps = r.maxKbps;
            copy.receivedKbps = r.receivedKbps;
            sent.add(copy);
            return 0;
        }, new ReceiverReporter.Stats() {
            @Override
            public boolean readCounters(int[] out) {
                counters[0] += 1000;
                counters[1] += 1000;
                counters[2] += 1_000_000;
                System.arraycopy(counters, 0, out, 0, 3);
                return true;
            }

            @Override public long rttInfo() { return 20L << 32; }
            @Override public int decodeQueueFrames() { return 0; }
            @Override public int averageDecodeMs() { return 0; }
        });
        return session;
    }

    private void run(long ms) {
        Shadows.shadowOf(session.looper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void theFirstSessionNegotiatesTheSettingAndLaterOnesTheRememberedValue() {
        assertEquals(20000, session(true).negotiate(false, 20000));
        new BitrateMemory(context).put("host-a", false, 11000);
        session.release();
        assertEquals(11000, session(true).negotiate(false, 20000));
        assertEquals(11000, session.negotiatedKbps());
        assertEquals(20000, BitrateSession.negotiate(null, false, 20000));
    }

    @Test
    public void nothingIsSentUntilTheHostIsProven() {
        session(true).negotiate(false, 20000);
        session.onStreamStarted();
        run(5000);
        assertTrue(sent.isEmpty());

        session.startTask().run();
        run(3500);
        assertEquals(3, sent.size());
        assertEquals(20000, sent.get(0).maxKbps);
        assertTrue(sent.get(0).autoBitrate);
        assertEquals(8000, sent.get(0).receivedKbps);
    }

    @Test
    public void withTheOffSwitchReportsSayNotToAdaptAndNothingIsRemembered() {
        session(false).negotiate(false, 20000);
        session.onStreamStarted();
        session.startTask().run();
        run(1500);
        assertEquals(1, sent.size());
        assertEquals(false, sent.get(0).autoBitrate);
        MeowStreamBridgeAccess.bitrateApplied(9000);
        run(12_000);
        session.onStreamStopped();
        run(100);
        assertEquals(0, new BitrateMemory(context).get("host-a", false));
    }

    @Test
    public void whereTheSessionSettledIsRememberedForTheNextOne() {
        session(true).negotiate(false, 20000);
        session.onStreamStarted();
        session.startTask().run();
        run(1100);
        MeowStreamBridgeAccess.bitrateApplied(13000);
        run(ReceiverReporter.STABLE_MS + 1000);
        assertEquals(13000, session.reporter().appliedKbps());

        session.onStreamStopped();
        run(100);
        assertEquals(13000, new BitrateMemory(context).get("host-a", false));
    }

    @Test
    public void stoppingEndsTheReports() {
        session(true).negotiate(false, 20000);
        session.onStreamStarted();
        session.startTask().run();
        MeowStreamBridgeAccess.bitrateApplied(13000);
        run(2100);
        int before = sent.size();
        session.onStreamStopped();
        run(5000);
        assertEquals(before, sent.size());
        assertTrue(SystemClock.uptimeMillis() > 0);
    }

    @Test
    public void aHostProvenSignalQueuedBehindTheStopNeverStartsReports() {
        // Teardown drains this session before the viewport thread, so a first echo already
        // queued there can still announce the host afterwards.
        session(true).negotiate(false, 20000);
        session.onStreamStarted();
        session.onStreamStopped();
        session.startTask().run();
        run(5000);
        assertTrue(sent.isEmpty());
        // The next stream reports normally.
        session.onStreamStarted();
        session.startTask().run();
        run(1500);
        assertEquals(1, sent.size());
    }

    @Test
    public void aHostThatStoppedAdaptingIsForgotten() {
        new BitrateMemory(context).put("host-a", false, 6000);
        assertEquals(6000, session(true).negotiate(false, 20000));
        session.onStreamStarted();
        session.startTask().run();
        run((ReceiverReporter.GIVE_UP_AFTER_REPORTS + 2) * 1000L);
        session.onStreamStopped();
        run(100);
        assertEquals("the next session starts at the setting again",
                0, new BitrateMemory(context).get("host-a", false));
    }

    @Test
    public void meteredAndUnmeteredStartsAreIndependent() {
        new BitrateMemory(context).put("host-a", true, 3000);
        assertEquals(20000, session(true).negotiate(false, 20000));
        session.release();
        assertEquals(3000, session(true).negotiate(true, 8000));
    }

    @Test
    public void aPoorConnectionOnAnAdaptingHostSaysSoInsteadOfAskingTheUserToLowerTheBitrate() {
        assertEquals(null, BitrateSession.poorConnectionText(null, context));
        session(true).negotiate(false, 20000);
        session.onStreamStarted();
        assertEquals("the host has not adapted anything yet: keep the advice",
                null, BitrateSession.poorConnectionText(session, context));
        MeowStreamBridgeAccess.bitrateApplied(6200);
        assertEquals("Connection slow · adapting bitrate (6.2 Mbps)",
                BitrateSession.poorConnectionText(session, context));
        session.release();
        session(false).negotiate(false, 20000);
        session.onStreamStarted();
        MeowStreamBridgeAccess.bitrateApplied(6200);
        assertEquals("automatic bitrate off: keep the advice",
                null, BitrateSession.poorConnectionText(session, context));
    }
}
