package com.limelight.meow.keyboard;

import android.view.View;

import com.limelight.R;

import java.util.ArrayList;

/**
 * The "visible area changed" signal: which part of the window is not covered by a keyboard
 * (the system keyboard, the strip above it, or the PC keyboard) or by a system bar.
 *
 * <p>One per window, published by {@link PcKeyboardController} and found from any view in
 * that window with {@link #of(View)}, so a consumer needs no reference to the controller and
 * {@code Game} needs no wiring for it. Consumers:
 * <ul>
 *   <li>the quick bar, which rides above the keyboard instead of disappearing under it;</li>
 *   <li>the cursor follower (PR #16, {@code CursorFollowController}): its viewport binder
 *       should take its visible bottom from {@link #visibleBottom()} instead of subtracting
 *       only the IME inset, so the PC keyboard counts too, and call
 *       {@code ensureVisible()} from a listener registered here;</li>
 *   <li>and the other way round: whoever knows where the host cursor or caret is sets a
 *       {@link FocusSource}, and the stream lift keeps that point above the keyboard.</li>
 * </ul>
 *
 * <p>UI thread. Coordinates are window pixels.
 */
public final class KeyboardVisibleArea {

    public interface Listener {
        /** The uncovered rectangle of the window changed. */
        void onVisibleAreaChanged(int left, int top, int right, int bottom);
    }

    /** Where the user's point of interest (host cursor, text caret) is on the stream. */
    public interface FocusSource {
        /**
         * @return its y in the stream container's own pixels, or {@link Float#NaN} if unknown
         */
        float focusY();
    }

    private final ArrayList<Listener> listeners = new ArrayList<>();
    private FocusSource focusSource;
    private int left;
    private int top;
    private int right;
    private int bottom;
    private boolean known;

    /** The area published in the window {@code anyView} belongs to, or null if none is. */
    public static KeyboardVisibleArea of(View anyView) {
        if (anyView == null) {
            return null;
        }
        Object tag = anyView.getRootView().getTag(R.id.meow_keyboard_visible_area);
        return tag instanceof KeyboardVisibleArea ? (KeyboardVisibleArea) tag : null;
    }

    static KeyboardVisibleArea install(View anyView) {
        KeyboardVisibleArea existing = of(anyView);
        if (existing != null) {
            return existing;
        }
        KeyboardVisibleArea area = new KeyboardVisibleArea();
        anyView.getRootView().setTag(R.id.meow_keyboard_visible_area, area);
        return area;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            if (known) {
                listener.onVisibleAreaChanged(left, top, right, bottom);
            }
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setFocusSource(FocusSource source) {
        this.focusSource = source;
    }

    float focusY() {
        return focusSource != null ? focusSource.focusY() : Float.NaN;
    }

    public boolean isKnown() {
        return known;
    }

    public int visibleTop() {
        return top;
    }

    public int visibleBottom() {
        return bottom;
    }

    /** Publishes a new area; listeners hear about real changes only. */
    void publish(int left, int top, int right, int bottom) {
        if (known && left == this.left && top == this.top && right == this.right && bottom == this.bottom) {
            return;
        }
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.known = true;
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onVisibleAreaChanged(left, top, right, bottom);
        }
    }
}
