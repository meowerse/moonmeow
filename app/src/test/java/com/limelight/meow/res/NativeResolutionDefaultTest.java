package com.limelight.meow.res;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NativeResolutionDefaultTest {

    @Test
    public void aTallPhonePanelBecomesItsOwnLandscapeResolution() {
        // The bug this exists for: a 20:9 phone streaming 16:9 gets a black bar down each
        // side that zoom cannot reach. 1220x2712 is the reporting device.
        assertEquals("2712x1220", NativeResolutionDefault.resolve(1220, 2712, false));
    }

    @Test
    public void aPanelReportedLandscapeGivesTheSameAnswerAsPortrait() {
        // Devices report either orientation; the default must not depend on which.
        assertEquals(
                NativeResolutionDefault.resolve(1220, 2712, false),
                NativeResolutionDefault.resolve(2712, 1220, false));
    }

    @Test
    public void aSixteenByNinePanelStillResolvesToItself() {
        assertEquals("1920x1080", NativeResolutionDefault.resolve(1920, 1080, false));
    }

    @Test
    public void aTelevisionKeepsUpstreamsFixedDefault() {
        // TVs report nonsense panel geometry, and are 16:9 anyway, so they have none of
        // the letterboxing this changes the default to fix.
        assertEquals(NativeResolutionDefault.FALLBACK,
                NativeResolutionDefault.resolve(3840, 2160, true));
    }

    @Test
    public void aPanelBeyondFourKIsNotChosenOnTheUsersBehalf() {
        assertEquals(NativeResolutionDefault.FALLBACK,
                NativeResolutionDefault.resolve(7680, 4320, false));
    }

    @Test
    public void exactlyFourKIsStillAcceptable() {
        // The guard is "beyond 4K", not "4K or more" -- an off-by-one here would silently
        // deny 4K panels the native default.
        assertEquals("3840x2160", NativeResolutionDefault.resolve(3840, 2160, false));
    }

    @Test
    public void unusableGeometryFallsBackRatherThanEmittingZeroes() {
        assertEquals(NativeResolutionDefault.FALLBACK, NativeResolutionDefault.resolve(0, 0, false));
        assertEquals(NativeResolutionDefault.FALLBACK, NativeResolutionDefault.resolve(1080, 0, false));
        assertEquals(NativeResolutionDefault.FALLBACK, NativeResolutionDefault.resolve(-1, -1, false));
    }

    @Test
    public void anOddDimensionIsRoundedDownToTheChromaFloor() {
        // Odd dimensions cannot be expressed in YUV420 and configure to a black stream
        // rather than throwing.
        assertEquals("1918x1078", NativeResolutionDefault.resolve(1919, 1079, false));
    }

    @Test
    public void aStandardSixteenByNineHeightIsNotClampedAway() {
        // Regression guard: an earlier revision aligned to 16, which silently turned
        // 1920x1080 -- the most widely decoded size there is, and already a shipped preset --
        // into 1920x1072. Alignment is 2 for exactly this reason.
        assertEquals("1920x1080", NativeResolutionDefault.resolve(1920, 1080, false));
        assertEquals("1280x720", NativeResolutionDefault.resolve(1280, 720, false));
    }

    @Test
    public void theReportingDevicePanelIsAlreadyAligned() {
        // 1220x2712 is even on both axes, so alignment must not perturb it.
        assertEquals("2712x1220", NativeResolutionDefault.resolve(1220, 2712, false));
    }

    @Test
    public void theResultIsAlwaysParsableAsAResolutionString() {
        // PreferenceConfiguration splits on 'x' and parses both halves; a default it cannot
        // parse would crash at startup rather than degrade.
        String[] parts = NativeResolutionDefault.resolve(1220, 2712, false).split("x");
        assertEquals(2, parts.length);
        assertEquals(2712, Integer.parseInt(parts[0]));
        assertEquals(1220, Integer.parseInt(parts[1]));
    }
}
