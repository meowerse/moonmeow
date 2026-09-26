package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;

import com.limelight.nvstream.input.KeyboardPacket;

import org.junit.Test;

/** The controller's pure decisions. Its wiring into Game is {@link PcKeyboardWiringTest}. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = {33})
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

    private static int[] clear(android.graphics.Rect container, android.graphics.Rect bar) {
        int[] visible = {0, 2400, 2400};
        PcKeyboardController.clearOf(container, bar, visible);
        return visible;
    }

    @Test
    public void aBottomBarBelowTheStreamCostsNothing() {
        int[] v = clear(new android.graphics.Rect(0, 896, 1080, 1504),
                new android.graphics.Rect(20, 2200, 1060, 2380));
        assertEquals(2400, v[2]);
    }

    @Test
    public void aBottomBarOverTheStreamRaisesTheVisibleBottom() {
        int[] v = clear(new android.graphics.Rect(0, 896, 1080, 1504),
                new android.graphics.Rect(20, 1250, 1060, 1400));
        assertEquals(1250, v[2]);
    }

    @Test
    public void aSideBarInTheLetterboxCostsNothingAndOneOverTheStreamNarrowsIt() {
        android.graphics.Rect stream = new android.graphics.Rect(240, 0, 2160, 1080);
        int[] v = clear(stream, new android.graphics.Rect(2170, 200, 2390, 880));
        assertEquals(2400, v[1]);
        v = clear(stream, new android.graphics.Rect(2100, 200, 2390, 880));
        assertEquals(2100, v[1]);
        v = clear(stream, new android.graphics.Rect(10, 200, 300, 880));
        assertEquals(300, v[0]);
    }
}
