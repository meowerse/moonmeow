package com.limelight.meow.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Translucent control bar that sits <em>outside</em> the StreamContainer transform so zoom/pan
 * never moves it. Auto-hides after 3 s, reappears via handle tap or 2-finger tap.
 * Keeps Game.java small: all view logic lives here.
 */
public class QuickBarView extends FrameLayout {

    public interface Listener {
        void onKeyboard();
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
    private boolean barVisible = true;

    private final Runnable autoHideRunnable = this::hideBar;

    // 2-finger tap detection
    private long twoFingerDownTime = 0;
    private float twoFingerDownX, twoFingerDownY;
    private static final long TWO_FINGER_TAP_TIMEOUT_MS = 300;
    private static final float TWO_FINGER_MOVE_SLOP_DP = 20f;

    public QuickBarView(@NonNull Context context, @NonNull Listener listener) {
        super(context);
        this.listener = listener;
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
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        barRow = new LinearLayout(ctx);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        barRow.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addBarButton(ctx, "KB", "Keyboard", android.R.drawable.ic_input_add, listener::onKeyboard);
        addBarButton(ctx, "Cursor", "Toggle local cursor", android.R.drawable.ic_menu_compass, listener::onToggleLocalCursor);
        addBarButton(ctx, "Mode", "Switch mouse mode", android.R.drawable.ic_menu_preferences, listener::onCycleMouseMode);
        addBarButton(ctx, "HUD", "Toggle performance overlay", android.R.drawable.ic_menu_info_details, listener::onTogglePerfOverlay);
        addBarButton(ctx, "Menu", "Open menu", android.R.drawable.ic_menu_more, listener::onOpenMenu);

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
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.leftMargin = dp(4);
        colLp.rightMargin = dp(4);
        col.setLayoutParams(colLp);
        col.setClickable(true);
        col.setFocusable(true);
        col.setContentDescription(contentDesc);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#22FFFFFF"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#22FFFFFF"));
        col.setBackground(bg);
        col.setPadding(dp(10), dp(8), dp(10), dp(6));

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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
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
        handler.removeCallbacks(autoHideRunnable);
    }

    public void onConfigurationChanged(Configuration newConfig) {
        applyOrientation(newConfig);
    }

    private void applyOrientation(Configuration cfg) {
        boolean isPortrait = cfg.orientation == Configuration.ORIENTATION_PORTRAIT;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) barContainer.getLayoutParams();
        if (isPortrait) {
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(16);
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(12);
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        barContainer.setLayoutParams(lp);
    }

    public void showBar() {
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
    }

    public void hideImmediately() {
        handler.removeCallbacks(autoHideRunnable);
        barVisible = false;
        barContainer.setVisibility(GONE);
        handleView.setVisibility(GONE);
    }

    private void scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable);
        if (barVisible && getVisibility() == VISIBLE) {
            handler.postDelayed(autoHideRunnable, AUTO_HIDE_MS);
        }
    }

    public void toggleFromGesture() {
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

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
