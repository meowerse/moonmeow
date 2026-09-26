package com.limelight.meow.keyboard;

import android.view.KeyEvent;

/**
 * Turns PC-keyboard touches, and the keys typed around it, into host key events.
 *
 * <p><b>Modifiers reach the host lazily.</b> A latched or held modifier is pressed on the host
 * only when the key it modifies goes down, and released after that key comes up. Tapping
 * Super to latch it and tapping it again to cancel therefore sends nothing, instead of a
 * lone Super press that opens the KDE launcher or the Windows Start menu. A <em>locked</em>
 * modifier is the exception: it is pressed as soon as it locks and held until unlocked, so it
 * also applies to mouse clicks (Ctrl+click, Shift+click) and to Alt+Tab held open across
 * several Tabs.
 *
 * <p><b>Everything that types goes through here</b>, not only the on-screen keys: a key from
 * a physical keyboard or the system keyboard calls {@link #beforeExternalKey} and
 * {@link #afterExternalKey}, and text from the system keyboard calls {@link #onImeText}, so a
 * latched Ctrl applies to whichever keyboard the next key comes from.
 *
 * <p><b>Key repeat is the host's.</b> A held key stays down on the host until the finger
 * lifts, exactly like a physical key, and the host's own repeat delay and rate apply. Nothing
 * here synthesises repeats: {@code Game.handleKeyDown} drops Android's repeats for the same
 * reason, and doing both would double every repeated character.
 *
 * <p>UI thread. No allocation on any path taken per key.
 */
public final class PcKeyboardEngine {

    /** Told whenever something the keyboard draws (a modifier state, the layer) changed. */
    public interface Listener {
        void onKeyboardStateChanged();
    }

    private static final int[] HOST_MODIFIER_KEYS = {
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_META_LEFT,
    };

    private final HostKeySink sink;
    private final ModifierLatch latch;
    private Listener listener;

    /** Modifiers this engine has pressed on the host and not yet released. */
    private int hostMods;
    /** Of those, the ones pressed only for the chord currently down. */
    private int chordMods;
    /** Non-modifier keys currently down, from any keyboard. */
    private int keysDown;
    /** Of those, the ones from another keyboard that this engine modified. */
    private int externalDown;

    public PcKeyboardEngine(HostKeySink sink, long doubleTapTimeoutMs) {
        this.sink = sink;
        this.latch = new ModifierLatch(doubleTapTimeoutMs);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int modifierState(int mod) {
        return latch.state(mod);
    }

    public boolean isModifierActive(int mod) {
        return latch.isActive(mod);
    }

    /** Whether the Fn layer is showing: Fn latched, locked or held. */
    public boolean isFnLayer() {
        return latch.isActive(ModifierLatch.MOD_FN);
    }

    /** Whether any host modifier would apply to the next key. */
    public boolean hasActiveHostModifier() {
        return latch.activeHostMask() != 0;
    }

    /** Modifiers currently held down on the host by this engine, as {@code 1 << MOD_*} bits. */
    public int hostModifierMask() {
        return hostMods;
    }

    // ---- the on-screen keys -------------------------------------------------------------

    public void modifierDown(int mod) {
        latch.press(mod);
        notifyChanged();
    }

    public void modifierUp(int mod, long nowMs) {
        latch.release(mod, nowMs);
        syncHost();
        notifyChanged();
    }

    /** The long-press timeout expired on a modifier still held. */
    public boolean modifierLongPress(int mod) {
        if (!latch.longPress(mod)) {
            return false;
        }
        syncHost();
        notifyChanged();
        return true;
    }

    public void keyDown(int androidKeyCode) {
        beginKey();
        sink.sendKey(androidKeyCode, true);
    }

    public void keyUp(int androidKeyCode) {
        sink.sendKey(androidKeyCode, false);
        endKey();
    }

    /** A shortcut chip: its own modifiers join whatever is latched (Shift latched + Ctrl+Z). */
    public void chordDown(int[] chord) {
        beginKey();
        for (int code : chord) {
            int mod = hostModifierFor(code);
            if (mod >= 0) {
                int bit = 1 << mod;
                if ((hostMods & bit) == 0) {
                    sink.sendKey(code, true);
                    hostMods |= bit;
                    chordMods |= bit;
                }
            } else {
                sink.sendKey(code, true);
            }
        }
    }

    public void chordUp(int[] chord) {
        for (int i = chord.length - 1; i >= 0; i--) {
            if (hostModifierFor(chord[i]) < 0) {
                sink.sendKey(chord[i], false);
            }
        }
        releaseHost(chordMods);
        chordMods = 0;
        endKey();
    }

    public void virtualKeyTap(short virtualKey) {
        beginKey();
        sink.tapVirtualKey(virtualKey);
        endKey();
    }

    // ---- keys from other keyboards --------------------------------------------------------

    /**
     * A physical or system-keyboard key is about to be sent down. Presses the active
     * modifiers on the host first, so it arrives modified. Modifier keys themselves are
     * ignored: their own state is {@code Game}'s.
     */
    public void beforeExternalKey(int androidKeyCode) {
        if (hostModifierFor(androidKeyCode) >= 0 || latch.activeHostMask() == 0) {
            return;
        }
        beginKey();
        externalDown++;
    }

    /** The key sent after {@link #beforeExternalKey} was released on the host. */
    public void afterExternalKey(int androidKeyCode) {
        if (hostModifierFor(androidKeyCode) >= 0 || externalDown == 0) {
            return;
        }
        externalDown--;
        endKey();
    }

    /**
     * Text from the system keyboard. With a host modifier active it is typed as key presses
     * instead, so Ctrl latched plus "c" (or the Cyrillic "с" on the same key) is Ctrl+C.
     *
     * @return true if it was consumed as keys; false to send it as text as usual
     */
    public boolean onImeText(CharSequence text) {
        if (text == null || text.length() == 0 || !hasActiveHostModifier()) {
            return false;
        }
        int codePoint = Character.codePointAt(text, 0);
        int mapped = Character.charCount(codePoint) == text.length() ? CharKeyMap.lookup(codePoint) : 0;
        if (mapped == 0) {
            // A word from swipe typing, an emoji: there is no key for it. Type it as text and
            // spend the latches, so they do not wait for a key the user already typed.
            if (latch.consumeLatches()) {
                syncHost();
                notifyChanged();
            }
            return false;
        }
        int keyCode = CharKeyMap.keyCode(mapped);
        boolean addShift = CharKeyMap.needsShift(mapped) && !latch.isActive(ModifierLatch.MOD_SHIFT);
        beginKey();
        if (addShift) {
            sink.sendKey(KeyEvent.KEYCODE_SHIFT_LEFT, true);
        }
        sink.sendKey(keyCode, true);
        sink.sendKey(keyCode, false);
        if (addShift) {
            sink.sendKey(KeyEvent.KEYCODE_SHIFT_LEFT, false);
        }
        endKey();
        return true;
    }

    // ---- teardown -----------------------------------------------------------------------

    /**
     * Drops every latch and lock and releases every modifier this engine holds on the host.
     * The view releases the keys its fingers hold before calling this.
     */
    public void releaseAll() {
        releaseHost(hostMods);
        chordMods = 0;
        keysDown = 0;
        externalDown = 0;
        if (latch.clear()) {
            notifyChanged();
        }
    }

    // ---- internals ----------------------------------------------------------------------

    private void beginKey() {
        int wanted = latch.activeHostMask();
        for (int mod = 0; mod < ModifierLatch.HOST_COUNT; mod++) {
            int bit = 1 << mod;
            if ((wanted & bit) != 0 && (hostMods & bit) == 0) {
                sink.sendKey(HOST_MODIFIER_KEYS[mod], true);
                hostMods |= bit;
            }
        }
        latch.onKeyUsed();
        keysDown++;
    }

    private void endKey() {
        if (keysDown > 0) {
            keysDown--;
        }
        if (keysDown == 0) {
            boolean changed = latch.consumeLatches();
            syncHost();
            if (changed) {
                notifyChanged();
            }
        }
    }

    /** Locked modifiers are down on the host; the rest only while a key they modify is. */
    private void syncHost() {
        int wanted = latch.lockedHostMask();
        if (keysDown > 0) {
            wanted |= latch.activeHostMask();
        }
        releaseHost(hostMods & ~wanted);
        for (int mod = 0; mod < ModifierLatch.HOST_COUNT; mod++) {
            int bit = 1 << mod;
            if ((wanted & bit) != 0 && (hostMods & bit) == 0 && latch.state(mod) == ModifierLatch.LOCKED) {
                sink.sendKey(HOST_MODIFIER_KEYS[mod], true);
                hostMods |= bit;
            }
        }
    }

    private void releaseHost(int mask) {
        for (int mod = ModifierLatch.HOST_COUNT - 1; mod >= 0; mod--) {
            int bit = 1 << mod;
            if ((mask & bit) != 0 && (hostMods & bit) != 0) {
                sink.sendKey(HOST_MODIFIER_KEYS[mod], false);
                hostMods &= ~bit;
            }
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onKeyboardStateChanged();
        }
    }

    /** The {@code MOD_*} index a host modifier key code belongs to, or -1. */
    static int hostModifierFor(int androidKeyCode) {
        switch (androidKeyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return ModifierLatch.MOD_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return ModifierLatch.MOD_CTRL;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return ModifierLatch.MOD_ALT;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return ModifierLatch.MOD_SUPER;
            default:
                return -1;
        }
    }

    static int hostKeyFor(int mod) {
        return HOST_MODIFIER_KEYS[mod];
    }
}
