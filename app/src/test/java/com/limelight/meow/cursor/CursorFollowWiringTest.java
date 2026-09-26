package com.limelight.meow.cursor;

import static org.junit.Assert.assertTrue;

import com.limelight.meow.SourceFiles;

import org.junit.Test;

import java.io.IOException;

/** The one-line hooks cursor follow depends on, pinned against the sources. */
public class CursorFollowWiringTest {

    private static String code(String path) throws IOException {
        return SourceFiles.stripComments(SourceFiles.read(path));
    }

    @Test
    public void aRelativeMoveTheFollowerPlacedItselfIsNotAlsoSentAsRelative() throws IOException {
        String conn = code("app/src/main/java/com/limelight/nvstream/NvConnection.java");
        int method = conn.indexOf("public void sendMouseMove(");
        int hook = conn.indexOf("if (com.limelight.meow.cursor.CursorInputTap.relative(deltaX, deltaY)) return;",
                method);
        int send = conn.indexOf("MoonBridge.sendMouseMove(deltaX, deltaY)", method);
        assertTrue(method > 0 && hook > method && send > hook);
    }

    @Test
    public void gameGivesTheFollowerASinkAndTheTouchMode() throws IOException {
        String game = code("app/src/main/java/com/limelight/Game.java");
        assertTrue(game.contains("cursorFollow.setPointerSink("));
        assertTrue(game.contains("conn.sendMousePosition(x, y, w, h)"));
        assertTrue(game.contains("cursorFollow.setTouchMode("));
        assertTrue(game.contains("!prefConfig.touchscreenTrackpad && touchContextMap[0] != null"));
    }

    @Test
    public void aMouseModeSwitchAndACaptureToggleReCheckVisibility() throws IOException {
        String game = code("app/src/main/java/com/limelight/Game.java");
        int apply = game.indexOf("private void applyMouseMode(int mode)");
        assertTrue(game.indexOf("cursorFollow.ensureVisible()", apply) > apply);
        int grab = game.indexOf("private void setInputGrabState(boolean grab)");
        assertTrue(game.indexOf("cursorFollow.resetEstimate()", grab) > grab);
    }

    @Test
    public void theBinderWatchesTheSoftKeyboardAndForwardsViewChanges() throws IOException {
        String binder = code(
                "app/src/main/java/com/limelight/meow/viewport/StreamViewportBinder.java");
        assertTrue(binder.contains("parent.setOnApplyWindowInsetsListener("));
        assertTrue(binder.contains("WindowInsets.Type.ime()"));
        assertTrue(binder.contains("cursorFollow.onViewTransformChanged()"));
    }
}
