package com.limelight.meow.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.profiles.ProfilesManager;

/**
 * The PC keyboard's settings, read the way {@code ViewportPreference} reads its own: through
 * the profile-overlaying store, and here rather than in the upstream
 * {@code PreferenceConfiguration}, so that file is not touched. Read at stream start.
 *
 * <p>Also keeps which keyboard the user last opened, in a file of its own: that is state,
 * not a setting, and must not appear in (or be overwritten by) a settings profile.
 */
public final class PcKeyboardPreferences {

    public static final String KEY_KEEP_NAV_BAR = "checkbox_meow_keep_nav_bar";
    public static final String KEY_LIFT_STREAM = "checkbox_meow_lift_stream";
    public static final String KEY_IME_EXTRA_KEYS = "checkbox_meow_ime_extra_keys";

    public static final boolean DEFAULT_KEEP_NAV_BAR = true;
    public static final boolean DEFAULT_LIFT_STREAM = true;
    public static final boolean DEFAULT_IME_EXTRA_KEYS = true;

    static final String STATE_FILE = "meow_pc_keyboard";
    static final String STATE_LAST_KEYBOARD = "last_keyboard";
    public static final String LAST_SYSTEM = "system";
    public static final String LAST_PC = "pc";

    public final boolean keepNavBar;
    public final boolean liftStream;
    public final boolean imeExtraKeys;

    PcKeyboardPreferences(boolean keepNavBar, boolean liftStream, boolean imeExtraKeys) {
        this.keepNavBar = keepNavBar;
        this.liftStream = liftStream;
        this.imeExtraKeys = imeExtraKeys;
    }

    public static PcKeyboardPreferences read(Context context) {
        try {
            SharedPreferences prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context);
            if (prefs != null) {
                return new PcKeyboardPreferences(
                        prefs.getBoolean(KEY_KEEP_NAV_BAR, DEFAULT_KEEP_NAV_BAR),
                        prefs.getBoolean(KEY_LIFT_STREAM, DEFAULT_LIFT_STREAM),
                        prefs.getBoolean(KEY_IME_EXTRA_KEYS, DEFAULT_IME_EXTRA_KEYS));
            }
        } catch (RuntimeException e) {
            // A preference read must never be able to take the stream down with it.
        }
        return new PcKeyboardPreferences(DEFAULT_KEEP_NAV_BAR, DEFAULT_LIFT_STREAM, DEFAULT_IME_EXTRA_KEYS);
    }

    public static String lastKeyboard(Context context) {
        return context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
                .getString(STATE_LAST_KEYBOARD, LAST_SYSTEM);
    }

    public static void setLastKeyboard(Context context, String which) {
        context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE).edit()
                .putString(STATE_LAST_KEYBOARD, which).apply();
    }
}
