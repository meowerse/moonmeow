package com.limelight.meow.bitrate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StartingBitrateTest {

    @Test
    public void withAutomaticOffTheSettingIsUsedAsIs() {
        assertEquals(20000, StartingBitrate.choose(false, 20000, 8000, 1920, 1080, 60));
    }

    @Test
    public void theFirstSessionOnAHostStartsAtTheSetting() {
        assertEquals(20000, StartingBitrate.choose(true, 20000, 0, 1920, 1080, 60));
    }

    @Test
    public void laterSessionsStartWhereTheLastOneSettled() {
        assertEquals(12000, StartingBitrate.choose(true, 20000, 12000, 1920, 1080, 60));
    }

    @Test
    public void theSettingIsTheCeilingEvenIfTheUserLoweredIt() {
        assertEquals(10000, StartingBitrate.choose(true, 10000, 18000, 1920, 1080, 60));
    }

    @Test
    public void oneBadSessionCannotPinTheNextNearTheFloor() {
        // A DERP-relayed session that settled at 1.5 Mbps: the next one starts at a quarter of
        // the ceiling, not at 1.5.
        assertEquals(5000, StartingBitrate.choose(true, 20000, 1500, 1920, 1080, 60));
        assertEquals(StartingBitrate.MIN_START_KBPS, StartingBitrate.choose(true, 6000, 500, 1280, 720, 30));
    }

    @Test
    public void aTinyCeilingIsNeverExceededByTheFloor() {
        assertEquals(1000, StartingBitrate.choose(true, 1000, 300, 1920, 1080, 60));
        assertEquals(0, StartingBitrate.choose(true, 0, 5000, 1920, 1080, 60));
    }

    @Test
    public void theFirstSessionIsAlwaysTheSettingWhateverTheResolution() {
        assertEquals(15000, StartingBitrate.choose(true, 15000, 0, 2160, 3840, 30));
    }

    @Test
    public void aRememberedValueNeverStartsBelowWhatKeepsTheDesktopReadable() {
        // The user's stream: 2160x3840 at 30 FPS, 15 Mbps set. A bad session remembered 3
        // Mbps; the start is the readable floor (~10 Mbps), not 3.
        int start = StartingBitrate.choose(true, 15000, 3000, 2160, 3840, 30);
        assertEquals(9953, start);
        // And never above the setting, even when the setting itself is below that floor.
        assertEquals(6000, StartingBitrate.choose(true, 6000, 3000, 2160, 3840, 30));
    }
}
