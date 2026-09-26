package com.limelight.meow.ui;

import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

/**
 * The owner: "why also buttons bar is still hiding to this line? let's always show it".
 * Uses only the bar's original API, so it ran (and failed) against the auto-hiding bar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class QuickBarAlwaysVisibleTest {

    private static View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) {
            return root;
        }
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                View found = find(((ViewGroup) root).getChildAt(i), description);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** isShown() for a view tree with no window: every ancestor up to the root visible. */
    static boolean shownWithin(View view, View root) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v.getVisibility() != View.VISIBLE) {
                return false;
            }
            if (v == root) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void theBarIsStillShownLongAfterTheStreamStarts() {
        QuickBarView bar = new QuickBarView(ApplicationProvider.getApplicationContext(),
                new QuickBarView.Listener() {
                    @Override public void onKeyboard() { }
                    @Override public void onToggleLocalCursor() { }
                    @Override public void onCycleMouseMode() { }
                    @Override public void onTogglePerfOverlay() { }
                    @Override public void onOpenMenu() { }
                });
        bar.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, 1080, 2400);
        bar.onStreamStarted();
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS);
        assertTrue("the keyboard button is still on screen ten seconds later",
                shownWithin(find(bar, "Keyboard"), bar));
    }
}
