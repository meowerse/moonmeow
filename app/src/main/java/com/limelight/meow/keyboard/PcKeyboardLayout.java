package com.limelight.meow.keyboard;

import android.view.KeyEvent;

/**
 * The PC keyboard's key maps: a main and an Fn layer for each orientation, the one-row strip
 * shown above the system keyboard, and the shortcut chips of the portrait toolbar.
 *
 * <h2>Why two geometries</h2>
 * Landscape has the width for the real ANSI layout (15 units: every key where a PC user's
 * fingers expect it, both Shifts, a wide Enter). Portrait does not: 15 units across a 392 dp
 * phone is 26 dp a key, which is below any touch-target guidance. Portrait therefore uses 11
 * units (36 dp keys, Gboard's width) on six rows, and moves the eleven punctuation keys to a
 * row of their own instead of hiding them on another layer, because a coder needs them.
 *
 * <p>Both layers of an orientation share their bottom row and their arrow and Shift positions,
 * so switching to Fn never moves a key the thumb is already on: hold Ctrl, tap Fn, tap F5.
 *
 * <p>Built once, at class load; every instance is immutable and shared.
 */
public final class PcKeyboardLayout {

    public final String name;
    public final PcKey[][] rows;
    /** The widest row, in key units: the unit width is the keyboard width divided by this. */
    public final float units;

    private PcKeyboardLayout(String name, PcKey[]... rows) {
        this.name = name;
        this.rows = rows;
        float widest = 0;
        for (PcKey[] row : rows) {
            float sum = 0;
            for (PcKey key : row) {
                sum += key.width;
            }
            widest = Math.max(widest, sum);
        }
        this.units = widest;
    }

    public int keyCount() {
        int count = 0;
        for (PcKey[] row : rows) {
            count += row.length;
        }
        return count;
    }

    // ---- virtual keys with no Android key code (Windows VK_*; Sunshine maps them per OS) ---

    static final short VK_VOLUME_MUTE = 0xAD;
    static final short VK_VOLUME_DOWN = 0xAE;
    static final short VK_VOLUME_UP = 0xAF;
    static final short VK_MEDIA_NEXT = 0xB0;
    static final short VK_MEDIA_PREV = 0xB1;
    static final short VK_MEDIA_PLAY_PAUSE = 0xB3;

    // ---- key factories --------------------------------------------------------------------

    private static PcKey ch(int code, String label, String shiftLabel, String description) {
        return new PcKey(PcKey.KIND_KEY, code, null, label, shiftLabel, description, 1f,
                PcKey.STYLE_CHAR, true, PcKey.ICON_NONE);
    }

    private static PcKey letter(char c) {
        return ch(KeyEvent.KEYCODE_A + (c - 'a'), String.valueOf(c), null, String.valueOf(c));
    }

    private static PcKey special(int code, String label, String description, float width, int icon) {
        return new PcKey(PcKey.KIND_KEY, code, null, label, null, description, width,
                PcKey.STYLE_SPECIAL, true, icon);
    }

    private static PcKey special(int code, String label, float width) {
        return special(code, label, label, width, PcKey.ICON_NONE);
    }

    private static PcKey modifier(int mod, String label, String description, float width, int icon) {
        return new PcKey(PcKey.KIND_MODIFIER, mod, null, label, null, description, width,
                PcKey.STYLE_SPECIAL, false, icon);
    }

    private static PcKey chip(String label, String description, float width, int... chord) {
        return new PcKey(PcKey.KIND_CHORD, 0, chord, label, null, description, width,
                PcKey.STYLE_CHIP, false, PcKey.ICON_NONE);
    }

    private static PcKey media(short vk, String label, String description) {
        return new PcKey(PcKey.KIND_VIRTUAL_KEY, vk, null, label, null, description, 1f,
                PcKey.STYLE_SPECIAL, false, PcKey.ICON_NONE);
    }

    private static PcKey action(int action, String label, String description, float width, int icon) {
        return new PcKey(PcKey.KIND_ACTION, action, null, label, null, description, width,
                PcKey.STYLE_SPECIAL, false, icon);
    }

    // ---- the keys ---------------------------------------------------------------------------

    private static final int CTRL = KeyEvent.KEYCODE_CTRL_LEFT;
    private static final int SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT;
    private static final int ALT = KeyEvent.KEYCODE_ALT_LEFT;
    private static final int SUPER = KeyEvent.KEYCODE_META_LEFT;

    static final PcKey GRAVE = ch(KeyEvent.KEYCODE_GRAVE, "`", "~", "Backtick");
    static final PcKey MINUS = ch(KeyEvent.KEYCODE_MINUS, "-", "_", "Minus");
    static final PcKey EQUALS = ch(KeyEvent.KEYCODE_EQUALS, "=", "+", "Equals");
    static final PcKey LBRACKET = ch(KeyEvent.KEYCODE_LEFT_BRACKET, "[", "{", "Left bracket");
    static final PcKey RBRACKET = ch(KeyEvent.KEYCODE_RIGHT_BRACKET, "]", "}", "Right bracket");
    static final PcKey BACKSLASH = ch(KeyEvent.KEYCODE_BACKSLASH, "\\", "|", "Backslash");
    static final PcKey SEMICOLON = ch(KeyEvent.KEYCODE_SEMICOLON, ";", ":", "Semicolon");
    static final PcKey APOSTROPHE = ch(KeyEvent.KEYCODE_APOSTROPHE, "'", "\"", "Apostrophe");
    static final PcKey COMMA = ch(KeyEvent.KEYCODE_COMMA, ",", "<", "Comma");
    static final PcKey PERIOD = ch(KeyEvent.KEYCODE_PERIOD, ".", ">", "Period");
    static final PcKey SLASH = ch(KeyEvent.KEYCODE_SLASH, "/", "?", "Slash");

    private static final String DIGIT_SHIFTS = ")!@#$%^&*(";

    private static PcKey digit(int d) {
        return ch(KeyEvent.KEYCODE_0 + d, String.valueOf(d),
                String.valueOf(DIGIT_SHIFTS.charAt(d)), String.valueOf(d));
    }

    static final PcKey ESC = special(KeyEvent.KEYCODE_ESCAPE, "Esc", "Escape", 1f, PcKey.ICON_NONE);
    static final PcKey TAB = special(KeyEvent.KEYCODE_TAB, "Tab", 1f);
    static final PcKey CAPS = special(KeyEvent.KEYCODE_CAPS_LOCK, "Caps", "Caps Lock", 1f, PcKey.ICON_NONE);
    static final PcKey ENTER = new PcKey(PcKey.KIND_KEY, KeyEvent.KEYCODE_ENTER, null, "Enter",
            null, "Enter", 1f, PcKey.STYLE_ACCENT, true, PcKey.ICON_ENTER);
    static final PcKey BACKSPACE = special(KeyEvent.KEYCODE_DEL, "Bksp", "Backspace", 1f, PcKey.ICON_BACKSPACE);
    static final PcKey SPACE = new PcKey(PcKey.KIND_KEY, KeyEvent.KEYCODE_SPACE, null, "Space",
            null, "Space", 1f, PcKey.STYLE_CHAR, true, PcKey.ICON_SPACE);
    static final PcKey LEFT = special(KeyEvent.KEYCODE_DPAD_LEFT, "Left", "Left arrow", 1f, PcKey.ICON_LEFT);
    static final PcKey UP = special(KeyEvent.KEYCODE_DPAD_UP, "Up", "Up arrow", 1f, PcKey.ICON_UP);
    static final PcKey DOWN = special(KeyEvent.KEYCODE_DPAD_DOWN, "Down", "Down arrow", 1f, PcKey.ICON_DOWN);
    static final PcKey RIGHT = special(KeyEvent.KEYCODE_DPAD_RIGHT, "Right", "Right arrow", 1f, PcKey.ICON_RIGHT);
    static final PcKey HOME = special(KeyEvent.KEYCODE_MOVE_HOME, "Home", 1f);
    static final PcKey END = special(KeyEvent.KEYCODE_MOVE_END, "End", 1f);
    static final PcKey PGUP = special(KeyEvent.KEYCODE_PAGE_UP, "PgUp", "Page Up", 1f, PcKey.ICON_NONE);
    static final PcKey PGDN = special(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn", "Page Down", 1f, PcKey.ICON_NONE);
    static final PcKey INSERT = special(KeyEvent.KEYCODE_INSERT, "Ins", "Insert", 1f, PcKey.ICON_NONE);
    static final PcKey DELETE = special(KeyEvent.KEYCODE_FORWARD_DEL, "Del", "Delete", 1f, PcKey.ICON_NONE);
    static final PcKey PRTSC = special(KeyEvent.KEYCODE_SYSRQ, "PrtSc", "Print Screen", 1f, PcKey.ICON_NONE);
    static final PcKey SCRLK = special(KeyEvent.KEYCODE_SCROLL_LOCK, "ScrLk", "Scroll Lock", 1f, PcKey.ICON_NONE);
    static final PcKey PAUSE = special(KeyEvent.KEYCODE_BREAK, "Pause", "Pause", 1f, PcKey.ICON_NONE);
    static final PcKey MENU = special(KeyEvent.KEYCODE_MENU, "Menu", "Context menu", 1f, PcKey.ICON_NONE);

    private static PcKey f(int n) {
        return special(KeyEvent.KEYCODE_F1 + n - 1, "F" + n, 1f);
    }

    static final PcKey MOD_SHIFT = modifier(ModifierLatch.MOD_SHIFT, "Shift", "Shift", 1f, PcKey.ICON_SHIFT);
    static final PcKey MOD_CTRL = modifier(ModifierLatch.MOD_CTRL, "Ctrl", "Control", 1f, PcKey.ICON_NONE);
    static final PcKey MOD_ALT = modifier(ModifierLatch.MOD_ALT, "Alt", "Alt", 1f, PcKey.ICON_NONE);
    static final PcKey MOD_SUPER = modifier(ModifierLatch.MOD_SUPER, "Super", "Super, the Windows key", 1f, PcKey.ICON_NONE);
    static final PcKey MOD_FN = modifier(ModifierLatch.MOD_FN, "Fn", "Function keys layer", 1f, PcKey.ICON_NONE);

    static final PcKey MUTE = media(VK_VOLUME_MUTE, "Mute", "Mute");
    static final PcKey VOL_DOWN = media(VK_VOLUME_DOWN, "Vol−", "Volume down");
    static final PcKey VOL_UP = media(VK_VOLUME_UP, "Vol+", "Volume up");
    static final PcKey PREV = media(VK_MEDIA_PREV, "Prev", "Previous track");
    static final PcKey PLAY = media(VK_MEDIA_PLAY_PAUSE, "Play", "Play or pause");
    static final PcKey NEXT = media(VK_MEDIA_NEXT, "Next", "Next track");

    static final PcKey HIDE = action(PcKey.ACTION_HIDE, "Hide", "Hide PC keyboard", 1f, PcKey.ICON_HIDE);
    static final PcKey SYSTEM_KEYBOARD = action(PcKey.ACTION_SYSTEM_KEYBOARD, "ABC",
            "Switch to the system keyboard", 1f, PcKey.ICON_KEYBOARD);
    static final PcKey PC_KEYBOARD = action(PcKey.ACTION_PC_KEYBOARD, "PC",
            "Open the full PC keyboard", 1f, PcKey.ICON_KEYBOARD);

    // Shortcuts. Chosen for a KDE/GNOME or Windows desktop; on a macOS host Ctrl is still Ctrl,
    // which is why the chips are labelled with the keys they press and not with "Copy".
    static final PcKey COPY = chip("Ctrl+C", "Control C, copy", 1f, CTRL, KeyEvent.KEYCODE_C);
    static final PcKey PASTE = chip("Ctrl+V", "Control V, paste", 1f, CTRL, KeyEvent.KEYCODE_V);
    static final PcKey CUT = chip("Ctrl+X", "Control X, cut", 1f, CTRL, KeyEvent.KEYCODE_X);
    static final PcKey UNDO = chip("Ctrl+Z", "Control Z, undo", 1f, CTRL, KeyEvent.KEYCODE_Z);
    static final PcKey REDO = chip("Ctrl+Shift+Z", "Control Shift Z, redo", 1f, CTRL, SHIFT, KeyEvent.KEYCODE_Z);
    static final PcKey SELECT_ALL = chip("Ctrl+A", "Control A, select all", 1f, CTRL, KeyEvent.KEYCODE_A);
    static final PcKey SAVE = chip("Ctrl+S", "Control S, save", 1f, CTRL, KeyEvent.KEYCODE_S);
    static final PcKey FIND = chip("Ctrl+F", "Control F, find", 1f, CTRL, KeyEvent.KEYCODE_F);
    static final PcKey TERM_COPY = chip("Ctrl+Shift+C", "Control Shift C, copy in a terminal", 1f, CTRL, SHIFT, KeyEvent.KEYCODE_C);
    static final PcKey TERM_PASTE = chip("Ctrl+Shift+V", "Control Shift V, paste in a terminal", 1f, CTRL, SHIFT, KeyEvent.KEYCODE_V);
    static final PcKey ALT_TAB = chip("Alt+Tab", "Alt Tab, switch window", 1f, ALT, KeyEvent.KEYCODE_TAB);
    static final PcKey ALT_F4 = chip("Alt+F4", "Alt F4, close window", 1f, ALT, KeyEvent.KEYCODE_F4);
    static final PcKey CTRL_ALT_DEL = chip("Ctrl+Alt+Del", "Control Alt Delete", 1f, CTRL, ALT, KeyEvent.KEYCODE_FORWARD_DEL);
    static final PcKey CTRL_SHIFT_ESC = chip("Ctrl+Shift+Esc", "Control Shift Escape, task manager", 1f, CTRL, SHIFT, KeyEvent.KEYCODE_ESCAPE);

    private static PcKey[] row(Object... items) {
        // Each item is a key, optionally followed by a Float width overriding the key's own.
        int count = 0;
        for (Object o : items) {
            if (o instanceof PcKey) {
                count++;
            }
        }
        PcKey[] keys = new PcKey[count];
        int i = -1;
        for (Object o : items) {
            if (o instanceof PcKey) {
                keys[++i] = (PcKey) o;
            } else {
                keys[i] = keys[i].withWidth((Float) o);
            }
        }
        return keys;
    }

    private static PcKey[] letters(String s) {
        PcKey[] keys = new PcKey[s.length()];
        for (int i = 0; i < s.length(); i++) {
            keys[i] = letter(s.charAt(i));
        }
        return keys;
    }

    private static PcKey[] concat(PcKey[]... parts) {
        int n = 0;
        for (PcKey[] p : parts) {
            n += p.length;
        }
        PcKey[] out = new PcKey[n];
        int i = 0;
        for (PcKey[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    private static PcKey[] digits() {
        PcKey[] keys = new PcKey[10];
        for (int i = 0; i < 10; i++) {
            keys[i] = digit((i + 1) % 10);
        }
        return keys;
    }

    // ---- portrait: 11 units -----------------------------------------------------------------

    private static PcKey[] portraitBottomRow() {
        return row(MOD_CTRL, 1.25f, MOD_FN, 1.25f, MOD_SUPER, 1f, MOD_ALT, 1.25f, SPACE, 3.25f,
                LEFT, DOWN, RIGHT);
    }

    public static final PcKeyboardLayout PORTRAIT_MAIN = new PcKeyboardLayout("portrait-main",
            row(ESC, GRAVE, MINUS, EQUALS, LBRACKET, RBRACKET, BACKSLASH, SEMICOLON, APOSTROPHE, COMMA, PERIOD),
            concat(row(TAB), digits()),
            concat(letters("qwertyuiop"), row(BACKSPACE)),
            concat(letters("asdfghjkl"), row(ENTER, 2f)),
            concat(row(MOD_SHIFT, 2f), letters("zxcvbnm"), row(UP, SLASH)),
            portraitBottomRow());

    public static final PcKeyboardLayout PORTRAIT_FN = new PcKeyboardLayout("portrait-fn",
            row(ESC, f(1), f(2), f(3), f(4), f(5), f(6), f(7), f(8), f(9), f(10)),
            row(f(11), f(12), PRTSC, SCRLK, PAUSE, INSERT, DELETE, HOME, END, PGUP, PGDN),
            row(CAPS, MENU, MUTE, VOL_DOWN, VOL_UP, PREV, PLAY, NEXT, SYSTEM_KEYBOARD, 1.5f, HIDE, 1.5f),
            row(CTRL_ALT_DEL, 3f, CTRL_SHIFT_ESC, 3f, ALT_F4, 2.5f, ALT_TAB, 2.5f),
            row(MOD_SHIFT, 2f, TERM_COPY, 2.5f, TERM_PASTE, 2.5f, REDO, 2f, UP, DELETE),
            portraitBottomRow());

    // ---- landscape: 15 units, ANSI ----------------------------------------------------------

    private static PcKey[] landscapeBottomRow() {
        return row(MOD_CTRL, 1.5f, MOD_FN, 1.25f, MOD_SUPER, 1.25f, MOD_ALT, 1.25f, SPACE, 4.5f,
                ESC, 1.25f, LEFT, UP, DOWN, RIGHT);
    }

    public static final PcKeyboardLayout LANDSCAPE_MAIN = new PcKeyboardLayout("landscape-main",
            concat(row(GRAVE), digits(), row(MINUS, EQUALS, BACKSPACE, 2f)),
            concat(row(TAB, 1.5f), letters("qwertyuiop"), row(LBRACKET, RBRACKET, BACKSLASH, 1.5f)),
            concat(row(CAPS, 1.75f), letters("asdfghjkl"), row(SEMICOLON, APOSTROPHE, ENTER, 2.25f)),
            concat(row(MOD_SHIFT, 2.25f), letters("zxcvbnm"), row(COMMA, PERIOD, SLASH, MOD_SHIFT, 2.75f)),
            landscapeBottomRow());

    public static final PcKeyboardLayout LANDSCAPE_FN = new PcKeyboardLayout("landscape-fn",
            row(ESC, f(1), f(2), f(3), f(4), f(5), f(6), f(7), f(8), f(9), f(10), f(11), f(12), INSERT, DELETE),
            row(TAB, 1.5f, HOME, END, PGUP, PGDN, PRTSC, PAUSE, MENU, CAPS, MUTE, VOL_DOWN, VOL_UP, PLAY,
                    SYSTEM_KEYBOARD, 1.5f),
            row(COPY, 2.5f, PASTE, 2.5f, CUT, 2.5f, UNDO, 2.5f, REDO, 2.5f, SELECT_ALL, 2.5f),
            row(MOD_SHIFT, 2.25f, TERM_COPY, 2.5f, TERM_PASTE, 2.5f, ALT_TAB, 2.25f, ALT_F4, 2f,
                    CTRL_ALT_DEL, 2.25f, HIDE, 1.25f),
            landscapeBottomRow());

    // ---- the strip above the system keyboard ------------------------------------------------

    /**
     * Termux's idea: the keys a phone keyboard lacks, one row, above whatever keyboard the
     * user types with. The modifiers here latch exactly like the full keyboard's and apply to
     * the next key or character from the system keyboard.
     */
    public static final PcKeyboardLayout IME_STRIP = new PcKeyboardLayout("ime-strip",
            row(ESC, TAB, MOD_CTRL, MOD_ALT, MOD_SUPER, LEFT, UP, DOWN, RIGHT, PC_KEYBOARD, 1.25f));

    /** The portrait toolbar's shortcut chips, in priority order: as many as fit are shown. */
    public static final PcKey[] TOOLBAR_CHIPS = {COPY, PASTE, UNDO, CUT, SELECT_ALL, SAVE, FIND, REDO};

    /** The toolbar's fixed ends. */
    public static final PcKey TOOLBAR_SYSTEM_KEYBOARD = SYSTEM_KEYBOARD;
    public static final PcKey TOOLBAR_HIDE = HIDE;

    public static PcKeyboardLayout main(boolean landscape) {
        return landscape ? LANDSCAPE_MAIN : PORTRAIT_MAIN;
    }

    public static PcKeyboardLayout fn(boolean landscape) {
        return landscape ? LANDSCAPE_FN : PORTRAIT_FN;
    }

    /** Every layout, for tests and the accessibility tree. */
    public static PcKeyboardLayout[] all() {
        return new PcKeyboardLayout[]{PORTRAIT_MAIN, PORTRAIT_FN, LANDSCAPE_MAIN, LANDSCAPE_FN, IME_STRIP};
    }
}
