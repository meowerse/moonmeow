package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The dead-reckoned host cursor. Plain arithmetic, so tested directly.
 *
 * <p>The scaling case is the one that matters: the absolute-mouse path sends its delta in
 * {@code streamContainer} pixels and the library normalises it against that same reference, so
 * accumulating the raw delta into a stream-pixel position is wrong by the ratio between the
 * two. That mis-scaling is what made the estimate drift away from the real cursor.
 */
public class RelativeCursorTrackerTest {

    private static final int W = 1920;
    private static final int H = 1080;

    @Test
    public void anUnseededTrackerRefusesToGuessAnOrigin() {
        RelativeCursorTracker t = new RelativeCursorTracker();
        assertFalse(t.isSeeded());
        assertFalse("nothing to add to yet", t.accumulate(10, 10, W, H));
    }

    @Test
    public void seedingHappensOnceSoCallersNeedNotRemember() {
        RelativeCursorTracker t = new RelativeCursorTracker();
        t.seed(100, 200, W, H);
        t.seed(900, 900, W, H);
        assertEquals(100, t.hostX());
        assertEquals(200, t.hostY());
    }

    @Test
    public void deltasAccumulate() {
        RelativeCursorTracker t = new RelativeCursorTracker();
        t.seed(100, 200, W, H);
        assertTrue(t.accumulate(10, -20, W, H));
        assertTrue(t.accumulate(5, 5, W, H));
        assertEquals(115, t.hostX());
        assertEquals(185, t.hostY());
    }

    @Test
    public void theFrameEdgeIsWhereTheEstimateResynchronises() {
        // The real host cursor cannot leave the screen either, so running into an edge makes
        // the estimate exact again on that axis. This is the only self-correction there is.
        RelativeCursorTracker t = new RelativeCursorTracker();
        t.seed(10, 10, W, H);
        t.accumulate(-5000, -5000, W, H);
        assertEquals(0, t.hostX());
        assertEquals(0, t.hostY());

        t.accumulate(9999, 9999, W, H);
        assertEquals(W, t.hostX());
        assertEquals(H, t.hostY());
    }

    @Test
    public void aSeedOutsideTheFrameIsClamped() {
        RelativeCursorTracker t = new RelativeCursorTracker();
        t.seed(-40, 99999, W, H);
        assertEquals(0, t.hostX());
        assertEquals(H, t.hostY());
    }

    @Test
    public void resetAllowsANewSeed() {
        RelativeCursorTracker t = new RelativeCursorTracker();
        t.seed(100, 100, W, H);
        t.reset();
        assertFalse(t.isSeeded());
        t.seed(500, 600, W, H);
        assertEquals(500, t.hostX());
    }

    // --- scaleDelta ---------------------------------------------------------------------

    @Test
    public void aDeltaIsRescaledFromTheReferenceFrameIntoStreamPixels() {
        // A 10px move across a 960px-wide view is 20px of a 1920px stream.
        assertEquals(20, RelativeCursorTracker.scaleDelta(10, 960, 1920));
        assertEquals(-20, RelativeCursorTracker.scaleDelta(-10, 960, 1920));
        // The other direction: a 2340px phone view driving a 1920px stream.
        assertEquals(8, RelativeCursorTracker.scaleDelta(10, 2340, 1920));
    }

    @Test
    public void anIdenticalReferenceIsAPassThrough() {
        assertEquals(7, RelativeCursorTracker.scaleDelta(7, 1920, 1920));
    }

    @Test
    public void aSmallMovementIsNeverRoundedAwayToNothing() {
        // Otherwise a slow drag across a large view would accumulate zero and the cursor
        // estimate would stick where it started.
        assertEquals(1, RelativeCursorTracker.scaleDelta(1, 4000, 100));
        assertEquals(-1, RelativeCursorTracker.scaleDelta(-1, 4000, 100));
    }

    @Test
    public void degenerateSizesProduceNoMovementRatherThanNaN() {
        assertEquals(0, RelativeCursorTracker.scaleDelta(10, 0, 1920));
        assertEquals(0, RelativeCursorTracker.scaleDelta(10, 960, 0));
        assertEquals(0, RelativeCursorTracker.scaleDelta(0, 960, 1920));
    }
}
