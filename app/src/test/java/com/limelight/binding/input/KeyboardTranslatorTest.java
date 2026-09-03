package com.limelight.binding.input;

import android.content.Context;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * Covers the keycode mappings back-ported from moonlight-stream/moonlight-android
 * 8974dcda (Android 15/16 keycodes).
 *
 * These are pure functions over ints, so they are tested directly rather than through
 * a stream. Every case passes deviceId -1 so that neither the force-QWERTY remap nor
 * hasNormalizedMapping() can intervene -- both are no-ops for a negative device ID.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
public class KeyboardTranslatorTest {

    /**
     * translate() returns the Windows virtual key code OR'd with a 0x80 high byte
     * (KeyboardTranslator.KEY_PREFIX, which is private), so every expectation goes
     * through this rather than comparing a bare VK code.
     */
    private static short expected(int windowsVk) {
        return (short) (0x8000 | windowsVk);
    }

    /** Windows virtual key codes the host expects, before the prefix is applied. */
    private static final int VK_F1 = 0x70;
    private static final int VK_F12 = 0x7B;
    private static final int VK_F13 = 0x7C;
    private static final int VK_F24 = 0x87;
    private static final int VK_PRINT = 0x2A;
    private static final int VK_SNAPSHOT = 0x2C;
    private static final int VK_OEM_PLUS = 0xBB;

    private KeyboardTranslator translator;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(ctx);
        translator = new KeyboardTranslator(prefConfig);
    }

    private short translate(int keycode) {
        return translator.translate(keycode, 0, -1);
    }

    @Test
    public void f13_mapsToVkF13() {
        assertEquals(expected(VK_F13), translate(KeyEvent.KEYCODE_F13));
    }

    @Test
    public void f24_mapsToVkF24() {
        assertEquals(expected(VK_F24), translate(KeyEvent.KEYCODE_F24));
    }

    @Test
    public void f13ThroughF24_areContiguousAndFollowF12() {
        // The back-ported branch is range arithmetic, so a gap in either the Android
        // keycodes or the Windows VK codes would silently shift every key above it.
        for (int i = 0; i <= KeyEvent.KEYCODE_F24 - KeyEvent.KEYCODE_F13; i++) {
            assertEquals("F" + (13 + i) + " translated wrongly",
                    expected(VK_F13 + i), translate(KeyEvent.KEYCODE_F13 + i));
        }
        // F12 and F13 must be adjacent on the Windows side even though they are far
        // apart on the Android side (142 vs 326).
        assertEquals(expected(VK_F12), translate(KeyEvent.KEYCODE_F12));
        assertEquals(expected(VK_F12 + 1), translate(KeyEvent.KEYCODE_F13));
    }

    @Test
    public void existingF1ThroughF12_stillTranslate() {
        // The new branch sits directly below the F1-F12 branch; make sure it did not
        // capture keycodes that belong to the older range.
        assertEquals(expected(VK_F1), translate(KeyEvent.KEYCODE_F1));
        assertEquals(expected(VK_F12), translate(KeyEvent.KEYCODE_F12));
    }

    @Test
    public void printKey_mapsToVkPrint() {
        assertEquals(expected(VK_PRINT), translate(KeyEvent.KEYCODE_PRINT));
    }

    @Test
    public void screenshotKey_mapsToVkSnapshot() {
        assertEquals(expected(VK_SNAPSHOT), translate(KeyEvent.KEYCODE_SCREENSHOT));
    }

    @Test
    public void printAndScreenshot_areNotSwallowedByTheF13Range() {
        // KEYCODE_PRINT (323) and KEYCODE_SCREENSHOT (318) sit just below KEYCODE_F13
        // (326). If the new range branch ever widened downwards it would shadow both
        // switch cases and this would start returning function keys.
        assertEquals(expected(VK_PRINT), translate(KeyEvent.KEYCODE_PRINT));
        assertEquals(expected(VK_SNAPSHOT), translate(KeyEvent.KEYCODE_SCREENSHOT));
    }

    @Test
    public void plusAndEquals_shareTheSingleUsOemPlusKey() {
        // Why b9c5eddd is needed: both Android keycodes collapse onto VK_OEM_PLUS, so
        // the plus key is indistinguishable from equals at this layer and Game.java has
        // to add the Shift modifier itself.
        assertEquals(expected(VK_OEM_PLUS), translate(KeyEvent.KEYCODE_PLUS));
        assertEquals(expected(VK_OEM_PLUS), translate(KeyEvent.KEYCODE_EQUALS));
    }
}
