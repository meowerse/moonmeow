package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CropRequestHistoryTest {

    private static final SunmeowCropModel HOST = new SunmeowCropModel(5360, 1440, 1920, 1080);

    @Test
    public void anEchoIsMatchedToTheRequestItAnswersAmongRecentOnes() {
        CropRequestHistory history = new CropRequestHistory();
        ViewportRect first = new ViewportRect(300, 400, 672, 379);
        ViewportRect second = new ViewportRect(420, 400, 672, 379);
        history.record(first);
        history.record(second);
        int[] source = HOST.source(first);
        FrameMapping m = history.exactMapping(HOST.echo(source), 5360, 1440, 1920, 1080);
        assertNotNull(m);
        FrameMapping truth = HOST.trueMapping(source);
        assertEquals(truth.offsetX, m.offsetX, 1e-9);
        assertEquals(truth.scaleX, m.scaleX, 1e-12);
    }

    @Test
    public void anEchoForARequestNotRememberedIsNotMatched() {
        CropRequestHistory history = new CropRequestHistory();
        for (int i = 0; i < CropRequestHistory.SIZE + 1; i++) {
            history.record(new ViewportRect(300 + i * 40, 400, 672, 379));
        }
        // The first one fell out of the ring.
        ViewportRect echo = HOST.echo(HOST.source(new ViewportRect(300, 400, 672, 379)));
        assertNull(history.exactMapping(echo, 5360, 1440, 1920, 1080));
        history.clear();
        ViewportRect last = new ViewportRect(300 + CropRequestHistory.SIZE * 40, 400, 672, 379);
        assertNull(history.exactMapping(HOST.echo(HOST.source(last)), 5360, 1440, 1920, 1080));
    }
}
