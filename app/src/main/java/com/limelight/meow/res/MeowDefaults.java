package com.limelight.meow.res;

import android.content.SharedPreferences;

import com.limelight.meow.bitrate.AutoBitratePreference;
import com.limelight.meow.cursor.CursorFollowPreference;
import com.limelight.meow.viewport.ViewportPreference;

/**
 * The meow half of {@code PreferenceConfiguration.applyDefaultsMigration()}: which meow features
 * an existing install is switched on for, and at which schema version.
 *
 * <p>Changing an {@code android:defaultValue} reaches only fresh installs (see the migration's
 * own comment), so every feature that ships on by default is also written here, once, for
 * installs that predate it. Each keeps its explicit off switch in Settings, and because each
 * version's steps run exactly once, a switch turned off afterwards stays off.
 *
 * <h2>Version 2 (spec D1: every meow feature on, fresh and existing installs)</h2>
 * <ul>
 *   <li>{@link CursorFollowPreference} and {@link AutoBitratePreference} — new keys.</li>
 *   <li>{@link ViewportPreference} — <em>again</em>. Version 1 already turned it on once. It is
 *       turned on a second time on purpose: until version 2 a host crop was shown magnified
 *       twice and taps landed off target (F1), which is a good reason to have switched it off.
 *       The defect is fixed in the same release as this step, and the owner asked for every
 *       feature on for existing installs. A {@code false} stored after this runs is a choice
 *       made about the working feature and is left alone.</li>
 * </ul>
 */
public final class MeowDefaults {

    /** The migration schema this build writes. {@code PreferenceConfiguration} gates on it. */
    public static final int SCHEMA_VERSION = 2;

    private MeowDefaults() {
    }

    /**
     * Writes the meow defaults an install at schema {@code migratedTo} has not had yet.
     * Writes into {@code editor} only; the caller applies it.
     */
    public static void apply(int migratedTo, SharedPreferences.Editor editor) {
        if (migratedTo < 2) {
            editor.putBoolean(ViewportPreference.KEY, true);
            editor.putBoolean(CursorFollowPreference.KEY, true);
            editor.putBoolean(AutoBitratePreference.KEY, true);
        }
    }
}
