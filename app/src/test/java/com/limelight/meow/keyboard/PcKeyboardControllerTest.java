package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;

import com.limelight.nvstream.input.KeyboardPacket;

import org.junit.Test;

/** The controller's pure decisions. Its wiring into Game is {@link PcKeyboardWiringTest}. */
public class PcKeyboardControllerTest {

    @Test
    public void focusLossReleasesEveryPhysicalModifierOnTheHost() {
        // Regression: Game cleared its modifier flags on focus loss without telling the host,
        // so a Ctrl held while a dialog or the notification shade took focus stayed down.
        RecordingSink sink = new RecordingSink();
        PcKeyboardController.releaseModifierFlags(sink,
                KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_META);
        assertEquals("-CTRL -RCTRL -SUPER -RSUPER", sink.joined());
    }

    @Test
    public void everyModifierFlagIsReleased() {
        RecordingSink sink = new RecordingSink();
        PcKeyboardController.releaseModifierFlags(sink, 0x0F);
        assertEquals("-SHIFT -RSHIFT -CTRL -RCTRL -ALT -RALT -SUPER -RSUPER", sink.joined());
    }

    @Test
    public void noFlagsNothingSent() {
        RecordingSink sink = new RecordingSink();
        PcKeyboardController.releaseModifierFlags(sink, 0);
        assertEquals("", sink.joined());
    }

    @Test
    public void legacyImeDetection() {
        assertEquals("a navigation bar is not a keyboard", 0,
                PcKeyboardController.legacyImeInset(2400, 2400 - 130, 130));
        assertEquals(900, PcKeyboardController.legacyImeInset(2400, 1500, 130));
    }

    @Test
    public void hardKeyboardDetection() {
        Configuration c = new Configuration();
        c.keyboard = Configuration.KEYBOARD_QWERTY;
        c.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        assertTrue(PcKeyboardController.hasHardKeyboard(c));
        c.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES;
        assertFalse(PcKeyboardController.hasHardKeyboard(c));
        c.keyboard = Configuration.KEYBOARD_NOKEYS;
        c.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        assertFalse(PcKeyboardController.hasHardKeyboard(c));
    }
}
