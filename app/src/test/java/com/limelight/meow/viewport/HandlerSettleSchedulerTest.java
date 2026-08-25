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
public class HandlerSettleSchedulerTest {

    private ShadowLooper looper;
    private HandlerSettleScheduler scheduler;
    private int runs;

    @Before
    public void setUp() {
        looper = Shadows.shadowOf(Looper.getMainLooper());
        scheduler = new HandlerSettleScheduler(new Handler(Looper.getMainLooper()));
        runs = 0;
    }

    @Test
    public void aScheduledTaskRunsAfterTheDelay() {
        scheduler.scheduleSettle(120L, () -> runs++);
        looper.idleFor(java.time.Duration.ofMillis(119));
        assertEquals(0, runs);
        looper.idleFor(java.time.Duration.ofMillis(1));
        assertEquals(1, runs);
    }

    @Test
    public void reschedulingReplacesTheOutstandingTaskRatherThanQueueingASecond() {
        // Every input frame during a pinch reschedules the settle. If they accumulated, a
        // one-second gesture would fire hundreds of trailing sends.
        for (int i = 0; i < 50; i++) {
            scheduler.scheduleSettle(120L, () -> runs++);
            looper.idleFor(java.time.Duration.ofMillis(5));
        }
        assertEquals(0, runs);
        looper.idleFor(java.time.Duration.ofMillis(200));
        assertEquals(1, runs);
    }

    @Test
    public void cancellingStopsTheTask() {
        scheduler.scheduleSettle(120L, () -> runs++);
        scheduler.cancelSettle();
        looper.idleFor(java.time.Duration.ofMillis(500));
        assertEquals(0, runs);
    }

    @Test
    public void cancellingWithNothingScheduledIsHarmless() {
        scheduler.cancelSettle();
        scheduler.cancelSettle();
        looper.idleFor(java.time.Duration.ofMillis(500));
        assertEquals(0, runs);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aHandlerIsRequired() {
        new HandlerSettleScheduler(null);
    }
}
