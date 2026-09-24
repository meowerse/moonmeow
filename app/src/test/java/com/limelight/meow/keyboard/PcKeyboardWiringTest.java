package com.limelight.meow.keyboard;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The PC keyboard reaches into {@code Game} and {@code StreamContainer} through one-line
 * hooks that no JVM test can drive (they live in the Activity). Deleting one leaves every
 * other test green and produces a specific, silent defect, named in each assertion. Same
 * approach, and the same reasoning, as {@code ViewportWiringTest}.
 */
public class PcKeyboardWiringTest {

    private static final String GAME = "app/src/main/java/com/limelight/Game.java";
    private static final String CONTAINER = "app/src/main/java/com/limelight/ui/StreamContainer.java";

    @Test
    public void focusLossReleasesHostModifiersBeforeGameForgetsThem() throws IOException {
        // The upstream bug: Game zeroed its modifier flags on focus loss and never sent the
        // key-ups, so the host kept Ctrl (or Alt, Shift, Super) held.
        String body = methodBody(read(GAME), "public void onWindowFocusChanged(boolean hasFocus)");
        int hook = body.indexOf("pcKeyboard.onWindowFocusChanged(hasFocus, modifierFlags)");
        int clear = body.indexOf("this.modifierFlags = 0;");
        assertTrue("onWindowFocusChanged must hand the flags to the PC keyboard:\n" + body, hook >= 0);
        assertTrue("... before it clears them:\n" + body, clear > hook);
    }

    @Test
    public void theKeyboardIsAttachedInOnCreate() throws IOException {
        assertContains("onCreate must build the PC keyboard", methodBody(read(GAME),
                "protected void onCreate(Bundle savedInstanceState)"),
                "PcKeyboardController.attach(this, streamContainer, prefConfig)");
    }

    @Test
    public void physicalAndImeKeysCarryLatchedModifiers() throws IOException {
        String down = methodBody(read(GAME), "public boolean handleKeyDown(KeyEvent event)");
        int before = down.indexOf("pcKeyboard.beforeExternalKey(event.getKeyCode())");
        int send = down.indexOf("conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN");
        assertTrue("the latch must be applied before the key goes down:\n" + down, before >= 0 && before < send);

        String up = methodBody(read(GAME), "public boolean handleKeyUp(KeyEvent event)");
        int sendUp = up.indexOf("conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP");
        int after = up.indexOf("pcKeyboard.afterExternalKey(event.getKeyCode())");
        assertTrue("the latch must be spent after the key comes up:\n" + up, sendUp >= 0 && after > sendUp);
    }

    @Test
    public void imeTextCarriesLatchedModifiers() throws IOException {
        String game = read(GAME);
        assertContains("committed text must be offered to the PC keyboard first",
                methodBody(game, "public boolean handleCommitText(CharSequence text)"),
                "pcKeyboard.onImeText(text)");
        String multiple = methodBody(game, "public boolean handleKeyMultiple(KeyEvent event)");
        int hook = multiple.indexOf("pcKeyboard.onImeText(event.getCharacters())");
        int send = multiple.indexOf("conn.sendUtf8Text(event.getCharacters())");
        assertTrue("ACTION_MULTIPLE text must be offered first:\n" + multiple, hook >= 0 && hook < send);
    }

    @Test
    public void entryPointsRouteThroughTheController() throws IOException {
        String game = read(GAME);
        assertContains("toggleKeyboard must open the last used keyboard",
                methodBody(game, "public void toggleKeyboard()"), "pcKeyboard.onToggleKeyboard()");
        assertContains("back must hide the PC keyboard first",
                methodBody(game, "public void onBackPressed()"), "pcKeyboard.onBackPressed()");
        assertContains("the quick bar must have the PC keyboard button",
                game, "public void onPcKeyboard() { if (pcKeyboard != null) pcKeyboard.toggle(); }");
        assertContains("the quick bar's keyboard button must choose the system keyboard",
                game, "pcKeyboard.preferSystemKeyboard(); toggleKeyboard();");
    }

    @Test
    public void immersiveModeAsksTheControllerFirst() throws IOException {
        String game = read(GAME);
        int runnable = game.indexOf("private final Runnable hideSystemUi = new Runnable()");
        assertTrue(runnable >= 0);
        String body = methodBody(game.substring(runnable), "public void run()");
        int hook = body.indexOf("pcKeyboard.applySystemBars()");
        int legacy = body.indexOf("setSystemUiVisibility(");
        assertTrue("the navigation-bar policy must run before immersive mode:\n" + body,
                hook >= 0 && hook < legacy);
    }

    @Test
    public void theStreamUsesTheComposingAwareConnection() throws IOException {
        assertContains("StreamContainer must hand the IME the connection that does not resend words",
                methodBody(read(CONTAINER), "public InputConnection onCreateInputConnection(EditorInfo outAttrs)"),
                "new com.limelight.meow.keyboard.ImeInputConnection(this, mInputCallbacks)");
    }

    // ---- helpers ----------------------------------------------------------------------------

    static String read(String relativePath) throws IOException {
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

    private static String methodBody(String source, String declaration) {
        int start = source.indexOf(declaration);
        if (start < 0) {
            fail("could not find `" + declaration + "`");
        }
        int open = source.indexOf('{', start);
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

    private static void assertContains(String message, String haystack, String needle) {
        assertTrue(message + "\n  expected: " + needle, haystack.contains(needle));
    }
}
