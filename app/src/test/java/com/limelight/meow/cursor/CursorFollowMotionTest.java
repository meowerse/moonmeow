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

    @Test
    public void aFrameMovesPartOfTheWayAndNeverOvershoots() {
        float step = CursorFollowMotion.step(200f, 400f, 1f / 60f);
        assertTrue(step > 0f && step < 200f);
        assertEquals(-step, CursorFollowMotion.step(-200f, 400f, 1f / 60f), 0f);
        assertEquals(0.3f, CursorFollowMotion.step(0.3f, 400f, 1f / 60f), 0f);
    }

    @Test
    public void speedIsCappedAtAFewViewsASecond() {
        float step = CursorFollowMotion.step(100000f, 400f, 1f / 60f);
        assertEquals(CursorFollowMotion.MAX_VIEWS_PER_SECOND * 400f / 60f, step, 0.01f);
    }

    @Test
    public void repeatedFramesConverge() {
        float remaining = 300f;
        int frames = 0;
        while (remaining != 0f && frames < 120) {
            remaining -= CursorFollowMotion.step(remaining, 400f, 1f / 60f);
            frames++;
        }
        assertEquals(0f, remaining, 0f);
        assertTrue("settles within two thirds of a second at 60 Hz, took " + frames, frames <= 40);
    }
}
