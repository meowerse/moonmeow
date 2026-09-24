package com.limelight.meow.viewport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.meow.SourceFiles;

import org.junit.Test;

import java.io.IOException;

/**
 * The composition only works if its one-line hooks in upstream files are still there. None of
 * them can be exercised without a decoder or a live stream, so this reads the sources — with
 * comments stripped, so the prose documenting a hook cannot satisfy the check for it.
 */
public class ViewportCompositionWiringTest {

    private static final String RENDERER =
            "app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java";
    private static final String GAME = "app/src/main/java/com/limelight/Game.java";
    private static final String ABSOLUTE_TOUCH =
            "app/src/main/java/com/limelight/binding/input/touch/AbsoluteTouchContext.java";
    private static final String PAN_ZOOM = "app/src/main/java/com/limelight/utils/PanZoomHandler.java";

    private static String code(String path) throws IOException {
        return SourceFiles.stripComments(SourceFiles.read(path));
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("not found: " + signature, start >= 0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced: " + signature);
    }

    @Test
    public void everyQueuedPictureIsPairedWithItsTimestamp() throws IOException {
        String submit = body(code(RENDERER), "public int submitDecodeUnit(");
        int stamp = submit.indexOf("lastTimestampUs = timestampUs;");
        int hook = submit.indexOf("DecodedFrameGate.onFrameQueued(frameNumber, timestampUs)");
        int queue = submit.indexOf("queueNextInputBuffer(timestampUs, codecFlags)");
        assertTrue("the hook must follow the final timestamp", stamp >= 0 && hook > stamp);
        assertTrue("and precede the queue, so the frame is known before it can be output",
                queue > hook);
    }

    @Test
    public void everyPresentedFrameReportsItsTimestamp() throws IOException {
        // updateDecodeLatencyStats is the one call every render path makes for a frame it
        // hands to the display (and the balanced path for a frame it queues to).
        String stats = body(code(RENDERER), "private void updateDecodeLatencyStats(");
        assertTrue(stats.contains("DecodedFrameGate.onFramePresented(presentationTimeUs)"));
    }

    @Test
    public void gameWiresTheLogicalTransformIntoTheBinderAndTheInputMapper() throws IOException {
        String game = code(GAME);
        assertTrue(game.contains("viewportBinder.setTransformSource(panZoomHandler)"));
        assertTrue(game.contains("ReferencePointer.install(panZoomHandler)"));
        assertTrue(game.contains("ReferencePointer.uninstall(panZoomHandler)"));
        assertTrue("the local cursor scaler must read the logical zoom",
                game.contains("new LocalCursorScaler(panZoomHandler, inputCaptureProvider)"));
    }

    @Test
    public void bothAbsoluteSendSitesMapThroughTheView() throws IOException {
        String update = body(code(GAME), "private void updateMousePosition(");
        assertTrue(update.contains("conn.sendMousePosition(ReferencePointer.x(eventX,"));
        assertTrue(update.contains("ReferencePointer.y(eventY,"));

        String touch = body(code(ABSOLUTE_TOUCH), "private void updatePosition(");
        assertTrue(touch.contains("ReferencePointer.x(eventX, targetView.getWidth())"));
        assertTrue(touch.contains("ReferencePointer.y(eventY, targetView.getHeight())"));
    }

    @Test
    public void panningNeverReadsThePresentedTransformBack() throws IOException {
        String pan = body(code(PAN_ZOOM), "public void panBy(");
        assertFalse("once composed the view's X is not the logical X",
                pan.contains("streamView.getX()"));
        assertFalse(pan.contains("streamView.getY()"));
    }
}
