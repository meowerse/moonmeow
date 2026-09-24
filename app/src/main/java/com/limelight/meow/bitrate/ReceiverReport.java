package com.limelight.meow.bitrate;

/**
 * One 0x3005 receiver report, computed from two snapshots of the library's free-running
 * counters one interval apart. Mutable and reused, so building a report allocates nothing.
 *
 * <p>The arithmetic is the one {@code docs/meow-protocol.md} prescribes. The counters are uint32
 * values that wrap, carried in Java ints, so every difference is taken modulo 2^32; the
 * received-vs-expected difference is then read as a signed 32-bit value and clamped at 0,
 * because duplicates and packets straddling a snapshot can make it slightly negative.
 */
public final class ReceiverReport {

    public boolean autoBitrate;
    public int intervalMs;
    public int receivedKbps;
    public int lossPermille;
    public int rttMs;
    public int rttVarianceMs;
    public int decodeQueueFrames;
    public int avgDecodeMs;
    public int maxKbps;

    /** Indices into the counter snapshots from {@code MeowStreamBridge.readVideoNetworkStats}. */
    public static final int PACKETS_RECEIVED = 0;
    public static final int PACKETS_EXPECTED = 1;
    public static final int BYTES_RECEIVED = 2;

    /**
     * Fills the network fields from two counter snapshots.
     *
     * @param previous   counters at the start of the interval
     * @param current    counters at the end
     * @param intervalMs the interval's length; non-positive leaves goodput at 0
     */
    public void setNetwork(int[] previous, int[] current, int intervalMs) {
        this.intervalMs = Math.max(0, intervalMs);
        long expected = (current[PACKETS_EXPECTED] - previous[PACKETS_EXPECTED]) & 0xFFFFFFFFL;
        long received = (current[PACKETS_RECEIVED] - previous[PACKETS_RECEIVED]) & 0xFFFFFFFFL;
        long bytes = (current[BYTES_RECEIVED] - previous[BYTES_RECEIVED]) & 0xFFFFFFFFL;

        int lost = Math.max(0, (int) (expected - received));
        lossPermille = expected > 0 ? (int) Math.min(1000L, lost * 1000L / expected) : 0;
        // bytes * 8 bits over ms is kilobits per second.
        receivedKbps = intervalMs > 0 ? (int) Math.min(Integer.MAX_VALUE, bytes * 8L / intervalMs) : 0;
    }

    /** {@code MoonBridge.getEstimatedRttInfo()} when ENet has no estimate yet. */
    public static final long RTT_UNKNOWN = -1L;

    /**
     * Splits {@code LiGetEstimatedRttInfo()}: RTT in the high word, variance in the low.
     *
     * <p>The wire has no "unknown" value, and a 0 ms RTT would become the host's windowed
     * minimum RTT baseline (N4), after which every real RTT looks like queueing delay. So an
     * unknown estimate keeps the last known one instead.
     *
     * @return whether {@link #rttMs} now holds a real estimate (this one or an earlier one)
     */
    public boolean setRtt(long rttInfo) {
        if (rttInfo != RTT_UNKNOWN) {
            rttMs = (int) (rttInfo >>> 32);
            rttVarianceMs = (int) rttInfo;
            rttKnown = true;
        }
        return rttKnown;
    }

    private boolean rttKnown;
}
