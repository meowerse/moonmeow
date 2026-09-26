package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The owner's report of 2026-09-26, as a simulation: a continuous pan at 6.6x over a 5360x1440
 * desktop on an upright phone, with crop requests going up, crops being applied by a host that
 * does sunmeow's arithmetic ({@link SunmeowCropModel}), echoes and frames coming back at 60 fps
 * over a link whose delay jitters, frames being decoded, released on vsync and put on screen.
 * Everything on the client side is production code: {@link GuardBand} decides the requests,
 * {@link CropRequestHistory} and {@link HostCropPlan} turn echoes into mappings,
 * {@link CropTimeline}, {@link FrameStamps}, {@link DecodedFrameGate} and {@link FrameSelector}
 * pair each released buffer with its crop, and {@link FrameLayerGeometry} places it.
 *
 * <p>For every frame on screen it measures where each desktop pixel under the view really lands,
 * against where the user's logical view puts it: the composite of the layer (what the client
 * thinks the frame shows) and the frame's true content (what the host encoded). The view's own
 * transform is the same on both sides, so any difference is a jump or wobble the user sees.
 *
 * <p>Two negative controls prove the measure can fail: the view-property swap this change
 * replaces (the transform reaches the screen a display frame after the buffer), and a mapping
 * estimated from the rounded echo instead of computed from the request.
 */
public class SmoothPanSimulationTest {

    private static final long VSYNC_US = 16_667L;
    private static final long HOST_FRAME_US = 16_667L;
    private static final long ENCODE_US = 5_000L;
    private static final long SERIALIZE_US = 8_000L;
    private static final long DECODE_US = 6_000L;
    /** The echo's hop from the library's callback thread to the binder's reporter thread. */
    private static final long REPORTER_ECHO_US = 1_000L;
    private static final long LIBRARY_RATE_US = 50_000L;

    private enum Presentation { PER_FRAME, VIEW_PROPERTY_SWAP }

    private enum Mapping { EXACT, ESTIMATED_FROM_ECHO }

    private static final class Result {
        double maxErrorPx;
        int framesChecked;
        int requests;
        int cropChanges;
        final List<String> sizes = new ArrayList<>();
        int backwards;
        int lateEchoes;
        int framesOverHalfPixel;
        /** Request sizes after the desktop became known (the first echo). */
        final List<String> sizesOnceKnown = new ArrayList<>();
    }

    /** A link whose delay wanders smoothly between base and base + jitter. */
    private static final class Link {
        private final Random random;
        private final long baseUs;
        private final long jitterUs;
        private double level;
        private long lastUs = -1;

        Link(long seed, long baseUs, long jitterUs) {
            this.random = new Random(seed);
            this.baseUs = baseUs;
            this.jitterUs = jitterUs;
        }

        long delay(long nowUs) {
            while (lastUs < nowUs) {
                lastUs += 1000;
                level = Math.max(0, Math.min(1, level + (random.nextDouble() - 0.5) * 0.15));
            }
            return baseUs + (long) (level * jitterUs);
        }
    }

    private static final class Echo {
        final long atUs;
        final ViewportRect rect;
        final int frame;

        Echo(long atUs, ViewportRect rect, int frame) {
            this.atUs = atUs;
            this.rect = rect;
            this.frame = frame;
        }
    }

    private static final class Frame {
        final int number;
        final long ptsUs;
        final long decodedUs;
        final int[] source;

        Frame(int number, long ptsUs, long decodedUs, int[] source) {
            this.number = number;
            this.ptsUs = ptsUs;
            this.decodedUs = decodedUs;
            this.source = source;
        }
    }

    @Before
    public void setUp() {
        FrameStamps.reset();
        DecodedFrameGate.reset();
    }

    /**
     * @param stream  {width, height} of the negotiated stream (= the view's surface)
     * @param zoom    the user's zoom
     * @param path    reference-pixel view origin over time: {tMs, x, y} waypoints
     */
    private Result run(int[] stream, float zoom, double[][] path, Presentation presentation,
                       Mapping mappingMode, long seed) {
        return run(stream, zoom, path, presentation, mappingMode, seed, 0L);
    }

    /**
     * @param echoSkewUs the echo may arrive up to this much later than the frame it names
     *                   (the control channel retransmitting while video flows)
     */
    private Result run(int[] stream, float zoom, double[][] path, Presentation presentation,
                       Mapping mappingMode, long seed, long echoSkewUs) {
        FrameStamps.reset();
        DecodedFrameGate.reset();
        queuedFrames.clear();
        final int sw = stream[0];
        final int sh = stream[1];
        SunmeowCropModel host = new SunmeowCropModel(5360, 1440, sw, sh);
        ViewportRect content = host.content();

        // As in production: the desktop's size is unknown until the first echo carries it.
        GuardBand band = new GuardBand();
        boolean desktopKnown = false;
        Random skew = new Random(seed * 101 + 3);
        CropRequestHistory history = new CropRequestHistory();
        CropTimeline timeline = new CropTimeline();
        FrameSelector selector = new FrameSelector(timeline);
        Link up = new Link(seed, 30_000, 40_000);
        Link down = new Link(seed * 31 + 7, 30_000, 40_000);
        band.setLeadMs((2 * 30 + 40 + 16 + 5 + 8 + 6) + 17);

        // The view: width and height of V in reference pixels at this zoom (view == stream).
        float vw = sw / zoom;
        float vh = sh / zoom;
        double pxPerRef = zoom; // view pixels == stream pixels here

        Result result = new Result();
        ViewportRect pendingSend = null;
        long lastSendUs = -LIBRARY_RATE_US;
        ArrayDeque<long[]> hostInbox = new ArrayDeque<>();      // {arrivalUs, index}
        List<ViewportRect> sent = new ArrayList<>();
        ArrayDeque<Echo> echoes = new ArrayDeque<>();
        ArrayDeque<Frame> inFlight = new ArrayDeque<>();
        Frame newestDecoded = null;
        int[] hostSource = null;
        ViewportRect hostRequest = null;
        int frameNumber = 1;
        long nextHostFrame = 3_000;
        int releasedUpTo = 0;
        FrameMapping shownMapping = FrameMapping.IDENTITY;   // what the layer / view shows
        FrameMapping pendingViewMapping = FrameMapping.IDENTITY; // legacy: lags a frame
        Frame shown = null;
        int lastShownNumber = 0;
        ViewportRect firstSize = null;

        long endUs = (long) (path[path.length - 1][0] * 1000);
        for (long now = 0; now <= endUs; now += 250) {
            // ---- UI frame: the view moves, the band decides --------------------------------
            boolean vsync = now % VSYNC_US < 250;
            if (vsync) {
                double[] origin = at(path, now / 1000.0);
                // Both edges rounded separately, as ViewportGeometry does.
                int left = (int) Math.round(origin[0]);
                int top = (int) Math.round(origin[1]);
                ViewportRect v = new ViewportRect(left, top,
                        (int) Math.round(origin[0] + vw) - left,
                        (int) Math.round(origin[1] + vh) - top);
                ViewportRect request = band.onVisible(v, now / 1000, content, sw, sh);
                if (request != null) {
                    result.requests++;
                    history.record(request);
                    pendingSend = request;
                    if (firstSize == null) {
                        firstSize = request;
                    }
                    String size = request.width + "x" + request.height;
                    if (!result.sizes.contains(size)) {
                        result.sizes.add(size);
                    }
                    if (desktopKnown && !result.sizesOnceKnown.contains(size)) {
                        result.sizesOnceKnown.add(size);
                    }
                }
            }
            // ---- the library: at most one send per 50 ms, the latest wins -----------------
            if (pendingSend != null && now - lastSendUs >= LIBRARY_RATE_US) {
                sent.add(pendingSend);
                hostInbox.add(new long[] {now + up.delay(now), sent.size() - 1});
                pendingSend = null;
                lastSendUs = now;
            }
            // ---- host: applies the newest request at a frame boundary ---------------------
            if (now >= nextHostFrame) {
                nextHostFrame += HOST_FRAME_US;
                while (!hostInbox.isEmpty() && hostInbox.peek()[0] <= now) {
                    hostRequest = sent.get((int) hostInbox.poll()[1]);
                }
                int[] source = hostRequest != null ? host.source(hostRequest) : null;
                boolean changed = !java.util.Arrays.equals(source, hostSource);
                hostSource = source;
                long sentUs = now + ENCODE_US;
                long arrive = sentUs + down.delay(sentUs);
                if (changed) {
                    result.cropChanges++;
                    // Sent from the encode path once the frame is produced (meow-protocol.md).
                    long late = echoSkewUs > 0 ? (long) (skew.nextDouble() * echoSkewUs) : 0L;
                    echoes.add(new Echo(arrive + late + REPORTER_ECHO_US, host.echo(source),
                            frameNumber));
                }
                inFlight.add(new Frame(frameNumber, arrive, arrive + SERIALIZE_US + DECODE_US,
                        source));
                frameNumber++;
            }
            // ---- client: echoes reach the compositor ------------------------------------
            // Echoes can overtake each other once skewed: take any that are due.
            for (java.util.Iterator<Echo> it = echoes.iterator(); it.hasNext(); ) {
                Echo e = it.next();
                if (e.atUs > now) {
                    continue;
                }
                it.remove();
                if (!desktopKnown) {
                    band.setDesktop(5360, 1440);
                    desktopKnown = true;
                }
                FrameMapping m = mappingMode == Mapping.EXACT
                        ? history.exactMapping(e.rect, 5360, 1440, sw, sh) : null;
                if (m == null) {
                    m = HostCropPlan.mappingFor(e.rect, 5360, 1440, sw, sh);
                }
                timeline.add(e.frame, m);
            }
            // ---- decoder: a frame is queued on arrival, decoded later --------------------
            while (!inFlight.isEmpty() && inFlight.peek().ptsUs <= now) {
                Frame f = inFlight.peek();
                if (f.number > releasedUpTo && f.ptsUs <= now && !queued(f)) {
                    DecodedFrameGate.onFrameQueued(f.number, f.ptsUs);
                    markQueued(f);
                }
                if (f.decodedUs <= now) {
                    newestDecoded = inFlight.poll();
                } else {
                    break;
                }
            }
            // ---- vsync: release the newest decoded frame; the presenter pairs it ----------
            if (vsync) {
                // The legacy swap reaches the screen one display frame after the buffer.
                FrameMapping legacyView = pendingViewMapping;
                if (newestDecoded != null && newestDecoded.number > releasedUpTo) {
                    Frame f = newestDecoded;
                    releasedUpTo = f.number;
                    int index = f.number % 8;
                    long renderTs = now * 1000L;
                    FrameStamps.onOutput(index, f.ptsUs);
                    FrameStamps.onRelease(index, renderTs);
                    FrameMapping m = selector.select(renderTs);
                    if (m != null) {
                        shownMapping = m;
                        shown = f;
                        if (f.number < lastShownNumber) {
                            result.backwards++;
                        }
                        lastShownNumber = f.number;
                    }
                }
                // From the first crop on screen: before it there is nothing to compare.
                if (shown != null && shown.source != null) {
                    FrameMapping presented = presentation == Presentation.PER_FRAME
                            ? shownMapping : legacyView;
                    double[] origin = at(path, now / 1000.0);
                    double error = error(host, shown.source, presented, origin[0], origin[1],
                            vw, vh, pxPerRef);
                    result.maxErrorPx = Math.max(result.maxErrorPx, error);
                    result.framesChecked++;
                    if (error > 0.5) {
                        result.framesOverHalfPixel++;
                    }
                }
                pendingViewMapping = shownMapping;
            }
        }
        result.lateEchoes = timeline.lateEchoes();
        // One size before the first echo (the desktop's size unknown) and one after it: the
        // first echo is at stream start, before the user moves -- never during the pan.
        assertTrue("sizes " + result.sizes, result.sizes.size() <= 2);
        assertEquals("the band never resized once the desktop was known: "
                + result.sizesOnceKnown, 1, result.sizesOnceKnown.size());
        return result;
    }

    private final java.util.Set<Integer> queuedFrames = new java.util.HashSet<>();

    private boolean queued(Frame f) {
        return queuedFrames.contains(f.number);
    }

    private void markQueued(Frame f) {
        queuedFrames.add(f.number);
    }

    /**
     * The largest distance, in screen pixels, between where a desktop point under the view is
     * shown and where the user's view puts it, over the part of the frame on screen.
     */
    private static double error(SunmeowCropModel host, int[] source, FrameMapping presented,
                                double viewX, double viewY, double vw, double vh,
                                double pxPerRef) {
        FrameMapping truth = host.trueMapping(source);
        double[] picture = host.picture(source);
        double worst = 0;
        // x axis: the decoded columns showing the view's left and right edges.
        double[] xs = {viewX, viewX + vw};
        for (double ref : xs) {
            double d = (ref - truth.offsetX) / truth.scaleX;
            d = Math.max(picture[0], Math.min(picture[2], d));
            double shownAt = presented.offsetX + d * presented.scaleX;
            double really = truth.offsetX + d * truth.scaleX;
            worst = Math.max(worst, Math.abs(shownAt - really) * pxPerRef);
        }
        double[] ys = {Math.max(viewY, host.contentY),
                Math.min(viewY + vh, host.contentY + host.contentHeight)};
        for (double ref : ys) {
            double d = (ref - truth.offsetY) / truth.scaleY;
            d = Math.max(picture[1], Math.min(picture[3], d));
            double shownAt = presented.offsetY + d * presented.scaleY;
            double really = truth.offsetY + d * truth.scaleY;
            worst = Math.max(worst, Math.abs(shownAt - really) * pxPerRef);
        }
        return worst;
    }

    /** Linear interpolation along {tMs, x, y} waypoints. */
    private static double[] at(double[][] path, double tMs) {
        if (tMs <= path[0][0]) {
            return new double[] {path[0][1], path[0][2]};
        }
        for (int i = 1; i < path.length; i++) {
            if (tMs <= path[i][0]) {
                double f = (tMs - path[i - 1][0]) / (path[i][0] - path[i - 1][0]);
                return new double[] {path[i - 1][1] + f * (path[i][1] - path[i - 1][1]),
                        path[i - 1][2] + f * (path[i][2] - path[i - 1][2])};
            }
        }
        double[] last = path[path.length - 1];
        return new double[] {last[1], last[2]};
    }

    // ---- the owner's setup: portrait 1220x2712 stream, 5360x1440 desktop, 6.6x ------------

    private static final int[] PORTRAIT = {1220, 2712};
    private static final float OWNER_ZOOM = 6.6f;

    /** Across the desktop and back: 1.9 views a second there, 5.6 back (a fling). */
    private static double[][] ownerPan() {
        // V is 184.8 x 410.9 reference pixels; the desktop strip is 1220 x 327 at y 1192.
        double y = 1192 + 327 / 2.0 - 2712 / OWNER_ZOOM / 2;
        return new double[][] {
                {0, 0, y}, {300, 0, y}, {3300, 1220 - 1220 / OWNER_ZOOM, y},
                {3600, 1220 - 1220 / OWNER_ZOOM, y}, {4600, 0, y}, {5200, 0, y}};
    }

    @Test
    public void everyFrameOfTheOwnersPanShowsTheDesktopWhereTheViewPutsIt() {
        for (long seed = 1; seed <= 5; seed++) {
            Result r = run(PORTRAIT, OWNER_ZOOM, ownerPan(), Presentation.PER_FRAME,
                    Mapping.EXACT, seed);
            assertTrue("seed " + seed + ": frames checked " + r.framesChecked,
                    r.framesChecked > 250);
            assertTrue("seed " + seed + ": the crop moved during the pan, " + r.cropChanges,
                    r.cropChanges >= 5);
            assertTrue("seed " + seed + ": worst " + r.maxErrorPx + " px",
                    r.maxErrorPx <= 0.5);
            assertEquals("no frame older than the one on screen is shown", 0, r.backwards);
        }
    }

    @Test
    public void aLandscapeDiagonalPanAt4xIsJustAsStable() {
        int[] landscape = {1920, 1080};
        // Content 1920 x 515 at y 282; V is 480 x 270.
        double[][] path = {{0, 100, 300}, {200, 100, 300}, {2200, 1300, 500},
                {2600, 1300, 500}, {3400, 200, 330}, {3800, 200, 330}};
        for (long seed = 11; seed <= 13; seed++) {
            Result r = run(landscape, 4f, path, Presentation.PER_FRAME, Mapping.EXACT, seed);
            assertTrue("seed " + seed + ": worst " + r.maxErrorPx + " px", r.maxErrorPx <= 0.5);
            assertTrue(r.cropChanges >= 5);
        }
    }

    @Test
    public void aSteadyPanAsksForFewCrops() {
        Result r = run(PORTRAIT, OWNER_ZOOM, ownerPan(), Presentation.PER_FRAME,
                Mapping.EXACT, 3);
        // ~310 UI frames of motion; a request per frame would be ~310.
        assertTrue("requests " + r.requests, r.requests <= 40);
    }

    /** Negative control: the view-property swap this replaces jumps at crop changes. */
    @Test
    public void theViewPropertySwapThatThisReplacesJumpsAtEveryCropChange() {
        Result r = run(PORTRAIT, OWNER_ZOOM, ownerPan(), Presentation.VIEW_PROPERTY_SWAP,
                Mapping.EXACT, 1);
        assertTrue("a one-display-frame lag must be visible: " + r.maxErrorPx,
                r.maxErrorPx > 20);
    }

    /** Negative control: a mapping estimated from the rounded echo is off by pixels at 6.6x. */
    @Test
    public void aMappingEstimatedFromTheEchoWouldStepByPixels() {
        double worst = 0;
        for (long seed = 1; seed <= 3; seed++) {
            worst = Math.max(worst, run(PORTRAIT, OWNER_ZOOM, ownerPan(),
                    Presentation.PER_FRAME, Mapping.ESTIMATED_FROM_ECHO, seed).maxErrorPx);
        }
        assertTrue("estimated mappings must show a measurable step: " + worst, worst > 0.5);
    }

    /**
     * The echo is sent once its frame is produced, on the same link just ahead of the frame's
     * packets, and the frame still has to be reassembled and decoded (14 ms here): an echo up
     * to 10 ms later than its frame's packets is still in time.
     */
    @Test
    public void anEchoALittleBehindItsFrameIsStillInTime() {
        for (long seed = 21; seed <= 23; seed++) {
            Result r = run(PORTRAIT, OWNER_ZOOM, ownerPan(), Presentation.PER_FRAME,
                    Mapping.EXACT, seed, 10_000L);
            assertEquals(0, r.lateEchoes);
            assertTrue("worst " + r.maxErrorPx, r.maxErrorPx <= 0.5);
        }
    }

    /**
     * The residual, stated: an echo that arrives after its frame is on screen (the control
     * channel retransmitting) cannot fix that frame. It is counted, and the frames shown
     * before it arrives are the only ones off -- by the crop step, until the echo lands.
     */
    @Test
    public void aLateEchoIsCountedAndOnlyTheFramesBeforeItAreOff() {
        Result r = run(PORTRAIT, OWNER_ZOOM, ownerPan(), Presentation.PER_FRAME,
                Mapping.EXACT, 31, 60_000L);
        assertTrue("late echoes " + r.lateEchoes, r.lateEchoes > 0);
        assertTrue(r.framesOverHalfPixel > 0);
        // At most the frames that fit in the skew, per late echo.
        assertTrue(r.framesOverHalfPixel + " frames over for " + r.lateEchoes + " late echoes",
                r.framesOverHalfPixel <= r.lateEchoes * (60_000 / VSYNC_US + 1));
    }
}

