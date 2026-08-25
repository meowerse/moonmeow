package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;

import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class HandlerDeadlineSchedulerTest {

    private ShadowLooper looper;
    private HandlerDeadlineScheduler scheduler;
    private int runs;

    @Before
    public void setUp() {
        looper = Shadows.shadowOf(Looper.getMainLooper());
        scheduler = new HandlerDeadlineScheduler(new Handler(Looper.getMainLooper()));
        runs = 0;
    }

    @Test
    public void aScheduledTaskRunsAfterTheDelay() {
        scheduler.schedule(ViewportReporter.ECHO_DEADLINE_MS, () -> runs++);
        looper.idleFor(java.time.Duration.ofMillis(ViewportReporter.ECHO_DEADLINE_MS - 1));
        assertEquals(0, runs);
        looper.idleFor(java.time.Duration.ofMillis(1));
        assertEquals(1, runs);
    }

    @Test
    public void reschedulingReplacesTheOutstandingTaskRatherThanQueueingASecond() {
        // Each probe re-arms the deadline. If they accumulated, one retry would fire the
        // "host does not support this" verdict twice.
        for (int i = 0; i < 50; i++) {
            scheduler.schedule(120L, () -> runs++);
            looper.idleFor(java.time.Duration.ofMillis(5));
        }
        assertEquals(0, runs);
        looper.idleFor(java.time.Duration.ofMillis(200));
        assertEquals(1, runs);
    }

    @Test
    public void cancellingStopsTheTask() {
        scheduler.schedule(120L, () -> runs++);
        scheduler.cancel();
        looper.idleFor(java.time.Duration.ofMillis(500));
        assertEquals(0, runs);
    }

    @Test
    public void cancellingWithNothingScheduledIsHarmless() {
        scheduler.cancel();
        scheduler.cancel();
        looper.idleFor(java.time.Duration.ofMillis(500));
        assertEquals(0, runs);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aHandlerIsRequired() {
        new HandlerDeadlineScheduler(null);
    }
}
