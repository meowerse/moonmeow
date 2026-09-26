package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class CropTimelineTest {

    private static final FrameMapping A = new FrameMapping(0.5, 0.5, 100, 50);
    private static final FrameMapping B = new FrameMapping(0.5, 0.5, 140, 50);
    private static final FrameMapping C = new FrameMapping(0.5, 0.5, 180, 50);

    private CropTimeline timeline;

    @Before
    public void setUp() {
        timeline = new CropTimeline();
    }

    @Test
    public void beforeAnyCropEveryFrameIsTheWholeDesktop() {
        assertSame(FrameMapping.IDENTITY, timeline.mappingFor(1));
        assertSame(FrameMapping.IDENTITY, timeline.mappingFor(0));
    }

    @Test
    public void aCropAppliesFromItsFirstFrameAndNotBefore() {
        timeline.add(100, A);
        assertSame(FrameMapping.IDENTITY, timeline.mappingFor(99));
        assertSame(A, timeline.mappingFor(100));
        assertSame(A, timeline.mappingFor(150));
    }

    @Test
    public void framesBetweenTwoCropsKeepTheFirst() {
        // An echo can arrive long before its frame: frames still in flight keep the old crop.
        timeline.add(100, A);
        timeline.add(110, B);
        assertSame(A, timeline.mappingFor(105));
        assertSame(B, timeline.mappingFor(110));
    }

    @Test
    public void aReorderedEchoReplacesWhatItSupersedes() {
        timeline.add(100, A);
        timeline.add(120, C);
        timeline.add(110, B);   // arrived late; C (a later crop) was dropped with it
        assertSame(A, timeline.mappingFor(105));
        assertSame(B, timeline.mappingFor(125));
    }

    @Test
    public void aHostWithoutFrameIndicesAppliesFromTheNextFrameShown() {
        timeline.onPresented(40);
        timeline.add(0, A);
        assertSame(FrameMapping.IDENTITY, timeline.mappingFor(40));
        assertSame(A, timeline.mappingFor(41));
    }

    @Test
    public void aLateEchoIsCountedAndAppliesFromThenOn() {
        timeline.onPresented(200);
        timeline.add(190, A);
        assertEquals(1, timeline.lateEchoes());
        assertSame(A, timeline.mappingFor(201));
    }

    @Test
    public void anUnnamedFrameGetsTheNewestCropAlreadyShown() {
        timeline.add(10, A);
        timeline.add(20, B);
        timeline.onPresented(15);
        assertSame(A, timeline.mappingFor(0));
    }

    @Test
    public void oldCropsAreFoldedAwaySoTheTimelineStaysSmall() {
        for (int i = 1; i <= 50; i++) {
            timeline.add(i * 10, new FrameMapping(0.5, 0.5, i, 0));
            timeline.onPresented(i * 10 + 5);
        }
        assertTrue("held " + timeline.size(), timeline.size() <= 2);
        assertEquals(50.0, timeline.mappingFor(505).offsetX, 0.0);
        // Nothing shown yet: still bounded.
        CropTimeline fresh = new CropTimeline();
        for (int i = 1; i <= 50; i++) {
            fresh.add(i * 10, A);
        }
        assertTrue(fresh.size() <= CropTimeline.MAX_ENTRIES);
    }

    @Test
    public void frameNumbersThatWrapStillOrder() {
        timeline.add(Integer.MAX_VALUE - 1, A);
        timeline.add(Integer.MIN_VALUE + 2, B);
        assertSame(A, timeline.mappingFor(Integer.MAX_VALUE));
        assertSame(B, timeline.mappingFor(Integer.MIN_VALUE + 5));
    }

    @Test
    public void resetStartsOver() {
        timeline.add(10, A);
        timeline.onPresented(12);
        timeline.reset();
        assertSame(FrameMapping.IDENTITY, timeline.mappingFor(12));
        assertEquals(0, timeline.lastPresented());
    }
}
