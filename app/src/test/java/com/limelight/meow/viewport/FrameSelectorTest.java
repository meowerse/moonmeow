package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FrameSelectorTest {

    private static final FrameMapping A = new FrameMapping(0.5, 0.5, 100, 50);
    private static final FrameMapping B = new FrameMapping(0.5, 0.5, 140, 50);

    private CropTimeline timeline;
    private FrameSelector selector;

    @Before
    public void setUp() {
        FrameStamps.reset();
        DecodedFrameGate.reset();
        timeline = new CropTimeline();
        selector = new FrameSelector(timeline);
    }

    @After
    public void tearDown() {
        FrameStamps.reset();
        DecodedFrameGate.reset();
    }

    /** Frame {@code number} queued, decoded into buffer {@code index}, released at {@code ts}. */
    private static void release(int number, int index, long ts) {
        long pts = 1_000_000L + number * 16_667L;
        DecodedFrameGate.onFrameQueued(number, pts);
        FrameStamps.onOutput(index, pts);
        FrameStamps.onRelease(index, ts);
    }

    @Test
    public void eachBufferGetsTheCropItsFrameWasEncodedWith() {
        timeline.add(10, A);
        timeline.add(12, B);
        release(9, 0, 900L);
        release(10, 1, 1000L);
        release(11, 2, 1100L);
        release(12, 3, 1200L);
        assertSame(FrameMapping.IDENTITY, selector.select(900L));
        assertSame(A, selector.select(1000L));
        assertSame(A, selector.select(1100L));
        assertSame(B, selector.select(1200L));
        assertEquals(12, timeline.lastPresented());
        assertTrue(selector.releasedAt() > 0L);
    }

    @Test
    public void aBufferOlderThanTheOneOnScreenIsDropped() {
        release(20, 0, 2000L);
        release(19, 1, 2100L);   // the Choreographer thread's queued frame, released late
        selector.select(2000L);
        assertNull(selector.select(2100L));
        assertEquals(1, selector.olderDropped());
    }

    @Test
    public void anUnnamedBufferKeepsTheLastCrop() {
        timeline.add(5, A);
        release(5, 0, 500L);
        assertSame(A, selector.select(500L));
        assertSame("never released through the hooks", A, selector.select(777L));
        assertEquals(1, selector.unnamed());
        selector.resetCounts();
        assertEquals(0, selector.unnamed());
    }

    @Test
    public void aNewStreamStartsOver() {
        timeline.add(5, A);
        release(5, 0, 500L);
        selector.select(500L);
        selector.reset();
        timeline.reset();
        FrameStamps.reset();
        DecodedFrameGate.reset();
        release(1, 0, 10L);   // an earlier presentation time than before: a new stream
        assertSame(FrameMapping.IDENTITY, selector.select(10L));
    }
}
