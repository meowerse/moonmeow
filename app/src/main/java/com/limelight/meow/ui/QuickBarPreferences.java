package com.limelight.meow.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.profiles.ProfilesManager;

/**
 * The quick bar's one setting, read through the profile-overlaying store like the other meow
 * preferences, so the upstream {@code PreferenceConfiguration} is not touched.
 *
 * <h2>Why there is no defaults migration</h2>
 * The bar used to auto-hide unconditionally; there was no setting. This key is new, so every
 * existing install has no stored value and reads {@link #DEFAULT_AUTO_HIDE} — off, i.e. the
 * new always-visible behaviour — exactly as a fresh install does. A migration step exists to
 * flip a value users already have stored; there is none to flip here, and adding one would
 * only collide with the schema bump PR #16 makes to {@code applyDefaultsMigration()}.
 * {@code QuickBarPreferencesTest} pins this: an install whose migration has already run still
 * gets the visible bar.
 */
public final class QuickBarPreferences {

    public static final String KEY_AUTO_HIDE = "checkbox_meow_quickbar_auto_hide";
    public static final boolean DEFAULT_AUTO_HIDE = false;

    private QuickBarPreferences() {
    }

    public static boolean autoHide(Context context) {
        try {
            SharedPreferences prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context);
            return prefs != null ? prefs.getBoolean(KEY_AUTO_HIDE, DEFAULT_AUTO_HIDE) : DEFAULT_AUTO_HIDE;
        } catch (RuntimeException e) {
            // A preference read must never be able to take the stream down with it.
            return DEFAULT_AUTO_HIDE;
        }
    }
}
