package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Window pixels of a 1080x2400 phone (portrait) and 2400x1080 (landscape). */
public class StreamLiftTest {

    private static final float NO_FOCUS = Float.NaN;

    @Test
    public void nothingCoveredNothingMoves() {
        assertEquals(0f, StreamLift.liftFor(896, 1504, 0, 2400, NO_FOCUS), 0f);
        assertEquals(0f, StreamLift.liftFor(896, 1504, 0, 1504, NO_FOCUS), 0f);
    }

    @Test
    public void portraitStreamThatFitsIsCentredAboveTheKeyboard() {
        // A 1080x608 desktop centred in 2400; a 1000 px keyboard leaves rows 0..1400.
        float lift = StreamLift.liftFor(896, 1504, 0, 1400, NO_FOCUS);
        assertEquals(700f - 1200f, lift, 0.01f);
        float top = 896 + lift;
        float bottom = 1504 + lift;
        assertEquals("equal space above and below", top - 0, 1400 - bottom, 0.01f);
    }

    @Test
    public void aVisibleStatusBarIsPartOfTheCover() {
        float lift = StreamLift.liftFor(896, 1504, 100, 1400, NO_FOCUS);
        assertEquals(750f - 1200f, lift, 0.01f);
    }

    @Test
    public void landscapeWithoutFocusPutsTheBottomOnTheKeyboard() {
        // Stream fills the height; a 600 px keyboard leaves rows 0..480.
        float lift = StreamLift.liftFor(0, 1080, 0, 480, NO_FOCUS);
        assertEquals(-600f, lift, 0.01f);
    }

    @Test
    public void landscapeWithFocusCentresTheFocus() {
        // Caret at y=700 in the stream: centre it in 0..480.
        float lift = StreamLift.liftFor(0, 1080, 0, 480, 700f);
        assertEquals(240f - 700f, lift, 0.01f);
    }

    @Test
    public void focusNearTheTopDoesNotMoveTheStreamDownOrPastItsTop() {
        assertEquals(0f, StreamLift.liftFor(0, 1080, 0, 480, 50f), 0f);
    }

    @Test
    public void focusNearTheBottomStopsAtTheBottomEdge() {
        assertEquals(-600f, StreamLift.liftFor(0, 1080, 0, 480, 1070f), 0.01f);
    }

    @Test
    public void neverPositive() {
        assertEquals(0f, StreamLift.liftFor(-50, 1030, 100, 480, 10f), 0f);
    }

    @Test
    public void degenerateBandIsIgnored() {
        assertEquals(0f, StreamLift.liftFor(0, 1080, 500, 400, NO_FOCUS), 0f);
    }

    @Test
    public void aSideBarInTheLetterboxMovesNothing() {
        assertEquals(0f, StreamLift.shiftFor(240, 2160, 0, 2250), 0f);
    }

    @Test
    public void aSideBarOverTheStreamSlidesItLeftIntoTheSpareLetterbox() {
        // A 16:10-ish stream with 100 px letterbox each side; the bar covers from 2180.
        assertEquals(-100f, StreamLift.shiftFor(100, 2300, 0, 2180), 0.01f);
        assertEquals(-50f, StreamLift.shiftFor(100, 2300, 0, 2250), 0.01f);
    }

    @Test
    public void withNoRoomOnTheLeftNothingMoves() {
        assertEquals(0f, StreamLift.shiftFor(0, 2400, 0, 2300), 0f);
    }

    @Test
    public void aNudgeIsZeroWhileTheFocusIsAboveTheBar() {
        assertEquals(0f, StreamLift.nudgeFor(0, 2400, 2285, 1200f, 24f), 0f);
        assertEquals(0f, StreamLift.nudgeFor(0, 2400, 2285, Float.NaN, 24f), 0f);
        assertEquals("nothing covered", 0f, StreamLift.nudgeFor(0, 2000, 2285, 1990f, 24f), 0f);
    }

    @Test
    public void aNudgeLiftsOnlyAsFarAsTheFocusNeeds() {
        assertEquals(2285f - 24f - 2300f, StreamLift.nudgeFor(0, 2400, 2285, 2300f, 24f), 0.01f);
    }

    @Test
    public void aNudgeNeverLiftsPastBottomAligned() {
        assertEquals(2285f - 2400f, StreamLift.nudgeFor(0, 2400, 2285, 2399f, 200f), 0.01f);
    }
}
