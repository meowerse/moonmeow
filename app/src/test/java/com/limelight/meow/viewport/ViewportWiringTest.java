package com.limelight.meow.viewport;

import static org.junit.Assert.assertFalse;
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
        String body = methodBody(stripComments(read(PAN_ZOOM)), "private void constrainToBounds()");
        assertContains("constrainToBounds must notify the zoom observer",
                body, "notifyZoomTransformChanged()");
    }

    @Test
    public void aRestoredZoomAlsoNotifies() throws IOException {
        // setInitialZoomAndPan is the one transform that bypasses constrainToBounds. With
        // rememberZoomPan on, missing this leaves the host uncropped until the user moves.
        String body = methodBody(stripComments(read(PAN_ZOOM)), "public void setInitialZoomAndPan(");
        assertContains("setInitialZoomAndPan must notify the zoom observer",
                body, "notifyZoomTransformChanged()");
    }

    @Test
    public void theBinderIsBuiltWheneverTheRendererIs2D() throws IOException {
        // ViewportGeometry assumes the stream view's box is the video frame: StreamContainer
        // stops sizing itself to the stream aspect outside MODE_2D and getSurfaceView() then
        // returns a GLSurfaceView rendering a stereo composition, so the mapping is nonsense.
        // That is the only real precondition, and it is the only gate left.
        String condition = enclosingIfCondition(stripComments(read(GAME)), "new StreamViewportBinder(");
        assertContains("the binder must be gated on the 2D render mode", condition,
                "MODE_2D");
        assertContains("the render mode must come from mapIntToStreamMode, not a literal",
                condition, "mapIntToStreamMode(");
    }

    @Test
    public void thePreferenceGatesTheWireAndNotTheConstruction() throws IOException {
        // The binder owns two features: telling the host where to spend bitrate, and panning
        // our own view to chase the cursor. Only the first needs the preference or the host.
        // Gating construction on the preference is what silently killed cursor-follow for
        // anyone who had it off -- see docs/meow/TOUCHPOINTS.md, MEOW-TOUCH(cursor-follow).
        String game = stripComments(read(GAME));
        assertContains("the preference must reach the reporter rather than the constructor",
                game, "viewportBinder.setEnabled(ViewportPreference.isEnabled(this))");
        assertFalse("the binder must not be constructed behind the preference",
                enclosingIfCondition(game, "new StreamViewportBinder(")
                        .contains("ViewportPreference"));
    }

    @Test
    public void cursorFollowIsNotGatedOnHostSupport() throws IOException {
        // Panning the local view sends nothing. Gating it on `live` -- which means "the host
        // echoed our viewport message" -- is what made the feature look implemented and dead.
        String body = methodBody(stripComments(read(BINDER)),
                "public boolean handleCursorViewPosition(");
        assertFalse("cursor-follow must not depend on the host echo", body.contains("!live"));
        assertContains("it depends on the stream being up, and nothing else",
                body, "streamStarted");
    }

    @Test
    public void theBinderIsAttachedToThePanZoomHandler() throws IOException {
        assertContains("the binder must be attached to the pan/zoom handler",
                stripComments(read(GAME)), "panZoomHandler.setZoomTransformObserver(viewportBinder)");
    }

    @Test
    public void theStreamStartAlsoReportsAZoomThatWasAlreadyRestored() throws IOException {
        // setInitialZoomAndPan fires long before the connection is up, so its notify is
        // discarded; the readback in onStreamStarted is what actually delivers it.
        String body = methodBody(stripComments(read(BINDER)), "public void onStreamStarted(");
        assertContains("onStreamStarted must read the live transform back",
                body, "computeVisibleHostRect()");
        assertContains("and hand it to the reporter", body, "onVisibleRectChanged(");
    }

    @Test
    public void theStreamStartResetsTheHostToTheFullDesktop() throws IOException {
        String body = methodBody(stripComments(read(GAME)), "public void connectionStarted()");
        assertContains("connectionStarted must reset the viewport",
                body, "viewportBinder.onStreamStarted(");
    }

    @Test
    public void theStreamStopUncropsBeforeTheConnectionGoesDown() throws IOException {
        // LiSendViewportEvent may only be called between LiStartConnection and
        // LiStopConnection, so this must sit above conn.stop().
        //
        // Comments are stripped first. The site is documented with a comment that says
        // "must precede conn.stop()", and matching that as if it were code found the
        // ordering reversed on a correct implementation -- the exact failure mode this
        // file was rewritten to stop making.
        String source = stripComments(read(GAME));
        String body = methodBody(source, "private void stopConnection()");
        int uncrop = body.indexOf("viewportBinder.onStreamStopped()");
        int connStop = body.indexOf("conn.stop()");
        assertTrue("stopConnection() must uncrop; no viewportBinder.onStreamStopped() in:\n"
                + indent(body), uncrop >= 0);
        assertTrue("conn.stop() not found inside stopConnection():\n" + indent(body),
                connStop >= 0);
        assertTrue("the uncrop must come before conn.stop(), because sending a viewport"
                        + " after LiStopConnection races the ENet peer's destruction:\n"
                        + indent(body),
                uncrop < connStop);
    }

    @Test
    public void theUncropDoesNotRunOnTheUiThread() throws IOException {
        // It blocks, bounded, waiting for the send to reach the library. stopConnection()
        // already spawns a worker for conn.stop() precisely because that does network I/O;
        // the uncrop belongs on the same worker, before it.
        String body = methodBody(stripComments(read(GAME)), "private void stopConnection()");
        int thread = body.indexOf("new Thread()");
        int uncrop = body.indexOf("viewportBinder.onStreamStopped()");
        assertTrue("stopConnection() must still spawn its teardown worker:\n" + indent(body),
                thread >= 0);
        assertTrue("the uncrop must be inside that worker, not on the UI thread above it:\n"
                + indent(body), uncrop > thread);
    }

    @Test
    public void theBinderIsReleasedUnconditionallyOnDestroy() throws IOException {
        // stopConnection() is guarded on connecting||connected, so a handshake that never
        // completed never reaches onStreamStopped(). Without this the reporter's thread and
        // the echo registration -- which holds the Activity -- outlive the Activity.
        String body = methodBody(stripComments(read(GAME)), "protected void onDestroy()");
        assertContains("onDestroy must release the viewport binder", body,
                "viewportBinder.release()");
    }

    @Test
    public void theEchoCallbackIsWiredIntoTheConnectionListener() throws IOException {
        // Without this the host's echo goes to the library's stub and the client has no
        // capability signal at all -- which is how it ends up talking to stock Sunshine.
        String source = stripComments(read("app/src/main/jni/moonlight-core/callbacks.c"));
        assertContains("callbacks.c must install a setViewport callback",
                source, ".setViewport =");
        assertContains("and it must be the one implemented in meowjni.c",
                source, "MeowBridgeClSetViewport");
    }

    /**
     * Replaces the contents of line and block comments with spaces, preserving every
     * offset and every newline so brace and paren matching still work.
     *
     * <p>Without this these assertions match the prose that documents the very thing they
     * are checking, which is worse than useless: it fails correct code and passes nothing.
     */
    private static String stripComments(String source) {
        char[] out = source.toCharArray();
        int i = 0;
        while (i < out.length - 1) {
            if (out[i] == '/' && out[i + 1] == '/') {
                while (i < out.length && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (out[i] == '/' && out[i + 1] == '*') {
                while (i < out.length) {
                    boolean end = i < out.length - 1 && out[i] == '*' && out[i + 1] == '/';
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                    if (end) {
                        if (i < out.length && out[i] != '\n') {
                            out[i] = ' ';
                        }
                        i++;
                        break;
                    }
                }
            } else {
                i++;
            }
        }
        return new String(out);
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
