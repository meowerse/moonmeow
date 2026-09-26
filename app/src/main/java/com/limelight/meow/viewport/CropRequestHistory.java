package com.limelight.meow.viewport;

/**
 * The last few crops the client asked for, so an echo can be matched to the request it
 * answers and its mapping computed exactly ({@link HostCropPlan#exactMapping}) instead of
 * estimated from the rounded echo. UI thread only; a fixed ring, allocation-free to record.
 */
public final class CropRequestHistory {

    /** Requests in flight at once: the library sends at most one per 50 ms. */
    static final int SIZE = 8;

    private final ViewportRect[] requests = new ViewportRect[SIZE];
    private int next;

    /** A request is on its way to the host. */
    public void record(ViewportRect request) {
        if (request == null) {
            return;
        }
        requests[next] = request;
        next = (next + 1) % SIZE;
    }

    public void clear() {
        for (int i = 0; i < SIZE; i++) {
            requests[i] = null;
        }
        next = 0;
    }

    /**
     * The exact mapping for {@code applied} if it answers one of the recent requests, newest
     * first; null when none of them produces that echo.
     */
    public FrameMapping exactMapping(ViewportRect applied, int desktopWidth, int desktopHeight,
                                     int streamWidth, int streamHeight) {
        for (int i = 1; i <= SIZE; i++) {
            ViewportRect request = requests[(next - i + SIZE) % SIZE];
            if (request == null) {
                continue;
            }
            FrameMapping mapping = HostCropPlan.exactMapping(request, applied, desktopWidth,
                    desktopHeight, streamWidth, streamHeight);
            if (mapping != null) {
                return mapping;
            }
        }
        return null;
    }
}
