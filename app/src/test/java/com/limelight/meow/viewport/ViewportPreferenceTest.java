package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ViewportPreferenceTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void aFreshInstallHasTheFeatureOn() {
        // The feature is on out of the box. It is safe to default on because the reporter
        // probes the host at the start of every stream and disables itself for the session
        // when the host does not answer, so a host without support is unaffected.
        assertTrue(ViewportPreference.isEnabled(context));
    }

    @Test
    public void turningItOffIsRead() {
        // The off switch must still work -- a default is not a lock.
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(ViewportPreference.KEY, false).commit();
        assertFalse(ViewportPreference.isEnabled(context));
    }

    @Test
    public void turningItOnIsRead() {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(ViewportPreference.KEY, true).commit();
        assertTrue(ViewportPreference.isEnabled(context));
    }

    @Test
    public void aNullContextFallsBackToTheDefaultRatherThanThrowing() {
        assertEquals(ViewportPreference.DEFAULT, ViewportPreference.isEnabled(null));
    }

    @Test
    public void theKeyMatchesTheOneDeclaredInPreferencesXml() throws java.io.IOException {
        // The XML is what the user actually toggles. A mismatch between the two spellings is
        // a preference that does nothing and gives no sign of it.
        assertTrue("preferences.xml must declare " + ViewportPreference.KEY,
                readPreferencesXml().contains("\"" + ViewportPreference.KEY + "\""));
    }

    private static String readPreferencesXml() throws java.io.IOException {
        String relative = "app/src/main/res/xml/preferences.xml";
        java.io.File dir = new java.io.File("").getAbsoluteFile();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParentFile()) {
            java.io.File candidate = new java.io.File(dir, relative);
            if (candidate.isFile()) {
                return new String(java.nio.file.Files.readAllBytes(candidate.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            java.io.File here = new java.io.File(dir, relative.replaceFirst("^app/", ""));
            if (here.isFile()) {
                return new String(java.nio.file.Files.readAllBytes(here.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("could not locate " + relative);
    }
}
