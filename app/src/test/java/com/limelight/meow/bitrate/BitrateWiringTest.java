package com.limelight.meow.bitrate;

import static org.junit.Assert.assertTrue;

import com.limelight.meow.SourceFiles;

import org.junit.Test;

import java.io.IOException;

/** The one-line hooks automatic bitrate depends on, pinned against the sources. */
public class BitrateWiringTest {

    private static String code(String path) throws IOException {
        return SourceFiles.stripComments(SourceFiles.read(path));
    }

    @Test
    public void theNegotiatedBitrateComesFromTheSession() throws IOException {
        String game = code("app/src/main/java/com/limelight/Game.java");
        assertTrue(game.contains(".setBitrate(BitrateSession.negotiate(bitrateSession, "
                + "isMetered ? prefConfig.meteredBitrate: prefConfig.bitrate))"));
        assertTrue(game.contains("viewportBinder.setBitrateSession(bitrateSession)"));
        assertTrue("both extensions need the meow-host proof",
                game.contains("viewportBinder.setCapabilityProbe(true)"));
    }

    @Test
    public void theSessionIsCreatedBeforeTheStreamConfigurationIsBuilt() throws IOException {
        String game = code("app/src/main/java/com/limelight/Game.java");
        int created = game.indexOf("bitrateSession = new BitrateSession(");
        int negotiated = game.indexOf("BitrateSession.negotiate(bitrateSession");
        assertTrue(created > 0 && negotiated > created);
    }

    @Test
    public void theRendererPublishesDecodeTimeAndTheOverlayLine() throws IOException {
        String renderer = code(
                "app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
        int copy = renderer.indexOf("lastWindowVideoStats.copy(activeWindowVideoStats);");
        int publish = renderer.indexOf("DecodeTimeWindow.publish(lastWindowVideoStats.decoderTimeMs, "
                + "lastWindowVideoStats.totalFramesReceived)");
        assertTrue(copy > 0 && publish > copy);
        int append = renderer.indexOf("BitrateOverlay.append(sb, context, prefs.enablePerfOverlayLite)");
        int fullLog = renderer.indexOf("String fullLog = sb.toString();");
        assertTrue(append > 0 && fullLog > append);
    }

    @Test
    public void thePoorConnectionWarningIsUntouched() throws IOException {
        String game = code("app/src/main/java/com/limelight/Game.java");
        assertTrue(game.contains("MoonBridge.CONN_STATUS_POOR"));
    }
}
