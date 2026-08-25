package com.limelight.meow.viewport;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The feature's correctness rests on four call sites in upstream files, and none of them can
 * be reached from a JVM test: they live in an Activity and in a View helper. Deleting any one
 * leaves everything else compiling and passing while producing a specific, silent defect.
 * This reads the sources and asserts they are still there, the same way
 * {@code InlinePinchZoomDispatchOrderTest} pins its dispatch ordering.
 *
 * <p>Line numbers are deliberately not asserted here — {@code docs/meow/TOUCHPOINTS.md}
 * carries those, and the touch-point markers in the sources are the authority. (This file
 * deliberately does not spell the marker out, so it never shows up in the registry audit.)
 */
public class ViewportWiringTest {

    private static final String GAME = "app/src/main/java/com/limelight/Game.java";
    private static final String PAN_ZOOM = "app/src/main/java/com/limelight/utils/PanZoomHandler.java";

    @Test
    public void everyTransformNotifiesTheObserver() throws IOException {
        // constrainToBounds is the single choke point: pinchBy, panBy and handleSurfaceChange
        // all end there. Without the notify the host is never told about a zoom at all.
        String source = read(PAN_ZOOM);
        int constrain = source.indexOf("private void constrainToBounds()");
        int nextMethod = source.indexOf("public void handleSurfaceChange()", constrain);
        assertTrue("constrainToBounds must notify the zoom observer",
                source.substring(constrain, nextMethod).contains("notifyZoomTransformChanged()"));
    }

    @Test
    public void aRestoredZoomAlsoNotifies() throws IOException {
        // setInitialZoomAndPan is the one transform that bypasses constrainToBounds. With
        // rememberZoomPan on, missing this leaves the host uncropped until the user moves.
        String source = read(PAN_ZOOM);
        int restore = source.indexOf("public void setInitialZoomAndPan(");
        int end = source.indexOf("public float getScaleFactor()", restore);
        assertTrue("setInitialZoomAndPan must notify the zoom observer",
                source.substring(restore, end).contains("notifyZoomTransformChanged()"));
    }

    @Test
    public void theBinderIsBuiltOnlyWhenThePreferenceIsOn() throws IOException {
        // An install that has not opted in must run exactly the code it ran before.
        String source = read(GAME);
        int guard = source.indexOf("ViewportPreference.isEnabled(this)");
        assertTrue("Game must gate the binder on the preference", guard > 0);
        int construct = source.indexOf("new StreamViewportBinder(");
        assertTrue("the binder must be constructed inside that guard",
                construct > guard && construct - guard < 400);
        assertTrue("the binder must be attached to the pan/zoom handler",
                source.contains("panZoomHandler.setZoomTransformObserver(viewportBinder)"));
    }

    @Test
    public void theStreamStartResetsTheHostToTheFullDesktop() throws IOException {
        String source = read(GAME);
        int started = source.indexOf("public void connectionStarted()");
        assertTrue("connectionStarted must exist", started > 0);
        int call = source.indexOf("viewportBinder.onStreamStarted(", started);
        assertTrue("connectionStarted must reset the viewport", call > 0);
    }

    @Test
    public void theStreamStopUncropsBeforeTheConnectionGoesDown() throws IOException {
        // LiSendViewportEvent may only be called between LiStartConnection and
        // LiStopConnection, so this must sit above conn.stop().
        String source = read(GAME);
        int stop = source.indexOf("private void stopConnection()");
        assertTrue("stopConnection must exist", stop > 0);
        int uncrop = source.indexOf("viewportBinder.onStreamStopped()", stop);
        int connStop = source.indexOf("conn.stop()", stop);
        assertTrue("stopConnection must uncrop", uncrop > 0);
        assertTrue("uncrop must happen before conn.stop()", uncrop < connStop);
    }

    private static String read(String relativePath) throws IOException {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParentFile()) {
            File candidate = new File(dir, relativePath);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            File here = new File(dir, relativePath.replaceFirst("^app/", ""));
            if (here.isFile()) {
                return new String(Files.readAllBytes(here.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not locate " + relativePath);
    }
}
