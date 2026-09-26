package com.limelight.meow.keyboard;

/**
 * Key sizes for a given screen, and the key grid laid out from them. Pure arithmetic, no
 * Android dependency: the sizing rules are the design, so they are tested directly.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Rows are never shorter than 44 dp.</b> The whole cell, gaps included, is the touch
 *       target (hits go to the nearest key), so 44 dp is the target height, not just the
 *       drawn key.</li>
 *   <li><b>The keyboard takes at most ~42% of a portrait window and ~60% of a landscape one</b>
 *       while the 44 dp floor allows it, so the stream keeps the larger share in portrait and
 *       a usable band in landscape. Rows grow up to 56 dp (portrait) / 50 dp (landscape)
 *       when there is room, never beyond: huge keys are as slow as tiny ones.</li>
 *   <li><b>A key unit is never wider than 64 dp.</b> On a tablet or a wide landscape window
 *       the keyboard stops growing and centres, instead of stretching keys across the glass.
 *   </li>
 *   <li>Gaps of 5 dp across and 7 dp down, 3 dp side padding, 6 dp above and below: the
 *       4/8 dp rhythm, tightened horizontally because width is the scarce axis.</li>
 * </ul>
 */
public final class PcKeyboardMetrics {

    static final float MIN_ROW_DP = 44f;
    static final float MAX_ROW_PORTRAIT_DP = 56f;
    static final float MAX_ROW_LANDSCAPE_DP = 50f;
    static final float PORTRAIT_SHARE = 0.42f;
    static final float LANDSCAPE_SHARE = 0.60f;
    static final float MAX_UNIT_DP = 64f;
    static final float GAP_X_DP = 5f;
    static final float GAP_Y_DP = 7f;
    static final float PAD_X_DP = 3f;
    static final float PAD_Y_DP = 6f;
    static final float TOOLBAR_DP = 44f;

    public final float density;
    public final int widthPx;
    public final boolean landscape;
    public final boolean hasToolbar;
    public final float rowHeightPx;
    public final float toolbarHeightPx;
    public final float gapXPx;
    public final float gapYPx;
    public final float padXPx;
    public final float padYPx;
    public final int rows;
    public final int heightPx;

    private PcKeyboardMetrics(float density, int widthPx, boolean landscape, boolean hasToolbar,
                              float rowHeightPx, int rows) {
        this.density = density;
        this.widthPx = widthPx;
        this.landscape = landscape;
        this.hasToolbar = hasToolbar;
        this.rowHeightPx = rowHeightPx;
        this.rows = rows;
        this.toolbarHeightPx = hasToolbar ? TOOLBAR_DP * density : 0f;
        this.gapXPx = GAP_X_DP * density;
        this.gapYPx = GAP_Y_DP * density;
        this.padXPx = PAD_X_DP * density;
        this.padYPx = PAD_Y_DP * density;
        this.heightPx = Math.round(toolbarHeightPx + rows * rowHeightPx + 2 * padYPx);
    }

    /**
     * Sizes for the full keyboard.
     *
     * @param widthPx        the keyboard's width (the window's, usually)
     * @param windowHeightPx the height of the window it sits in
     * @param landscape      which layout is used (the window is wider than tall)
     */
    public static PcKeyboardMetrics forKeyboard(int widthPx, int windowHeightPx, float density,
                                                boolean landscape, int rows) {
        boolean toolbar = !landscape;
        float share = landscape ? LANDSCAPE_SHARE : PORTRAIT_SHARE;
        float maxRow = (landscape ? MAX_ROW_LANDSCAPE_DP : MAX_ROW_PORTRAIT_DP) * density;
        float fixed = (toolbar ? TOOLBAR_DP : 0f) * density + 2 * PAD_Y_DP * density;
        float row = (windowHeightPx * share - fixed) / rows;
        row = Math.max(MIN_ROW_DP * density, Math.min(maxRow, row));
        return new PcKeyboardMetrics(density, widthPx, landscape, toolbar, row, rows);
    }

    /** Sizes for the one-row strip above the system keyboard. */
    public static PcKeyboardMetrics forStrip(int widthPx, float density) {
        return new PcKeyboardMetrics(density, widthPx, false, false, MIN_ROW_DP * density, 1);
    }

    /** Where the key rows start, below the toolbar. */
    public float keysTopPx() {
        return padYPx + toolbarHeightPx;
    }

    /** The width of one key unit for a layout, capped, and the left edge that centres it. */
    public float unitPx(PcKeyboardLayout layout) {
        float available = widthPx - 2 * padXPx;
        return Math.min(available / layout.units, MAX_UNIT_DP * density);
    }

    public float leftPx(PcKeyboardLayout layout) {
        return (widthPx - unitPx(layout) * layout.units) / 2f;
    }

    /** Lays a layout out on these metrics. Allocates; call on size or layout change only. */
    public KeyGrid grid(PcKeyboardLayout layout) {
        return new KeyGrid(this, layout);
    }
}
