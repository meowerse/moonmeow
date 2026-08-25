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
 * <h2>Why the default is off</h2>
 * Three reasons, in order of weight:
 * <ol>
 *   <li><b>Upgrade safety.</b> An install that works today must keep behaving identically
 *       until the user asks for something different. This feature changes what the host
 *       encodes, which is the least reversible thing a client preference can do.</li>
 *   <li><b>The host half does not exist in the wild.</b> No released host implements the
 *       viewport extension; stock Sunshine and every current sunmeow answer "unsupported".
 *       A default-on preference would, today, be a default-on no-op that only costs a
 *       control-stream round trip at every stream start.</li>
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
