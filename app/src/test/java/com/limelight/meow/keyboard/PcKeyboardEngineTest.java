package com.limelight.meow.keyboard;

import static com.limelight.meow.keyboard.ModifierLatch.LATCHED;
import static com.limelight.meow.keyboard.ModifierLatch.LOCKED;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_ALT;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_CTRL;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_FN;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_SHIFT;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_SUPER;
import static com.limelight.meow.keyboard.ModifierLatch.OFF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class PcKeyboardEngineTest {

    private final RecordingSink sink = new RecordingSink();
    private final PcKeyboardEngine engine = new PcKeyboardEngine(sink, 300);
    private long now = 10_000;

    private void tapModifier(int mod) {
        engine.modifierDown(mod);
        engine.modifierUp(mod, now);
        now += 1000;
    }

    private void doubleTapModifier(int mod) {
        engine.modifierDown(mod);
        engine.modifierUp(mod, now);
        engine.modifierDown(mod);
        engine.modifierUp(mod, now + 100);
        now += 1000;
    }

    private void tapKey(int code) {
        engine.keyDown(code);
        engine.keyUp(code);
    }

    @Test
    public void aPlainKeyIsDownThenUp() {
        engine.keyDown(KeyEvent.KEYCODE_A);
        assertEquals("+A", sink.joined());
        engine.keyUp(KeyEvent.KEYCODE_A);
        assertEquals("+A -A", sink.joined());
    }

    @Test
    public void aLatchedCtrlAppliesToTheNextKeyOnlyThenReleases() {
        tapModifier(MOD_CTRL);
        assertEquals("a latch sends nothing on its own", "", sink.joined());
        tapKey(KeyEvent.KEYCODE_C);
        assertEquals("+CTRL +C -C -CTRL", sink.joined());
        assertEquals(OFF, engine.modifierState(MOD_CTRL));
        sink.clear();
        tapKey(KeyEvent.KEYCODE_V);
        assertEquals("+V -V", sink.joined());
    }

    @Test
    public void tappingSuperOnAndOffSendsNothing() {
        // A lone Super press opens the KDE launcher / Start menu: cancelling a latch must not.
        tapModifier(MOD_SUPER);
        tapModifier(MOD_SUPER);
        assertEquals("", sink.joined());
        assertEquals(OFF, engine.modifierState(MOD_SUPER));
    }

    @Test
    public void latchedModifiersCombine() {
        tapModifier(MOD_CTRL);
        tapModifier(MOD_SHIFT);
        tapKey(KeyEvent.KEYCODE_T);
        assertEquals("+SHIFT +CTRL +T -T -CTRL -SHIFT", sink.joined());
    }

    @Test
    public void aLockIsHeldOnTheHostUntilUnlocked() {
        doubleTapModifier(MOD_ALT);
        assertEquals(LOCKED, engine.modifierState(MOD_ALT));
        assertEquals("a lock is pressed at once, so it applies to clicks too", "+ALT", sink.joined());
        tapKey(KeyEvent.KEYCODE_TAB);
        tapKey(KeyEvent.KEYCODE_TAB);
        assertEquals("Alt stays down across Tabs: the switcher stays open",
                "+ALT +TAB -TAB +TAB -TAB", sink.joined());
        tapModifier(MOD_ALT);
        assertEquals("+ALT +TAB -TAB +TAB -TAB -ALT", sink.joined());
        assertEquals(OFF, engine.modifierState(MOD_ALT));
    }

    @Test
    public void longPressLocks() {
        engine.modifierDown(MOD_CTRL);
        assertTrue(engine.modifierLongPress(MOD_CTRL));
        engine.modifierUp(MOD_CTRL, now);
        assertEquals(LOCKED, engine.modifierState(MOD_CTRL));
        assertEquals("+CTRL", sink.joined());
    }

    @Test
    public void holdCtrlLockThenFnThenF5IsCtrlF5() {
        // The user's scenario: hold a special key until it sticks, switch to Fn, press a key.
        engine.modifierDown(MOD_CTRL);
        engine.modifierLongPress(MOD_CTRL);
        engine.modifierUp(MOD_CTRL, now);
        tapModifier(MOD_FN);
        assertTrue(engine.isFnLayer());
        tapKey(KeyEvent.KEYCODE_F5);
        assertEquals("+CTRL +F5 -F5", sink.joined());
        assertFalse("a latched Fn goes back to the main layer after one key", engine.isFnLayer());
        assertEquals("Ctrl is still locked", LOCKED, engine.modifierState(MOD_CTRL));
    }

    @Test
    public void latchedCtrlSurvivesTappingFnAndAppliesToAnFnKey() {
        tapModifier(MOD_CTRL);
        tapModifier(MOD_FN);
        tapKey(KeyEvent.KEYCODE_F5);
        assertEquals("+CTRL +F5 -F5 -CTRL", sink.joined());
        assertFalse(engine.isFnLayer());
        assertEquals(OFF, engine.modifierState(MOD_CTRL));
    }

    @Test
    public void aLockedFnLayerStays() {
        doubleTapModifier(MOD_FN);
        tapKey(KeyEvent.KEYCODE_F1);
        tapKey(KeyEvent.KEYCODE_F2);
        assertTrue(engine.isFnLayer());
        assertEquals("Fn never reaches the host", "+F1 -F1 +F2 -F2", sink.joined());
    }

    @Test
    public void aHeldModifierChordsWithAnotherFingerAndIsOffAfterwards() {
        engine.modifierDown(MOD_SHIFT);
        engine.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT);
        engine.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT);
        engine.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT);
        engine.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT);
        engine.modifierUp(MOD_SHIFT, now);
        assertEquals("+SHIFT +RIGHT -RIGHT -SHIFT +SHIFT +RIGHT -RIGHT -SHIFT", sink.joined());
        assertEquals(OFF, engine.modifierState(MOD_SHIFT));
    }

    @Test
    public void aHeldKeyStaysDownSoTheHostRepeatsIt() {
        engine.keyDown(KeyEvent.KEYCODE_DEL);
        assertEquals("no synthetic repeats: the host's own repeat applies", "+BKSP", sink.joined());
        engine.keyUp(KeyEvent.KEYCODE_DEL);
        assertEquals("+BKSP -BKSP", sink.joined());
    }

    @Test
    public void theModifiersOfTheFirstKeyStayForAKeyPressedBeforeItIsReleased() {
        tapModifier(MOD_CTRL);
        engine.keyDown(KeyEvent.KEYCODE_A);
        engine.keyDown(KeyEvent.KEYCODE_B);
        engine.keyUp(KeyEvent.KEYCODE_A);
        engine.keyUp(KeyEvent.KEYCODE_B);
        assertEquals("+CTRL +A +B -A -B -CTRL", sink.joined());
    }

    @Test
    public void aChipPressesItsChord() {
        engine.chordDown(PcKeyboardLayout.COPY.chord);
        engine.chordUp(PcKeyboardLayout.COPY.chord);
        assertEquals("+CTRL +C -C -CTRL", sink.joined());
    }

    @Test
    public void aChipCombinesWithALatchedModifier() {
        tapModifier(MOD_SHIFT);
        engine.chordDown(PcKeyboardLayout.UNDO.chord);
        engine.chordUp(PcKeyboardLayout.UNDO.chord);
        assertEquals("Shift latched + Ctrl+Z is redo", "+SHIFT +CTRL +Z -Z -CTRL -SHIFT", sink.joined());
    }

    @Test
    public void aChipDoesNotPressAModifierThatIsAlreadyDown() {
        doubleTapModifier(MOD_CTRL);
        sink.clear();
        engine.chordDown(PcKeyboardLayout.PASTE.chord);
        engine.chordUp(PcKeyboardLayout.PASTE.chord);
        assertEquals("+V -V", sink.joined());
        assertEquals(LOCKED, engine.modifierState(MOD_CTRL));
    }

    @Test
    public void aMediaKeyIsATapOfItsVirtualKey() {
        engine.virtualKeyTap(PcKeyboardLayout.VK_VOLUME_UP);
        assertEquals("tap:0xaf", sink.joined());
    }

    @Test
    public void aPhysicalKeyTakesALatchedModifier() {
        tapModifier(MOD_CTRL);
        engine.beforeExternalKey(KeyEvent.KEYCODE_S);
        sink.events.add("[host S down]");
        engine.afterExternalKey(KeyEvent.KEYCODE_S);
        assertEquals("+CTRL [host S down] -CTRL", sink.joined());
        assertEquals(OFF, engine.modifierState(MOD_CTRL));
    }

    @Test
    public void physicalKeysAreUntouchedWithNoModifierActive() {
        engine.beforeExternalKey(KeyEvent.KEYCODE_S);
        engine.afterExternalKey(KeyEvent.KEYCODE_S);
        assertEquals("", sink.joined());
    }

    @Test
    public void physicalModifierKeysDoNotSpendALatch() {
        tapModifier(MOD_CTRL);
        engine.beforeExternalKey(KeyEvent.KEYCODE_SHIFT_LEFT);
        engine.afterExternalKey(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertEquals(LATCHED, engine.modifierState(MOD_CTRL));
        assertEquals("", sink.joined());
    }

    @Test
    public void anUnmatchedKeyUpFromAnotherKeyboardIsIgnored() {
        engine.afterExternalKey(KeyEvent.KEYCODE_S);
        tapModifier(MOD_CTRL);
        engine.afterExternalKey(KeyEvent.KEYCODE_S);
        assertEquals(LATCHED, engine.modifierState(MOD_CTRL));
    }

    @Test
    public void imeTextWithLatchedCtrlIsAShortcut() {
        tapModifier(MOD_CTRL);
        assertTrue(engine.onImeText("c"));
        assertEquals("+CTRL +C -C -CTRL", sink.joined());
    }

    @Test
    public void cyrillicImeTextWithLatchedCtrlUsesTheKeyItSitsOn() {
        tapModifier(MOD_CTRL);
        assertTrue(engine.onImeText("с"));   // Cyrillic es, on the C key
        assertEquals("+CTRL +C -C -CTRL", sink.joined());
    }

    @Test
    public void anUpperCaseLetterAddsShift() {
        tapModifier(MOD_CTRL);
        assertTrue(engine.onImeText("T"));
        assertEquals("+CTRL +SHIFT +T -T -SHIFT -CTRL", sink.joined());
    }

    @Test
    public void imeTextWithNoModifierIsLeftAsText() {
        assertFalse(engine.onImeText("c"));
        assertFalse(engine.onImeText("привет"));
        assertEquals("", sink.joined());
    }

    @Test
    public void aWholeWordWithALatchIsTextAndSpendsTheLatch() {
        tapModifier(MOD_ALT);
        assertFalse(engine.onImeText("hello"));
        assertEquals(OFF, engine.modifierState(MOD_ALT));
        assertEquals("", sink.joined());
    }

    @Test
    public void releaseAllReleasesEveryHostModifierAndClearsState() {
        doubleTapModifier(MOD_CTRL);
        doubleTapModifier(MOD_SHIFT);
        tapModifier(MOD_ALT);
        sink.clear();
        engine.releaseAll();
        assertEquals("-CTRL -SHIFT", sink.joined());
        assertEquals(OFF, engine.modifierState(MOD_CTRL));
        assertEquals(OFF, engine.modifierState(MOD_ALT));
        assertEquals(0, engine.hostModifierMask());
    }

    @Test
    public void listenerHearsStateChanges() {
        int[] calls = {0};
        engine.setListener(() -> calls[0]++);
        tapModifier(MOD_CTRL);
        assertTrue(calls[0] >= 2);
        int before = calls[0];
        tapKey(KeyEvent.KEYCODE_A);
        assertTrue("the spent latch is redrawn", calls[0] > before);
    }
}
