package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * There is no native library on the JVM, which makes this the one place the JNI failure mode
 * can actually be exercised: it is exactly what a mangled-name mismatch looks like at
 * runtime. The bridge must turn it into "host unsupported" rather than letting it escape
 * into the middle of a stream.
 */
public class MeowViewportBridgeTest {

    @Test
    public void aMissingNativeSymbolDegradesToHostUnsupportedInsteadOfThrowing() {
        assertEquals(ViewportReporter.LI_UNSUPPORTED,
                new MeowViewportBridge().send(0, 0, 1920, 1080));
    }

    @Test
    public void andTheReporterThenShutsTheFeatureDownWithoutTouchingTheStream() {
        ViewportReporter reporter = new ViewportReporter(new MeowViewportBridge(),
                new ViewportReporter.Scheduler() {
                    @Override
                    public void scheduleSettle(long delayMs, Runnable task) {
                    }

                    @Override
                    public void cancelSettle() {
                    }
                });
        reporter.setEnabled(true, 0L);
        reporter.onStreamStarted(5360, 1440, 0L);
        assertFalse(reporter.isHostSupported());
        assertFalse(reporter.isActive());
    }
}
