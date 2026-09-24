package com.limelight.meow.bitrate;

/**
 * The client half of automatic bitrate: a 0x3005 receiver report every second, and the host's
 * answers. Plain Java and single-threaded; {@link BitrateSession} runs it on its own thread.
 *
 * <h2>Only to a host that has proven it listens</h2>
 * Reports are opt-in extensions a stock host would only log, so {@link #start} is called once
 * the host has answered a viewport probe. A meow host answers the first report with an APPLIED
 * message; if none has arrived after {@value #GIVE_UP_AFTER_REPORTS} reports, the host does not
 * adapt and reporting stops for the session, as the protocol document asks.
 *
 * <h2>What "stable" means</h2>
 * The bitrate remembered for the next session is the last APPLIED value the host held for at
 * least {@value #STABLE_MS} ms. A value the controller passed through on its way somewhere else
 * is not a place worth starting from.
 */
public final class ReceiverReporter {

    /** The wire. Returns the {@code LiSendReceiverReport} result. */
    public interface Sender {
        int send(ReceiverReport report);
    }

    /** Where the numbers come from. */
    public interface Stats {
        /** Fills {packets received, packets expected, bytes received}; false if unavailable. */
        boolean readCounters(int[] out);

        /** {@code LiGetEstimatedRttInfo()}. */
        long rttInfo();

        /** {@code LiGetPendingVideoFrames()}. */
        int decodeQueueFrames();

        /** The renderer's average decode time over its last window. */
        int averageDecodeMs();
    }

    public static final long INTERVAL_MS = 1000L;
    static final int GIVE_UP_AFTER_REPORTS = 5;
    static final long STABLE_MS = 10_000L;

    /** {@code LiSendReceiverReport}: this host generation has no packet type for it. */
    private static final int LI_NO_PACKET_TYPE = -3;
    /** The native symbol did not bind. */
    private static final int LI_LIBRARY_UNAVAILABLE = -100;

    private final Sender sender;
    private final Stats stats;
    private final ReceiverReport report = new ReceiverReport();

    private int[] previous = new int[3];
    private int[] current = new int[3];
    private boolean running;
    private boolean haveBaseline;
    private long lastTickMs;
    private int reportsSent;
    private boolean appliedEver;

    private int appliedKbps;
    private long appliedSinceMs;
    private int stableKbps;

    public ReceiverReporter(Sender sender, Stats stats) {
        if (sender == null || stats == null) {
            throw new IllegalArgumentException("sender and stats are required");
        }
        this.sender = sender;
        this.stats = stats;
    }

    /**
     * Begins reporting. The first report goes out one {@link #INTERVAL_MS} later, when there
     * is an interval to describe.
     *
     * @param automatic whether the user wants the host to adapt (the report's flag)
     * @param maxKbps   the user's configured bitrate, the ceiling the host may climb to
     */
    public void start(long nowMs, boolean automatic, int maxKbps) {
        running = true;
        report.autoBitrate = automatic;
        report.maxKbps = Math.max(0, maxKbps);
        reportsSent = 0;
        appliedEver = false;
        lastTickMs = nowMs;
        haveBaseline = stats.readCounters(previous);
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * One interval has passed: build and send a report.
     *
     * @return whether reporting continues
     */
    public boolean tick(long nowMs) {
        updateStable(nowMs);
        if (!running) {
            return false;
        }
        int intervalMs = (int) Math.max(1L, Math.min(65535L, nowMs - lastTickMs));
        lastTickMs = nowMs;
        if (!stats.readCounters(current)) {
            return true;
        }
        if (!haveBaseline) {
            swap();
            haveBaseline = true;
            return true;
        }

        report.setNetwork(previous, current, intervalMs);
        report.setRtt(stats.rttInfo());
        report.decodeQueueFrames = Math.max(0, stats.decodeQueueFrames());
        report.avgDecodeMs = Math.max(0, stats.averageDecodeMs());
        swap();

        int result = sender.send(report);
        if (result == LI_NO_PACKET_TYPE || result == LI_LIBRARY_UNAVAILABLE) {
            running = false;
            return false;
        }
        reportsSent++;
        if (!appliedEver && reportsSent >= GIVE_UP_AFTER_REPORTS) {
            // The host is not adapting; stop talking to it about bitrate.
            running = false;
        }
        return running;
    }

    /** The host changed (or confirmed) its encoder bitrate. */
    public void onApplied(int kbps, long nowMs) {
        if (kbps <= 0) {
            return;
        }
        appliedEver = true;
        updateStable(nowMs);
        if (kbps != appliedKbps) {
            appliedKbps = kbps;
            appliedSinceMs = nowMs;
        }
    }

    /** Stops reporting. The stable value is settled as of {@code nowMs}. */
    public void stop(long nowMs) {
        updateStable(nowMs);
        running = false;
    }

    /** The bitrate the host last said it applied, or 0 if it never has. */
    public int appliedKbps() {
        return appliedKbps;
    }

    /** The last applied bitrate held for {@link #STABLE_MS}, or 0 if none has been. */
    public int stableKbps() {
        return stableKbps;
    }

    /** The report most recently sent, for tests. */
    ReceiverReport lastReport() {
        return report;
    }

    private void updateStable(long nowMs) {
        if (appliedKbps > 0 && nowMs - appliedSinceMs >= STABLE_MS) {
            stableKbps = appliedKbps;
        }
    }

    private void swap() {
        int[] t = previous;
        previous = current;
        current = t;
    }
}
