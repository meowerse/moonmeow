package com.limelight.meow.bitrate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StartingBitrateTest {

    @Test
    public void withAutomaticOffTheSettingIsUsedAsIs() {
        assertEquals(20000, StartingBitrate.choose(false, 20000, 8000));
    }

    @Test
    public void theFirstSessionOnAHostStartsAtTheSetting() {
        assertEquals(20000, StartingBitrate.choose(true, 20000, 0));
    }

    @Test
    public void laterSessionsStartWhereTheLastOneSettled() {
        assertEquals(12000, StartingBitrate.choose(true, 20000, 12000));
    }

    @Test
    public void theSettingIsTheCeilingEvenIfTheUserLoweredIt() {
        assertEquals(10000, StartingBitrate.choose(true, 10000, 18000));
    }

    @Test
    public void oneBadSessionCannotPinTheNextNearTheFloor() {
        // A DERP-relayed session that settled at 1.5 Mbps: the next one starts at a quarter of
        // the ceiling, not at 1.5.
        assertEquals(5000, StartingBitrate.choose(true, 20000, 1500));
        assertEquals(StartingBitrate.MIN_START_KBPS, StartingBitrate.choose(true, 6000, 500));
    }

    @Test
    public void aTinyCeilingIsNeverExceededByTheFloor() {
        assertEquals(1000, StartingBitrate.choose(true, 1000, 300));
        assertEquals(0, StartingBitrate.choose(true, 0, 5000));
    }
}
