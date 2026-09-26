package com.limelight.meow.keyboard;

/**
 * A layout placed on a keyboard of a given size: one cell per key, flat arrays, and the hit
 * test. Cells tile the keyboard with no dead space between them — a touch in a gap belongs to
 * the nearer key, and a touch at the very edge to the edge key — so the whole keyboard area
 * is touch target and the drawn keys are the cells inset by half a gap.
 *
 * <p>Built on a size or layout change; {@link #hitTest} and the accessors allocate nothing.
 */
public final class KeyGrid {

    /** Measures a label in pixels, for sizing the toolbar's chips. */
    public interface TextMeasurer {
        float measure(String text);
    }

    public final PcKeyboardMetrics metrics;
    public final PcKeyboardLayout layout;

    private PcKey[] keys;
    private float[] left;
    private float[] top;
    private float[] right;
    private float[] bottom;
    private final int[] rowStart;
    /** Keys from this index on are the toolbar's. Equal to {@link #size()} without one. */
    private int toolbarStart;
    private int size;

    KeyGrid(PcKeyboardMetrics metrics, PcKeyboardLayout layout) {
        this.metrics = metrics;
        this.layout = layout;
        int count = layout.keyCount();
        keys = new PcKey[count];
        left = new float[count];
        top = new float[count];
        right = new float[count];
        bottom = new float[count];
        rowStart = new int[layout.rows.length + 1];

        float unit = metrics.unitPx(layout);
        float startX = metrics.leftPx(layout);
        float keysTop = metrics.keysTopPx();
        int i = 0;
        for (int r = 0; r < layout.rows.length; r++) {
            rowStart[r] = i;
            float y0 = keysTop + r * metrics.rowHeightPx;
            float y1 = y0 + metrics.rowHeightPx;
            if (r == 0) {
                y0 = metrics.hasToolbar ? keysTop : 0f;
            }
            if (r == layout.rows.length - 1) {
                y1 = metrics.heightPx;
            }
            float x = startX;
            PcKey[] row = layout.rows[r];
            for (int k = 0; k < row.length; k++) {
                keys[i] = row[k];
                left[i] = k == 0 ? Math.min(x, 0f) : x;
                x += row[k].width * unit;
                right[i] = k == row.length - 1 ? Math.max(x, metrics.widthPx) : x;
                top[i] = y0;
                bottom[i] = y1;
                i++;
            }
        }
        rowStart[layout.rows.length] = i;
        toolbarStart = i;
        size = i;
    }

    /**
     * Adds the toolbar: {@code first} at the left, {@code last} at the right, and as many of
     * {@code chips}, in order, as fit between them at their measured width. No-op without a
     * toolbar in the metrics.
     */
    public void addToolbar(PcKey first, PcKey[] chips, PcKey last, TextMeasurer measurer) {
        if (!metrics.hasToolbar) {
            return;
        }
        float d = metrics.density;
        float end = metrics.widthPx - metrics.padXPx;
        float buttonWidth = 56f * d;
        float chipPad = 12f * d;
        float x = metrics.padXPx + buttonWidth + metrics.gapXPx;
        float limit = end - buttonWidth - metrics.gapXPx;
        int fit = 0;
        float[] widths = new float[chips.length];
        for (PcKey chip : chips) {
            float w = Math.max(56f * d, measurer.measure(chip.label) + 2 * chipPad);
            if (x + w > limit) {
                break;
            }
            widths[fit++] = w;
            x += w + metrics.gapXPx;
        }
        // Spread the leftover width between the chips, so the row is justified.
        float slack = fit > 0 ? (limit - x + metrics.gapXPx) / fit : 0f;
        grow(size + 2 + fit);
        float top0 = 0f;
        float bottom0 = metrics.keysTopPx();
        put(first, 0f, top0, metrics.padXPx + buttonWidth + metrics.gapXPx / 2f, bottom0);
        float cx = metrics.padXPx + buttonWidth + metrics.gapXPx;
        for (int c = 0; c < fit; c++) {
            float w = widths[c] + slack;
            put(chips[c], cx - metrics.gapXPx / 2f, top0, cx + w + metrics.gapXPx / 2f, bottom0);
            cx += w + metrics.gapXPx;
        }
        put(last, end - buttonWidth - metrics.gapXPx / 2f, top0, metrics.widthPx, bottom0);
    }

    private void grow(int capacity) {
        if (capacity <= keys.length) {
            return;
        }
        keys = java.util.Arrays.copyOf(keys, capacity);
        left = java.util.Arrays.copyOf(left, capacity);
        top = java.util.Arrays.copyOf(top, capacity);
        right = java.util.Arrays.copyOf(right, capacity);
        bottom = java.util.Arrays.copyOf(bottom, capacity);
    }

    private void put(PcKey key, float l, float t, float r, float b) {
        keys[size] = key;
        left[size] = l;
        top[size] = t;
        right[size] = r;
        bottom[size] = b;
        size++;
    }

    public int size() {
        return size;
    }

    public int toolbarStart() {
        return toolbarStart;
    }

    public PcKey key(int index) {
        return keys[index];
    }

    public float left(int index) {
        return left[index];
    }

    public float top(int index) {
        return top[index];
    }

    public float right(int index) {
        return right[index];
    }

    public float bottom(int index) {
        return bottom[index];
    }

    /** The key under a point, or -1 outside the keyboard (and in a toolbar gap). */
    public int hitTest(float x, float y) {
        if (y < 0 || y >= metrics.heightPx || x < 0 || x >= metrics.widthPx) {
            return -1;
        }
        if (metrics.hasToolbar && y < metrics.keysTopPx()) {
            for (int i = toolbarStart; i < size; i++) {
                if (x >= left[i] && x < right[i]) {
                    return i;
                }
            }
            return -1;
        }
        int rows = layout.rows.length;
        int r = (int) ((y - metrics.keysTopPx()) / metrics.rowHeightPx);
        r = Math.max(0, Math.min(rows - 1, r));
        for (int i = rowStart[r]; i < rowStart[r + 1]; i++) {
            if (x < right[i]) {
                return i;
            }
        }
        return rowStart[r + 1] - 1;
    }
}
