package com.limelight.meow.viewport;

/**
 * An independent transcription of sunmeow's crop arithmetic ({@code src/meow/viewport.h}:
 * {@code reference_frame}, {@code to_desktop}, {@code sanitize}, {@code plan},
 * {@code to_reference}), written from the host source for the tests, so that
 * {@link HostCropPlan}'s mirror is checked against something other than itself. It also knows
 * what the client never sees: the true mapping of each decoded frame.
 */
final class SunmeowCropModel {

    static final int MIN_SOURCE_EXTENT = 64;
    static final int MIN_OUTPUT_EXTENT = 32;

    final int captureWidth;
    final int captureHeight;
    final int surfaceWidth;
    final int surfaceHeight;
    final int contentX;
    final int contentY;
    final int contentWidth;
    final int contentHeight;

    SunmeowCropModel(int captureWidth, int captureHeight, int surfaceWidth, int surfaceHeight) {
        this.captureWidth = captureWidth;
        this.captureHeight = captureHeight;
        this.surfaceWidth = surfaceWidth;
        this.surfaceHeight = surfaceHeight;
        float scalar = Math.min((float) surfaceWidth / (float) captureWidth,
                (float) surfaceHeight / (float) captureHeight);
        contentWidth = (int) ((float) captureWidth * scalar);
        contentHeight = (int) ((float) captureHeight * scalar);
        contentX = (surfaceWidth - contentWidth) / 2;
        contentY = (surfaceHeight - contentHeight) / 2;
    }

    ViewportRect content() {
        return new ViewportRect(contentX, contentY, contentWidth, contentHeight);
    }

    private static int floorEven(int v) {
        return v <= 0 ? 0 : v & ~1;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    /** sanitize(to_desktop(request)) as {x, y, w, h}, or null when refused. */
    int[] source(ViewportRect in) {
        long left = Math.max(in.x, contentX);
        long top = Math.max(in.y, contentY);
        long right = Math.min((long) in.x + in.width, (long) contentX + contentWidth);
        long bottom = Math.min((long) in.y + in.height, (long) contentY + contentHeight);
        if (right <= left || bottom <= top) {
            return null;
        }
        double sx = (double) captureWidth / contentWidth;
        double sy = (double) captureHeight / contentHeight;
        int dx = (int) Math.floor((left - contentX) * sx);
        int dy = (int) Math.floor((top - contentY) * sy);
        int dr = (int) Math.ceil((right - contentX) * sx);
        int db = (int) Math.ceil((bottom - contentY) * sy);
        int x = clamp(dx, 0, captureWidth - 1);
        int y = clamp(dy, 0, captureHeight - 1);
        int w = clamp(dr, x + 1, captureWidth) - x;
        int h = clamp(db, y + 1, captureHeight) - y;

        w = Math.min(w, captureWidth - x);
        h = Math.min(h, captureHeight - y);
        if (w < MIN_SOURCE_EXTENT) {
            w = MIN_SOURCE_EXTENT;
            x = Math.min(x, captureWidth - w);
        }
        if (h < MIN_SOURCE_EXTENT) {
            h = MIN_SOURCE_EXTENT;
            y = Math.min(y, captureHeight - h);
        }
        x = floorEven(x);
        y = floorEven(y);
        w = floorEven(Math.min(w, captureWidth - x));
        h = floorEven(Math.min(h, captureHeight - y));
        if (w < MIN_SOURCE_EXTENT || h < MIN_SOURCE_EXTENT) {
            return null;
        }
        return new int[] {x, y, w, h};
    }

    /** to_reference(source): the echo. */
    ViewportRect echo(int[] s) {
        if (s == null) {
            return content();
        }
        double sx = (double) contentWidth / captureWidth;
        double sy = (double) contentHeight / captureHeight;
        int left = contentX + (int) Math.round(s[0] * sx);
        int top = contentY + (int) Math.round(s[1] * sy);
        int right = contentX + (int) Math.round((s[0] + s[2]) * sx);
        int bottom = contentY + (int) Math.round((s[1] + s[3]) * sy);
        int x = clamp(left, contentX, contentX + contentWidth - 1);
        int y = clamp(top, contentY, contentY + contentHeight - 1);
        return new ViewportRect(x, y, clamp(right, x + 1, contentX + contentWidth) - x,
                clamp(bottom, y + 1, contentY + contentHeight) - y);
    }

    /** What a decoded frame encoded with {@code source} really shows (plan()'s layout). */
    FrameMapping trueMapping(int[] s) {
        if (s == null || (s[0] == 0 && s[1] == 0 && s[2] == captureWidth
                && s[3] == captureHeight)) {
            return FrameMapping.IDENTITY;
        }
        float scalar = Math.min((float) surfaceWidth / (float) s[2],
                (float) surfaceHeight / (float) s[3]);
        int outW = floorEven((int) ((float) s[2] * scalar));
        int outH = floorEven((int) ((float) s[3] * scalar));
        if (outW < MIN_OUTPUT_EXTENT || outH < MIN_OUTPUT_EXTENT) {
            return FrameMapping.IDENTITY;
        }
        int offW = floorEven((surfaceWidth - outW) / 2);
        int offH = floorEven((surfaceHeight - outH) / 2);
        // decoded x -> desktop: s.x + (x - offW) * s.w / outW; desktop -> reference.
        double toRefX = (double) contentWidth / captureWidth;
        double toRefY = (double) contentHeight / captureHeight;
        double scaleX = (double) s[2] / outW * toRefX;
        double scaleY = (double) s[3] / outH * toRefY;
        return new FrameMapping(scaleX, scaleY,
                contentX + s[0] * toRefX - offW * scaleX,
                contentY + s[1] * toRefY - offH * scaleY);
    }

    /** The decoded pixels that show desktop: {left, top, right, bottom}. */
    double[] picture(int[] s) {
        if (s == null) {
            return new double[] {contentX, contentY, contentX + contentWidth,
                    contentY + contentHeight};
        }
        float scalar = Math.min((float) surfaceWidth / (float) s[2],
                (float) surfaceHeight / (float) s[3]);
        int outW = floorEven((int) ((float) s[2] * scalar));
        int outH = floorEven((int) ((float) s[3] * scalar));
        int offW = floorEven((surfaceWidth - outW) / 2);
        int offH = floorEven((surfaceHeight - outH) / 2);
        return new double[] {offW, offH, offW + outW, offH + outH};
    }
}
