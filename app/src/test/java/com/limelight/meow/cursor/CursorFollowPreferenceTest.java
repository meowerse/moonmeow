package com.limelight.meow.cursor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.SourceFiles;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class CursorFollowPreferenceTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void onByDefault() {
        assertTrue(CursorFollowPreference.DEFAULT);
        assertTrue(CursorFollowPreference.isEnabled(context));
    }

    @Test
    public void theOffSwitchIsHonoured() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(CursorFollowPreference.KEY, false).commit();
        assertFalse(CursorFollowPreference.isEnabled(context));
    }

    @Test
    public void aNullContextFallsBackToTheDefault() {
        assertTrue(CursorFollowPreference.isEnabled(null));
    }

    @Test
    public void theXmlDefaultMatchesTheConstant() throws IOException {
        String xml = SourceFiles.read("app/src/main/res/xml/preferences.xml");
        int key = xml.indexOf("android:key=\"" + CursorFollowPreference.KEY + "\"");
        assertTrue(key > 0);
        String element = xml.substring(xml.lastIndexOf("<CheckBoxPreference", key), key);
        assertTrue(element.contains("android:defaultValue=\"true\""));
    }
}
