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

    /** @return the remembered bitrate for {@code hostUuid}, or 0 */
    public int get(String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) {
            return 0;
        }
        return Math.max(0, prefs.getInt(KEY_PREFIX + hostUuid, 0));
    }

    /** Remembers {@code kbps} for {@code hostUuid}; ignores nonsense. */
    public void put(String hostUuid, int kbps) {
        if (hostUuid == null || hostUuid.isEmpty() || kbps <= 0) {
            return;
        }
        prefs.edit().putInt(KEY_PREFIX + hostUuid, kbps).apply();
    }
}
