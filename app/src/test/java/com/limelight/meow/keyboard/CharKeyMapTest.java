package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class CharKeyMapTest {

    private static void assertKey(int codePoint, int keyCode, boolean shift) {
        int r = CharKeyMap.lookup(codePoint);
        assertEquals(new String(Character.toChars(codePoint)), keyCode, CharKeyMap.keyCode(r));
        assertEquals(new String(Character.toChars(codePoint)) + " shift", shift, CharKeyMap.needsShift(r));
    }

    @Test
    public void latin() {
        assertKey('a', KeyEvent.KEYCODE_A, false);
        assertKey('z', KeyEvent.KEYCODE_Z, false);
        assertKey('Q', KeyEvent.KEYCODE_Q, true);
    }

    @Test
    public void digitsAndTheirShiftedSymbols() {
        assertKey('0', KeyEvent.KEYCODE_0, false);
        assertKey('7', KeyEvent.KEYCODE_7, false);
        assertKey('!', KeyEvent.KEYCODE_1, true);
        assertKey('(', KeyEvent.KEYCODE_9, true);
        assertKey(')', KeyEvent.KEYCODE_0, true);
    }

    @Test
    public void punctuation() {
        assertKey('/', KeyEvent.KEYCODE_SLASH, false);
        assertKey('?', KeyEvent.KEYCODE_SLASH, true);
        assertKey('~', KeyEvent.KEYCODE_GRAVE, true);
        assertKey('"', KeyEvent.KEYCODE_APOSTROPHE, true);
        assertKey('|', KeyEvent.KEYCODE_BACKSLASH, true);
        assertKey(' ', KeyEvent.KEYCODE_SPACE, false);
        assertKey('\n', KeyEvent.KEYCODE_ENTER, false);
    }

    @Test
    public void russianLettersMapToTheKeyTheySitOn() {
        assertKey('й', KeyEvent.KEYCODE_Q, false);
        assertKey('с', KeyEvent.KEYCODE_C, false);
        assertKey('м', KeyEvent.KEYCODE_V, false);
        assertKey('ч', KeyEvent.KEYCODE_X, false);
        assertKey('я', KeyEvent.KEYCODE_Z, false);
        assertKey('ф', KeyEvent.KEYCODE_A, false);
        assertKey('х', KeyEvent.KEYCODE_LEFT_BRACKET, false);
        assertKey('ж', KeyEvent.KEYCODE_SEMICOLON, false);
        assertKey('ю', KeyEvent.KEYCODE_PERIOD, false);
        assertKey('ё', KeyEvent.KEYCODE_GRAVE, false);
        assertKey('С', KeyEvent.KEYCODE_C, true);
        assertKey('Ё', KeyEvent.KEYCODE_GRAVE, true);
    }

    @Test
    public void everyRussianLetterIsMapped() {
        for (char c = 'а'; c <= 'я'; c++) {
            assertTrue("lower " + c, CharKeyMap.lookup(c) != 0);
            assertTrue("upper " + c, CharKeyMap.lookup(Character.toUpperCase(c)) != 0);
        }
    }

    @Test
    public void unmappedIsZero() {
        assertEquals(0, CharKeyMap.lookup(0x1F600));
        assertEquals(0, CharKeyMap.lookup('é'));
        assertEquals(0, CharKeyMap.lookup(-1));
        assertFalse(CharKeyMap.needsShift(0));
    }
}
