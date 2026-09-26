package com.limelight.meow.keyboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.bitrate.AutoBitratePreference;
import com.limelight.meow.cursor.CursorFollowPreference;
import com.limelight.meow.ui.QuickBarPreferences;
import com.limelight.meow.viewport.ViewportPreference;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * The keyboard branch's settings and #16's MeowDefaults schema 2 on one install: an existing
 * install that has been through schema 1 is migrated by #16's step, and every meow feature,
 * theirs and ours, reads on. Ours need no step of their own: their keys are new, so the stored
 * value is absent and the default (on) applies, as it does on a fresh install.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
public class MeowDefaultsCoexistTest {

    private Context ctx;

    @Before
    public void setUp() throws Exception {
        Field f = ProfilesManager.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
        ctx = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
    }

    private void assertEverythingOn() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        assertTrue(ViewportPreference.isEnabled(ctx));
        assertTrue(prefs.getBoolean(CursorFollowPreference.KEY, false));
        assertTrue(prefs.getBoolean(AutoBitratePreference.KEY, false));
        PcKeyboardPreferences keyboard = PcKeyboardPreferences.read(ctx);
        assertTrue("navigation bar kept", keyboard.keepNavBar);
        assertTrue("stream lift", keyboard.liftStream);
        assertTrue("PC keys above the system keyboard", keyboard.imeExtraKeys);
        assertFalse("the quick bar always visible", QuickBarPreferences.autoHide(ctx));
    }

    @Test
    public void anExistingSchema1InstallGetsEveryFeatureOn() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putInt("meow_defaults_migration", 1)
                .putBoolean(CursorFollowPreference.KEY, false)
                .commit();
        PreferenceConfiguration.readPreferences(ctx);
        assertEverythingOn();
    }

    @Test
    public void aFreshInstallGetsEveryFeatureOn() {
        PreferenceConfiguration.readPreferences(ctx);
        assertEverythingOn();
    }
}
