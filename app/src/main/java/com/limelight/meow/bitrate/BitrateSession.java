package com.limelight.meow.bitrate;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.meow.stream.MeowStreamBridge;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Automatic bitrate for one {@code Game}: picks the bitrate to negotiate, runs the 1 Hz
 * receiver report on its own thread once the host is proven, shows what the host applied, and
 * remembers where the session settled. The decisions live in {@link StartingBitrate} and
 * {@link ReceiverReporter}; this class is the Android and JNI glue.
 *
 * <p>With the preference off the negotiated bitrate is exactly the user's setting and reports
 * still go to a proven host, flagged {@code auto_bitrate = 0}: the explicit way to tell a host
 * whose own adaptation is on not to adapt for this client.
 *
 * <h2>Threads</h2>
 * Reports run on a private {@link HandlerThread}: {@code LiSendReceiverReport} can block on
 * ENet backpressure, which the UI thread must never see. The host's APPLIED arrives on the
 * library's callback thread and is posted onto the same thread. {@link #onStreamStopped} runs
 * on {@code Game}'s teardown worker and blocks, bounded, until no report can still be in flight,
 * because sending after {@code LiStopConnection} is a use-after-free.
 */
public final class BitrateSession implements MeowStreamBridge.BitrateListener {

    static final long STOP_DRAIN_TIMEOUT_MS = 250L;

    private final boolean automatic;
    private final String hostUuid;
    private final BitrateMemory memory;
    private final HandlerThread thread;
    private final Handler handler;
    private final ReceiverReporter reporter;

    private volatile int configuredKbps;
    private volatile int negotiatedKbps;

    private final Runnable tick = this::onTick;
    private final Runnable start = this::onHostProven;

    /** The bitrate to negotiate: {@code session.negotiate()} or the setting when there is none. */
    public static int negotiate(BitrateSession session, int configuredKbps) {
        return session != null ? session.negotiate(configuredKbps) : configuredKbps;
    }

    public BitrateSession(Context context, String hostUuid, boolean automatic) {
        this(context, hostUuid, automatic, null, null);
    }

    /** Test seam: inject the wire and the statistics. */
    BitrateSession(Context context, String hostUuid, boolean automatic,
                   ReceiverReporter.Sender sender, ReceiverReporter.Stats stats) {
        this.automatic = automatic;
        this.hostUuid = hostUuid;
        this.memory = new BitrateMemory(context);
        this.thread = new HandlerThread("meow-bitrate");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        this.reporter = new ReceiverReporter(
                sender != null ? sender : BitrateSession::sendNative,
                stats != null ? stats : new NativeStats());
    }

    public boolean isAutomatic() {
        return automatic;
    }

    /**
     * Records the user's setting as the ceiling and returns the bitrate to start at: the last
     * stable bitrate on this host within bounds, or the setting.
     */
    public int negotiate(int configuredKbps) {
        this.configuredKbps = configuredKbps;
        int start = StartingBitrate.choose(automatic, configuredKbps, memory.get(hostUuid));
        this.negotiatedKbps = start;
        if (start != configuredKbps) {
            LimeLog.info("Bitrate: starting at " + start + " kbps (last stable on this host), "
                    + "ceiling " + configuredKbps + " kbps");
        }
        return start;
    }

    public int negotiatedKbps() {
        return negotiatedKbps;
    }

    /** Runs once the host has proven it is a meow host. Any thread. */
    public Runnable startTask() {
        return start;
    }

    /** UI thread, when the connection is up. */
    public void onStreamStarted() {
        DecodeTimeWindow.reset();
        BitrateOverlay.clear();
        MeowStreamBridge.setBitrateListener(this);
    }

    private void onHostProven() {
        handler.post(() -> {
            reporter.start(SystemClock.uptimeMillis(), automatic, configuredKbps);
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, ReceiverReporter.INTERVAL_MS);
        });
    }

    private void onTick() {
        if (reporter.tick(SystemClock.uptimeMillis())) {
            handler.postDelayed(tick, ReceiverReporter.INTERVAL_MS);
        }
    }

    /** The library's callback thread. */
    @Override
    public void onBitrateApplied(final int kbps) {
        BitrateOverlay.publish(kbps, automatic);
        handler.post(() -> reporter.onApplied(kbps, SystemClock.uptimeMillis()));
    }

    /**
     * Stops reporting and remembers where the session settled. Blocks, bounded, so no report is
     * in flight when the caller goes on to {@code LiStopConnection}. Any thread but this
     * session's own.
     */
    public void onStreamStopped() {
        MeowStreamBridge.clearBitrateListener(this);
        BitrateOverlay.clear();
        final CountDownLatch drained = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                handler.removeCallbacks(tick);
                reporter.stop(SystemClock.uptimeMillis());
                if (automatic) {
                    memory.put(hostUuid, reporter.stableKbps());
                }
            } finally {
                drained.countDown();
            }
        });
        if (Looper.myLooper() == handler.getLooper()) {
            return;
        }
        if (posted) {
            try {
                if (!drained.await(STOP_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    LimeLog.warning("Bitrate: report thread did not drain before teardown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Quits the thread. Idempotent; safe whether or not a stream ever started. */
    public void release() {
        MeowStreamBridge.clearBitrateListener(this);
        BitrateOverlay.clear();
        thread.quitSafely();
    }

    /** The reporter, for tests. Only touch it on this session's thread. */
    ReceiverReporter reporter() {
        return reporter;
    }

    /** This session's thread, for tests. */
    Looper looper() {
        return thread.getLooper();
    }

    private static int sendNative(ReceiverReport r) {
        return MeowStreamBridge.sendReport(r.autoBitrate, r.intervalMs, r.receivedKbps,
                r.lossPermille, r.rttMs, r.rttVarianceMs, r.decodeQueueFrames, r.avgDecodeMs,
                r.maxKbps);
    }

    /** The library and the renderer, read on the report thread. */
    private static final class NativeStats implements ReceiverReporter.Stats {
        @Override
        public boolean readCounters(int[] out) {
            return MeowStreamBridge.readVideoNetworkStats(out);
        }

        @Override
        public long rttInfo() {
            return MoonBridge.getEstimatedRttInfo();
        }

        @Override
        public int decodeQueueFrames() {
            return MoonBridge.getPendingVideoFrames();
        }

        @Override
        public int averageDecodeMs() {
            return DecodeTimeWindow.averageMs();
        }
    }
}
