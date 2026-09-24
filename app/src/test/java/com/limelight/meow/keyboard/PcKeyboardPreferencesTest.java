package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.profiles.ProfilesManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PcKeyboardPreferencesTest {

    private Context ctx;

    @Before
    public void setUp() throws Exception {
        Field f = ProfilesManager.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
        ctx = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        ctx.getSharedPreferences(PcKeyboardPreferences.STATE_FILE, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void defaultsAreAllOn() {
        PcKeyboardPreferences p = PcKeyboardPreferences.read(ctx);
        assertTrue(p.keepNavBar);
        assertTrue(p.liftStream);
        assertTrue(p.imeExtraKeys);
    }

    @Test
    public void settingsAreRead() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putBoolean(PcKeyboardPreferences.KEY_KEEP_NAV_BAR, false)
                .putBoolean(PcKeyboardPreferences.KEY_LIFT_STREAM, false)
                .putBoolean(PcKeyboardPreferences.KEY_IME_EXTRA_KEYS, false)
                .commit();
        PcKeyboardPreferences p = PcKeyboardPreferences.read(ctx);
        assertFalse(p.keepNavBar);
        assertFalse(p.liftStream);
        assertFalse(p.imeExtraKeys);
    }

    @Test
    public void lastKeyboardIsRememberedAndDefaultsToTheSystemOne() {
        assertEquals(PcKeyboardPreferences.LAST_SYSTEM, PcKeyboardPreferences.lastKeyboard(ctx));
        PcKeyboardPreferences.setLastKeyboard(ctx, PcKeyboardPreferences.LAST_PC);
        assertEquals(PcKeyboardPreferences.LAST_PC, PcKeyboardPreferences.lastKeyboard(ctx));
    }

    @Test
    public void defaultsMatchPreferencesXml() throws Exception {
        String xml = PcKeyboardWiringTest.read("app/src/main/res/xml/preferences.xml");
        for (String key : new String[]{PcKeyboardPreferences.KEY_KEEP_NAV_BAR,
                PcKeyboardPreferences.KEY_LIFT_STREAM, PcKeyboardPreferences.KEY_IME_EXTRA_KEYS}) {
            int at = xml.indexOf("android:key=\"" + key + "\"");
            assertTrue(key + " is in preferences.xml", at > 0);
            String before = xml.substring(xml.lastIndexOf("<CheckBoxPreference", at), at);
            assertTrue(key + " defaults to true", before.contains("android:defaultValue=\"true\""));
        }
    }
}
