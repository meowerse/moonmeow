package com.limelight.meow.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import com.limelight.R;

import java.util.List;

/**
 * The on-screen PC keyboard: one View that draws every key and handles every finger.
 *
 * <p>Seventy Buttons would be seventy views to measure, lay out and draw, each with its own
 * background drawable and state list. Here a key is a cell in a {@link KeyGrid} (flat
 * arrays, built on a size change) and a frame is a loop over them with paints allocated
 * once. The press path — hit test, engine, haptic, invalidate — allocates nothing.
 *
 * <p>Two modes: the full keyboard (main and Fn layers, plus a toolbar in portrait) and the
 * one-row strip shown above the system keyboard.
 *
 * <p>Multi-touch is real: each finger holds its own key, so Shift held with one thumb and a
 * letter tapped with the other is Shift+letter, and a held key stays down on the host (which
 * auto-repeats it) until its finger lifts.
 */
public class PcKeyboardView extends View implements PcKeyboardEngine.Listener {

    /** What the keyboard asks of its owner. */
    public interface Actions {
        void onKeyboardAction(int action);

        /** Rotation, a physical keyboard arriving, picture-in-picture: the window changed. */
        void onKeyboardConfigurationChanged(Configuration config);
    }

    public static final int MODE_FULL = 0;
    public static final int MODE_STRIP = 1;

    private static final int MAX_POINTERS = 10;

    // Palette: meowerse tokens (packages/ui/src/styles/tokens.css) on the dark plate.
    static final int COLOR_BG = 0xFF0D0D0D;
    static final int COLOR_EDGE = 0xFF242424;
    static final int COLOR_KEY = 0xFF262626;
    static final int COLOR_KEY_PRESSED = 0xFF3D3D3D;
    static final int COLOR_SPECIAL = 0xFF191919;
    static final int COLOR_SPECIAL_PRESSED = 0xFF303030;
    static final int COLOR_CHIP = 0xFF12261A;
    static final int COLOR_CHIP_PRESSED = 0xFF1D3B2A;
    static final int COLOR_ACCENT = 0xFF123322;
    static final int COLOR_ACCENT_PRESSED = 0xFF1C4A31;
    static final int COLOR_LABEL = 0xFFF2F2F2;
    static final int COLOR_LABEL_SPECIAL = 0xFFB8B8B8;
    static final int COLOR_HINT = 0xFF8C8C8C;
    static final int COLOR_GREEN = 0xFF00FF82;
    static final int COLOR_GREEN_HOVER = 0xFF00E676;
    static final int COLOR_ON_GREEN = 0xFF06331B;
    static final int COLOR_CHIP_LABEL = 0xFF9DFFC9;

    private final PcKeyboardEngine engine;
    private Actions actions;
    private int mode = MODE_FULL;
    private int bottomInsetPx;

    private PcKeyboardMetrics metrics;
    private KeyGrid mainGrid;
    private KeyGrid fnGrid;
    private float[] labelSizes = new float[0];
    private float[] fnLabelSizes = new float[0];
    private int builtWidth = -1;
    private int builtHeight = -1;
    private int builtMode = -1;

    // Per-pointer state. A finger keeps the key it landed on, whatever layer shows later.
    private final PcKey[] pointerKey = new PcKey[MAX_POINTERS];
    private final KeyGrid[] pointerGrid = new KeyGrid[MAX_POINTERS];
    private final int[] pointerIndex = new int[MAX_POINTERS];
    private final LongPress[] longPresses = new LongPress[MAX_POINTERS];

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF iconRect = new RectF();
    private final Path path = new Path();
    private final float density;
    private final long longPressMs;

    private final Helper accessibility;
    /** Not View.postDelayed: that queue only runs while attached, and a detach must cancel. */
    private final Handler handler = new Handler(Looper.getMainLooper());

    public PcKeyboardView(Context context, PcKeyboardEngine engine) {
        super(context);
        this.engine = engine;
        this.density = context.getResources().getDisplayMetrics().density;
        this.longPressMs = ViewConfiguration.getLongPressTimeout();
        engine.setListener(this);
        for (int i = 0; i < MAX_POINTERS; i++) {
            longPresses[i] = new LongPress(i);
            pointerIndex[i] = -1;
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stroke.setStyle(Paint.Style.STROKE);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        setHapticFeedbackEnabled(true);
        setSoundEffectsEnabled(true);
        setClickable(true);
        setFocusable(false);
        setContentDescription(context.getString(R.string.meow_pc_keyboard_name));
        accessibility = new Helper(this);
        ViewCompat.setAccessibilityDelegate(this, accessibility);
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    public void setMode(int mode) {
        if (this.mode != mode) {
            releasePointers();
            this.mode = mode;
            requestLayout();
            invalidate();
        }
    }

    public int getMode() {
        return mode;
    }

    /** Space to leave below the keys, for a navigation bar the window draws under. */
    public void setBottomInset(int px) {
        if (bottomInsetPx != px) {
            bottomInsetPx = px;
            requestLayout();
        }
    }

    public PcKeyboardEngine getEngine() {
        return engine;
    }

    /** The grid currently drawn and hit-tested. For tests and the accessibility helper. */
    KeyGrid currentGrid() {
        if (mode == MODE_FULL && engine.isFnLayer() && fnGrid != null) {
            return fnGrid;
        }
        return mainGrid;
    }

    // ---- layout -----------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? getResources().getDisplayMetrics().heightPixels
                : MeasureSpec.getSize(heightMeasureSpec);
        build(width, available);
        setMeasuredDimension(width, metrics.heightPx + bottomInsetPx);
    }

    /** Builds metrics and grids for a size; no-op when nothing changed. */
    void build(int width, int windowHeight) {
        if (width == builtWidth && windowHeight == builtHeight && mode == builtMode && metrics != null) {
            return;
        }
        builtWidth = width;
        builtHeight = windowHeight;
        builtMode = mode;
        if (mode == MODE_STRIP) {
            metrics = PcKeyboardMetrics.forStrip(width, density);
            mainGrid = metrics.grid(PcKeyboardLayout.IME_STRIP);
            fnGrid = null;
        } else {
            boolean landscape = width > windowHeight;
            PcKeyboardLayout main = PcKeyboardLayout.main(landscape);
            metrics = PcKeyboardMetrics.forKeyboard(width, windowHeight, density, landscape, main.rows.length);
            mainGrid = metrics.grid(main);
            fnGrid = metrics.grid(PcKeyboardLayout.fn(landscape));
            KeyGrid.TextMeasurer measurer = s -> {
                text.setTextSize(chipTextSize());
                return text.measureText(s);
            };
            mainGrid.addToolbar(PcKeyboardLayout.TOOLBAR_SYSTEM_KEYBOARD, PcKeyboardLayout.TOOLBAR_CHIPS,
                    PcKeyboardLayout.TOOLBAR_HIDE, measurer);
            fnGrid.addToolbar(PcKeyboardLayout.TOOLBAR_SYSTEM_KEYBOARD, PcKeyboardLayout.TOOLBAR_CHIPS,
                    PcKeyboardLayout.TOOLBAR_HIDE, measurer);
        }
        labelSizes = labelSizes(mainGrid);
        fnLabelSizes = fnGrid != null ? labelSizes(fnGrid) : new float[0];
        accessibility.invalidateRoot();
    }

    private float charTextSize() {
        float unit = metrics.unitPx(mainGrid != null ? mainGrid.layout : PcKeyboardLayout.PORTRAIT_MAIN);
        return Math.min(metrics.rowHeightPx * 0.42f, unit * 0.62f);
    }

    private float chipTextSize() {
        return 13f * density;
    }

    /**
     * Label sizes, shrunk so each label fits its key with a margin, and then evened out across
     * each row's same-width non-character keys so one long label ("Pause") does not leave its neighbours
     * looking like a different typeface. Measured once per build.
     */
    private float[] labelSizes(KeyGrid grid) {
        float[] sizes = new float[grid.size()];
        float charSize = charTextSize();
        for (int i = 0; i < grid.size(); i++) {
            PcKey key = grid.key(i);
            float size;
            if (key.style == PcKey.STYLE_CHAR && key.label.length() == 1) {
                size = charSize;
            } else if (key.style == PcKey.STYLE_CHIP) {
                size = chipTextSize();
            } else {
                size = Math.max(11f * density, Math.min(15f * density, charSize * 0.66f));
            }
            float room = (grid.right(i) - grid.left(i)) - metrics.gapXPx - 8f * density;
            text.setTextSize(size);
            float w = Math.max(text.measureText(key.label), text.measureText(key.shiftedLabel));
            if (w > room && room > 0) {
                size = Math.max(8f * density, size * room / w);
            }
            sizes[i] = size;
        }
        // Even out per row (same top edge), per style.
        for (int i = 0; i < grid.size(); i++) {
            if (isCharLabel(grid.key(i))) {
                continue;
            }
            float min = sizes[i];
            for (int j = 0; j < grid.size(); j++) {
                if (grid.top(j) == grid.top(i) && grid.key(j).style == grid.key(i).style
                        && grid.key(j).width == grid.key(i).width
                        && !isCharLabel(grid.key(j)) && grid.key(j).icon == PcKey.ICON_NONE) {
                    min = Math.min(min, sizes[j]);
                }
            }
            sizes[i] = min;
        }
        return sizes;
    }

    private static boolean isCharLabel(PcKey key) {
        return key.style == PcKey.STYLE_CHAR && key.label.length() == 1;
    }

    public int keyboardHeight() {
        return metrics != null ? metrics.heightPx + bottomInsetPx : 0;
    }

    // ---- drawing ----------------------------------------------------------------------------

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (metrics == null) {
            return;
        }
        fill.setColor(COLOR_BG);
        canvas.drawRect(0, 0, getWidth(), getHeight(), fill);
        fill.setColor(COLOR_EDGE);
        canvas.drawRect(0, 0, getWidth(), Math.max(1f, density), fill);

        KeyGrid grid = currentGrid();
        float[] sizes = grid == fnGrid ? fnLabelSizes : labelSizes;
        for (int i = 0; i < grid.size(); i++) {
            drawKey(canvas, grid, i, sizes[i]);
        }
        drawPreviews(canvas);
    }

    private boolean isPressed(KeyGrid grid, int index) {
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (pointerGrid[p] == grid && pointerIndex[p] == index) {
                return true;
            }
        }
        return false;
    }

    private void drawKey(Canvas canvas, KeyGrid grid, int i, float textSize) {
        PcKey key = grid.key(i);
        boolean toolbar = i >= grid.toolbarStart();
        float halfGapX = metrics.gapXPx / 2f;
        float halfGapY = metrics.gapYPx / 2f;
        rect.set(grid.left(i) + halfGapX, grid.top(i) + halfGapY,
                grid.right(i) - halfGapX, grid.bottom(i) - halfGapY);
        // Edge cells extend to the view's edge for touch; draw them at their unit size.
        if (grid.left(i) <= 0f) {
            rect.left = Math.max(rect.left, metrics.padXPx + halfGapX);
        }
        if (grid.right(i) >= metrics.widthPx) {
            rect.right = Math.min(rect.right, metrics.widthPx - metrics.padXPx - halfGapX);
        }
        if (grid.top(i) <= 0f && !toolbar) {
            rect.top = Math.max(rect.top, metrics.padYPx + halfGapY);
        }
        if (grid.bottom(i) >= metrics.heightPx) {
            rect.bottom = Math.min(rect.bottom, metrics.heightPx - metrics.padYPx - halfGapY);
        }
        if (toolbar) {
            rect.top = metrics.padYPx + 4f * density;
            rect.bottom = metrics.keysTopPx() - 4f * density;
        }

        boolean pressed = isPressed(grid, i);
        boolean shifted = engine.isModifierActive(ModifierLatch.MOD_SHIFT);
        int state = key.isModifier() ? engine.modifierState(key.code) : ModifierLatch.OFF;
        boolean held = key.isModifier() && engine.isModifierActive(key.code) && state == ModifierLatch.OFF;

        int bg;
        int fg;
        switch (key.style) {
            case PcKey.STYLE_CHAR:
                bg = pressed ? COLOR_KEY_PRESSED : COLOR_KEY;
                fg = COLOR_LABEL;
                break;
            case PcKey.STYLE_CHIP:
                bg = pressed ? COLOR_CHIP_PRESSED : COLOR_CHIP;
                fg = COLOR_CHIP_LABEL;
                break;
            case PcKey.STYLE_ACCENT:
                bg = pressed ? COLOR_ACCENT_PRESSED : COLOR_ACCENT;
                fg = COLOR_GREEN;
                break;
            default:
                bg = pressed || held ? COLOR_SPECIAL_PRESSED : COLOR_SPECIAL;
                fg = COLOR_LABEL_SPECIAL;
                break;
        }
        if (toolbar && key.kind == PcKey.KIND_ACTION) {
            bg = pressed ? COLOR_SPECIAL_PRESSED : COLOR_BG;
        }
        if (state == ModifierLatch.LOCKED) {
            bg = pressed ? COLOR_GREEN_HOVER : COLOR_GREEN;
            fg = COLOR_ON_GREEN;
        } else if (state == ModifierLatch.LATCHED) {
            fg = COLOR_GREEN;
        }

        float radius = key.style == PcKey.STYLE_CHIP && toolbar ? rect.height() / 2f : 7f * density;
        fill.setColor(bg);
        canvas.drawRoundRect(rect, radius, radius, fill);
        if (state == ModifierLatch.LATCHED) {
            stroke.setColor(COLOR_GREEN);
            stroke.setStrokeWidth(1.5f * density);
            float inset = 0.75f * density;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, radius, radius, stroke);
            rect.inset(-inset, -inset);
        }

        float cx = rect.centerX();
        float cy = rect.centerY();
        if (key.icon != PcKey.ICON_NONE) {
            drawIcon(canvas, key.icon, cx, cy, Math.min(Math.min(rect.height(), rect.width()) * 0.30f,
                    10f * density), fg);
        } else {
            text.setColor(fg);
            text.setTextSize(textSize);
            float baseline = cy - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(shifted ? key.shiftedLabel : key.label, cx, baseline, text);
        }
        if (key.shiftLabel != null && !shifted && rect.height() > 30f * density) {
            text.setColor(COLOR_HINT);
            text.setTextSize(Math.max(9f * density, textSize * 0.48f));
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(key.shiftLabel, rect.right - 4f * density, rect.top - text.ascent() + 2f * density, text);
            text.setTextAlign(Paint.Align.CENTER);
        }
        // Latched and locked are told apart by shape as well as colour: a dot, or a bar.
        if (state == ModifierLatch.LATCHED) {
            fill.setColor(COLOR_GREEN);
            canvas.drawCircle(cx, rect.bottom - 5f * density, 2f * density, fill);
        } else if (state == ModifierLatch.LOCKED) {
            fill.setColor(COLOR_ON_GREEN);
            float half = Math.min(rect.width() * 0.25f, 9f * density);
            canvas.drawRoundRect(cx - half, rect.bottom - 6.5f * density, cx + half,
                    rect.bottom - 4f * density, density, density, fill);
        }
    }

    private void drawIcon(Canvas canvas, int which, float cx, float cy, float r, int color) {
        icon.setColor(color);
        icon.setStrokeWidth(Math.max(1.6f * density, r * 0.16f));
        path.reset();
        switch (which) {
            case PcKey.ICON_BACKSPACE:
                path.moveTo(cx - r * 1.2f, cy);
                path.lineTo(cx - r * 0.55f, cy - r * 0.7f);
                path.lineTo(cx + r * 1.1f, cy - r * 0.7f);
                path.lineTo(cx + r * 1.1f, cy + r * 0.7f);
                path.lineTo(cx - r * 0.55f, cy + r * 0.7f);
                path.close();
                path.moveTo(cx - r * 0.05f, cy - r * 0.3f);
                path.lineTo(cx + r * 0.55f, cy + r * 0.3f);
                path.moveTo(cx + r * 0.55f, cy - r * 0.3f);
                path.lineTo(cx - r * 0.05f, cy + r * 0.3f);
                break;
            case PcKey.ICON_ENTER:
                path.moveTo(cx + r, cy - r * 0.7f);
                path.lineTo(cx + r, cy + r * 0.2f);
                path.lineTo(cx - r, cy + r * 0.2f);
                path.moveTo(cx - r * 0.55f, cy - r * 0.25f);
                path.lineTo(cx - r, cy + r * 0.2f);
                path.lineTo(cx - r * 0.55f, cy + r * 0.65f);
                break;
            case PcKey.ICON_SHIFT:
                path.moveTo(cx, cy - r);
                path.lineTo(cx + r, cy);
                path.lineTo(cx + r * 0.45f, cy);
                path.lineTo(cx + r * 0.45f, cy + r * 0.8f);
                path.lineTo(cx - r * 0.45f, cy + r * 0.8f);
                path.lineTo(cx - r * 0.45f, cy);
                path.lineTo(cx - r, cy);
                path.close();
                break;
            case PcKey.ICON_LEFT:
                chevron(cx + r * 0.3f, cy - r * 0.6f, cx - r * 0.3f, cy, cx + r * 0.3f, cy + r * 0.6f);
                break;
            case PcKey.ICON_RIGHT:
                chevron(cx - r * 0.3f, cy - r * 0.6f, cx + r * 0.3f, cy, cx - r * 0.3f, cy + r * 0.6f);
                break;
            case PcKey.ICON_UP:
                chevron(cx - r * 0.6f, cy + r * 0.3f, cx, cy - r * 0.3f, cx + r * 0.6f, cy + r * 0.3f);
                break;
            case PcKey.ICON_DOWN:
            case PcKey.ICON_HIDE:
                chevron(cx - r * 0.6f, cy - r * 0.3f, cx, cy + r * 0.3f, cx + r * 0.6f, cy - r * 0.3f);
                break;
            case PcKey.ICON_KEYBOARD:
                iconRect.set(cx - r * 1.2f, cy - r * 0.75f, cx + r * 1.2f, cy + r * 0.75f);
                canvas.drawRoundRect(iconRect, r * 0.2f, r * 0.2f, icon);
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 4; col++) {
                        float x = cx - r * 0.75f + col * r * 0.5f;
                        float y = cy - r * 0.35f + row * r * 0.35f;
                        path.moveTo(x, y);
                        path.lineTo(x + 0.01f, y);
                    }
                }
                path.moveTo(cx - r * 0.5f, cy + r * 0.4f);
                path.lineTo(cx + r * 0.5f, cy + r * 0.4f);
                break;
            case PcKey.ICON_SPACE:
                path.moveTo(cx - r * 1.4f, cy + r * 0.1f);
                path.lineTo(cx - r * 1.4f, cy + r * 0.45f);
                path.lineTo(cx + r * 1.4f, cy + r * 0.45f);
                path.lineTo(cx + r * 1.4f, cy + r * 0.1f);
                break;
            default:
                break;
        }
        canvas.drawPath(path, icon);
    }

    private void chevron(float x0, float y0, float x1, float y1, float x2, float y2) {
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        path.lineTo(x2, y2);
    }

    /** A larger copy of each held character key above the finger, which covers the key. */
    private void drawPreviews(Canvas canvas) {
        for (int p = 0; p < MAX_POINTERS; p++) {
            PcKey key = pointerKey[p];
            KeyGrid grid = pointerGrid[p];
            if (key == null || grid != currentGrid() || key.style != PcKey.STYLE_CHAR
                    || key.label.length() != 1) {
                continue;
            }
            int i = pointerIndex[p];
            float w = Math.max((grid.right(i) - grid.left(i)) * 1.25f, 44f * density);
            float h = metrics.rowHeightPx * 1.15f;
            float cx = (grid.left(i) + grid.right(i)) / 2f;
            cx = Math.max(w / 2f + metrics.padXPx, Math.min(metrics.widthPx - w / 2f - metrics.padXPx, cx));
            float bottom = grid.top(i) + metrics.gapYPx / 2f;
            float top = Math.max(0f, bottom - h);
            rect.set(cx - w / 2f, top, cx + w / 2f, top + h);
            fill.setColor(COLOR_KEY_PRESSED);
            canvas.drawRoundRect(rect, 9f * density, 9f * density, fill);
            stroke.setColor(COLOR_GREEN);
            stroke.setStrokeWidth(1f * density);
            canvas.drawRoundRect(rect, 9f * density, 9f * density, stroke);
            text.setColor(COLOR_LABEL);
            text.setTextSize(charTextSize() * 1.35f);
            String label = engine.isModifierActive(ModifierLatch.MOD_SHIFT) ? key.shiftedLabel : key.label;
            canvas.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2f, text);
        }
    }

    // ---- touch ------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (metrics == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                pointerDown(event.getPointerId(idx), event.getX(idx), event.getY(idx));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = event.getActionIndex();
                pointerUp(event.getPointerId(idx));
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                releasePointers();
                return true;
            default:
                // A finger keeps the key it landed on: the key is already down on the host,
                // so sliding cannot move it to another one.
                return true;
        }
    }

    /** A finger landed. Package-private so the press path can be driven without MotionEvents. */
    void pointerDown(int pointerId, float x, float y) {
        if (pointerId < 0 || pointerId >= MAX_POINTERS || pointerKey[pointerId] != null) {
            return;
        }
        KeyGrid grid = currentGrid();
        int index = grid.hitTest(x, y);
        if (index < 0) {
            return;
        }
        PcKey key = grid.key(index);
        pointerKey[pointerId] = key;
        pointerGrid[pointerId] = grid;
        pointerIndex[pointerId] = index;
        feedback();
        switch (key.kind) {
            case PcKey.KIND_KEY:
                engine.keyDown(key.code);
                break;
            case PcKey.KIND_MODIFIER:
                engine.modifierDown(key.code);
                handler.postDelayed(longPresses[pointerId], longPressMs);
                break;
            case PcKey.KIND_CHORD:
                engine.chordDown(key.chord);
                break;
            case PcKey.KIND_VIRTUAL_KEY:
                engine.virtualKeyTap((short) key.code);
                break;
            default:
                break;
        }
        invalidate();
    }

    void pointerUp(int pointerId) {
        if (pointerId < 0 || pointerId >= MAX_POINTERS) {
            return;
        }
        PcKey key = pointerKey[pointerId];
        if (key == null) {
            return;
        }
        pointerKey[pointerId] = null;
        pointerGrid[pointerId] = null;
        pointerIndex[pointerId] = -1;
        switch (key.kind) {
            case PcKey.KIND_KEY:
                engine.keyUp(key.code);
                break;
            case PcKey.KIND_MODIFIER:
                handler.removeCallbacks(longPresses[pointerId]);
                engine.modifierUp(key.code, SystemClock.uptimeMillis());
                break;
            case PcKey.KIND_CHORD:
                engine.chordUp(key.chord);
                break;
            case PcKey.KIND_ACTION:
                if (actions != null) {
                    actions.onKeyboardAction(key.code);
                }
                break;
            default:
                break;
        }
        invalidate();
    }

    /** Lifts every finger: every held key goes up on the host. Latches are left alone. */
    public void releasePointers() {
        for (int p = 0; p < MAX_POINTERS; p++) {
            PcKey key = pointerKey[p];
            if (key == null) {
                continue;
            }
            if (key.kind == PcKey.KIND_ACTION) {
                // A cancelled gesture must not hide the keyboard or switch it.
                pointerKey[p] = null;
                pointerGrid[p] = null;
                pointerIndex[p] = -1;
            } else {
                pointerUp(p);
            }
        }
    }

    private void feedback() {
        // Both follow the system settings: "touch vibration" and "touch sounds".
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        playSoundEffect(SoundEffectConstants.CLICK);
    }

    @Override
    public void onKeyboardStateChanged() {
        invalidate();
        accessibility.invalidateRoot();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (actions != null) {
            actions.onKeyboardConfigurationChanged(newConfig);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releasePointers();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            releasePointers();
        }
    }

    private final class LongPress implements Runnable {
        private final int pointer;

        LongPress(int pointer) {
            this.pointer = pointer;
        }

        @Override
        public void run() {
            PcKey key = pointerKey[pointer];
            if (key != null && key.isModifier() && engine.modifierLongPress(key.code)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        }
    }

    // ---- accessibility ----------------------------------------------------------------------

    /** Describes a key for TalkBack: its name, and a modifier's state. */
    String describe(PcKey key) {
        if (!key.isModifier()) {
            return key.description;
        }
        switch (engine.modifierState(key.code)) {
            case ModifierLatch.LATCHED:
                return getContext().getString(R.string.meow_pc_keyboard_state_latched, key.description);
            case ModifierLatch.LOCKED:
                return getContext().getString(R.string.meow_pc_keyboard_state_locked, key.description);
            default:
                return key.description;
        }
    }

    /** A tap, as TalkBack's double-tap performs it: down and up at once. */
    void tapIndex(int index) {
        KeyGrid grid = currentGrid();
        if (grid == null || index < 0 || index >= grid.size()) {
            return;
        }
        int slot = MAX_POINTERS - 1;
        if (pointerKey[slot] != null) {
            pointerUp(slot);
        }
        pointerDown(slot, (grid.left(index) + grid.right(index)) / 2f, (grid.top(index) + grid.bottom(index)) / 2f);
        pointerUp(slot);
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    private static final class Helper extends ExploreByTouchHelper {
        private final PcKeyboardView view;

        Helper(PcKeyboardView view) {
            super(view);
            this.view = view;
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            KeyGrid grid = view.currentGrid();
            if (grid == null) {
                return INVALID_ID;
            }
            int index = grid.hitTest(x, y);
            return index < 0 ? INVALID_ID : index;
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            KeyGrid grid = view.currentGrid();
            if (grid == null) {
                return;
            }
            for (int i = 0; i < grid.size(); i++) {
                ids.add(i);
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
            KeyGrid grid = view.currentGrid();
            if (grid == null || id >= grid.size()) {
                node.setContentDescription("");
                node.setBoundsInParent(new android.graphics.Rect());
                return;
            }
            PcKey key = grid.key(id);
            node.setContentDescription(view.describe(key));
            node.setClassName(android.widget.Button.class.getName());
            if (key.isModifier()) {
                node.setCheckable(true);
                node.setChecked(view.engine.modifierState(key.code) != ModifierLatch.OFF);
            }
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            node.setBoundsInParent(new android.graphics.Rect(
                    (int) Math.max(0, grid.left(id)), (int) grid.top(id),
                    (int) Math.min(view.getWidth() > 0 ? view.getWidth() : grid.right(id), grid.right(id)),
                    (int) grid.bottom(id)));
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                view.tapIndex(id);
                invalidateVirtualView(id);
                return true;
            }
            return false;
        }
    }
}
