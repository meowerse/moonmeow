package com.limelight.meow.viewport;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The feature's correctness rests on a handful of call sites in upstream files, and none of
 * them can be reached from a JVM test: they live in an Activity and in a View helper.
 * Deleting any one leaves everything else compiling and passing while producing a specific,
 * silent defect. This reads the sources and asserts they are still there, the same way
 * {@code InlinePinchZoomDispatchOrderTest} pins its dispatch ordering.
 *
 * <p><b>These are token assertions, not offset assertions.</b> An earlier revision asserted
 * that two substrings were within 400 characters of each other and that a guard fitted on
 * one physical line. Both are properties of the formatting rather than of the code: adding a
 * comment or wrapping an {@code if} across two lines failed a correct implementation. What
 * matters is whether the enclosing construct <em>contains</em> the terms, so that is what is
 * checked, with a failure message that prints what was actually found.
 *
 * <p>Line numbers are deliberately not asserted here — {@code docs/meow/TOUCHPOINTS.md}
 * carries those, and the touch-point markers in the sources are the authority. (This file
 * deliberately does not spell the marker out, so it never shows up in the registry audit.)
 */
public class ViewportWiringTest {

    private static final String GAME = "app/src/main/java/com/limelight/Game.java";
    private static final String PAN_ZOOM = "app/src/main/java/com/limelight/utils/PanZoomHandler.java";
    private static final String BINDER =
            "app/src/main/java/com/limelight/meow/viewport/StreamViewportBinder.java";

    @Test
    public void everyTransformNotifiesTheObserver() throws IOException {
        // constrainToBounds is the single choke point: pinchBy, panBy and handleSurfaceChange
        // all end there. Without the notify the host is never told about a zoom at all.
        String body = methodBody(read(PAN_ZOOM), "private void constrainToBounds()");
        assertContains("constrainToBounds must notify the zoom observer",
                body, "notifyZoomTransformChanged()");
    }

    @Test
    public void aRestoredZoomAlsoNotifies() throws IOException {
        // setInitialZoomAndPan is the one transform that bypasses constrainToBounds. With
        // rememberZoomPan on, missing this leaves the host uncropped until the user moves.
        String body = methodBody(read(PAN_ZOOM), "public void setInitialZoomAndPan(");
        assertContains("setInitialZoomAndPan must notify the zoom observer",
                body, "notifyZoomTransformChanged()");
    }

    @Test
    public void theBinderIsBuiltOnlyWhenThePreferenceIsOnAndTheRendererIs2D() throws IOException {
        // An install that has not opted in must run exactly the code it ran before. And
        // ViewportGeometry assumes the stream view's box is the video frame: StreamContainer
        // stops sizing itself to the stream aspect outside MODE_2D and getSurfaceView() then
        // returns a GLSurfaceView rendering a stereo composition, so the mapping is nonsense.
        String condition = enclosingIfCondition(read(GAME), "new StreamViewportBinder(");
        assertContains("the binder must be gated on the preference", condition,
                "ViewportPreference.isEnabled(this)");
        assertContains("the binder must be gated on the 2D render mode", condition,
                "MODE_2D");
        assertContains("the render mode must come from mapIntToStreamMode, not a literal",
                condition, "mapIntToStreamMode(");
    }

    @Test
    public void theBinderIsAttachedToThePanZoomHandler() throws IOException {
        assertContains("the binder must be attached to the pan/zoom handler",
                read(GAME), "panZoomHandler.setZoomTransformObserver(viewportBinder)");
    }

    @Test
    public void theStreamStartAlsoReportsAZoomThatWasAlreadyRestored() throws IOException {
        // setInitialZoomAndPan fires long before the connection is up, so its notify is
        // discarded; the readback in onStreamStarted is what actually delivers it.
        String body = methodBody(read(BINDER), "public void onStreamStarted(");
        assertContains("onStreamStarted must read the live transform back",
                body, "computeVisibleHostRect()");
        assertContains("and hand it to the reporter", body, "onVisibleRectChanged(");
    }

    @Test
    public void theStreamStartResetsTheHostToTheFullDesktop() throws IOException {
        String body = methodBody(read(GAME), "public void connectionStarted()");
        assertContains("connectionStarted must reset the viewport",
                body, "viewportBinder.onStreamStarted(");
    }

    @Test
    public void theStreamStopUncropsBeforeTheConnectionGoesDown() throws IOException {
        // LiSendViewportEvent may only be called between LiStartConnection and
        // LiStopConnection, so this must sit above conn.stop().
        String source = read(GAME);
        int stop = source.indexOf("private void stopConnection()");
        if (stop < 0) {
            fail("stopConnection() not found in " + GAME);
        }
        int uncrop = source.indexOf("viewportBinder.onStreamStopped()", stop);
        int connStop = source.indexOf("conn.stop()", stop);
        assertTrue("stopConnection must uncrop; found no viewportBinder.onStreamStopped()"
                + " after stopConnection()", uncrop > 0);
        assertTrue("conn.stop() not found after stopConnection()", connStop > 0);
        assertTrue("the uncrop must happen before conn.stop(), because sending a viewport"
                + " after LiStopConnection races the ENet peer's destruction",
                uncrop < connStop);
    }

    @Test
    public void theEchoCallbackIsWiredIntoTheConnectionListener() throws IOException {
        // Without this the host's echo goes to the library's stub and the client has no
        // capability signal at all -- which is how it ends up talking to stock Sunshine.
        String source = read("app/src/main/jni/moonlight-core/callbacks.c");
        assertContains("callbacks.c must install a setViewport callback",
                source, ".setViewport =");
        assertContains("and it must be the one implemented in meowjni.c",
                source, "MeowBridgeClSetViewport");
    }

    /**
     * The body of a method, from its declaration to the closing brace that matches its
     * opening one. Brace matching rather than "up to the next method" so that reordering
     * methods, or adding one between them, does not throw
     * {@code StringIndexOutOfBoundsException} instead of asserting.
     */
    private static String methodBody(String source, String declaration) {
        int start = source.indexOf(declaration);
        if (start < 0) {
            fail("could not find `" + declaration + "`; it was renamed, removed, or its"
                    + " signature changed");
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            fail("`" + declaration + "` has no body");
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        fail("unbalanced braces after `" + declaration + "`");
        return "";
    }

    /**
     * The condition of the innermost {@code if} that encloses {@code needle}, with newlines
     * collapsed. Formatting-independent: the condition may be wrapped over any number of
     * lines and carry any number of comments.
     */
    private static String enclosingIfCondition(String source, String needle) {
        int at = source.indexOf(needle);
        if (at < 0) {
            fail("could not find `" + needle + "` at all");
        }
        // Walk back to the nearest `if (` before it, then take everything up to the matching
        // close paren.
        Matcher m = Pattern.compile("\\bif\\s*\\(").matcher(source.substring(0, at));
        int ifAt = -1;
        while (m.find()) {
            ifAt = m.end();
        }
        if (ifAt < 0) {
            fail("`" + needle + "` is not inside an if statement at all");
        }
        int depth = 1;
        for (int i = ifAt; i < at; i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(ifAt, i).replaceAll("\\s+", " ").trim();
                }
            }
        }
        fail("could not find the end of the if condition guarding `" + needle + "`");
        return "";
    }

    private static void assertContains(String message, String haystack, String needle) {
        assertTrue(message + "\n  expected to find: " + needle
                        + "\n  in:\n" + indent(haystack),
                haystack.contains(needle));
    }

    private static String indent(String text) {
        String trimmed = text.length() > 2000 ? text.substring(0, 2000) + "\n  [truncated]" : text;
        return "    " + trimmed.replace("\n", "\n    ");
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
