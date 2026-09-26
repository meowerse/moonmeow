package com.limelight.meow.keyboard;

import com.limelight.Game;
import com.limelight.binding.input.evdev.EvdevListener;

/**
 * Sends PC-keyboard keys down the path a physical keyboard's keys take.
 *
 * <p>{@code Game.keyboardEvent} is the {@link EvdevListener} entry the Artemis on-screen keys
 * already use: it translates the Android key code with the session's
 * {@code KeyboardTranslator}, keeps {@code Game}'s modifier flags in step (so a physical key
 * pressed while a PC-keyboard modifier is down carries it), honours the Right-Alt-as-Command
 * option and ends in {@code LiSendKeyboardEvent}. Nothing is mapped twice.
 */
final class GameKeySink implements HostKeySink {

    private final Game game;
    private final EvdevListener keys;

    GameKeySink(Game game) {
        this.game = game;
        this.keys = game;
    }

    @Override
    public void sendKey(int androidKeyCode, boolean down) {
        keys.keyboardEvent(down, (short) androidKeyCode);
    }

    @Override
    public void tapVirtualKey(short virtualKey) {
        game.sendKeys(new short[]{virtualKey});
    }
}
