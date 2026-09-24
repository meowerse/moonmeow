package com.limelight.meow.keyboard;

/**
 * One key of the on-screen PC keyboard. Immutable; every layout is built once and shared, so
 * nothing about a key is allocated while the keyboard is in use.
 *
 * <p>No Android dependency beyond the {@code KeyEvent} constants the layouts pass in, which
 * are compile-time ints: the whole model is a plain JVM class and is tested as one.
 */
public final class PcKey {

    /** Sends {@link #code}, an Android key code, down on touch-down and up on release. */
    public static final int KIND_KEY = 0;
    /** A modifier; {@link #code} is one of the {@code ModifierLatch.MOD_*} indexes. */
    public static final int KIND_MODIFIER = 1;
    /** Presses every key code in {@link #chord} together, modifiers first. */
    public static final int KIND_CHORD = 2;
    /** Taps a Windows virtual key directly ({@link #code}); used for keys Android has no code for. */
    public static final int KIND_VIRTUAL_KEY = 3;
    /** A keyboard action (hide, switch keyboard); {@link #code} is one of the {@code ACTION_*}. */
    public static final int KIND_ACTION = 4;

    public static final int ACTION_HIDE = 1;
    public static final int ACTION_SYSTEM_KEYBOARD = 2;
    public static final int ACTION_PC_KEYBOARD = 3;

    /** Visual weight: ordinary character keys are the lightest, everything else sits back. */
    public static final int STYLE_CHAR = 0;
    public static final int STYLE_SPECIAL = 1;
    public static final int STYLE_CHIP = 2;
    public static final int STYLE_ACCENT = 3;

    /** Drawn glyphs, so no key depends on a font having a symbol. 0 draws {@link #label}. */
    public static final int ICON_NONE = 0;
    public static final int ICON_BACKSPACE = 1;
    public static final int ICON_ENTER = 2;
    public static final int ICON_SHIFT = 3;
    public static final int ICON_LEFT = 4;
    public static final int ICON_UP = 5;
    public static final int ICON_DOWN = 6;
    public static final int ICON_RIGHT = 7;
    public static final int ICON_KEYBOARD = 8;
    public static final int ICON_HIDE = 9;
    public static final int ICON_SPACE = 10;

    public final int kind;
    public final int code;
    public final int[] chord;
    public final String label;
    /** The character this key types with Shift, drawn small; null for none. */
    public final String shiftLabel;
    /** Spoken by TalkBack. Never null. */
    public final String description;
    /** Width in key units; the layout divides the row width by the widest row's units. */
    public final float width;
    public final int style;
    /** Whether a finger held on it keeps it down on the host, so the host auto-repeats it. */
    public final boolean holdable;
    public final int icon;
    /** What the key shows while Shift is active: the upper-case letter, or its shifted symbol. */
    public final String shiftedLabel;

    PcKey(int kind, int code, int[] chord, String label, String shiftLabel, String description,
          float width, int style, boolean holdable, int icon) {
        this.kind = kind;
        this.code = code;
        this.chord = chord;
        this.label = label;
        this.shiftLabel = shiftLabel;
        this.description = description;
        this.width = width;
        this.style = style;
        this.holdable = holdable;
        this.icon = icon;
        if (shiftLabel != null) {
            this.shiftedLabel = shiftLabel;
        } else if (kind == KIND_KEY && style == STYLE_CHAR && label.length() == 1) {
            this.shiftedLabel = label.toUpperCase(java.util.Locale.ROOT);
        } else {
            this.shiftedLabel = label;
        }
    }

    /** The same key at another width. Layouts reuse one definition in several places. */
    PcKey withWidth(float newWidth) {
        return new PcKey(kind, code, chord, label, shiftLabel, description, newWidth, style, holdable, icon);
    }

    public boolean isModifier() {
        return kind == KIND_MODIFIER;
    }

    /** Fn is a modifier of the keyboard itself: it switches layers and never reaches the host. */
    public boolean isFn() {
        return kind == KIND_MODIFIER && code == ModifierLatch.MOD_FN;
    }

    @Override
    public String toString() {
        return label;
    }
}
