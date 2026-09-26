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
 * <p>Each kind of activity has its own limit ({@link #INPUT}, {@link #VIEW}, {@link #PAN}).
 * With one shared limit, a trackpad swipe starved the follow step: Choreographer runs input
 * before animation callbacks in every frame, so the input line took every slot and the log of
 * an emulator run showed no "follow pan" at all while the view was following.
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

    /** A relative move the controller did or did not take over. */
    public static final int INPUT = 0;
    /** A zoom or resize and what the controller did about it. */
    public static final int VIEW = 1;
    /** A follow step. */
    public static final int PAN = 2;

    private final Sink sink;
    private final long[] lastActivityMs = {Long.MIN_VALUE / 2, Long.MIN_VALUE / 2,
            Long.MIN_VALUE / 2};
    private final int[] suppressed = new int[3];

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

    /** Whether a line of this kind may be written now. Call before formatting it. */
    public boolean activityAllowed(int kind, long nowMs) {
        if (nowMs - lastActivityMs[kind] < ACTIVITY_INTERVAL_MS) {
            suppressed[kind]++;
            return false;
        }
        lastActivityMs[kind] = nowMs;
        return true;
    }

    /** A line of this kind; call only after {@link #activityAllowed} said yes. */
    public void activity(int kind, String line) {
        if (suppressed[kind] > 0) {
            sink.write(line + " (+" + suppressed[kind] + " suppressed)");
            suppressed[kind] = 0;
        } else {
            sink.write(line);
        }
    }
}
