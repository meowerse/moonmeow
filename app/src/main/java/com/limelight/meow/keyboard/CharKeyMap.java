package com.limelight.meow.keyboard;

import android.view.KeyEvent;

/**
 * Which physical key, on a US PC keyboard, types a character, and whether it needs Shift.
 *
 * <p>Used only when the user has a PC-keyboard modifier active and then types with the system
 * keyboard: Ctrl latched and "c" typed must reach the host as Ctrl+C, not as a Ctrl press
 * followed by the text "c". Shortcuts are defined by key position, so Cyrillic letters map to
 * the key they sit on in the standard Russian (ЙЦУКЕН) layout: Ctrl+с is Ctrl+C on the host,
 * which is what a Russian user pressing Ctrl and that key on a real keyboard sends.
 *
 * <p>Two flat tables, built once; a lookup is an array read.
 */
public final class CharKeyMap {

    /** Set in a lookup result when the character needs Shift. */
    public static final int SHIFT = 0x10000;
    private static final int KEY_MASK = 0xFFFF;

    private static final int[] ASCII = new int[128];
    private static final int CYRILLIC_BASE = 0x400;
    private static final int[] CYRILLIC = new int[0x60];

    static {
        for (int c = 'a'; c <= 'z'; c++) {
            ASCII[c] = KeyEvent.KEYCODE_A + (c - 'a');
            ASCII[c - 'a' + 'A'] = (KeyEvent.KEYCODE_A + (c - 'a')) | SHIFT;
        }
        for (int c = '0'; c <= '9'; c++) {
            ASCII[c] = KeyEvent.KEYCODE_0 + (c - '0');
        }
        String shiftedDigits = ")!@#$%^&*(";
        for (int i = 0; i < shiftedDigits.length(); i++) {
            ASCII[shiftedDigits.charAt(i)] = (KeyEvent.KEYCODE_0 + i) | SHIFT;
        }
        pair(' ', ' ', KeyEvent.KEYCODE_SPACE);
        pair('`', '~', KeyEvent.KEYCODE_GRAVE);
        pair('-', '_', KeyEvent.KEYCODE_MINUS);
        pair('=', '+', KeyEvent.KEYCODE_EQUALS);
        pair('[', '{', KeyEvent.KEYCODE_LEFT_BRACKET);
        pair(']', '}', KeyEvent.KEYCODE_RIGHT_BRACKET);
        pair('\\', '|', KeyEvent.KEYCODE_BACKSLASH);
        pair(';', ':', KeyEvent.KEYCODE_SEMICOLON);
        pair('\'', '"', KeyEvent.KEYCODE_APOSTROPHE);
        pair(',', '<', KeyEvent.KEYCODE_COMMA);
        pair('.', '>', KeyEvent.KEYCODE_PERIOD);
        pair('/', '?', KeyEvent.KEYCODE_SLASH);
        ASCII['\n'] = KeyEvent.KEYCODE_ENTER;
        ASCII['\t'] = KeyEvent.KEYCODE_TAB;

        // ЙЦУКЕН, lower case, in the order of the US keys they share a position with.
        String russian = "йцукенгшщзхъфывапролджэячсмитьбюё";
        int[] keys = {
                KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
                KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I,
                KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_LEFT_BRACKET,
                KeyEvent.KEYCODE_RIGHT_BRACKET,
                KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
                KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K,
                KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE,
                KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
                KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M,
                KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_GRAVE,
        };
        for (int i = 0; i < russian.length(); i++) {
            char lower = russian.charAt(i);
            char upper = Character.toUpperCase(lower);
            CYRILLIC[lower - CYRILLIC_BASE] = keys[i];
            CYRILLIC[upper - CYRILLIC_BASE] = keys[i] | SHIFT;
        }
    }

    private static void pair(char plain, char shifted, int keyCode) {
        ASCII[plain] = keyCode;
        if (shifted != plain) {
            ASCII[shifted] = keyCode | SHIFT;
        }
    }

    private CharKeyMap() {
    }

    /**
     * @return the Android key code, with {@link #SHIFT} or'd in when needed, or 0 when no key
     *         of a US keyboard types this code point
     */
    public static int lookup(int codePoint) {
        if (codePoint >= 0 && codePoint < ASCII.length) {
            return ASCII[codePoint];
        }
        int index = codePoint - CYRILLIC_BASE;
        if (index >= 0 && index < CYRILLIC.length) {
            return CYRILLIC[index];
        }
        return 0;
    }

    public static int keyCode(int lookupResult) {
        return lookupResult & KEY_MASK;
    }

    public static boolean needsShift(int lookupResult) {
        return (lookupResult & SHIFT) != 0;
    }
}
