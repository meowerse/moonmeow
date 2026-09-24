package com.limelight.meow.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The quick bar's PC-keyboard button and its keyboard avoidance (the PC keyboard change). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class QuickBarViewTest {

    private int pc;
    private int system;

    private QuickBarView bar() {
        Context ctx = ApplicationProvider.getApplicationContext();
        return new QuickBarView(ctx, new QuickBarView.Listener() {
            @Override public void onKeyboard() { system++; }
            @Override public void onPcKeyboard() { pc++; }
            @Override public void onToggleLocalCursor() { }
            @Override public void onCycleMouseMode() { }
            @Override public void onTogglePerfOverlay() { }
            @Override public void onOpenMenu() { }
        });
    }

    private static View findByDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findByDescription(g.getChildAt(i), description);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void thePcKeyboardButtonSitsBesideTheSystemKeyboardButton() {
        QuickBarView bar = bar();
        Context ctx = bar.getContext();
        View pcButton = findByDescription(bar, ctx.getString(R.string.meow_quickbar_pc_keyboard_description));
        View kbButton = findByDescription(bar, "Keyboard");
        assertTrue(pcButton != null && kbButton != null);
        ViewGroup row = (ViewGroup) pcButton.getParent();
        assertEquals(row.indexOfChild(kbButton) + 1, row.indexOfChild(pcButton));
        pcButton.performClick();
        kbButton.performClick();
        assertEquals(1, pc);
        assertEquals(1, system);
    }

    @Test
    public void aListenerWithoutThePcMethodStillWorks() {
        QuickBarView.Listener legacy = new QuickBarView.Listener() {
            @Override public void onKeyboard() { }
            @Override public void onToggleLocalCursor() { }
            @Override public void onCycleMouseMode() { }
            @Override public void onTogglePerfOverlay() { }
            @Override public void onOpenMenu() { }
        };
        legacy.onPcKeyboard();
    }

    @Test
    public void itRidesAboveTheKeyboard() {
        QuickBarView bar = bar();
        bar.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, 1080, 2400);
        bar.onVisibleAreaChanged(0, 0, 1080, 1400);
        bar.animate().setDuration(0);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(-1000f, bar.getTranslationY(), 1f);
        bar.onVisibleAreaChanged(0, 0, 1080, 2400);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0f, bar.getTranslationY(), 1f);
    }
}
