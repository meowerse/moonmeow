package com.limelight.meow.keyboard;

import android.graphics.Rect;
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
 *   <li>the viewport binder ({@code StreamViewportBinder}): it turns every change into its
 *       bottom obstruction, so cursor follow and the host crop end above the keyboards, and
 *       re-reports when the stream itself moves ({@link #setStreamMovedListener});</li>
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

    /**
     * Something drawn over the stream that the stream should be kept clear of, and that must
     * itself stay above the keyboards: the quick bar. Found by the controller among the
     * content view's children.
     */
    public interface Obstruction {
        /**
         * Chooses where to stand for this stream box and keyboard state, before
         * {@link #placeAboveKeyboards}. Window pixels; {@code contentRight}/{@code contentBottom}
         * are where the window's content area ends (the navigation bar lies beyond).
         */
        void arrange(Rect streamInWindow, int contentRight, int contentBottom, int keyboardTopInWindow);

        /** Keyboards cover the window from this row down: move above it (animated). */
        void placeAboveKeyboards(int keyboardTopInWindow);

        /**
         * Where this will sit over the window once placed above {@code keyboardTopInWindow},
         * margins included.
         *
         * @return false if it should not be kept clear of right now (hidden, or a transient
         *         overlay by the user's choice)
         */
        boolean obstructionInWindow(int keyboardTopInWindow, android.graphics.Rect out);

        /** Told when {@link #obstructionInWindow} would answer differently. */
        void setObstructionChangedListener(Runnable listener);
    }

    /** Where the user's point of interest (host cursor, text caret) is on the stream. */
    public interface FocusSource {
        /**
         * @return its y in the stream container's own pixels, or {@link Float#NaN} if unknown
         */
        float focusY();

        /** Its x in the stream container's own pixels, or {@link Float#NaN} if unknown. */
        default float focusX() {
            return Float.NaN;
        }
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

    /** Finds the window's area, creating it if nobody has yet. UI thread. */
    public static KeyboardVisibleArea install(View anyView) {
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

    /**
     * The stream container finished moving (the keyboard lift or a sideways slide): what is
     * visible of the stream changed even though the covered area did not. UI thread.
     */
    public void setStreamMovedListener(Runnable listener) {
        this.streamMovedListener = listener;
    }

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE)
    public void onStreamMoved() {
        if (streamMovedListener != null) {
            streamMovedListener.run();
        }
    }

    private Runnable streamMovedListener;

    /** The focus source's point moved enough to re-place the stream. UI thread. */
    public void onFocusMoved() {
        if (focusMovedListener != null) {
            focusMovedListener.run();
        }
    }

    void setFocusMovedListener(Runnable listener) {
        this.focusMovedListener = listener;
    }

    /** Tests outside this package: observe {@link #onFocusMoved}. */
    public void setFocusMovedListenerForTest(Runnable listener) {
        setFocusMovedListener(listener);
    }

    private Runnable focusMovedListener;

    /** The current point of interest, container pixels, or NaN. */
    public float focusY() {
        return focusSource != null ? focusSource.focusY() : Float.NaN;
    }

    /** The point of interest's x, container pixels, or NaN. */
    public float focusX() {
        return focusSource != null ? focusSource.focusX() : Float.NaN;
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

    /** Publishes a new area; listeners hear about real changes only. The keyboard controller
     * is the only production caller; public for the binder's tests. */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE)
    public void publish(int left, int top, int right, int bottom) {
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
