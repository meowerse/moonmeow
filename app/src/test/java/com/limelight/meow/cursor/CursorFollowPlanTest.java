package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The value type. Small, but it is on the hot path and {@link CursorFollowPlan#NONE} is shared. */
public class CursorFollowPlanTest {

    @Test
    public void theNoMoveCaseIsTheSharedInstance() {
        assertSame(CursorFollowPlan.NONE, CursorFollowPlan.of(0, 0));
        assertFalse(CursorFollowPlan.NONE.isMove());
    }

    @Test
    public void anyNonZeroAxisIsAMove() {
        assertTrue(CursorFollowPlan.of(1, 0).isMove());
        assertTrue(CursorFollowPlan.of(0, -1).isMove());
    }

    @Test
    public void equalityIsByValue() {
        assertEquals(CursorFollowPlan.of(3, -4), CursorFollowPlan.of(3, -4));
        assertEquals(CursorFollowPlan.of(3, -4).hashCode(), CursorFollowPlan.of(3, -4).hashCode());
        assertNotEquals(CursorFollowPlan.of(3, -4), CursorFollowPlan.of(-4, 3));
        assertNotEquals(CursorFollowPlan.of(1, 1), "not a plan");
    }

    @Test
    public void toStringNamesBothAxes() {
        assertEquals("CursorFollowPlan{2,-7}", CursorFollowPlan.of(2, -7).toString());
    }
}
