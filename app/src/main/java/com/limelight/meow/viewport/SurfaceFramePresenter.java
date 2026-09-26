package com.limelight.meow.viewport;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.RequiresApi;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Puts each decoded frame on screen <em>together with</em> the crop transform that belongs to
 * it, so a crop change lands on exactly the frame that carries it. API 33+.
 *
 * <h2>The defect</h2>
 * While the host streams a crop, the decoded picture has to be shown shifted and scaled by
 * the crop's mapping ({@link ViewComposition}). The first implementation put that transform
 * on the {@code SurfaceView} itself. A view property reaches SurfaceFlinger with the UI
 * thread's next drawn frame; a decoded buffer reaches it directly from the codec. The two are
 * never latched together, so at every crop change the new picture showed under the old
 * transform, or the old picture under the new one, for one or two display frames: during a
 * continuous pan, a visible jump at every crop update (the owner's report, 2026-09-26).
 *
 * <h2>What this does</h2>
 * The decoder renders into an {@link ImageReader} instead of the view's surface. For every
 * frame this thread receives, it looks up which host frame it is ({@link FrameStamps},
 * {@link DecodedFrameGate#frameForPts}), which crop that frame was encoded with
 * ({@link CropTimeline}), and hands SurfaceFlinger the buffer <em>and</em> that crop's layer
 * geometry ({@link FrameLayerGeometry}) in one {@link SurfaceControl.Transaction}, on a child
 * layer of the stream view's surface. A transaction is applied atomically, so the pixels and
 * their transform cannot be split. The view itself keeps the user's logical transform (pinch
 * and pan) and draws it with the UI as always; the layer composes under it.
 *
 * <p>The cost: an image reader hop instead of the codec queueing straight to the view's
 * buffer queue. The codec's queue to the view is a transaction too (BLAST), so the path has
 * the same number of steps; the added latency is this thread's wake-up, logged as
 * "release to apply" under {@code MeowFrame} every {@value #LOG_INTERVAL_MS} ms.
 *
 * <p>Not used for HDR streams (the image path drops the codec's HDR metadata) or below API 33
 * (no {@code Transaction.setBuffer}); those keep the view-property swap.
 *
 * <p>Also drops a frame older than the one on screen: the renderer can release a queued
 * frame from its Choreographer thread after a newer one from its own, which would show the
 * picture stepping back for a frame.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class SurfaceFramePresenter implements SurfaceHolder.Callback,
        ImageReader.OnImageAvailableListener {

    private static final String TAG = "MeowFrame";
    static final long LOG_INTERVAL_MS = 5000L;
    /** Images the reader lets this side hold: one on screen, one queued, one in hand. */
    static final int MAX_IMAGES = 4;
    private static final long DETACH_TIMEOUT_MS = 200L;
    /** Latency histogram: 0.25 ms buckets up to 50 ms. */
    private static final int BUCKET_US = 250;
    private static final int BUCKETS = 200;

    /** Whether this device can present this way at all. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private final SurfaceView view;
    private final CropTimeline timeline;
    private final HandlerThread thread;
    private final Handler handler;
    private final ImageReader reader;

    /** Presenter thread only. */
    private final SurfaceControl.Transaction frameTransaction = new SurfaceControl.Transaction();
    private final Slot[] slots = new Slot[MAX_IMAGES + 1];
    private final float[] geometry = new float[4];
    private final FrameSelector selector;

    /** Written on the UI thread, read on the presenter thread. */
    private volatile SurfaceControl layer;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private volatile int streamWidth;
    private volatile int streamHeight;
    private volatile float frameRate;
    private volatile boolean released;

    // Statistics, presenter thread only.
    private final int[] latencyBuckets = new int[BUCKETS + 1];
    private long statsStartMs;
    private int presented;
    private long latencyMaxUs;

    private final class Slot implements Consumer<SyncFence>, Runnable {
        Image image;
        SyncFence releaseFence;

        /** SurfaceFlinger no longer needs the buffer. Any thread. */
        @Override
        public void accept(SyncFence fence) {
            releaseFence = fence;
            if (!handler.post(this)) {
                // The thread is gone (released): nothing reads the image any more.
                run();
            }
        }

        /** Presenter thread: give the buffer back to the codec. */
        @Override
        public void run() {
            Image held = image;
            SyncFence fence = releaseFence;
            image = null;
            releaseFence = null;
            if (held == null) {
                return;
            }
            boolean handedOver = false;
            if (fence != null && fence.isValid()) {
                try {
                    // The codec must not write the buffer until the display stops reading it.
                    held.setFence(fence);
                    handedOver = true;
                } catch (Exception ignored) {
                    // Closed or not settable: close it without.
                }
            }
            if (!handedOver && fence != null) {
                fence.close();
            }
            held.close();
            if (!released) {
                // A slot is free again: frames that arrived while every slot was taken wait.
                onImageAvailable(reader);
            }
        }
    }

    /**
     * @param view          the stream view; the layer is a child of its surface
     * @param timeline      the crops, written by {@link ViewportCompositor}
     * @param bufferWidth   the stream size, for the reader's default buffer size
     * @param bufferHeight
     */
    @SuppressLint("WrongConstant")
    public SurfaceFramePresenter(SurfaceView view, CropTimeline timeline,
                                 int bufferWidth, int bufferHeight) {
        this.view = view;
        this.timeline = timeline;
        this.selector = new FrameSelector(timeline);
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }
        thread = new HandlerThread("meow-present", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(Math.max(1, bufferWidth), Math.max(1, bufferHeight),
                ImageFormat.PRIVATE, MAX_IMAGES,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_COMPOSER_OVERLAY);
        reader.setOnImageAvailableListener(this, handler);
        if (Build.VERSION.SDK_INT >= 37) {
            // As the view's own surface: no producer throttling on the video path.
            reader.getSurface().setProducerThrottlingEnabled(false);
        }

        SurfaceHolder holder = view.getHolder();
        holder.addCallback(this);
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            Rect frame = holder.getSurfaceFrame();
            surfaceWidth = frame.width();
            surfaceHeight = frame.height();
            attach();
        }
    }

    /** What the decoder renders into. */
    public Surface surface() {
        return reader.getSurface();
    }

    /** The negotiated stream size. UI thread. */
    public void onStreamStarted(int width, int height) {
        streamWidth = width;
        streamHeight = height;
        handler.post(selector::reset);
    }

    /** The frame rate the stream view's surface was given, carried to the layer. UI thread. */
    public void setFrameRate(float rate) {
        frameRate = rate;
        SurfaceControl target = layer;
        if (target != null && rate > 0f) {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setFrameRate(target, rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
            t.apply();
        }
    }

    /**
     * Stops presenting. The layer is removed at once; the reader stays open a little longer so
     * a decoder that is still stopping never renders into a closed surface. Any thread.
     */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        view.post(() -> view.getHolder().removeCallback(this));
        detach();
        handler.postDelayed(() -> {
            for (Slot slot : slots) {
                slot.run();
            }
            reader.close();
            thread.quitSafely();
        }, 2000L);
    }

    // ---- SurfaceHolder.Callback, UI thread ---------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        surfaceWidth = frame.width();
        surfaceHeight = frame.height();
        attach();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        detach();
    }

    /** UI thread: a child layer of the view's surface, above its own (empty) content. */
    private void attach() {
        if (released || layer != null) {
            return;
        }
        SurfaceControl parent = view.getSurfaceControl();
        if (parent == null || !parent.isValid()) {
            return;
        }
        SurfaceControl child = new SurfaceControl.Builder()
                .setName("moonmeow-video")
                .setParent(parent)
                .setFormat(PixelFormat.OPAQUE)
                .setOpaque(true)
                .setHidden(false)
                .build();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.setLayer(child, 1).setVisibility(child, true);
        float rate = frameRate;
        if (rate > 0f) {
            t.setFrameRate(child, rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
        }
        t.apply();
        layer = child;
    }

    /**
     * Removes the layer before the view's surface goes away. The presenter thread may be in
     * the middle of a frame on it, so the removal runs there, and this waits for it (bounded).
     */
    private void detach() {
        final SurfaceControl old = layer;
        layer = null;
        if (old == null) {
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                t.reparent(old, null);
                t.apply();
                old.release();
            } finally {
                done.countDown();
            }
        });
        if (posted) {
            try {
                done.await(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- presenter thread --------------------------------------------------------------

    @Override
    public void onImageAvailable(ImageReader source) {
        if (released) {
            return;
        }
        Slot slot = freeSlot();
        if (slot == null) {
            // Every slot is on screen or queued; a slot's release brings us back here.
            return;
        }
        Image image;
        try {
            image = source.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (image == null) {
            return;
        }
        SurfaceControl target = layer;
        if (target == null) {
            image.close();
            return;
        }

        FrameMapping mapping = selector.select(image.getTimestamp());
        if (mapping == null) {
            // Older than the frame on screen.
            image.close();
            return;
        }
        long releasedAt = selector.releasedAt();

        Rect crop = image.getCropRect();
        float[] g = FrameLayerGeometry.layer(mapping, surfaceWidth, surfaceHeight,
                streamWidth > 0 ? streamWidth : crop.width(),
                streamHeight > 0 ? streamHeight : crop.height(),
                crop.left, crop.top, crop.width(), crop.height(), geometry);
        HardwareBuffer buffer = image.getHardwareBuffer();
        if (g == null || buffer == null) {
            image.close();
            if (buffer != null) {
                buffer.close();
            }
            return;
        }
        SyncFence acquire = null;
        try {
            SyncFence fence = image.getFence();
            acquire = fence.isValid() ? fence : null;
        } catch (java.io.IOException e) {
            // No fence to wait on: the buffer is complete as far as the reader knows.
        }
        slot.image = image;
        try {
            frameTransaction
                    .setBuffer(target, buffer, acquire, slot)
                    .setCrop(target, crop)
                    .setPosition(target, g[FrameLayerGeometry.X], g[FrameLayerGeometry.Y])
                    .setScale(target, g[FrameLayerGeometry.SCALE_X],
                            g[FrameLayerGeometry.SCALE_Y])
                    .setDataSpace(target, image.getDataSpace())
                    .apply();
        } catch (RuntimeException e) {
            // The layer went away between the read and the apply (surface destroyed).
            slot.image = null;
            image.close();
            Log.w(TAG, "frame not presented: " + e);
        } finally {
            buffer.close();
        }
        record(releasedAt);
    }

    private Slot freeSlot() {
        for (Slot slot : slots) {
            if (slot.image == null) {
                return slot;
            }
        }
        return null;
    }

    private void record(long releasedAt) {
        presented++;
        long now = System.nanoTime();
        if (releasedAt != FrameStamps.NONE) {
            long us = Math.max(0L, (now - releasedAt) / 1000L);
            latencyMaxUs = Math.max(latencyMaxUs, us);
            latencyBuckets[(int) Math.min(BUCKETS, us / BUCKET_US)]++;
        }
        long nowMs = now / 1_000_000L;
        if (statsStartMs == 0L) {
            statsStartMs = nowMs;
        } else if (nowMs - statsStartMs >= LOG_INTERVAL_MS) {
            Log.i(TAG, "presented " + presented + " frames in " + (nowMs - statsStartMs)
                    + " ms; release to apply median " + medianMs() + " ms, max "
                    + (latencyMaxUs / 1000f) + " ms; unnamed " + selector.unnamed()
                    + ", older dropped " + selector.olderDropped()
                    + ", late echoes " + timeline.lateEchoes());
            statsStartMs = nowMs;
            presented = 0;
            selector.resetCounts();
            latencyMaxUs = 0L;
            for (int i = 0; i <= BUCKETS; i++) {
                latencyBuckets[i] = 0;
            }
        }
    }

    private float medianMs() {
        int total = 0;
        for (int count : latencyBuckets) {
            total += count;
        }
        int seen = 0;
        for (int i = 0; i <= BUCKETS; i++) {
            seen += latencyBuckets[i];
            if (seen * 2 >= total && total > 0) {
                return (i * BUCKET_US + BUCKET_US / 2f) / 1000f;
            }
        }
        return 0f;
    }
}
