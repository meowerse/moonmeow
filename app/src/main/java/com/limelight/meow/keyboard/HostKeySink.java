package com.limelight.meow.keyboard;

/**
 * Where the PC keyboard's key presses go. Production: {@link GameKeySink}, which routes them
 * through the exact path a physical keyboard takes ({@code Game.keyboardEvent}, and so
 * {@code KeyboardTranslator} and {@code LiSendKeyboardEvent}). Tests record the calls.
 *
 * <p>UI thread only. Implementations must not allocate on {@link #sendKey}: it is the
 * key-press path.
 */
public interface HostKeySink {
    /** One Android key code going down or up on the host. */
    void sendKey(int androidKeyCode, boolean down);

    /**
     * Presses and releases a Windows virtual key that has no Android key code (volume and
     * media keys). The host decides what it means on its OS.
     */
    void tapVirtualKey(short virtualKey);
}
