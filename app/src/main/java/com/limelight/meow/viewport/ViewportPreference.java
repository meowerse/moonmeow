package com.limelight.meow.viewport;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.profiles.ProfilesManager;

/**
 * Reads the viewport-following preference.
 *
 * <p>Kept out of {@code PreferenceConfiguration} on purpose: that class is upstream and this
 * is a moonmeow feature, so reading the key here means the upstream file is not touched at
 * all. It goes through the same profile-overlaying store, so a per-profile override behaves
 * like every other preference.
 *
 * <p><b>Read once, at stream start.</b> {@code Game.onCreate} reads this and there is no
 * route from the in-stream menu back to Settings, so toggling it applies to the next stream
 * rather than the running one. {@link ViewportReporter#setEnabled} exists and works, but
 * nothing currently calls it mid-session.
 *
 * <h2>Why the default is on, and what that cost</h2>
 * It was off for three reasons. Two no longer hold; the third was accepted deliberately.
 * <ol>
 *   <li><b>Upgrade safety — now handled, not ignored.</b> The original objection was that an
 *       install which works today must keep behaving identically. That is still right, which
 *       is why flipping this constant is not the whole change:
 *       {@code PreferenceConfiguration.applyDefaultsMigration()} turns it on exactly once,
 *       version-gated, and the switch keeps working afterwards. A stored {@code false} is
 *       indistinguishable from an inherited one, so a user who had deliberately turned it
 *       off is flipped once and must turn it off again. That is the accepted cost of
 *       changing a boolean default, and it is the reason the migration is version-gated
 *       rather than run on every launch.</li>
 *   <li><b>No longer true that almost no host implements it.</b> sunmeow does, and it is the
 *       host this fork exists to talk to. The probe described above is what makes default-on
 *       safe anyway: {@link ViewportReporter} latches the feature off for the session when no
 *       echo arrives, so a stock-Sunshine user pays two control packets at stream start and
 *       nothing else.</li>
 *   <li><b>Cropping is still opinionated — this is still a setting.</b> The user gets detail
 *       in the region they zoomed into and nothing outside it, which is wrong for someone who
 *       zooms in to read one thing while watching another. Defaulting it on says the common
 *       case is worth more than the uncommon one; it does not say the preference should go
 *       away, and it has not.</li>
 * </ol>
 */
public final class ViewportPreference {

    public static final String KEY = "checkbox_enable_viewport_follow";
    // MEOW-TOUCH(defaults): on by default -- see the class comment for why, and
    // PreferenceConfiguration.applyDefaultsMigration() for how existing installs get it.
    // Must stay in step with the android:defaultValue of checkbox_enable_viewport_follow
    // in res/xml/preferences.xml.
    public static final boolean DEFAULT = true;

    private ViewportPreference() {
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
            // A preference read must never be able to take the stream down with it.
            return DEFAULT;
        }
    }
}
