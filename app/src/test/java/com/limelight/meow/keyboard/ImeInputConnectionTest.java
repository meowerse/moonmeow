package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.limelight.ui.StreamContainer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * The stream's input connection, driven the way Gboard drives it, through the real
 * {@link StreamContainer} in a real window.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class ImeInputConnectionTest {

    /** Everything that reached the host, in order: text, backspaces and raw key events. */
    private final List<String> host = new ArrayList<>();
    private InputConnection ic;

    private final StreamContainer.InputCallbacks callbacks = new StreamContainer.InputCallbacks() {
        @Override
        public boolean handleKeyUp(KeyEvent event) {
            return true;
        }

        @Override
        public boolean handleKeyDown(KeyEvent event) {
            host.add("key:" + (event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN
                    ? event.getCharacters() : String.valueOf((char) event.getUnicodeChar())));
            return true;
        }

        @Override
        public boolean handleCommitText(CharSequence text) {
            host.add("text:" + text);
            return true;
        }

        @Override
        public boolean handleDeleteSurroundingText(int beforeLength, int afterLength) {
            host.add("bksp:" + beforeLength);
            return true;
        }

        @Override
        public boolean handleFocusChange(boolean hasWindowFocus) {
            return false;
        }
    };

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        StreamContainer container = new StreamContainer(activity, null);
        activity.setContentView(container);
        container.setInputCallbacks(callbacks);
        // Game is the container's key listener too: ACTION_MULTIPLE (a string of characters
        // the base connection synthesises) reaches it there and is typed on the host.
        container.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
                host.add("key:" + event.getCharacters());
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                host.add("key:" + (char) event.getUnicodeChar());
            }
            return true;
        });
        container.setCommitTextEnabled(true);
        container.requestFocus();
        ic = container.onCreateInputConnection(new EditorInfo());
        assertNotNull(ic);
    }

    private String host() {
        ShadowLooper.idleMainLooper();
        return String.join(" ", host);
    }

    @Test
    public void aComposedWordReachesTheHostExactlyOnce() {
        // Regression: the base class re-sent the composing buffer as key events from
        // finishComposingText(), so every composed word was typed twice.
        ic.setComposingText("hel", 1);
        ic.setComposingText("hello", 1);
        ic.commitText("hello ", 1);
        ic.finishComposingText();
        String sent = host();
        assertEquals("text:hel text:lo text: ", sent);
    }

    @Test
    public void nothingIsLeftInTheBufferThatFinishingWouldSendAgain() {
        // The mechanism of the duplicate: BaseInputConnection in dummy mode keeps composing
        // text in its own buffer and, in finishComposingText(), sends that buffer to the view
        // as key events (Game.onKey -> handleKeyMultiple -> sendUtf8Text). The old override
        // consumed commitText without letting the base class clear it, so the committed word
        // stayed buffered and was typed a second time. What the base class would send is
        // exactly what it reports before the cursor.
        ic.setComposingText("hello", 1);
        ic.commitText("hello", 1);
        CharSequence buffered = ic.getTextBeforeCursor(100, 0);
        assertEquals("", buffered == null ? "" : buffered.toString());
    }

    @Test
    public void committingThenFinishingNeverSynthesisesKeyEvents() {
        // The duplicate, isolated: after a commit, finishComposingText() must send nothing.
        ic.setComposingText("hello", 1);
        ic.commitText("hello", 1);
        ic.finishComposingText();
        String sent = host();
        assertEquals("no key events in: " + sent, -1, sent.indexOf("key:"));
    }

    @Test
    public void finishingAfterComposingDoesNotRetype() {
        ic.setComposingText("word", 1);
        ic.finishComposingText();
        assertEquals("text:word", host());
    }

    @Test
    public void autocorrectBackspacesInOrder() {
        ic.setComposingText("helo", 1);
        ic.commitText("hello", 1);
        assertEquals("text:helo bksp:1 text:lo", host());
    }

    @Test
    public void anInsertIsSentBeforeALaterDelete() {
        ic.setComposingText("ab", 1);
        ic.setComposingText("a", 1);
        assertEquals("text:ab bksp:1", host());
    }

    @Test
    public void russianTextGoesAsText() {
        ic.commitText("привет", 1);
        assertEquals("text:привет", host());
    }

    @Test
    public void deleteSurroundingTextIsBackspaces() {
        ic.deleteSurroundingText(2, 0);
        assertEquals("bksp:2", host());
    }
}
