package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

/**
 * There is no native library on the JVM, which makes this the one place the JNI failure mode
 * can actually be exercised: it is exactly what a mangled-name mismatch looks like at
 * runtime. The bridge must turn it into "unavailable" rather than letting it escape into the
 * middle of a stream.
 */
public class MeowViewportBridgeTest {

    @After
    public void tearDown() {
        MeowViewportBridge.setEchoListener(null);
    }

    @Test
    public void clearingWithSomeoneElsesListenerLeavesTheLiveOneRegistered() {
        // A stream restart through PiP can register the new binder before the old one tears
        // down. An unconditional clear would deregister the live stream and it would never
        // see an echo.
        final Object[] captured = new Object[1];
        MeowViewportBridge.EchoListener stale = (x, y, w, h, dw, dh) -> {
        };
        MeowViewportBridge.EchoListener live =
                (x, y, w, h, dw, dh) -> captured[0] = "called";
        MeowViewportBridge.setEchoListener(stale);
        MeowViewportBridge.setEchoListener(live);

        MeowViewportBridge.clearEchoListener(stale);
        MeowViewportBridge.onViewportEcho(0, 0, 1, 1, 0, 0);
        assertEquals("called", captured[0]);

        MeowViewportBridge.clearEchoListener(live);
        captured[0] = null;
        MeowViewportBridge.onViewportEcho(0, 0, 1, 1, 0, 0);
        assertNull(captured[0]);
    }

    @Test
    public void aMissingNativeLibraryIsDetectedAtClassInitRatherThanAtFirstCall() {
        assertFalse("no .so on the JVM, so this must be false here",
                MeowViewportBridge.isNativeReady());
    }

    @Test
    public void aMissingNativeSymbolDegradesToUnavailableInsteadOfThrowing() {
        assertEquals(ViewportReporter.LI_LIBRARY_UNAVAILABLE,
                new MeowViewportBridge().send(0, 0, 1920, 1080, false));
    }

    @Test
    public void andTheReporterThenShutsTheFeatureDownWithoutTouchingTheStream() {
        ViewportReporter reporter =
                new ViewportReporter(new MeowViewportBridge(), new RecordingScheduler());
        reporter.setEnabled(true);
        reporter.onStreamStarted(1920, 516);
        assertEquals(ViewportReporter.HostSupport.UNSUPPORTED, reporter.hostSupport());
        assertFalse(reporter.isActive());
        assertFalse(reporter.isLive());
    }

    @Test
    public void anEchoWithNoListenerRegisteredIsHarmless() {
        MeowViewportBridge.setEchoListener(null);
        MeowViewportBridge.onViewportEcho(0, 0, 1920, 516, 5360, 1440);
    }

    @Test
    public void anEchoReachesTheRegisteredListener() {
        final int[] seen = new int[6];
        MeowViewportBridge.setEchoListener((x, y, w, h, dw, dh) -> {
            seen[0] = x;
            seen[1] = y;
            seen[2] = w;
            seen[3] = h;
            seen[4] = dw;
            seen[5] = dh;
        });
        MeowViewportBridge.onViewportEcho(10, 20, 300, 400, 5360, 1440);
        assertEquals(10, seen[0]);
        assertEquals(20, seen[1]);
        assertEquals(300, seen[2]);
        assertEquals(400, seen[3]);
        assertEquals(5360, seen[4]);
        assertEquals(1440, seen[5]);
    }

    @Test
    public void aThrowingListenerCannotEscapeBackIntoTheNativeCallbackThread() {
        // An exception left pending here would abort the next JNI call made from the
        // library's async callback thread -- which belongs to rumble, HDR or clipboard.
        MeowViewportBridge.setEchoListener((x, y, w, h, dw, dh) -> {
            throw new IllegalStateException("boom");
        });
        MeowViewportBridge.onViewportEcho(0, 0, 1920, 516, 0, 0);
    }

    @Test
    public void clearingTheListenerAtTeardownStopsDelivery() {
        // A late echo from a stream that has already ended must not reach a binder whose
        // handler thread has been quit.
        final Object[] captured = new Object[1];
        MeowViewportBridge.EchoListener listener =
                (x, y, w, h, dw, dh) -> captured[0] = "called";
        MeowViewportBridge.setEchoListener(listener);
        MeowViewportBridge.clearEchoListener(listener);
        MeowViewportBridge.onViewportEcho(0, 0, 1, 1, 0, 0);
        assertNull(captured[0]);
    }

    private static final class RecordingScheduler implements ViewportReporter.Scheduler {
        @Override
        public void schedule(long delayMs, Runnable task) {
        }

        @Override
        public void cancel() {
        }
    }
}
