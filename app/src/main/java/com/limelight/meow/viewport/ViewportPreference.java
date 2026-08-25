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
 * <h2>Why the default is off</h2>
 * Three reasons, in order of weight:
 * <ol>
 *   <li><b>Upgrade safety.</b> An install that works today must keep behaving identically
 *       until the user asks for something different. This feature changes what the host
 *       encodes, which is the least reversible thing a client preference can do.</li>
 *   <li><b>Almost no host implements it.</b> Only sunmeow does, and only with
 *       {@code meow_viewport_following} turned on. Stock Sunshine does not — and it does
 *       not <em>refuse</em> either: the library puts the packet on the wire happily and
 *       stock Sunshine ignores it. That is why {@link ViewportReporter} probes for the
 *       host's echo and latches the feature off when none arrives, rather than trusting a
 *       return code. A default-on preference would, today, mean two wasted control packets
 *       at the start of nearly every stream.</li>
 *   <li><b>Cropping is a visible, opinionated change.</b> When a host does honour it, the
 *       user gets detail in the region they zoomed into and nothing outside it. That is
 *       exactly what was asked for on a metered link, and exactly wrong for someone who
 *       zooms in to read one thing while watching another. It is a choice, so it is a
 *       setting.</li>
 * </ol>
 */
public final class ViewportPreference {

    public static final String KEY = "checkbox_enable_viewport_follow";
    public static final boolean DEFAULT = false;

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
