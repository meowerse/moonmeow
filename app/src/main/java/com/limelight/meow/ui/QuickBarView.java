package com.limelight.meow.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.limelight.R;
import com.limelight.meow.keyboard.KeyboardVisibleArea;

/**
 * Translucent control bar that sits <em>outside</em> the StreamContainer transform so zoom/pan
 * never moves it. Always shown by default, at the bottom in portrait and down the right-hand
 * letterbox in landscape, and the stream is kept clear of it (see {@link #obstructionInWindow}).
 * With the "auto-hide toolbar" setting it collapses to a handle line after 3 s, as it used to,
 * and is then a transient overlay the stream does not move for. A 2-finger tap toggles it
 * either way. Keeps Game.java small: all view logic lives here.
 */
public class QuickBarView extends FrameLayout implements KeyboardVisibleArea.Obstruction {

    public interface Listener {
        void onKeyboard();

        /** The PC keyboard button. Default no-op, so a listener without one still compiles. */
        default void onPcKeyboard() {
        }

        void onToggleLocalCursor();
        void onCycleMouseMode();
        void onTogglePerfOverlay();
        void onOpenMenu();
    }

    private static final long AUTO_HIDE_MS = 3000L;
    private static final int BAR_ANIM_MS = 180;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private View handleView;
    private View barContainer;
    private LinearLayout barRow;
    private HorizontalScrollView scrollView;
    private ScrollView verticalScrollView;
    private boolean barVisible = true;
    private boolean landscape;
    private boolean autoHide;
    private Runnable obstructionChanged;

    // Guarded: a permanent bar never collapses on a timer armed while it was transient.
    private final Runnable autoHideRunnable = () -> {
        if (isTransient()) {
            hideBar();
        }
    };
    /** The user hid the bar with a two-finger tap: do not bring it back on our own. */
    private boolean hiddenByUser;

    // 2-finger tap detection
    private long twoFingerDownTime = 0;
    private float twoFingerDownX, twoFingerDownY;
    private static final long TWO_FINGER_TAP_TIMEOUT_MS = 300;
    private static final float TWO_FINGER_MOVE_SLOP_DP = 20f;

    public QuickBarView(@NonNull Context context, @NonNull Listener listener) {
        super(context);
        this.listener = listener;
        this.autoHide = QuickBarPreferences.autoHide(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setClickable(false);
        setFocusable(false);
        buildViews(context);
        applyOrientation(context.getResources().getConfiguration());
        // Start hidden until stream connects — onStreamStarted() will show
        barVisible = false;
        barContainer.setVisibility(GONE);
        handleView.setVisibility(GONE);
        setVisibility(GONE);
    }

    private void buildViews(Context ctx) {
        barContainer = new FrameLayout(ctx);
        int barBg = Color.parseColor("#D91E1E20");
        GradientDrawable barBgDrawable = new GradientDrawable();
        barBgDrawable.setColor(barBg);
        barBgDrawable.setCornerRadius(dp(20));
        barBgDrawable.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        barContainer.setBackground(barBgDrawable);
        barContainer.setElevation(dp(6));
        barContainer.setClipToOutline(true);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barLp.bottomMargin = dp(12);
        barLp.leftMargin = dp(8);
        barLp.rightMargin = dp(8);
        barContainer.setLayoutParams(barLp);
        barContainer.setPadding(dp(6), dp(6), dp(6), dp(6));

        scrollView = new HorizontalScrollView(ctx);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        barRow = new LinearLayout(ctx);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        barRow.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addBarButton(ctx, "KB", "Keyboard", android.R.drawable.ic_input_add, listener::onKeyboard);
        addBarButton(ctx, ctx.getString(R.string.meow_quickbar_pc_keyboard),
                ctx.getString(R.string.meow_quickbar_pc_keyboard_description),
                R.drawable.meow_ic_pc_keyboard, listener::onPcKeyboard);
        addBarButton(ctx, "Cursor", "Toggle local cursor", android.R.drawable.ic_menu_compass, listener::onToggleLocalCursor);
        addBarButton(ctx, "Mode", "Switch mouse mode", android.R.drawable.ic_menu_preferences, listener::onCycleMouseMode);
        addBarButton(ctx, "HUD", "Toggle performance overlay", android.R.drawable.ic_menu_info_details, listener::onTogglePerfOverlay);
        addBarButton(ctx, "Menu", "Open menu", android.R.drawable.ic_menu_more, listener::onOpenMenu);

        verticalScrollView = new ScrollView(ctx);
        verticalScrollView.setVerticalScrollBarEnabled(false);
        verticalScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        verticalScrollView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView.addView(barRow);
        ((FrameLayout) barContainer).addView(scrollView);
        addView(barContainer);

        handleView = new View(ctx);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#99FFFFFF"));
        handleBg.setCornerRadius(dp(3));
        handleView.setBackground(handleBg);
        handleView.setElevation(dp(4));
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(48), dp(6), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        handleLp.bottomMargin = dp(4);
        handleView.setLayoutParams(handleLp);
        handleView.setClickable(true);
        handleView.setFocusable(true);
        handleView.setContentDescription("Show toolbar");
        handleView.setOnClickListener(v -> showBar());
        handleView.setVisibility(GONE);
        addView(handleView);
    }

    private void addBarButton(Context ctx, String label, String contentDesc, int fallbackIcon, Runnable action) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        // Fixed width: every button the same size, and no label ("Cursor", "PC") truncated.
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.leftMargin = dp(3);
        colLp.rightMargin = dp(3);
        colLp.topMargin = dp(3);
        colLp.bottomMargin = dp(3);
        col.setLayoutParams(colLp);
        col.setClickable(true);
        col.setFocusable(true);
        col.setContentDescription(contentDesc);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#22FFFFFF"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#22FFFFFF"));
        col.setBackground(bg);
        col.setPadding(dp(4), dp(8), dp(4), dp(6));

        ImageButton icon = new ImageButton(ctx);
        icon.setImageResource(fallbackIcon);
        icon.setBackgroundColor(Color.TRANSPARENT);
        icon.setClickable(false);
        icon.setFocusable(false);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        icon.setLayoutParams(iconLp);
        icon.setColorFilter(Color.WHITE);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding(0, dp(2), 0, 0);

        col.addView(icon);
        col.addView(tv);

        col.setOnClickListener(v -> {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(70).start()).start();
            action.run();
            scheduleAutoHide();
        });
        barRow.addView(col);
    }

    public void onStreamStarted() {
        setVisibility(VISIBLE);
        showBar();
    }

    public void onStreamStopped() {
        handler.removeCallbacks(autoHideRunnable);
        hideImmediately();
        setVisibility(GONE);
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        obstructionChanged = null;
    }

    public void onConfigurationChanged(Configuration newConfig) {
        applyOrientation(newConfig);
    }

    private void applyOrientation(Configuration cfg) {
        // Until the keyboard controller arranges it for the real stream box, the bar stands
        // across the bottom, as it always did. Afterwards arrange() owns the placement, so a
        // configuration change (rotation included) waits for it instead of flashing a layout.
        if (!layoutKnown) {
            setVertical(false);
        }
    }

    /** Across the bottom, or down the right-hand side. No-op when unchanged. */
    private void setVertical(boolean vertical) {
        if (layoutKnown && vertical == landscape) {
            return;
        }
        layoutKnown = true;
        landscape = vertical;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) barContainer.getLayoutParams();
        FrameLayout bar = (FrameLayout) barContainer;
        ViewGroup from = landscape ? scrollView : verticalScrollView;
        ViewGroup to = landscape ? verticalScrollView : scrollView;
        if (barRow.getParent() == from) {
            from.removeView(barRow);
            bar.removeView(from);
            to.addView(barRow);
            bar.addView(to);
        }
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        FrameLayout.LayoutParams handleLp = (FrameLayout.LayoutParams) handleView.getLayoutParams();
        if (landscape) {
            // Down the right-hand side: a 16:9 desktop on a 20:9 phone leaves ~90 dp of
            // letterbox there, which the bar fits into without covering the stream.
            barRow.setOrientation(LinearLayout.VERTICAL);
            barRow.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < barRow.getChildCount(); i++) {
                LinearLayout.LayoutParams c = (LinearLayout.LayoutParams) barRow.getChildAt(i).getLayoutParams();
                c.width = dp(60);
                c.weight = 0f;
            }
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            handleLp.width = dp(6);
            handleLp.height = dp(48);
            handleLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            handleLp.bottomMargin = 0;
            handleLp.rightMargin = dp(4);
        } else {
            barRow.setOrientation(LinearLayout.HORIZONTAL);
            // Across the bottom, every button an equal share of the width: all of them on
            // screen at once, none truncated, none needing a scroll.
            barRow.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scrollView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < barRow.getChildCount(); i++) {
                LinearLayout.LayoutParams c = (LinearLayout.LayoutParams) barRow.getChildAt(i).getLayoutParams();
                c.width = 0;
                c.weight = 1f;
            }
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.bottomMargin = dp(12);
            handleLp.width = dp(48);
            handleLp.height = dp(6);
            handleLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(4);
            handleLp.rightMargin = 0;
        }
        handleView.setLayoutParams(handleLp);
        barContainer.setLayoutParams(lp);
        notifyObstructionChangedAfterLayout();
    }

    private boolean layoutKnown;
    /** No place to stand without covering the stream: behave as auto-hide for now. */
    private boolean transientBar;

    /**
     * Where to stand, decided from where the letterbox actually is rather than from the
     * orientation:
     * <ul>
     *   <li>a keyboard is open in portrait: across the bottom, ridden up above the keyboard;</li>
     *   <li>a keyboard is open in landscape: across the bottom, but transient — the band above
     *       a landscape keyboard is too thin to give a permanent bar a share of it, and the
     *       keyboard has its own hide key;</li>
     *   <li>otherwise: in the bottom letterbox if it is deep enough, else in the right-hand one
     *       if it is wide enough, else (a 16:9 or 16:10 window with no letterbox) across the
     *       bottom as a transient bar, exactly as with "auto-hide toolbar" on, rather than
     *       covering the stream for good.</li>
     * </ul>
     */
    @Override
    public void arrange(Rect stream, int contentRight, int contentBottom, int keyboardTopInWindow) {
        int need = dp(BAR_SPACE_DP);
        boolean keyboard = keyboardTopInWindow < contentBottom - 1;
        boolean landscapeWindow = getWidth() > getHeight();
        boolean vertical;
        boolean nowTransient;
        if (keyboard) {
            vertical = false;
            nowTransient = landscapeWindow;
        } else if (contentBottom - stream.bottom >= need) {
            vertical = false;
            nowTransient = false;
        } else if (contentRight - stream.right >= need) {
            vertical = true;
            nowTransient = false;
        } else {
            vertical = false;
            nowTransient = true;
        }
        setVertical(vertical);
        if (nowTransient != transientBar) {
            transientBar = nowTransient;
            // Re-arms the timer when transient, and cancels one armed earlier when not.
            scheduleAutoHide();
            if (!isTransient() && !hiddenByUser && getVisibility() == VISIBLE && !barVisible) {
                showBar();
            }
        }
    }

    /** The room a bar needs beside or below the stream, margins included. */
    // Column 60 + margins 6 + padding 12 + outer margin 8 + obstruction margin 4 (vertical);
    // the horizontal bar comes to about 89. A 20:9 phone leaves ~91 dp beside a 16:9 stream.
    static final int BAR_SPACE_DP = 90;

    private boolean isTransient() {
        return autoHide || transientBar;
    }

    /** Whether the bar collapses to a handle on its own (the "auto-hide toolbar" setting). */
    public boolean isAutoHide() {
        return autoHide;
    }

    /** Overrides the setting; for tests and for a future in-stream toggle. */
    public void setAutoHide(boolean autoHide) {
        this.autoHide = autoHide;
        scheduleAutoHide();
        notifyObstructionChanged();
    }

    public boolean isBarShown() {
        return barVisible;
    }

    public void showBar() {
        hiddenByUser = false;
        if (barVisible) {
            scheduleAutoHide();
            return;
        }
        barVisible = true;
        barContainer.setVisibility(VISIBLE);
        handleView.setVisibility(GONE);
        barContainer.setAlpha(0f);
        barContainer.setTranslationY(dp(10));
        barContainer.animate().alpha(1f).translationY(0).setDuration(BAR_ANIM_MS).start();
        scheduleAutoHide();
        notifyObstructionChangedAfterLayout();
    }

    public void hideBar() {
        if (!barVisible) return;
        barVisible = false;
        barContainer.animate().alpha(0f).translationY(dp(10)).setDuration(BAR_ANIM_MS).withEndAction(() -> {
            barContainer.setVisibility(GONE);
            handleView.setVisibility(VISIBLE);
            handleView.setAlpha(0f);
            handleView.animate().alpha(1f).setDuration(BAR_ANIM_MS).start();
        }).start();
        handler.removeCallbacks(autoHideRunnable);
        notifyObstructionChanged();
    }

    public void hideImmediately() {
        handler.removeCallbacks(autoHideRunnable);
        barVisible = false;
        barContainer.setVisibility(GONE);
        handleView.setVisibility(GONE);
        notifyObstructionChanged();
    }

    private void scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable);
        if (isTransient() && barVisible && getVisibility() == VISIBLE) {
            handler.postDelayed(autoHideRunnable, AUTO_HIDE_MS);
        }
    }

    public void toggleFromGesture() {
        hiddenByUser = barVisible;
        if (barVisible) hideBar();
        else showBar();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getPointerCount() == 2 && ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            twoFingerDownTime = ev.getEventTime();
            twoFingerDownX = (ev.getX(0) + ev.getX(1)) / 2f;
            twoFingerDownY = (ev.getY(0) + ev.getY(1)) / 2f;
        } else if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            long dt = ev.getEventTime() - twoFingerDownTime;
            if (twoFingerDownTime != 0 && dt < TWO_FINGER_TAP_TIMEOUT_MS && dt > 40) {
                float cx = ev.getX(0);
                float cy = ev.getY(0);
                float slop = dp(TWO_FINGER_MOVE_SLOP_DP);
                if (Math.abs(cx - twoFingerDownX) < slop && Math.abs(cy - twoFingerDownY) < slop) {
                    twoFingerDownTime = 0;
                    post(this::toggleFromGesture);
                    return true;
                }
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    /**
     * A keyboard opened or closed: ride above it rather than disappear under it. The bar and
     * its handle sit inside this full-window view, so the whole view moves.
     */
    @Override
    public void placeAboveKeyboards(int keyboardTopInWindow) {
        float lift = liftFor(keyboardTopInWindow);
        if (Math.abs(lift - lastLift) >= 0.5f) {
            lastLift = lift;
            animate().translationY(lift).setDuration(BAR_ANIM_MS).start();
        }
    }

    /** Where the bottom is laid out, not drawn: independent of the translation applied. */
    private float liftFor(int keyboardTopInWindow) {
        if (getHeight() == 0) {
            return 0f;
        }
        return Math.min(0f, keyboardTopInWindow - (parentTopInWindow() + getBottom()));
    }

    private int parentTopInWindow() {
        if (getParent() instanceof View) {
            ((View) getParent()).getLocationInWindow(windowLocation);
            return windowLocation[1];
        }
        return 0;
    }

    private int parentLeftInWindow() {
        if (getParent() instanceof View) {
            ((View) getParent()).getLocationInWindow(windowLocation);
            return windowLocation[0];
        }
        return 0;
    }

    /**
     * The shown bar, with a small margin, once placed above the keyboards. Only while it is
     * permanent: an auto-hiding bar is a transient overlay, and moving the stream for it every
     * few seconds would be worse than being covered briefly.
     */
    @Override
    public boolean obstructionInWindow(int keyboardTopInWindow, Rect out) {
        if (isTransient() || !barVisible || getVisibility() != VISIBLE
                || barContainer.getVisibility() != VISIBLE || barContainer.getWidth() == 0) {
            return false;
        }
        int x = parentLeftInWindow() + getLeft();
        int y = parentTopInWindow() + getTop() + Math.round(liftFor(keyboardTopInWindow));
        int m = dp(4);
        out.set(x + barContainer.getLeft() - m, y + barContainer.getTop() - m,
                x + barContainer.getRight() + m, y + barContainer.getBottom() + m);
        return true;
    }

    @Override
    public void setObstructionChangedListener(Runnable listener) {
        this.obstructionChanged = listener;
    }

    private void notifyObstructionChanged() {
        if (obstructionChanged != null) {
            obstructionChanged.run();
        }
    }

    /**
     * Soon, not synchronously: the caller is mid-change. The bounds after the next layout
     * reach the controller anyway, through its global-layout listener.
     */
    private void notifyObstructionChangedAfterLayout() {
        // The handler, not View.post: that queue only runs once attached.
        handler.post(this::notifyObstructionChanged);
    }

    private float lastLift;
    private final int[] windowLocation = new int[2];

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
