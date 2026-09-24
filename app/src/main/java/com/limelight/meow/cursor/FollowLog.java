package com.limelight.meow.cursor;

import android.util.Log;

/**
 * Cursor-follow diagnostics under one logcat tag, in release builds too, so a device session
 * can be read back with {@code adb logcat -s MeowFollow}.
 *
 * <p>Two kinds of line. <b>State</b> lines (stream start, host proven, first host report, touch
 * mode) are rare and always written. <b>Activity</b> lines (a zoom change and what the
 * controller did about it, a follow pan, a move it did or did not take over) happen per event,
 * so they are rate limited to one per {@link #ACTIVITY_INTERVAL_MS} -- the first of a burst is
 * written, the rest are counted and the count is reported on the next line that gets through.
 * Nothing is formatted for a line that is dropped.
 *
 * <p>{@code app/proguard-rules.pro} has no rule stripping {@code android.util.Log}, so these
 * calls survive R8 ({@code FollowLogTest.releaseBuildsKeepTheseLines}).
 */
public final class FollowLog {

    public static final String TAG = "MeowFollow";
    static final long ACTIVITY_INTERVAL_MS = 250L;

    /** Where lines go. Production: {@code Log.i}. */
    interface Sink {
        void write(String line);
    }

    private final Sink sink;
    private long lastActivityMs = Long.MIN_VALUE / 2;
    private int suppressed;

    public FollowLog() {
        this(line -> Log.i(TAG, line));
    }

    FollowLog(Sink sink) {
        this.sink = sink;
    }

    /** A rare state change: always written. */
    public void state(String line) {
        sink.write(line);
    }

    /** Whether an activity line may be written now. Call before formatting it. */
    public boolean activityAllowed(long nowMs) {
        if (nowMs - lastActivityMs < ACTIVITY_INTERVAL_MS) {
            suppressed++;
            return false;
        }
        lastActivityMs = nowMs;
        return true;
    }

    /** An activity line; call only after {@link #activityAllowed} said yes. */
    public void activity(String line) {
        if (suppressed > 0) {
            sink.write(line + " (+" + suppressed + " suppressed)");
            suppressed = 0;
        } else {
            sink.write(line);
        }
    }
}
