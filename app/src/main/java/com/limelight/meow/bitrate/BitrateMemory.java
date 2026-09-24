package com.limelight.meow.bitrate;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The last stable bitrate per host, remembered across sessions. A private preferences file of
 * its own, so it is never part of a profile and never shows up in Settings.
 */
public final class BitrateMemory {

    static final String FILE = "meow_bitrate_memory";
    private static final String KEY_PREFIX = "stable_kbps_";

    private final SharedPreferences prefs;

    public BitrateMemory(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Metered and unmetered sessions are remembered apart: they have different ceilings, and a
     * cellular session's settling point must not start the next Wi-Fi one.
     */
    private static String key(String hostUuid, boolean metered) {
        return KEY_PREFIX + hostUuid + (metered ? "_metered" : "");
    }

    /** @return the remembered bitrate for {@code hostUuid} on this kind of network, or 0 */
    public int get(String hostUuid, boolean metered) {
        if (hostUuid == null || hostUuid.isEmpty()) {
            return 0;
        }
        return Math.max(0, prefs.getInt(key(hostUuid, metered), 0));
    }

    /** Remembers {@code kbps}; ignores nonsense. */
    public void put(String hostUuid, boolean metered, int kbps) {
        if (hostUuid == null || hostUuid.isEmpty() || kbps <= 0) {
            return;
        }
        prefs.edit().putInt(key(hostUuid, metered), kbps).apply();
    }

    /** Forgets the remembered bitrate, so the next session starts at the user's setting. */
    public void clear(String hostUuid, boolean metered) {
        if (hostUuid == null || hostUuid.isEmpty()) {
            return;
        }
        prefs.edit().remove(key(hostUuid, metered)).apply();
    }
}
