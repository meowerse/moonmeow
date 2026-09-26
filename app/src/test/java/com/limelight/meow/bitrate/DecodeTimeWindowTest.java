package com.limelight.meow.bitrate;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class DecodeTimeWindowTest {

    @After
    public void tearDown() {
        DecodeTimeWindow.reset();
    }

    @Test
    public void theAverageIsOverTheFramesOfTheWindow() {
        DecodeTimeWindow.publish(420L, 60);
        assertEquals(7, DecodeTimeWindow.averageMs());
    }

    @Test
    public void anEmptyWindowIsZeroNotADivisionByZero() {
        DecodeTimeWindow.publish(420L, 0);
        assertEquals(0, DecodeTimeWindow.averageMs());
    }
}
