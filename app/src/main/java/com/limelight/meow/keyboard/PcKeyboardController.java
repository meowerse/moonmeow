package com.limelight.meow.keyboard;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.Game;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Owns everything keyboard-shaped in the stream window: the PC keyboard, the strip above the
 * system keyboard, moving the stream out from under whichever keyboard is open, the system
 * bars, and releasing keys to the host when input is interrupted.
 *
 * <p>{@code Game} calls in through one-line hooks (see {@code docs/meow/TOUCHPOINTS.md},
 * {@code MEOW-TOUCH(pc-keyboard)}); everything else is here. The full design and behaviour
 * table is {@code docs/meow/pc-keyboard.md}.
 *
 * <p>UI thread throughout.
 */
public final class PcKeyboardController implements PcKeyboardView.Actions,
        ViewTreeObserver.OnGlobalLayoutListener, Application.ActivityLifecycleCallbacks {

    private static final long LIFT_MS = 220L;
    private static final long SHOW_MS = 170L;

    private final Game game;
    private final View streamContainer;
    private final FrameLayout content;
    private final PreferenceConfiguration prefConfig;
    private final PcKeyboardPreferences prefs;
    private final PcKeyboardEngine engine;
    private final HostKeySink sink;
    private final PcKeyboardView view;
    private final KeyboardVisibleArea area;
    private final TimeInterpolator decelerate = new DecelerateInterpolator(1.6f);

    private final int[] location = new int[2];
    private final Rect frame = new Rect();
    private final Rect obstruction = new Rect();
    private final Rect container = new Rect();
    private final java.util.ArrayList<KeyboardVisibleArea.Obstruction> obstructions = new java.util.ArrayList<>();

    private boolean fullShown;
    private boolean imeVisible;
    private boolean hardKeyboard;
    private float liftTarget;
    private boolean destroyed;

    PcKeyboardController(Game game, View streamContainer, FrameLayout content,
                         PreferenceConfiguration prefConfig, PcKeyboardPreferences prefs,
                         HostKeySink sink) {
        this.game = game;
        this.streamContainer = streamContainer;
        this.content = content;
        this.prefConfig = prefConfig;
        this.prefs = prefs;
        this.sink = sink;
        this.engine = new PcKeyboardEngine(sink, android.view.ViewConfiguration.getDoubleTapTimeout());
        this.view = new PcKeyboardView(game, engine);
        this.area = KeyboardVisibleArea.install(content);
        this.hardKeyboard = hasHardKeyboard(game.getResources().getConfiguration());

        area.setFocusMovedListener(() -> {
            if (fullShown || imeVisible) {
                update();
            }
        });
        view.setActions(this);
        view.setVisibility(View.INVISIBLE);
        view.setElevation(12f * game.getResources().getDisplayMetrics().density);
        content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.post(this::update);
            return insets;
        });
        content.getViewTreeObserver().addOnGlobalLayoutListener(this);

        // Anything in the window that must stay above the keyboards and that the stream must
        // be kept clear of (the quick bar).
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof KeyboardVisibleArea.Obstruction) {
                KeyboardVisibleArea.Obstruction o = (KeyboardVisibleArea.Obstruction) child;
                obstructions.add(o);
                o.setObstructionChangedListener(this::update);
            }
        }
        game.getApplication().registerActivityLifecycleCallbacks(this);
    }

    /**
     * Builds the keyboard for a stream window. Null on an external display, where the stream
     * window is not the one the user touches.
     */
    @Nullable
    public static PcKeyboardController attach(Game game, View streamContainer, PreferenceConfiguration prefConfig) {
        if (game.isOnExternalDisplay()) {
            return null;
        }
        View root = game.findViewById(android.R.id.content);
        if (!(root instanceof FrameLayout)) {
            return null;
        }
        try {
            PcKeyboardPreferences prefs = PcKeyboardPreferences.read(game);
            PcKeyboardController controller = new PcKeyboardController(game, streamContainer,
                    (FrameLayout) root, prefConfig, prefs, new GameKeySink(game));
            // We move the stream ourselves; the window must not also pan or resize for the
            // IME. Below API 30 there is no IME inset type, and the IME only shows up in the
            // visible display frame when the window asks for adjustResize — which a
            // FLAG_FULLSCREEN window does not actually get. Without that flag (full screen
            // off, multi-window) adjustResize would really shrink the stream, so there the
            // window keeps the mode it had and the lift does nothing it cannot see.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                game.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
            } else if (prefConfig.fullScreen && !game.isInMultiWindowMode()) {
                game.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
            }
            controller.applySystemBars();
            return controller;
        } catch (RuntimeException e) {
            // A keyboard that cannot be built must not take the stream down with it.
            e.printStackTrace();
            return null;
        }
    }

    public PcKeyboardEngine engine() {
        return engine;
    }

    PcKeyboardView view() {
        return view;
    }

    public boolean isShown() {
        return fullShown;
    }

    // ---- showing and hiding ----------------------------------------------------------------

    /** The quick bar's PC-keyboard button. */
    public void toggle() {
        if (fullShown) {
            hide();
        } else {
            show();
        }
    }

    public void show() {
        if (fullShown || destroyed) {
            return;
        }
        fullShown = true;
        PcKeyboardPreferences.setLastKeyboard(game, PcKeyboardPreferences.LAST_PC);
        if (imeVisible) {
            InputMethodManager imm = (InputMethodManager) game.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(streamContainer.getWindowToken(), 0);
            }
        }
        update();
        view.setAlpha(0f);
        view.setTranslationY(24f * game.getResources().getDisplayMetrics().density);
        view.animate().alpha(1f).translationY(0f).setDuration(SHOW_MS).setInterpolator(decelerate).start();
    }

    public void hide() {
        if (!fullShown) {
            return;
        }
        fullShown = false;
        update();
    }

    /** The quick bar's system-keyboard button: that is now the keyboard to remember. */
    public void preferSystemKeyboard() {
        PcKeyboardPreferences.setLastKeyboard(game, PcKeyboardPreferences.LAST_SYSTEM);
        hide();
    }

    /**
     * {@code Game.toggleKeyboard()}, which the three-finger tap and the game menu call: open or
     * close whichever keyboard the user last chose.
     *
     * @return true if handled here; false to toggle the system keyboard as before
     */
    public boolean onToggleKeyboard() {
        if (fullShown) {
            hide();
            return true;
        }
        if (!imeVisible && PcKeyboardPreferences.LAST_PC.equals(PcKeyboardPreferences.lastKeyboard(game))) {
            show();
            return true;
        }
        return false;
    }

    @Override
    public void onKeyboardAction(int action) {
        switch (action) {
            case PcKey.ACTION_HIDE:
                hide();
                break;
            case PcKey.ACTION_SYSTEM_KEYBOARD:
                preferSystemKeyboard();
                game.toggleKeyboard();
                break;
            case PcKey.ACTION_PC_KEYBOARD:
                show();
                break;
            default:
                break;
        }
    }

    @Override
    public void onKeyboardConfigurationChanged(Configuration config) {
        boolean hard = hasHardKeyboard(config);
        if (hard && !hardKeyboard && fullShown) {
            // A physical keyboard arrived: like the system keyboard, step out of its way.
            hide();
        }
        hardKeyboard = hard;
        update();
    }

    static boolean hasHardKeyboard(Configuration config) {
        return config.keyboard != Configuration.KEYBOARD_NOKEYS
                && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    // ---- hooks from Game -------------------------------------------------------------------

    /** A physical or system-keyboard key is going down to the host. */
    public void beforeExternalKey(int androidKeyCode) {
        engine.beforeExternalKey(androidKeyCode);
    }

    public void afterExternalKey(int androidKeyCode) {
        engine.afterExternalKey(androidKeyCode);
    }

    /** Text from the system keyboard: typed as a shortcut when a modifier is active. */
    public boolean onImeText(CharSequence text) {
        return engine.onImeText(text);
    }

    /** Back hides the PC keyboard before it does anything else. */
    public boolean onBackPressed() {
        if (fullShown) {
            hide();
            return true;
        }
        return false;
    }

    /**
     * The window lost or regained focus. On loss, nothing that is down may stay down on the
     * host: the fingers on the PC keyboard, its latches and locks, and — the upstream bug —
     * the modifiers of a physical keyboard, which {@code Game} forgets locally at this point
     * without ever telling the host.
     *
     * @param gameModifierFlags {@code Game}'s modifier flags before it clears them
     */
    public void onWindowFocusChanged(boolean hasFocus, int gameModifierFlags) {
        if (hasFocus) {
            return;
        }
        releaseAll();
        releaseModifierFlags(engineSink(), gameModifierFlags);
    }

    /** Releases on the host every modifier set in a {@code KeyboardPacket} flag set. */
    static void releaseModifierFlags(HostKeySink sink, int flags) {
        if ((flags & KeyboardPacket.MODIFIER_SHIFT) != 0) {
            sink.sendKey(android.view.KeyEvent.KEYCODE_SHIFT_LEFT, false);
            sink.sendKey(android.view.KeyEvent.KEYCODE_SHIFT_RIGHT, false);
        }
        if ((flags & KeyboardPacket.MODIFIER_CTRL) != 0) {
            sink.sendKey(android.view.KeyEvent.KEYCODE_CTRL_LEFT, false);
            sink.sendKey(android.view.KeyEvent.KEYCODE_CTRL_RIGHT, false);
        }
        if ((flags & KeyboardPacket.MODIFIER_ALT) != 0) {
            sink.sendKey(android.view.KeyEvent.KEYCODE_ALT_LEFT, false);
            sink.sendKey(android.view.KeyEvent.KEYCODE_ALT_RIGHT, false);
        }
        if ((flags & KeyboardPacket.MODIFIER_META) != 0) {
            sink.sendKey(android.view.KeyEvent.KEYCODE_META_LEFT, false);
            sink.sendKey(android.view.KeyEvent.KEYCODE_META_RIGHT, false);
        }
    }

    private HostKeySink sinkOverride;

    private HostKeySink engineSink() {
        return sinkOverride != null ? sinkOverride : sink;
    }

    /** Tests only: where released modifier flags go. */
    void setSinkForTest(HostKeySink sink) {
        this.sinkOverride = sink;
    }

    public void releaseAll() {
        view.releasePointers();
        engine.releaseAll();
    }

    /**
     * {@code Game}'s immersive-mode runnable asks first. With "keep the navigation bar" on and
     * the stream fullscreen, the status bar is hidden and the navigation bar stays: the stream
     * is letterboxed anyway, so the bar costs nothing, and back and home stay one tap away.
     * The window is laid out above the bar rather than under it, so nothing draws beneath it.
     *
     * @return true if the flags were applied here; false for Game's own behaviour
     */
    @SuppressWarnings("deprecation")
    public boolean applySystemBars() {
        if (!prefs.keepNavBar || !prefConfig.fullScreen || game.isInMultiWindowMode()) {
            return false;
        }
        game.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        return true;
    }

    // ---- layout ----------------------------------------------------------------------------

    @Override
    public void onGlobalLayout() {
        update();
    }

    /**
     * Reads the insets and the views, then places the keyboard and the stream. Idempotent:
     * it only writes what changed, so the layout pass it may trigger settles at once.
     */
    void update() {
        if (destroyed) {
            return;
        }
        View decor = game.getWindow().getDecorView();
        int windowWidth = decor.getWidth();
        int windowHeight = decor.getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) {
            return;
        }
        int imeInset = 0;
        int topInset = 0;
        int navInset = 0;
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                imeInset = insets.isVisible(WindowInsets.Type.ime())
                        ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
                topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
                navInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                navInset = insets.getStableInsetBottom();
                decor.getWindowVisibleDisplayFrame(frame);
                imeInset = legacyImeInset(windowHeight, frame.bottom, navInset);
                if (imeInset > 0) {
                    navInset = 0;
                }
            }
        }
        boolean pip = game.isInPictureInPictureMode();
        boolean ime = imeInset > 0 && !pip;
        if (ime && !imeVisible && fullShown) {
            // The system keyboard came up some other way: it wins, and becomes the last used.
            fullShown = false;
            PcKeyboardPreferences.setLastKeyboard(game, PcKeyboardPreferences.LAST_SYSTEM);
        }
        imeVisible = ime;

        content.getLocationInWindow(location);
        int contentBottom = location[1] + content.getHeight();
        int navGap = Math.max(0, windowHeight - contentBottom);

        int visibleBottom = windowHeight - navInset;
        boolean strip = !fullShown && ime && prefs.imeExtraKeys;
        if (fullShown && !pip) {
            view.setMode(PcKeyboardView.MODE_FULL);
            view.setBottomInset(Math.max(0, navInset - navGap));
            setShown(true);
            measureKeyboard();
            visibleBottom = Math.min(visibleBottom, contentBottom - view.getMeasuredHeight());
        } else if (strip && !pip) {
            view.setMode(PcKeyboardView.MODE_STRIP);
            view.setBottomInset(0);
            setShown(true);
            measureKeyboard();
            float y = (windowHeight - imeInset) - contentBottom;
            if (view.getTranslationY() != y) {
                view.animate().cancel();
                view.setAlpha(1f);
                view.setTranslationY(y);
            }
            visibleBottom = Math.min(visibleBottom, windowHeight - imeInset - view.getMeasuredHeight());
        } else {
            if (view.getVisibility() == View.VISIBLE) {
                releaseAll();
            }
            setShown(false);
            if (ime) {
                visibleBottom = Math.min(visibleBottom, windowHeight - imeInset);
            }
        }
        // Keyboards are placed; now what stands over the stream on top of them.
        int keyboardTop = visibleBottom;
        int visibleLeft = 0;
        int visibleRight = windowWidth;
        containerRect(container);
        for (int i = 0; i < obstructions.size(); i++) {
            KeyboardVisibleArea.Obstruction o = obstructions.get(i);
            o.arrange(container, location[0] + content.getWidth(), contentBottom, pip ? windowHeight : keyboardTop);
            o.placeAboveKeyboards(pip ? windowHeight : keyboardTop);
            if (pip || !o.obstructionInWindow(keyboardTop, obstruction)) {
                continue;
            }
            visible[0] = visibleLeft;
            visible[1] = visibleRight;
            visible[2] = visibleBottom;
            clearOf(container, obstruction, visible);
            visibleLeft = visible[0];
            visibleRight = visible[1];
            visibleBottom = visible[2];
        }
        area.publish(visibleLeft, topInset, visibleRight, visibleBottom);
        if (pip) {
            applyLift(0, 0, windowWidth, windowHeight);
        } else {
            applyLift(visibleLeft, topInset, visibleRight, visibleBottom);
        }
    }

    /** Below API 30: the IME is whatever of the window's bottom the visible frame lost. */
    static int legacyImeInset(int windowHeight, int visibleFrameBottom, int navInset) {
        int gap = windowHeight - visibleFrameBottom;
        // Less than this is a navigation bar, not a keyboard.
        return gap > windowHeight * 0.15f ? gap : 0;
    }

    private void setShown(boolean shown) {
        int visibility = shown ? View.VISIBLE : View.INVISIBLE;
        if (view.getVisibility() != visibility) {
            view.setVisibility(visibility);
        }
    }

    private void measureKeyboard() {
        view.measure(View.MeasureSpec.makeMeasureSpec(content.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(content.getHeight(), View.MeasureSpec.AT_MOST));
    }

    private final int[] visible = new int[3];

    /**
     * Narrows the visible area so the stream is kept clear of one obstruction. A bar wider than
     * tall covers from its top down; one taller than wide covers its side of the stream. One
     * that does not overlap the stream's columns at all (it sits in the letterbox) costs
     * nothing.
     *
     * @param visible {left, right, bottom} in window pixels, narrowed in place
     */
    static void clearOf(Rect container, Rect obstruction, int[] visible) {
        if (obstruction.right <= container.left || obstruction.left >= container.right
                || obstruction.bottom <= container.top || obstruction.top >= container.bottom) {
            return;
        }
        if (obstruction.width() >= obstruction.height()) {
            visible[2] = Math.min(visible[2], obstruction.top);
        } else if (obstruction.centerX() > container.centerX()) {
            visible[1] = Math.min(visible[1], obstruction.left);
        } else {
            visible[0] = Math.max(visible[0], obstruction.right);
        }
    }

    /** The stream container's layout box in window pixels, without the lift. */
    private void containerRect(Rect out) {
        View parent = (View) streamContainer.getParent();
        parent.getLocationInWindow(location);
        out.set(location[0] + streamContainer.getLeft(), location[1] + streamContainer.getTop(),
                location[0] + streamContainer.getRight(), location[1] + streamContainer.getBottom());
    }

    private void applyLift(int visibleLeft, int visibleTop, int visibleRight, int visibleBottom) {
        float lift = 0f;
        float shift = 0f;
        if (prefs.liftStream) {
            containerRect(container);
            lift = StreamLift.liftFor(container.top, container.bottom, visibleTop, visibleBottom, area.focusY());
            shift = StreamLift.shiftFor(container.left, container.right, visibleLeft, visibleRight);
        }
        if (Math.abs(lift - liftTarget) < 0.5f && Math.abs(shift - shiftTarget) < 0.5f) {
            return;
        }
        liftTarget = lift;
        shiftTarget = shift;
        streamContainer.animate().translationY(lift).translationX(shift).setDuration(LIFT_MS)
                .setInterpolator(decelerate).withEndAction(streamMoved).start();
    }

    private float shiftTarget;
    /** Tells the area the stream settled at its new place (the binder re-reports). */
    private final Runnable streamMoved = this::notifyStreamMoved;

    private void notifyStreamMoved() {
        area.onStreamMoved();
    }

    float liftTarget() {
        return liftTarget;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == game) {
            // Before onStop tears the connection down: every key up while it still exists.
            releaseAll();
        }
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (activity != game) {
            return;
        }
        destroyed = true;
        game.getApplication().unregisterActivityLifecycleCallbacks(this);
        content.getViewTreeObserver().removeOnGlobalLayoutListener(this);
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }
}
