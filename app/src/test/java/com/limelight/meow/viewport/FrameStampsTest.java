package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A buffer the presenter receives carries only the timestamp it was released with; these are
 * the two timestamps a release can give it, and the cases where it cannot be named.
 */
public class FrameStampsTest {

    private final long[] out = new long[2];

    @Before
    @After
    public void clear() {
        FrameStamps.reset();
    }

    @Test
    public void aBufferReleasedWithARenderTimeIsNamedByThatTime() {
        FrameStamps.onOutput(3, 1_000_000L);
        FrameStamps.onRelease(3, 555_000_000L);
        assertTrue(FrameStamps.lookup(555_000_000L, out));
        assertEquals(1_000_000L, out[0]);
        assertTrue("the release time is recorded for the latency figure", out[1] > 0L);
    }

    @Test
    public void aBufferReleasedWithTrueIsNamedByItsPresentationTimeInNanoseconds() {
        // releaseOutputBuffer(index, true): the buffer's timestamp is the presentation time.
        FrameStamps.onOutput(0, 2_000_000L);
        FrameStamps.onRelease(0, 123L);
        assertTrue(FrameStamps.lookup(2_000_000_000L, out));
        assertEquals(2_000_000L, out[0]);
    }

    @Test
    public void theIndexTableFollowsTheCodecsReuseOfIndices() {
        FrameStamps.onOutput(1, 10L);
        FrameStamps.onRelease(1, 100L);
        FrameStamps.onOutput(1, 20L);
        FrameStamps.onRelease(1, 200L);
        assertTrue(FrameStamps.lookup(100L, out));
        assertEquals(10L, out[0]);
        assertTrue(FrameStamps.lookup(200L, out));
        assertEquals(20L, out[0]);
    }

    @Test
    public void unknownBuffersAreNotNamed() {
        assertFalse(FrameStamps.lookup(42L, out));
        FrameStamps.onRelease(5, 42L);   // released without an output record
        assertFalse(FrameStamps.lookup(42L, out));
        FrameStamps.onOutput(FrameStamps.INDEX_SLOTS, 7L);   // out of the table's range
        FrameStamps.onRelease(FrameStamps.INDEX_SLOTS, 43L);
        assertFalse(FrameStamps.lookup(43L, out));
    }

    @Test
    public void theRingKeepsTheRecentReleasesAndForgetsOldOnes() {
        for (int i = 0; i < FrameStamps.RING_SIZE + 10; i++) {
            FrameStamps.onOutput(i % 16, 1000L + i);
            FrameStamps.onRelease(i % 16, 5000L + i);
        }
        assertFalse(FrameStamps.lookup(5000L, out));
        assertTrue(FrameStamps.lookup(5000L + FrameStamps.RING_SIZE + 9, out));
        assertEquals(1000L + FrameStamps.RING_SIZE + 9, out[0]);
    }

    @Test
    public void resetForgetsEverything() {
        FrameStamps.onOutput(2, 9L);
        FrameStamps.onRelease(2, 99L);
        FrameStamps.reset();
        assertFalse(FrameStamps.lookup(99L, out));
    }

    @Test
    public void twoReleasingThreadsNeverCorruptEachOther() throws Exception {
        // The renderer thread and its Choreographer thread both release.
        Thread a = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                FrameStamps.onOutput(i % 8, 1_000_000L + i * 2L);
                FrameStamps.onRelease(i % 8, 9_000_000L + i * 2L);
            }
        });
        Thread b = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                FrameStamps.onOutput(8 + i % 8, 1_000_001L + i * 2L);
                FrameStamps.onRelease(8 + i % 8, 9_000_001L + i * 2L);
            }
        });
        a.start();
        b.start();
        long[] seen = new long[2];
        while (a.isAlive() || b.isAlive()) {
            for (long ts = 9_000_000L; ts < 9_040_000L; ts += 997) {
                if (FrameStamps.lookup(ts, seen)) {
                    // A slot read whole: its presentation time is the one written with it.
                    assertEquals(ts - 8_000_000L, seen[0]);
                }
            }
        }
        a.join();
        b.join();
    }

    @Test
    public void aReleaseThatNeverRecordedItsIndexIsNotNamedAfterAnEarlierUse() {
        FrameStamps.onOutput(1, 10L);
        FrameStamps.onRelease(1, 100L);
        FrameStamps.onRelease(1, 200L);   // a path without an onOutput hook
        assertFalse(FrameStamps.lookup(200L, out));
    }
}

