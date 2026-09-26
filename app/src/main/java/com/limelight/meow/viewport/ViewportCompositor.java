package com.limelight.meow.viewport;

import android.view.Choreographer;
import android.view.View;

import com.limelight.meow.gesture.InlinePinchZoomController;

/**
 * Puts the right transform on the stream view once the host crops, so the user sees their
 * view V at exactly one magnification in every state. UI thread only.
 *
 * <h2>The defect this closes (F1)</h2>
 * With viewport following on, a pinch to 4x made the host stream a crop of the desktop, and
 * the client kept presenting that already-magnified crop under its own 4x transform: the user
 * saw 16x of a region they had not asked for. Absolute input landed in the wrong place for
 * the same reason.
 *
 * <h2>What it does</h2>
 * {@code PanZoomHandler} stays the owner of the user's <em>logical</em> transform — zoom and
 * pan over the uncropped reference frame — and writes it to the view as it always has. This
 * class is notified right after each such write and replaces it with the
 * {@link ViewComposition presented} transform for whatever the decoded frame currently shows:
 * <ul>
 *   <li>a pinch or pan shows immediately, as a soft local zoom of the frame already on screen;
 *       </li>
 *   <li>when the host's echo says a new crop starts at frame N, the swap waits until
 *       {@link DecodedFrameGate} reports frame N handed to the display, checked once per vsync
 *       — so the sharp crop replaces the soft zoom on that frame and not one either side;</li>
 *   <li>a host without echo v2 (frame index 0) swaps when the echo arrives, the best it can
 *       do;</li>
 *   <li>a revocation (the host back on the full desktop) is just another crop — the identity
 *       — and swaps the same way;</li>
 *   <li>stream start resets to the identity, so a reconnect never inherits a crop; stream
 *       stop keeps the crop the frozen last frame was encoded with.</li>
 * </ul>
 * Rotation, PiP and an external display all reach this through the same
 * {@code constrainToBounds()} notification, and the presented transform is recomputed from
 * the view's current size each time.
 *
 * <p>Echoes the library coalesces can skip an intermediate crop, and a new echo can arrive
 * before the previous crop's first frame is on screen. Pending swaps are therefore kept in
 * order and the newest one whose frame is on screen wins, so frames of an older crop are never
 * presented under a newer crop's mapping.
 *
 * <p>No allocation on the vsync path; the pending queue is a fixed array.
 *
 * <h2>Per-frame presentation (API 33+)</h2>
 * The view-property swap above cannot be exact: a view property reaches the display with the
 * UI's next frame, a decoded buffer straight from the codec, and nothing latches the two
 * together, so the swap lands within one or two display frames of the named frame -- a jump
 * at every crop change of a pan. When a {@link CropTimeline} is wired
 * ({@link #setTimeline}), a {@link SurfaceFramePresenter} puts every buffer on screen with its
 * own crop transform in one transaction. Then this class only feeds the timeline, and the
 * view keeps the user's logical transform, exactly as {@code PanZoomHandler} wrote it.
 *
 * <p>Either way the mapping of an echo is computed exactly when it answers a request the
 * client recently made ({@link CropRequestHistory}), and estimated from the rounded echo
 * otherwise.
 */
public final class ViewportCompositor {

    /** How the decoder reports progress. Production: {@link DecodedFrameGate#hasPresented}. */
    public interface FrameClock {
        boolean hasPresented(int frameIndex);
    }

    /** One callback on the next vsync. Production: {@link Choreographer}. */
    public interface VsyncScheduler {
        void requestFrame(Runnable onFrame);
    }

    static final int MAX_PENDING = 4;

    private final View streamView;
    private final InlinePinchZoomController.ZoomTarget logical;
    private final FrameClock frameClock;
    private final VsyncScheduler vsync;

    private int streamWidth = 1;
    private int streamHeight = 1;

    private FrameMapping presented = FrameMapping.IDENTITY;

    private final FrameMapping[] pendingMappings = new FrameMapping[MAX_PENDING];
    private final int[] pendingFrames = new int[MAX_PENDING];
    private int pendingCount;
    private boolean frameRequested;

    /** Per-frame presentation: the crops by frame, or null for the view-property swap. */
    private CropTimeline timeline;
    /** The requests echoes are matched against, or null. */
    private CropRequestHistory requests;

    private final float[] scratch = new float[4];
    private final Runnable onVsync = this::onVsync;

    public ViewportCompositor(View streamView, InlinePinchZoomController.ZoomTarget logical) {
        this(streamView, logical, DecodedFrameGate::hasPresented, new ChoreographerScheduler());
    }

    ViewportCompositor(View streamView, InlinePinchZoomController.ZoomTarget logical,
                       FrameClock frameClock, VsyncScheduler vsync) {
        if (streamView == null || logical == null || frameClock == null || vsync == null) {
            throw new IllegalArgumentException("all arguments are required");
        }
        this.streamView = streamView;
        this.logical = logical;
        this.frameClock = frameClock;
        this.vsync = vsync;
    }

    /**
     * Hand crops to a per-frame presenter instead of swapping the view's transform. UI thread,
     * before the stream starts.
     */
    public void setTimeline(CropTimeline timeline) {
        this.timeline = timeline;
        clearPending();
        present(FrameMapping.IDENTITY);
    }

    /** Whether crops go to a per-frame presenter. */
    public boolean presentsPerFrame() {
        return timeline != null;
    }

    /** The recent requests, so echoes can be mapped exactly. UI thread. */
    public void setRequestHistory(CropRequestHistory requests) {
        this.requests = requests;
    }

    /** The mapping currently on screen. */
    public FrameMapping presentedMapping() {
        return presented;
    }

    /** Swaps still waiting for their first frame. */
    int pendingCount() {
        return pendingCount;
    }

    /** A new stream: nothing is cropped yet, and no frame of the old stream counts. */
    public void onStreamStarted(int streamWidth, int streamHeight) {
        this.streamWidth = Math.max(1, streamWidth);
        this.streamHeight = Math.max(1, streamHeight);
        DecodedFrameGate.reset();
        FrameStamps.reset();
        if (timeline != null) {
            timeline.reset();
        }
        clearPending();
        present(FrameMapping.IDENTITY);
    }

    /**
     * The stream is going away. Pending swaps are dropped, but the presented mapping is kept:
     * the last decoded frame stays on the surface, and it is still the crop it was, so
     * resetting to the identity here would show that frozen frame magnified twice. The next
     * {@link #onStreamStarted} resets.
     */
    public void onStreamStopped() {
        clearPending();
    }

    /**
     * The host applied {@code applied} starting at host frame {@code frameIndex}.
     *
     * @param applied       the echoed rectangle, already validated against the stream frame
     * @param desktopWidth  captured desktop width, or 0 when not reported
     * @param desktopHeight captured desktop height, or 0 when not reported
     * @param frameIndex    first frame carrying it, or 0 when the host does not say
     */
    public void onCropApplied(ViewportRect applied, int desktopWidth, int desktopHeight,
                              int frameIndex) {
        if (applied == null) {
            return;
        }
        FrameMapping mapping = requests != null
                ? requests.exactMapping(applied, desktopWidth, desktopHeight,
                        streamWidth, streamHeight)
                : null;
        if (mapping == null) {
            mapping = HostCropPlan.mappingFor(applied, desktopWidth, desktopHeight,
                    streamWidth, streamHeight);
        }

        if (timeline != null) {
            // The presenter pairs it with the frames that carry it; the view keeps the
            // logical transform.
            timeline.add(frameIndex, mapping);
            return;
        }

        if (frameIndex == 0 || frameClock.hasPresented(frameIndex)) {
            // Nothing to wait for. Anything still pending is older and superseded.
            clearPending();
            present(mapping);
            return;
        }

        if (pendingCount == MAX_PENDING) {
            // The oldest can no longer be told apart from its successors in time; fold it in.
            System.arraycopy(pendingMappings, 1, pendingMappings, 0, MAX_PENDING - 1);
            System.arraycopy(pendingFrames, 1, pendingFrames, 0, MAX_PENDING - 1);
            pendingCount--;
        }
        pendingMappings[pendingCount] = mapping;
        pendingFrames[pendingCount] = frameIndex;
        pendingCount++;
        requestFrame();
    }

    /** {@code PanZoomHandler} just wrote the logical transform to the view. */
    public void onLogicalTransformChanged() {
        apply();
    }

    private void onVsync() {
        frameRequested = false;
        int newestDue = -1;
        for (int i = 0; i < pendingCount; i++) {
            if (frameClock.hasPresented(pendingFrames[i])) {
                newestDue = i;
            }
        }
        if (newestDue >= 0) {
            FrameMapping mapping = pendingMappings[newestDue];
            int remaining = pendingCount - newestDue - 1;
            System.arraycopy(pendingMappings, newestDue + 1, pendingMappings, 0, remaining);
            System.arraycopy(pendingFrames, newestDue + 1, pendingFrames, 0, remaining);
            for (int i = remaining; i < pendingCount; i++) {
                pendingMappings[i] = null;
            }
            pendingCount = remaining;
            present(mapping);
        }
        if (pendingCount > 0) {
            requestFrame();
        }
    }

    private void requestFrame() {
        if (!frameRequested) {
            frameRequested = true;
            vsync.requestFrame(onVsync);
        }
    }

    private void clearPending() {
        for (int i = 0; i < pendingCount; i++) {
            pendingMappings[i] = null;
        }
        pendingCount = 0;
    }

    private void present(FrameMapping mapping) {
        presented = mapping;
        apply();
    }

    private void apply() {
        ViewComposition.present(logical.getScaleFactor(), logical.getChildX(), logical.getChildY(),
                streamView.getWidth(), streamView.getHeight(), streamWidth, streamHeight,
                presented, scratch);
        if (streamView.getScaleX() != scratch[ViewComposition.SCALE_X]) {
            streamView.setScaleX(scratch[ViewComposition.SCALE_X]);
        }
        if (streamView.getScaleY() != scratch[ViewComposition.SCALE_Y]) {
            streamView.setScaleY(scratch[ViewComposition.SCALE_Y]);
        }
        if (streamView.getX() != scratch[ViewComposition.X]) {
            streamView.setX(scratch[ViewComposition.X]);
        }
        if (streamView.getY() != scratch[ViewComposition.Y]) {
            streamView.setY(scratch[ViewComposition.Y]);
        }
    }

    /** One {@link Choreographer} frame callback, reused. Must be created on the UI thread. */
    private static final class ChoreographerScheduler
            implements VsyncScheduler, Choreographer.FrameCallback {
        private final Choreographer choreographer = Choreographer.getInstance();
        private Runnable target;

        @Override
        public void requestFrame(Runnable onFrame) {
            target = onFrame;
            choreographer.postFrameCallback(this);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            Runnable run = target;
            if (run != null) {
                run.run();
            }
        }
    }
}
