package com.limelight;

import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowGameManager;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the surface setup back-ported from moonlight-stream/moonlight-android 6d4c64a5:
 * on Android 16.1 (API 37, CINNAMON_BUN) Game.surfaceCreated() turns producer throttling
 * off on the video surface for lower display latency, and leaves it alone below that.
 *
 * surfaceCreated() only needs prefConfig and the surface holder, so the Activity is
 * constructed but never created -- no stream, no connection, no layout.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowMoonBridge.class, ShadowGameManager.class})
public class GameSurfaceProducerThrottlingTest {

    private Game game;
    private Surface surface;
    private SurfaceHolder holder;

    @BeforeClass
    public static void silenceLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() throws Exception {
        game = Robolectric.buildActivity(Game.class).get();

        PreferenceConfiguration prefConfig =
                PreferenceConfiguration.readPreferences(ApplicationProvider.getApplicationContext());
        Field f = Game.class.getDeclaredField("prefConfig");
        f.setAccessible(true);
        f.set(game, prefConfig);

        surface = mock(Surface.class);
        holder = mock(SurfaceHolder.class);
        when(holder.getSurface()).thenReturn(surface);
    }

    @Test
    @Config(sdk = 37)
    public void onAndroid16Point1ProducerThrottlingIsDisabled() {
        game.surfaceCreated(holder);

        verify(surface).setProducerThrottlingEnabled(false);
    }

    @Test
    @Config(sdk = 36)
    public void belowAndroid16Point1TheSurfaceIsLeftAlone() {
        // setProducerThrottlingEnabled() does not exist before API 37: an unguarded call
        // is a NoSuchMethodError on every stream start. The method cannot even be named
        // in a verify() on this SDK, so assert instead that the frame-rate hint is the
        // only thing surfaceCreated() did to the surface.
        game.surfaceCreated(holder);

        verify(surface).setFrameRate(anyFloat(), anyInt(), anyInt());
        verifyNoMoreInteractions(surface);
    }
}
