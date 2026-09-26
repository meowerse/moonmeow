package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.meow.bitrate.AutoBitratePreference;
import com.limelight.meow.cursor.CursorFollowPreference;
import com.limelight.meow.viewport.ViewportPreference;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Covers the delivery mechanism for the changed defaults, not the choice of value.
 *
 * <p>The pure choice is {@code NativeResolutionDefaultTest}. What breaks in practice is the
 * plumbing: whether the change reaches an install that already exists, and whether it stays
 * out of stores it has no business writing to.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class DefaultsMigrationTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private Context context;
    private SharedPreferences canonical;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        canonical = PreferenceManager.getDefaultSharedPreferences(context);
        canonical.edit().clear().commit();
    }

    @Test
    public void aVirginInstallEndsUpOnThePanelResolution() {
        PreferenceConfiguration.readPreferences(context);

        assertEquals(PreferenceConfiguration.getDefaultResolution(context),
                canonical.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
    }

    @Test
    public void anInstallCarryingTheOldInheritedDefaultIsMigrated() {
        // The reported case: the old 16:9 default was persisted on first launch, so changing
        // android:defaultValue alone would never have reached this user.
        canonical.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1280x720")
                .putBoolean("checkbox_auto_orientation", false)
                .putBoolean(ViewportPreference.KEY, false)
                .commit();

        PreferenceConfiguration.readPreferences(context);

        assertEquals(PreferenceConfiguration.getDefaultResolution(context),
                canonical.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertTrue(canonical.getBoolean("checkbox_auto_orientation", false));
        assertTrue(canonical.getBoolean(ViewportPreference.KEY, false));
    }

    @Test
    public void aResolutionTheUserPickedIsLeftAlone() {
        canonical.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1920x1080")
                .commit();

        PreferenceConfiguration.readPreferences(context);

        assertEquals("1920x1080",
                canonical.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
    }

    @Test
    public void theMigrationRunsOnceAndDoesNotUndoALaterChoice() {
        PreferenceConfiguration.readPreferences(context);
        assertEquals(PreferenceConfiguration.DEFAULTS_MIGRATION_VERSION,
                canonical.getInt(PreferenceConfiguration.DEFAULTS_MIGRATION_PREF_STRING, 0));

        // The user then turns things off and picks their own resolution.
        canonical.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1280x720")
                .putBoolean("checkbox_auto_orientation", false)
                .putBoolean(ViewportPreference.KEY, false)
                .commit();

        PreferenceConfiguration.readPreferences(context);

        assertEquals("1280x720",
                canonical.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertFalse(canonical.getBoolean("checkbox_auto_orientation", true));
        assertFalse(canonical.getBoolean(ViewportPreference.KEY, true));
    }

    @Test
    public void aCallerSuppliedStoreIsNeverWrittenTo() {
        // EditProfileActivity passes an in-memory store built from a profile's sparse option
        // map. Writing into it would bake these keys into that profile as overrides the user
        // never set, and saving the profile would persist them.
        SharedPreferences profileLike =
                context.getSharedPreferences("profile-like", Context.MODE_PRIVATE);
        profileLike.edit().clear().commit();

        PreferenceConfiguration.readPreferences(context, profileLike);

        assertFalse("migration must not write into a caller-supplied store",
                profileLike.contains(PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertFalse(profileLike.contains(PreferenceConfiguration.DEFAULTS_MIGRATION_PREF_STRING));
        assertFalse(profileLike.contains("checkbox_auto_orientation"));
        assertFalse(profileLike.contains(ViewportPreference.KEY));
    }

    // ---- schema 2: every meow feature on (D1) ----------------------------------------

    @Test
    public void aVirginInstallHasEveryMeowFeatureOn() {
        PreferenceConfiguration.readPreferences(context);
        assertTrue(canonical.getBoolean(ViewportPreference.KEY, false));
        assertTrue(canonical.getBoolean(CursorFollowPreference.KEY, false));
        assertTrue(canonical.getBoolean(AutoBitratePreference.KEY, false));
        assertEquals(3, canonical.getInt(PreferenceConfiguration.DEFAULTS_MIGRATION_PREF_STRING, 0));
    }

    @Test
    public void anInstallAlreadyAtSchemaOneGetsTheNewFeaturesAndNothingElse() {
        // Migrated once, then the user locked orientation, picked 720p and turned viewport
        // following off while it double-magnified.
        canonical.edit()
                .putInt(PreferenceConfiguration.DEFAULTS_MIGRATION_PREF_STRING, 1)
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1280x720")
                .putBoolean("checkbox_auto_orientation", false)
                .putBoolean(ViewportPreference.KEY, false)
                .commit();

        PreferenceConfiguration.readPreferences(context);

        assertTrue(canonical.getBoolean(ViewportPreference.KEY, false));
        assertTrue(canonical.getBoolean(CursorFollowPreference.KEY, false));
        assertTrue(canonical.getBoolean(AutoBitratePreference.KEY, false));
        assertEquals("schema 1 steps must not run again",
                "1280x720", canonical.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertFalse(canonical.getBoolean("checkbox_auto_orientation", true));
    }

    @Test
    public void theNewOffSwitchesStayOffAfterTheMigration() {
        PreferenceConfiguration.readPreferences(context);
        canonical.edit()
                .putBoolean(CursorFollowPreference.KEY, false)
                .putBoolean(AutoBitratePreference.KEY, false)
                .putBoolean(ViewportPreference.KEY, false)
                .commit();
        PreferenceConfiguration.readPreferences(context);
        assertFalse(canonical.getBoolean(CursorFollowPreference.KEY, true));
        assertFalse(canonical.getBoolean(AutoBitratePreference.KEY, true));
        assertFalse(canonical.getBoolean(ViewportPreference.KEY, true));
        assertFalse(CursorFollowPreference.isEnabled(context));
        assertFalse(AutoBitratePreference.isEnabled(context));
    }

    @Test
    public void theNewKeysAreNeverWrittenIntoACallerSuppliedStore() {
        SharedPreferences profileLike =
                context.getSharedPreferences("profile-like-2", Context.MODE_PRIVATE);
        profileLike.edit().clear().commit();
        PreferenceConfiguration.readPreferences(context, profileLike);
        assertFalse(profileLike.contains(CursorFollowPreference.KEY));
        assertFalse(profileLike.contains(AutoBitratePreference.KEY));
    }
}
