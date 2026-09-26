package com.limelight.meow.viewport;

/**
 * What a decoded frame shows once the host applies a crop, reproduced on the client from the
 * host's echo. Pure arithmetic, no Android types.
 *
 * <p><b>Why the client has to recompute this.</b> The echo tells the client <em>which</em>
 * reference rectangle the host applied, not <em>how it laid it out</em>. The host scales the
 * crop into the unchanged encoder surface with aspect-preserving letterboxing and rounds every
 * edge to an even pixel for chroma. To place the decoded pixels exactly where they belong the
 * client must run the same arithmetic.
 *
 * <p><b>A deliberate mirror of {@code sunmeow/src/meow/viewport.h}.</b> The layout — the
 * {@code float} scalar, the truncating multiply, {@code floor_even} of the output size and of
 * the letterbox offsets, the refusal of slivers — is the cropped branch of {@code plan()}
 * transcribed as is.
 *
 * <p><b>The one place it deliberately does not transcribe the host.</b> The host answers with
 * {@code to_reference(source)}, which rounds each desktop edge to the nearest reference pixel,
 * so the echo does not pin the desktop source down: on a 5360-wide desktop two or three even
 * columns round to each reference pixel. Which one the host used changes more than the edge
 * — the letterboxed output size and offset are {@code floor_even} of float products, and flip
 * by two decoded pixels between neighbouring sources. Feeding the echo back through the host's
 * own {@code to_desktop()} and {@code sanitize()} picks one source arbitrarily and was measured
 * up to 2.7 reference pixels off (13% of random crops over one pixel).
 *
 * <p>Instead every source consistent with the echo is enumerated — each edge an even desktop
 * column ({@code sanitize()} makes every streamed edge even) that rounds to the echoed pixel —
 * the host's layout is run for each, and the mapping is the midrange of the results: the one
 * whose worst error against any of them is smallest. Measured over random crops on the live
 * topology that keeps 99% of crops within one reference pixel everywhere, and the worst under
 * 1.7. Exactness would need the echo to carry the desktop-space source, which the protocol
 * does not.
 *
 * <p><b>Exact when the request is known.</b> The enumeration above is the fallback. The client
 * knows the rectangle it asked for, and the host's {@code to_desktop()} and {@code sanitize()}
 * are deterministic, so {@link #exactMapping} runs them on the <em>request</em> -- not on the
 * rounded echo -- and accepts the source only if {@code to_reference()} of it reproduces the
 * echo exactly. That source is the one the host used, and the mapping built from it is exact:
 * with the frame-exact swap this is what keeps the picture from stepping by a pixel or two at
 * every crop change of a pan (at 6.6x one reference pixel is seven screen pixels).
 *
 * <p>Every path that is not a real crop — no desktop extent and a full-frame echo, a refused
 * request, a request that covers the whole desktop — returns {@link FrameMapping#IDENTITY},
 * because that is what the host streams in all of those cases.
 */
public final class HostCropPlan {

    /** {@code meow::viewport::min_source_extent}. */
    static final int MIN_SOURCE_EXTENT = 64;
    /** {@code meow::viewport::min_output_extent}. */
    static final int MIN_OUTPUT_EXTENT = 32;

    private HostCropPlan() {
    }

    /**
     * @param applied       the rectangle the host echoed, in reference (stream) pixels
     * @param desktopWidth  captured desktop width from the echo, or 0 when not reported
     * @param desktopHeight captured desktop height from the echo, or 0 when not reported
     * @param streamWidth   negotiated stream width
     * @param streamHeight  negotiated stream height
     * @return how decoded pixels map into the reference frame; never null
     */
    public static FrameMapping mappingFor(ViewportRect applied, int desktopWidth, int desktopHeight,
                                          int streamWidth, int streamHeight) {
        if (applied == null || streamWidth <= 0 || streamHeight <= 0) {
            return FrameMapping.IDENTITY;
        }

        // An echo-v1 host without the desktop extent: the best model available is a desktop
        // the size of the stream, which is exact whenever the aspect ratios match.
        int captureWidth = desktopWidth > 0 && desktopHeight > 0 ? desktopWidth : streamWidth;
        int captureHeight = desktopWidth > 0 && desktopHeight > 0 ? desktopHeight : streamHeight;

        ViewportReferenceFrame reference =
                ViewportReferenceFrame.of(captureWidth, captureHeight, streamWidth, streamHeight);
        if (reference == null || applied.equals(reference.fullContent())) {
            return FrameMapping.IDENTITY;
        }

        double toDesktopX = (double) captureWidth / reference.contentWidth;
        double toDesktopY = (double) captureHeight / reference.contentHeight;
        int[] lefts = new int[MAX_EDGE_CANDIDATES];
        int[] rights = new int[MAX_EDGE_CANDIDATES];
        int[] tops = new int[MAX_EDGE_CANDIDATES];
        int[] bottoms = new int[MAX_EDGE_CANDIDATES];
        int leftCount = edgeCandidates(applied.x - reference.contentX, toDesktopX,
                captureWidth, lefts);
        int rightCount = edgeCandidates(applied.x + applied.width - reference.contentX,
                toDesktopX, captureWidth, rights);
        int topCount = edgeCandidates(applied.y - reference.contentY, toDesktopY,
                captureHeight, tops);
        int bottomCount = edgeCandidates(applied.y + applied.height - reference.contentY,
                toDesktopY, captureHeight, bottoms);

        double[] low = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] high = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
                -Double.MAX_VALUE};
        double[] one = new double[4];
        int cropped = 0;
        int considered = 0;
        for (int l = 0; l < leftCount; l++) {
            for (int r = 0; r < rightCount; r++) {
                for (int t = 0; t < topCount; t++) {
                    for (int b = 0; b < bottomCount; b++) {
                        int result = layout(lefts[l], tops[t], rights[r] - lefts[l],
                                bottoms[b] - tops[t], captureWidth, captureHeight,
                                streamWidth, streamHeight, reference, one);
                        if (result == INVALID) {
                            continue;
                        }
                        considered++;
                        if (result == CROPPED) {
                            cropped++;
                        }
                        for (int i = 0; i < 4; i++) {
                            low[i] = Math.min(low[i], one[i]);
                            high[i] = Math.max(high[i], one[i]);
                        }
                    }
                }
            }
        }
        if (considered == 0 || cropped == 0) {
            // Every consistent source is one the host streams uncropped.
            return FrameMapping.IDENTITY;
        }
        return new FrameMapping((low[0] + high[0]) / 2, (low[1] + high[1]) / 2,
                (low[2] + high[2]) / 2, (low[3] + high[3]) / 2);
    }

    /** More even columns than this never round to one reference pixel below 16:1. */
    /**
     * The exact mapping for an echo that answers {@code request}, or null when the echo is not
     * what the host makes of that request (it answered another one, clamped it differently, or
     * the desktop extent is unknown) -- then {@link #mappingFor} is the fallback.
     */
    public static FrameMapping exactMapping(ViewportRect request, ViewportRect applied,
                                            int desktopWidth, int desktopHeight,
                                            int streamWidth, int streamHeight) {
        if (request == null || applied == null || desktopWidth <= 0 || desktopHeight <= 0
                || streamWidth <= 0 || streamHeight <= 0) {
            return null;
        }
        ViewportReferenceFrame reference =
                ViewportReferenceFrame.of(desktopWidth, desktopHeight, streamWidth, streamHeight);
        if (reference == null) {
            return null;
        }
        int[] source = new int[4];
        if (!hostSource(request, desktopWidth, desktopHeight, reference, source)) {
            return null;
        }
        if (!applied.equals(hostEcho(source, desktopWidth, desktopHeight, reference))) {
            return null;
        }
        double[] layout = new double[4];
        int result = layout(source[0], source[1], source[2], source[3], desktopWidth,
                desktopHeight, streamWidth, streamHeight, reference, layout);
        if (result != CROPPED) {
            return FrameMapping.IDENTITY;
        }
        return new FrameMapping(layout[0], layout[1], layout[2], layout[3]);
    }

    /**
     * {@code sanitize(to_desktop(request))}: the desktop source the host crops to for a
     * request, into {@code out} as {x, y, width, height}. Allocation-free.
     *
     * @return false when the host refuses the request (it then streams the whole desktop)
     */
    static boolean hostSource(ViewportRect request, int captureWidth, int captureHeight,
                              ViewportReferenceFrame reference, int[] out) {
        if (captureWidth < MIN_SOURCE_EXTENT || captureHeight < MIN_SOURCE_EXTENT) {
            return false;
        }
        return hostAxis(request.x, request.width, reference.contentX, reference.contentWidth,
                captureWidth, out, 0)
                && hostAxis(request.y, request.height, reference.contentY,
                reference.contentHeight, captureHeight, out, 1);
    }

    /**
     * One axis of {@code sanitize(to_desktop(request))}; the host treats the two axes
     * independently. Writes the origin to {@code out[at]} and the extent to {@code out[at + 2]}.
     * Allocation-free.
     *
     * @return false when the host refuses the request on this axis
     */
    static boolean hostAxis(int start, int length, int contentStart, int contentLength,
                            int capture, int[] out, int at) {
        if (contentLength <= 0 || length <= 0) {
            return false;
        }
        // to_desktop(): intersect with the content, floor the origin, ceil the far edge.
        long near = Math.max(start, contentStart);
        long far = Math.min((long) start + length, (long) contentStart + contentLength);
        if (far <= near) {
            return false;
        }
        double scale = (double) capture / (double) contentLength;
        int origin = clamp((int) Math.floor((double) (near - contentStart) * scale),
                0, capture - 1);
        int extent = clamp((int) Math.ceil((double) (far - contentStart) * scale),
                origin + 1, capture) - origin;
        // sanitize()
        extent = Math.min(extent, capture - origin);
        if (extent < MIN_SOURCE_EXTENT) {
            extent = MIN_SOURCE_EXTENT;
            origin = Math.min(origin, capture - extent);
        }
        origin = floorEven(origin);
        extent = floorEven(Math.min(extent, capture - origin));
        if (extent < MIN_SOURCE_EXTENT) {
            return false;
        }
        out[at] = origin;
        out[at + 2] = extent;
        return true;
    }

    /** {@code to_reference(source)}: what the host echoes for a desktop source. */
    static ViewportRect hostEcho(int[] source, int captureWidth, int captureHeight,
                                 ViewportReferenceFrame reference) {
        double sx = (double) reference.contentWidth / (double) captureWidth;
        double sy = (double) reference.contentHeight / (double) captureHeight;
        int left = reference.contentX + (int) Math.round(source[0] * sx);
        int top = reference.contentY + (int) Math.round(source[1] * sy);
        int right = reference.contentX + (int) Math.round((source[0] + source[2]) * sx);
        int bottom = reference.contentY + (int) Math.round((source[1] + source[3]) * sy);
        int x = clamp(left, reference.contentX, reference.contentX + reference.contentWidth - 1);
        int y = clamp(top, reference.contentY, reference.contentY + reference.contentHeight - 1);
        int width = clamp(right, x + 1, reference.contentX + reference.contentWidth) - x;
        int height = clamp(bottom, y + 1, reference.contentY + reference.contentHeight) - y;
        return new ViewportRect(x, y, width, height);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    static final int MAX_EDGE_CANDIDATES = 8;

    private static final int INVALID = 0;
    private static final int FULL = 1;
    private static final int CROPPED = 2;

    /**
     * The host's layout of one candidate source, as {scaleX, scaleY, offsetX, offsetY} in
     * {@code out}: the cropped branch of {@code plan()}, or the identity for the sources it
     * streams uncropped.
     */
    private static int layout(int left, int top, int sourceWidth, int sourceHeight,
                              int captureWidth, int captureHeight,
                              int streamWidth, int streamHeight,
                              ViewportReferenceFrame reference, double[] out) {
        if (sourceWidth < MIN_SOURCE_EXTENT || sourceHeight < MIN_SOURCE_EXTENT) {
            return INVALID;
        }
        float scalar = Math.min((float) streamWidth / (float) sourceWidth,
                (float) streamHeight / (float) sourceHeight);
        int outWidth = floorEven((int) ((float) sourceWidth * scalar));
        int outHeight = floorEven((int) ((float) sourceHeight * scalar));
        if ((left == 0 && top == 0 && sourceWidth == captureWidth
                && sourceHeight == captureHeight)
                || outWidth < MIN_OUTPUT_EXTENT || outHeight < MIN_OUTPUT_EXTENT) {
            // plan() streams the whole desktop for these: the identity.
            out[0] = 1.0;
            out[1] = 1.0;
            out[2] = 0.0;
            out[3] = 0.0;
            return FULL;
        }
        int offsetWidth = floorEven((streamWidth - outWidth) / 2);
        int offsetHeight = floorEven((streamHeight - outHeight) / 2);

        // decoded x -> desktop x: source.x + (x - offset) * source.w / out.w
        // desktop x -> reference x: content.x + desktop * content.w / capture.w
        double desktopToReferenceX = (double) reference.contentWidth / captureWidth;
        double desktopToReferenceY = (double) reference.contentHeight / captureHeight;
        out[0] = (double) sourceWidth / outWidth * desktopToReferenceX;
        out[1] = (double) sourceHeight / outHeight * desktopToReferenceY;
        out[2] = reference.contentX + left * desktopToReferenceX - offsetWidth * out[0];
        out[3] = reference.contentY + top * desktopToReferenceY - offsetHeight * out[1];
        return CROPPED;
    }

    /**
     * Every even desktop edge {@code e} with {@code lround(e / toDesktop) == referenceOffset}:
     * the inverse of {@code to_reference()} for an edge {@code sanitize()} made even. The host's
     * own edge is always among them; the nearest even column is the fallback only an echo no
     * host sends can reach.
     *
     * @return how many were written to {@code out}
     */
    static int edgeCandidates(int referenceOffset, double toDesktop, int captureSize, int[] out) {
        double low = (referenceOffset - 0.5) * toDesktop;
        double high = (referenceOffset + 0.5) * toDesktop;
        int limit = floorEven(captureSize);
        int count = 0;
        for (int candidate = floorEven((int) Math.ceil(Math.max(0.0, low)));
             candidate <= high && candidate <= limit && count < out.length; candidate += 2) {
            if (candidate >= low) {
                out[count++] = candidate;
            }
        }
        if (count == 0) {
            int nearest = floorEven((int) Math.round(referenceOffset * toDesktop));
            out[count++] = Math.max(0, Math.min(nearest, limit));
        }
        return count;
    }

    /** {@code meow::viewport::floor_even}. */
    static int floorEven(int value) {
        return value <= 0 ? 0 : value & ~1;
    }
}
