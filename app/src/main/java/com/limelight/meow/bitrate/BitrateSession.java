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
    private volatile boolean metered;

    /**
     * Set when the stream stops, cleared when the next one starts. A host-proven signal that
     * was already queued on the viewport thread when teardown began must not start reports
     * after this session drained: the next send would race {@code LiStopConnection}.
     */
    private volatile boolean stopped = true;

    private final Runnable tick = this::onTick;
    private final Runnable start = this::onHostProven;

    /** The bitrate to negotiate: {@code session.negotiate()} or the setting when there is none. */
    public static int negotiate(BitrateSession session, boolean metered, int configuredKbps) {
        return session != null ? session.negotiate(metered, configuredKbps) : configuredKbps;
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
    public int negotiate(boolean metered, int configuredKbps) {
        this.metered = metered;
        this.configuredKbps = configuredKbps;
        int start = StartingBitrate.choose(automatic, configuredKbps, memory.get(hostUuid, metered));
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

    /**
     * The line to show on {@code CONN_STATUS_POOR} instead of "slow connection, lower the
     * bitrate", or null to keep that advice. When automatic bitrate is on and the host has
     * shown it adapts (an APPLIED arrived), lowering the bitrate is already happening, so the
     * advice is wrong; a short "adapting" line with the current bitrate says what is going on
     * without asking the user to do anything. Any thread.
     */
    public static String poorConnectionText(BitrateSession session, Context context) {
        if (session == null || !session.automatic || context == null) {
            return null;
        }
        int kbps = session.hostAdaptingKbps;
        if (kbps <= 0) {
            return null;
        }
        return context.getString(com.limelight.R.string.meow_bitrate_adapting, kbps / 1000f);
    }

    /** The last APPLIED bitrate this stream, or 0 before the host has adapted. */
    private volatile int hostAdaptingKbps;

    /** Runs once the host has proven it is a meow host. Any thread. */
    public Runnable startTask() {
        return start;
    }

    /** UI thread, when the connection is up. */
    public void onStreamStarted() {
        DecodeTimeWindow.reset();
        BitrateOverlay.clear();
        stopped = false;
        hostAdaptingKbps = 0;
        MeowStreamBridge.setBitrateListener(this);
    }

    private void onHostProven() {
        if (stopped) {
            return;
        }
        handler.post(() -> {
            if (stopped) {
                return;
            }
            reporter.start(SystemClock.uptimeMillis(), automatic, configuredKbps);
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, ReceiverReporter.INTERVAL_MS);
        });
    }

    private void onTick() {
        if (!stopped && reporter.tick(SystemClock.uptimeMillis())) {
            handler.postDelayed(tick, ReceiverReporter.INTERVAL_MS);
        }
    }

    /** The library's callback thread. */
    @Override
    public void onBitrateApplied(final int kbps) {
        hostAdaptingKbps = kbps;
        BitrateOverlay.publish(kbps, automatic);
        handler.post(() -> reporter.onApplied(kbps, SystemClock.uptimeMillis()));
    }

    /**
     * Stops reporting and remembers where the session settled. Blocks, bounded, so no report is
     * in flight when the caller goes on to {@code LiStopConnection}. Any thread but this
     * session's own.
     */
    public void onStreamStopped() {
        stopped = true;
        MeowStreamBridge.clearBitrateListener(this);
        BitrateOverlay.clear();
        final CountDownLatch drained = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                handler.removeCallbacks(tick);
                reporter.stop(SystemClock.uptimeMillis());
                if (automatic && reporter.hostNeverAdapted()) {
                    // A remembered start would cap every later session below the user's
                    // setting with nothing to climb back: forget it.
                    memory.clear(hostUuid, metered);
                } else if (automatic) {
                    memory.put(hostUuid, metered, reporter.stableKbps());
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
