package com.limelight.meow.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.meow.SourceFiles;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

/**
 * The owner wants the PC's audio on the PC (headphones) and on the phone at once. Moonlight's
 * "Play audio on host PC" is that switch: on, the launch asks the host not to move its default
 * sink to the stream's virtual one ({@code localAudioPlayMode=1}), so the PC keeps playing
 * while the stream captures a copy.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class HostAudioPreferenceTest {

    private static final String GAME = "app/src/main/java/com/limelight/Game.java";
    private static final String NVHTTP =
            "app/src/main/java/com/limelight/nvstream/http/NvHTTP.java";

    private Context context;

    @BeforeClass
    public static void quiet() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void aFreshInstallPlaysAudioOnThePcToo() {
        assertTrue(HostAudioPreference.DEFAULT);
        assertTrue(PreferenceConfiguration.readPreferences(context).playHostAudio);
    }

    @Test
    public void theOffSwitchIsKept() {
        PreferenceConfiguration.readPreferences(context);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(HostAudioPreference.KEY, false).commit();
        assertFalse(PreferenceConfiguration.readPreferences(context).playHostAudio);
    }

    @Test
    public void theXmlDefaultMatchesTheConstant() throws IOException {
        String xml = SourceFiles.read("app/src/main/res/xml/preferences.xml");
        int key = xml.indexOf("android:key=\"" + HostAudioPreference.KEY + "\"");
        assertTrue(key > 0);
        String element = xml.substring(xml.lastIndexOf("<CheckBoxPreference", key), key);
        assertTrue(element.contains("android:defaultValue=\"true\""));
    }

    /** The switch reaches the host: StreamConfiguration, then the launch/resume query. */
    @Test
    public void theSwitchReachesTheLaunchRequest() throws IOException {
        assertTrue(new StreamConfiguration.Builder().enableLocalAudioPlayback(true).build()
                .getPlayLocalAudio());
        String game = SourceFiles.stripComments(SourceFiles.read(GAME));
        assertTrue(game.contains(".enableLocalAudioPlayback(prefConfig.playHostAudio)"));
        String http = SourceFiles.stripComments(SourceFiles.read(NVHTTP));
        assertTrue(http.contains(
                "\"&localAudioPlayMode=\" + (context.streamConfig.getPlayLocalAudio() ? 1 : 0)"));
    }

    /**
     * Artemis' "3d mode v1" (08dd5406) passed playHostAudio where the audio renderer takes
     * enableAudioFx. With host audio on by default that would open an equalizer session and
     * skip the phone's low-latency audio track for everyone.
     */
    @Test
    public void thePhonesAudioRendererGetsTheEqualizerSwitchNotTheHostAudioOne()
            throws IOException {
        String game = SourceFiles.stripComments(SourceFiles.read(GAME));
        assertTrue(game.contains("new AndroidAudioRenderer(Game.this, prefConfig.enableAudioFx)"));
        assertFalse(game.contains("new AndroidAudioRenderer(Game.this, prefConfig.playHostAudio)"));
    }

    @Test
    public void installsBeforeSchemaThreeAreSwitchedOnOnce() {
        android.content.SharedPreferences canonical =
                PreferenceManager.getDefaultSharedPreferences(context);
        canonical.edit()
                .putInt("meow_defaults_migration", 2)
                .putBoolean(HostAudioPreference.KEY, false)
                .commit();
        assertTrue(PreferenceConfiguration.readPreferences(context).playHostAudio);
        assertEquals(3, canonical.getInt("meow_defaults_migration", 0));
        // Turned off after the migration: stays off.
        canonical.edit().putBoolean(HostAudioPreference.KEY, false).commit();
        assertFalse(PreferenceConfiguration.readPreferences(context).playHostAudio);
    }
}
