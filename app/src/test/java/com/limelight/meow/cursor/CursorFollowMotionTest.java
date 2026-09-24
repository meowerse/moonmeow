package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CursorFollowMotionTest {

    @Test
    public void aCursorInsideTheMarginNeedsNothing() {
        assertEquals(0f, CursorFollowMotion.remaining(100f, 400f, 300f, 0f, 1920f, 0.15f), 0f);
    }

    @Test
    public void aCursorPastTheTrailingMarginPullsTheViewAlong() {
        // View 100..500, margin 60: the cursor at 480 wants the view's end at 540.
        assertEquals(40f, CursorFollowMotion.remaining(100f, 400f, 480f, 0f, 1920f, 0.15f), 0.001f);
    }

    @Test
    public void aCursorOutsideTheViewIsCaughtUpByTheSameRule() {
        assertEquals(-160f, CursorFollowMotion.remaining(400f, 400f, 300f, 0f, 1920f, 0.15f),
                0.001f);
    }

    @Test
    public void theViewStopsAtTheDesktopEdge() {
        assertEquals(20f, CursorFollowMotion.remaining(1500f, 400f, 1915f, 0f, 1920f, 0.15f),
                0.001f);
        assertEquals(0f, CursorFollowMotion.remaining(0f, 1920f, 5f, 0f, 1920f, 0.15f), 0f);
    }

    private static float settleFrames(float remaining, float size, boolean offScreen) {
        float velocity = 0f;
        int frames = 0;
        while (remaining != 0f && frames < 600) {
            velocity = CursorFollowMotion.velocity(remaining, size, velocity, 1f / 60f, offScreen);
            float step = CursorFollowMotion.step(remaining, velocity, 1f / 60f);
            assertTrue("never overshoots", Math.abs(step) <= Math.abs(remaining));
            assertTrue("never backs up", step == 0f || Math.signum(step) == Math.signum(remaining));
            remaining -= step;
            frames++;
        }
        assertEquals(0f, remaining, 0f);
        return frames;
    }

    @Test
    public void theGentleRegimeSettlesSmoothlyAndTheFastOneQuickly() {
        float gentle = settleFrames(72f, 480f, false);
        float fast = settleFrames(2000f, 480f, true);
        assertTrue("gentle settles within a second, took " + gentle, gentle <= 60);
        // Four views away (a jump to the other monitor): capped at 10 views/s after an
        // acceleration ramp, so about 0.6 s -- fast, but not a teleport of the view.
        assertTrue("a cursor far off screen is back within 0.75 s, took " + fast, fast <= 45);
    }

    @Test
    public void speedAndAccelerationAreCapped() {
        float v = CursorFollowMotion.velocity(100000f, 480f, 0f, 1f / 60f, false);
        assertEquals("acceleration-limited from rest",
                CursorFollowMotion.GENTLE_VIEWS_PER_SECOND_SQUARED * 480f / 60f, v, 0.01f);
        for (int i = 0; i < 200; i++) {
            v = CursorFollowMotion.velocity(100000f, 480f, v, 1f / 60f, false);
        }
        assertEquals(CursorFollowMotion.GENTLE_VIEWS_PER_SECOND * 480f, v, 0.01f);
        for (int i = 0; i < 200; i++) {
            v = CursorFollowMotion.velocity(100000f, 480f, v, 1f / 60f, true);
        }
        assertEquals(CursorFollowMotion.FAST_VIEWS_PER_SECOND * 480f, v, 0.01f);
    }

    @Test
    public void aTinyRemainderFinishesAndNothingMovesWithoutARemainder() {
        assertEquals(0.3f, CursorFollowMotion.step(0.3f, 0f, 1f / 60f), 0f);
        assertEquals(0f, CursorFollowMotion.step(0f, 500f, 1f / 60f), 0f);
        assertEquals(0f, CursorFollowMotion.velocity(0f, 480f, 50f, 1f / 60f, false), 0f);
    }

    @Test
    public void decelerationFromTheOtherDirectionHoldsRatherThanBacksUp() {
        assertEquals(0f, CursorFollowMotion.step(50f, -200f, 1f / 60f), 0f);
    }
}
