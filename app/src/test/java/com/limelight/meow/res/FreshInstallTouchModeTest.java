package com.limelight.meow.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Fresh installs start in Track pad (natural): the app is desktop-first and the owner works
 * that way. Existing installs keep whatever {@code PcView}'s first
 * {@code setDefaultValues} stored for them; there is no migration.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class FreshInstallTouchModeTest {

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        context.getSharedPreferences(PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void aFreshInstallStartsInTrackpadNatural() {
        PreferenceManager.setDefaultValues(context, R.xml.preferences, false);
        assertEquals("2", prefs.getString("mouse_mode_list", null));
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.touchscreenTrackpad);
        assertFalse(config.enableMultiTouchScreen);
    }

    @Test
    public void anExistingInstallKeepsItsStoredMode() {
        prefs.edit().putString("mouse_mode_list", "0").commit();
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
        assertEquals("0", prefs.getString("mouse_mode_list", null));
        assertFalse(PreferenceConfiguration.readPreferences(context).touchscreenTrackpad);
    }
}
