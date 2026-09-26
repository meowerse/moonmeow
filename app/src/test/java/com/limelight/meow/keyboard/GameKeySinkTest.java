package com.limelight.meow.keyboard;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.view.KeyEvent;

import com.limelight.Game;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
public class GameKeySinkTest {

    @Test
    public void keysTakeThePhysicalKeyboardPath() {
        Game game = mock(Game.class);
        GameKeySink sink = new GameKeySink(game);
        sink.sendKey(KeyEvent.KEYCODE_CTRL_LEFT, true);
        sink.sendKey(KeyEvent.KEYCODE_C, false);
        verify(game).keyboardEvent(true, (short) KeyEvent.KEYCODE_CTRL_LEFT);
        verify(game).keyboardEvent(false, (short) KeyEvent.KEYCODE_C);
    }

    @Test
    public void virtualKeysAreTappedThroughSendKeys() {
        Game game = mock(Game.class);
        new GameKeySink(game).tapVirtualKey(PcKeyboardLayout.VK_VOLUME_MUTE);
        verify(game).sendKeys(new short[]{PcKeyboardLayout.VK_VOLUME_MUTE});
    }
}
