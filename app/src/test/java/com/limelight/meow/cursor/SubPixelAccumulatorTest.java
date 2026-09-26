package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SubPixelAccumulatorTest {

    private static int sum(SubPixelAccumulator a, float delta, int times, boolean vertical) {
        int total = 0;
        for (int i = 0; i < times; i++) {
            total += vertical ? a.y(delta) : a.x(delta);
        }
        return total;
    }

    @Test
    public void nothingIsLostAtSubPixelSteps() {
        assertEquals(1500, sum(new SubPixelAccumulator(), 1.5f, 1000, false));
        assertEquals(700, sum(new SubPixelAccumulator(), 0.7f, 1000, false), 1);
        assertEquals(700, sum(new SubPixelAccumulator(), 0.7f, 1000, true), 1);
    }

    @Test
    public void leftAndRightAreSymmetric() {
        assertEquals(-sum(new SubPixelAccumulator(), 0.37f, 777, false),
                sum(new SubPixelAccumulator(), -0.37f, 777, false));
    }

    @Test
    public void aReversalDoesNotJumpByTheCarriedFraction() {
        SubPixelAccumulator a = new SubPixelAccumulator();
        assertEquals(0, a.x(0.9f));
        // Back by the same amount: nothing was sent, nothing should be sent now.
        assertEquals(0, a.x(-0.9f));
        a.reset();
        assertEquals(1, a.x(1.2f));
    }
}
