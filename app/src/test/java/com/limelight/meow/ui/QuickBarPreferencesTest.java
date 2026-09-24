package com.limelight.meow.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.keyboard.PcKeyboardWiringTest;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
public class QuickBarPreferencesTest {

    private Context ctx;

    @Before
    public void setUp() throws Exception {
        Field f = ProfilesManager.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
        ctx = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    @Test
    public void aFreshInstallKeepsTheBarVisible() {
        assertFalse(QuickBarPreferences.autoHide(ctx));
    }

    @Test
    public void anExistingInstallWhoseMigrationAlreadyRanAlsoKeepsItVisible() {
        // Simulate an install that has been through every defaults migration: the new
        // default must reach it without another migration step.
        PreferenceConfiguration.readPreferences(ctx);
        assertFalse(QuickBarPreferences.autoHide(ctx));
        PreferenceConfiguration.readPreferences(ctx);
        assertFalse(QuickBarPreferences.autoHide(ctx));
    }

    @Test
    public void theSettingIsRead() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putBoolean(QuickBarPreferences.KEY_AUTO_HIDE, true).commit();
        assertTrue(QuickBarPreferences.autoHide(ctx));
    }

    @Test
    public void theDefaultMatchesPreferencesXml() throws Exception {
        String xml = PcKeyboardWiringTest.read("app/src/main/res/xml/preferences.xml");
        int at = xml.indexOf("android:key=\"" + QuickBarPreferences.KEY_AUTO_HIDE + "\"");
        assertTrue(at > 0);
        String before = xml.substring(xml.lastIndexOf("<CheckBoxPreference", at), at);
        assertTrue(before.contains("android:defaultValue=\"false\""));
    }
}
