package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.limelight.meow.viewport.ViewportRect;

import org.junit.Test;

/**
 * {@link CursorFollowPlanner} is pure arithmetic over ints, so it is tested directly rather
 * than through a view (CLAUDE.md §5 rule 3). The properties asserted here are the ones its
 * class comment promises: edge-pan and catch-up are one rule, the clamp is what lets the
 * cursor reach the true corner, and an unzoomed crop never moves.
 */
public class CursorFollowPlannerTest {

    private static final int W = 1920;
    private static final int H = 1080;

    private final CursorFollowPlanner planner = new CursorFollowPlanner();

    private static ViewportRect crop(int x, int y, int w, int h) {
        return new ViewportRect(x, y, w, h);
    }

    private CursorFollowPlan plan(ViewportRect visible, int cx, int cy) {
        return planner.plan(visible, cx, cy, 0, 0, W, H);
    }

    // --- when nothing should happen --------------------------------------------------------

    @Test
    public void aCropCoveringEverythingHasNowhereToGo() {
        assertSame(CursorFollowPlan.NONE, plan(crop(0, 0, W, H), 0, 0));
        assertSame(CursorFollowPlan.NONE, plan(crop(0, 0, W, H), W, H));
    }

    @Test
    public void aCursorComfortablyInsideTheCropMovesNothing() {
        ViewportRect visible = crop(480, 270, 960, 540);
        assertSame(CursorFollowPlan.NONE, plan(visible, 960, 540));
    }

    @Test
    public void degenerateInputIsRefusedRatherThanGuessed() {
        assertSame(CursorFollowPlan.NONE, planner.plan(null, 0, 0, 0, 0, W, H));
        assertSame(CursorFollowPlan.NONE, planner.plan(crop(0, 0, 10, 10), 5, 5, 0, 0, 0, H));
        assertSame(CursorFollowPlan.NONE, planner.plan(crop(0, 0, 10, 10), 5, 5, 0, 0, W, 0));
    }

    @Test
    public void anAxisThatIsFullyVisibleDoesNotScrollWhileTheOtherDoes() {
        // Full width, quarter height: only y can move.
        ViewportRect visible = crop(0, 400, W, 270);
        CursorFollowPlan p = plan(visible, 5, 410);
        assertEquals(0, p.dx);
        assertTrue(p.dy < 0);
    }

    // --- edge-pan ---------------------------------------------------------------------------

    @Test
    public void aCursorInsideTheMarginPinsItselfToTheMarginLine() {
        ViewportRect visible = crop(480, 270, 960, 540);
        int margin = Math.round(960 * CursorFollowPlanner.DEFAULT_EDGE_MARGIN_FRACTION);
        int cursor = visible.x + visible.width - 10;   // 10px from the right edge

        CursorFollowPlan p = plan(visible, cursor, 540);

        // The crop moves so the cursor ends up exactly `margin` in from the trailing edge.
        int newStart = visible.x + p.dx;
        assertEquals(cursor + margin - visible.width, newStart);
    }

    @Test
    public void theCursorNearTheLeadingEdgeScrollsTheCropBackwards() {
        ViewportRect visible = crop(480, 270, 960, 540);
        CursorFollowPlan p = plan(visible, visible.x + 3, 540);
        assertTrue("crop must move towards the origin", p.dx < 0);
        assertEquals(0, p.dy);
    }

    @Test
    public void bothAxesCanMoveAtOnce() {
        ViewportRect visible = crop(480, 270, 960, 540);
        CursorFollowPlan p = plan(visible, visible.x + 2, visible.y + 2);
        assertTrue(p.dx < 0);
        assertTrue(p.dy < 0);
        assertTrue(p.isMove());
    }

    // --- catch-up ---------------------------------------------------------------------------

    @Test
    public void aCursorOutsideTheCropIsCaughtUpByTheSameRule() {
        ViewportRect visible = crop(480, 270, 960, 540);
        int margin = Math.round(960 * CursorFollowPlanner.DEFAULT_EDGE_MARGIN_FRACTION);
        int cursor = 1800;   // well past the right edge at 1440

        CursorFollowPlan p = plan(visible, cursor, 540);

        assertEquals(cursor + margin - visible.width, visible.x + p.dx);
    }

    @Test
    public void catchUpIsContinuousWithEdgePan() {
        // One pixel further out must not produce a qualitatively different answer.
        ViewportRect visible = crop(480, 270, 960, 540);
        int atEdge = visible.x + visible.width;
        CursorFollowPlan inside = plan(visible, atEdge - 1, 540);
        CursorFollowPlan outside = plan(visible, atEdge + 1, 540);
        assertEquals(2, outside.dx - inside.dx);
    }

    // --- the clamp, which is what lets the pointer reach the corner --------------------------

    @Test
    public void theCropStopsAtTheBoundaryAndLetsTheCursorRunOn() {
        ViewportRect visible = crop(W - 960, 270, 960, 540);   // already flush right
        assertSame("nothing left to scroll into", CursorFollowPlan.NONE,
                CursorFollowPlan.of(plan(visible, W - 1, 540).dx, 0));
    }

    @Test
    public void theCropNeverLeavesTheContentBox() {
        ViewportRect visible = crop(0, 0, 960, 540);
        CursorFollowPlan p = plan(visible, 0, 0);
        assertEquals("already at the origin, cannot go further", 0, p.dx);
        assertEquals(0, p.dy);
    }

    @Test
    public void aLetterboxedContentBoxIsRespected() {
        // Desktop occupies x=[160,1760) of a 1920 frame; the crop must not scroll into padding.
        ViewportRect visible = crop(160, 0, 400, H);
        CursorFollowPlan p = planner.plan(visible, 161, H / 2, 160, 0, 1600, H);
        assertEquals("clamped against the content box, not the frame", 0, p.dx);
    }

    // --- the margin itself -------------------------------------------------------------------

    @Test
    public void aNarrowCropCannotHaveOverlappingBorderZones() {
        // A 10px crop with a 40% cap gives a 4px margin, so the two zones cannot meet in a way
        // that makes the two edges fight each other into an oscillation.
        CursorFollowPlanner wide = new CursorFollowPlanner(0.9f);
        ViewportRect visible = crop(100, 100, 10, 10);
        CursorFollowPlan p = wide.plan(visible, 105, 105, 0, 0, W, H);
        assertTrue("a centred cursor in a tiny crop must still be able to rest",
                Math.abs(p.dx) < 10);
    }

    @Test
    public void anAbsurdMarginFractionIsClampedRatherThanThrown() {
        // This sits on the mouse-event path; a bad constant must not take the stream down.
        ViewportRect visible = crop(480, 270, 960, 540);
        assertFalse(new CursorFollowPlanner(-5f).plan(visible, 960, 540, 0, 0, W, H).isMove());
        assertFalse(new CursorFollowPlanner(99f).plan(visible, 960, 540, 0, 0, W, H).isMove());
    }
}
