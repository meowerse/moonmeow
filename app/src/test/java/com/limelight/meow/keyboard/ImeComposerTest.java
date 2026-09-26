package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ImeComposerTest {

    private final List<String> ops = new ArrayList<>();
    private final ImeComposer composer = new ImeComposer(new ImeComposer.Output() {
        @Override
        public void delete(int codePoints) {
            ops.add("-" + codePoints);
        }

        @Override
        public void insert(String text) {
            ops.add("+" + text);
        }
    });

    private String ops() {
        return String.join(" ", ops);
    }

    @Test
    public void composingIsTypedAsItGrows() {
        composer.setComposing("h");
        composer.setComposing("he");
        composer.setComposing("hel");
        assertEquals("+h +e +l", ops());
    }

    @Test
    public void commitOfTheComposedWordAddsOnlyWhatIsNew() {
        composer.setComposing("hello");
        composer.commit("hello ");
        assertEquals("+hello + ", ops());
        assertEquals("", composer.composed());
    }

    @Test
    public void autocorrectBackspacesOnlyTheDifference() {
        composer.setComposing("helo");
        composer.commit("hello ");
        assertEquals("+helo -1 +lo ", ops());
    }

    @Test
    public void finishingKeepsTheTextAndNeverResendsIt() {
        // The duplicate-word bug: finishing composition must not type the word again.
        composer.setComposing("word");
        composer.finish();
        composer.setComposing("next");
        assertEquals("+word +next", ops());
    }

    @Test
    public void shrinkingCompositionIsBackspaces() {
        composer.setComposing("abc");
        composer.setComposing("a");
        composer.setComposing("");
        assertEquals("+abc -2 -1", ops());
    }

    @Test
    public void russianComposition() {
        composer.setComposing("прив");
        composer.commit("привет ");
        assertEquals("+прив +ет ", ops());
    }

    @Test
    public void surrogatePairsAreOneCharacter() {
        String smile = new String(Character.toChars(0x1F600));
        String wink = new String(Character.toChars(0x1F609));
        composer.setComposing("a" + smile);
        composer.setComposing("a" + wink);
        assertEquals("+a" + smile + " -1 +" + wink, ops());
    }

    @Test
    public void deletingTreatsCompositionAsCommitted() {
        composer.setComposing("ab");
        composer.deleteBefore(1);
        composer.setComposing("x");
        assertEquals("+ab -1 +x", ops());
    }

    @Test
    public void commitWithoutCompositionIsPlainText() {
        composer.commit("ok");
        composer.deleteBefore(0);
        assertEquals("+ok", ops());
    }

    @Test
    public void commonPrefixNeverSplitsAPair() {
        String a = new String(Character.toChars(0x1F600));
        String b = new String(Character.toChars(0x1F601));
        assertEquals(0, ImeComposer.commonPrefix(a, b));
        assertEquals(2, ImeComposer.commonPrefix(a, a + "x"));
    }
}
