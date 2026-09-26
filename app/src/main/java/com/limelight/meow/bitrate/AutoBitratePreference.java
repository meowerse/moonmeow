package com.limelight.meow.bitrate;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.profiles.ProfilesManager;

/**
 * Reads the "Automatic bitrate" preference, the explicit off switch for {@link BitrateSession}.
 * On by default; existing installs are switched on once by
 * {@code PreferenceConfiguration.applyDefaultsMigration()}. Read in {@code Game.onCreate}, so a
 * change applies to the next stream. Goes through the profile-overlaying store like
 * {@code ViewportPreference}.
 */
public final class AutoBitratePreference {

    public static final String KEY = "checkbox_meow_auto_bitrate";
    /** Must stay in step with {@code android:defaultValue} in {@code res/xml/preferences.xml}. */
    public static final boolean DEFAULT = true;

    private AutoBitratePreference() {
    }

    public static boolean isEnabled(Context context) {
        if (context == null) {
            return DEFAULT;
        }
        try {
            SharedPreferences prefs =
                    ProfilesManager.getInstance().getOverlayingSharedPreferences(context);
            return prefs != null ? prefs.getBoolean(KEY, DEFAULT) : DEFAULT;
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }
}
