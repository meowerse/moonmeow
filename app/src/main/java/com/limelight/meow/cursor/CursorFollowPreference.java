package com.limelight.meow.cursor;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.profiles.ProfilesManager;

/**
 * Reads the cursor-follow preference: the explicit off switch for {@link CursorFollowController}.
 *
 * <p>Kept out of {@code PreferenceConfiguration} for the same reason as
 * {@code ViewportPreference}: the upstream class stays untouched, and the read goes through the
 * same profile-overlaying store so a per-profile override works. Read once in
 * {@code Game.onCreate}, so a change applies to the next stream.
 *
 * <p>On by default: following is inert until the user zooms in, and once they have, a cursor
 * that walks off the screen is never what they wanted. Existing installs are switched on once
 * by {@code PreferenceConfiguration.applyDefaultsMigration()}.
 */
public final class CursorFollowPreference {

    public static final String KEY = "checkbox_meow_cursor_follow";
    /** Must stay in step with {@code android:defaultValue} in {@code res/xml/preferences.xml}. */
    public static final boolean DEFAULT = true;

    private CursorFollowPreference() {
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
            // A preference read must never take the stream down with it.
            return DEFAULT;
        }
    }
}
