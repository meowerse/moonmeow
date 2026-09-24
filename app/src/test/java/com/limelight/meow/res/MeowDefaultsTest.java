package com.limelight.meow.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.bitrate.AutoBitratePreference;
import com.limelight.meow.cursor.CursorFollowPreference;
import com.limelight.meow.viewport.ViewportPreference;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class MeowDefaultsTest {

    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = ApplicationProvider.getApplicationContext()
                .getSharedPreferences("meow-defaults-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test
    public void everyFeatureDefaultsOnAndMatchesTheSchemaStep() {
        assertTrue(ViewportPreference.DEFAULT);
        assertTrue(CursorFollowPreference.DEFAULT);
        assertTrue(AutoBitratePreference.DEFAULT);
        assertEquals(2, MeowDefaults.SCHEMA_VERSION);
    }

    @Test
    public void installsBeforeSchemaTwoAreSwitchedOn() {
        for (int from = 0; from < 2; from++) {
            prefs.edit().clear().putBoolean(ViewportPreference.KEY, false).commit();
            SharedPreferences.Editor editor = prefs.edit();
            MeowDefaults.apply(from, editor);
            editor.commit();
            assertTrue(prefs.getBoolean(ViewportPreference.KEY, false));
            assertTrue(prefs.getBoolean(CursorFollowPreference.KEY, false));
            assertTrue(prefs.getBoolean(AutoBitratePreference.KEY, false));
        }
    }

    @Test
    public void anInstallAtSchemaTwoIsLeftAlone() {
        SharedPreferences.Editor editor = prefs.edit();
        MeowDefaults.apply(2, editor);
        editor.commit();
        assertFalse(prefs.contains(CursorFollowPreference.KEY));
        assertFalse(prefs.contains(AutoBitratePreference.KEY));
        assertFalse(prefs.contains(ViewportPreference.KEY));
    }
}
