package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

/**
 * Every key on every layer reaches the host as a real key: its Android code goes through the
 * app's own {@link KeyboardTranslator} (the path a physical keyboard takes) and comes out as a
 * non-zero host code. And the geometry the layouts promise holds.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PcKeyboardLayoutTest {

    private final KeyboardTranslator translator = new KeyboardTranslator(new PreferenceConfiguration());

    private short host(int androidKeyCode) {
        return translator.translate(androidKeyCode, 0, -1);
    }

    @Test
    public void everyKeyOnEveryLayerTranslatesToAHostKey() {
        for (PcKeyboardLayout layout : PcKeyboardLayout.all()) {
            for (PcKey[] row : layout.rows) {
                for (PcKey key : row) {
                    assertMapped(layout, key);
                }
            }
        }
        for (PcKey chip : PcKeyboardLayout.TOOLBAR_CHIPS) {
            assertMapped(PcKeyboardLayout.PORTRAIT_MAIN, chip);
        }
    }

    private void assertMapped(PcKeyboardLayout layout, PcKey key) {
        String where = layout.name + " / " + key.label;
        assertNotNull(where + " has a description", key.description);
        assertTrue(where + " has a description", key.description.length() > 0);
        switch (key.kind) {
            case PcKey.KIND_KEY:
                assertNotEquals(where + " maps to a host key", 0, host(key.code));
                break;
            case PcKey.KIND_CHORD:
                assertTrue(where + " is a chord of at least two keys", key.chord.length >= 2);
                for (int code : key.chord) {
                    assertNotEquals(where + " chord part maps", 0, host(code));
                }
                break;
            case PcKey.KIND_MODIFIER:
                if (key.code != ModifierLatch.MOD_FN) {
                    assertNotEquals(where + " modifier maps", 0,
                            host(PcKeyboardEngine.hostKeyFor(key.code)));
                }
                break;
            case PcKey.KIND_VIRTUAL_KEY:
                assertTrue(where + " is a media virtual key", key.code >= 0xAD && key.code <= 0xB3);
                break;
            case PcKey.KIND_ACTION:
                assertTrue(where, key.code >= PcKey.ACTION_HIDE && key.code <= PcKey.ACTION_PC_KEYBOARD);
                break;
            default:
                throw new AssertionError(where + " has an unknown kind");
        }
    }

    @Test
    public void specificHostCodes() {
        // Spot checks of the table, against Windows VK codes (with the 0x80 prefix byte).
        assertEquals(0x8000 | 0x1B, host(PcKeyboardLayout.ESC.code) & 0xFFFF);
        assertEquals(0x8000 | 0x2E, host(PcKeyboardLayout.DELETE.code) & 0xFFFF);
        assertEquals(0x8000 | 0x5B, host(PcKeyboardEngine.hostKeyFor(ModifierLatch.MOD_SUPER)) & 0xFFFF);
        assertEquals(0x8000 | 0x9A, host(PcKeyboardLayout.PRTSC.code) & 0xFFFF);
    }

    @Test
    public void rowsAreFullWidth() {
        assertRows(PcKeyboardLayout.PORTRAIT_MAIN, 11f);
        assertRows(PcKeyboardLayout.PORTRAIT_FN, 11f);
        assertRows(PcKeyboardLayout.LANDSCAPE_MAIN, 15f);
        assertRows(PcKeyboardLayout.LANDSCAPE_FN, 15f);
    }

    private static void assertRows(PcKeyboardLayout layout, float units) {
        assertEquals(layout.name, units, layout.units, 1e-4);
        for (int r = 0; r < layout.rows.length; r++) {
            float sum = 0;
            for (PcKey key : layout.rows[r]) {
                sum += key.width;
            }
            assertEquals(layout.name + " row " + r, units, sum, 1e-4);
        }
    }

    @Test
    public void bothLayersOfAnOrientationShareTheirShapeAndBottomRow() {
        assertEquals(PcKeyboardLayout.PORTRAIT_MAIN.rows.length, PcKeyboardLayout.PORTRAIT_FN.rows.length);
        assertEquals(PcKeyboardLayout.LANDSCAPE_MAIN.rows.length, PcKeyboardLayout.LANDSCAPE_FN.rows.length);
        assertSameRow(PcKeyboardLayout.PORTRAIT_MAIN, PcKeyboardLayout.PORTRAIT_FN, 5);
        assertSameRow(PcKeyboardLayout.LANDSCAPE_MAIN, PcKeyboardLayout.LANDSCAPE_FN, 4);
    }

    private static void assertSameRow(PcKeyboardLayout a, PcKeyboardLayout b, int row) {
        assertEquals(a.rows[row].length, b.rows[row].length);
        for (int i = 0; i < a.rows[row].length; i++) {
            assertEquals(a.rows[row][i].label, b.rows[row][i].label);
            assertEquals(a.rows[row][i].width, b.rows[row][i].width, 0f);
        }
    }

    @Test
    public void upSitsAboveDownInPortraitOnBothLayers() {
        for (PcKeyboardLayout layout : new PcKeyboardLayout[]{PcKeyboardLayout.PORTRAIT_MAIN, PcKeyboardLayout.PORTRAIT_FN}) {
            float up = startOf(layout, 4, KeyEvent.KEYCODE_DPAD_UP);
            float down = startOf(layout, 5, KeyEvent.KEYCODE_DPAD_DOWN);
            assertEquals(layout.name, down, up, 1e-4);
        }
    }

    private static float startOf(PcKeyboardLayout layout, int row, int code) {
        float x = 0;
        for (PcKey key : layout.rows[row]) {
            if (key.kind == PcKey.KIND_KEY && key.code == code) {
                return x;
            }
            x += key.width;
        }
        throw new AssertionError(layout.name + " row " + row + " has no " + code);
    }

    @Test
    public void everyFullLayoutHasEveryModifierAndAWayOut() {
        for (PcKeyboardLayout layout : new PcKeyboardLayout[]{PcKeyboardLayout.PORTRAIT_MAIN,
                PcKeyboardLayout.PORTRAIT_FN, PcKeyboardLayout.LANDSCAPE_MAIN, PcKeyboardLayout.LANDSCAPE_FN}) {
            Set<Integer> mods = new HashSet<>();
            for (PcKey[] row : layout.rows) {
                for (PcKey key : row) {
                    if (key.isModifier()) {
                        mods.add(key.code);
                    }
                }
            }
            assertEquals(layout.name, ModifierLatch.COUNT, mods.size());
        }
        // Portrait hides from its toolbar, on both layers; landscape has none, so Fn carries it.
        assertEquals(PcKey.ACTION_HIDE, PcKeyboardLayout.TOOLBAR_HIDE.code);
        assertTrue(contains(PcKeyboardLayout.PORTRAIT_FN, PcKey.KIND_KEY, PcKeyboardLayout.SUPER_TAP.code));
        assertTrue(contains(PcKeyboardLayout.LANDSCAPE_FN, PcKey.KIND_KEY, PcKeyboardLayout.SUPER_TAP.code));
        assertTrue(contains(PcKeyboardLayout.LANDSCAPE_FN, PcKey.KIND_ACTION, PcKey.ACTION_HIDE));
        assertTrue(contains(PcKeyboardLayout.IME_STRIP, PcKey.KIND_ACTION, PcKey.ACTION_PC_KEYBOARD));
    }

    @Test
    public void everyAnsiKeyIsOnTheMainLayerInBothOrientations() {
        int[] required = {
                KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH,
                KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_COMMA,
                KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
        };
        for (PcKeyboardLayout layout : new PcKeyboardLayout[]{PcKeyboardLayout.PORTRAIT_MAIN, PcKeyboardLayout.LANDSCAPE_MAIN}) {
            for (int code : required) {
                assertTrue(layout.name + " has " + code, contains(layout, PcKey.KIND_KEY, code));
            }
            for (int code = KeyEvent.KEYCODE_A; code <= KeyEvent.KEYCODE_Z; code++) {
                assertTrue(layout.name + " has letter " + code, contains(layout, PcKey.KIND_KEY, code));
            }
            for (int code = KeyEvent.KEYCODE_0; code <= KeyEvent.KEYCODE_9; code++) {
                assertTrue(layout.name + " has digit " + code, contains(layout, PcKey.KIND_KEY, code));
            }
        }
    }

    @Test
    public void everyFunctionKeyIsOnTheFnLayerInBothOrientations() {
        for (PcKeyboardLayout layout : new PcKeyboardLayout[]{PcKeyboardLayout.PORTRAIT_FN, PcKeyboardLayout.LANDSCAPE_FN}) {
            for (int code = KeyEvent.KEYCODE_F1; code <= KeyEvent.KEYCODE_F12; code++) {
                assertTrue(layout.name + " has F" + (code - KeyEvent.KEYCODE_F1 + 1),
                        contains(layout, PcKey.KIND_KEY, code));
            }
            int[] nav = {KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL,
                    KeyEvent.KEYCODE_SYSRQ, KeyEvent.KEYCODE_BREAK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPS_LOCK};
            for (int code : nav) {
                assertTrue(layout.name + " has " + code, contains(layout, PcKey.KIND_KEY, code));
            }
        }
    }

    private static boolean contains(PcKeyboardLayout layout, int kind, int code) {
        for (PcKey[] row : layout.rows) {
            for (PcKey key : row) {
                if (key.kind == kind && key.code == code) {
                    return true;
                }
            }
        }
        return false;
    }
}
