package com.limelight.meow.keyboard;

/**
 * The per-modifier state machine of the PC keyboard: Shift, Ctrl, Alt, Super and Fn.
 *
 * <p>Each modifier is {@link #OFF}, {@link #LATCHED} (applies to the next key, then turns
 * itself off) or {@link #LOCKED} (stays on until tapped again), and independently of that may
 * be <em>held</em> by a finger. The rules, which are the ones Gboard's Shift key and Termux's
 * extra keys taught everybody:
 * <ul>
 *   <li>tap an off modifier: it latches;</li>
 *   <li>tap a latched modifier within the double-tap timeout: it locks; later: it turns off;</li>
 *   <li>tap a locked modifier: it turns off;</li>
 *   <li>long-press any modifier that has not been used in a chord: it locks;</li>
 *   <li>hold a modifier and press other keys with another finger (a chord): it applies to all
 *       of them and is off again when the finger lifts, whatever it was before, unless it
 *       was locked.</li>
 * </ul>
 * Pure state: no clock of its own (times are passed in) and no Android dependency. Nothing
 * here allocates, so it can sit on the key-press path.
 */
public final class ModifierLatch {

    public static final int MOD_SHIFT = 0;
    public static final int MOD_CTRL = 1;
    public static final int MOD_ALT = 2;
    public static final int MOD_SUPER = 3;
    /** The keyboard's own layer switch. Never sent to the host. */
    public static final int MOD_FN = 4;
    public static final int COUNT = 5;
    /** The modifiers that exist on the host: every index below {@link #MOD_FN}. */
    public static final int HOST_COUNT = 4;

    public static final int OFF = 0;
    public static final int LATCHED = 1;
    public static final int LOCKED = 2;

    private final int[] state = new int[COUNT];
    private final boolean[] held = new boolean[COUNT];
    private final boolean[] usedWhileHeld = new boolean[COUNT];
    private final boolean[] longPressed = new boolean[COUNT];
    private final long[] lastTapUpMs = new long[COUNT];
    private final long doubleTapTimeoutMs;

    public ModifierLatch(long doubleTapTimeoutMs) {
        this.doubleTapTimeoutMs = doubleTapTimeoutMs;
        for (int i = 0; i < COUNT; i++) {
            lastTapUpMs[i] = Long.MIN_VALUE / 2;
        }
    }

    public int state(int mod) {
        return state[mod];
    }

    public boolean isHeld(int mod) {
        return held[mod];
    }

    /** Whether the modifier applies to the next key: latched, locked, or held down. */
    public boolean isActive(int mod) {
        return state[mod] != OFF || held[mod];
    }

    /** Bit {@code 1 << mod} set for every active host modifier. */
    public int activeHostMask() {
        int mask = 0;
        for (int i = 0; i < HOST_COUNT; i++) {
            if (isActive(i)) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** Bit {@code 1 << mod} set for every locked host modifier. */
    public int lockedHostMask() {
        int mask = 0;
        for (int i = 0; i < HOST_COUNT; i++) {
            if (state[i] == LOCKED) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** A finger went down on the modifier. Its state only changes when the finger lifts. */
    public void press(int mod) {
        held[mod] = true;
        usedWhileHeld[mod] = false;
        longPressed[mod] = false;
    }

    /**
     * The finger lifted.
     *
     * @return true if the state changed
     */
    public boolean release(int mod, long nowMs) {
        if (!held[mod]) {
            return false;
        }
        held[mod] = false;
        int before = state[mod];
        if (longPressed[mod]) {
            // The long press already locked it; lifting the finger is not a second tap.
            longPressed[mod] = false;
        } else if (usedWhileHeld[mod]) {
            // A chord: the modifier was held for the keys pressed with it, and that is all.
            if (state[mod] == LATCHED) {
                state[mod] = OFF;
            }
        } else if (state[mod] == OFF) {
            state[mod] = LATCHED;
            lastTapUpMs[mod] = nowMs;
        } else if (state[mod] == LATCHED) {
            state[mod] = nowMs - lastTapUpMs[mod] <= doubleTapTimeoutMs ? LOCKED : OFF;
        } else {
            state[mod] = OFF;
        }
        usedWhileHeld[mod] = false;
        return state[mod] != before;
    }

    /**
     * The long-press timeout expired with the finger still down.
     *
     * @return true if that locked the modifier
     */
    public boolean longPress(int mod) {
        if (!held[mod] || usedWhileHeld[mod] || state[mod] == LOCKED) {
            return false;
        }
        state[mod] = LOCKED;
        longPressed[mod] = true;
        return true;
    }

    /** A non-modifier key went down: every modifier currently held becomes part of a chord. */
    public void onKeyUsed() {
        for (int i = 0; i < COUNT; i++) {
            if (held[i]) {
                usedWhileHeld[i] = true;
            }
        }
    }

    /**
     * The keys pressed under the latches are all up again: one-shot latches are spent.
     *
     * @return true if any state changed
     */
    public boolean consumeLatches() {
        boolean changed = false;
        for (int i = 0; i < COUNT; i++) {
            if (state[i] == LATCHED && !held[i]) {
                state[i] = OFF;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Everything off, no finger down. Used when input must be dropped: focus loss, pause,
     * disconnect, the keyboard being hidden.
     *
     * @return true if anything was on
     */
    public boolean clear() {
        boolean changed = false;
        for (int i = 0; i < COUNT; i++) {
            if (state[i] != OFF || held[i]) {
                changed = true;
            }
            state[i] = OFF;
            held[i] = false;
            usedWhileHeld[i] = false;
            longPressed[i] = false;
        }
        return changed;
    }
}
