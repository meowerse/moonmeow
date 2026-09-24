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

    private static QuickBarView laidOut(QuickBarView bar, int w, int h) {
        bar.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, w, h);
        return bar;
    }

    private static void idle(long seconds) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(seconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    public void itRidesAboveTheKeyboard() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        bar.placeAboveKeyboards(1400);
        idle(1);
        assertEquals(-1000f, bar.getTranslationY(), 1f);
        bar.placeAboveKeyboards(2400);
        idle(1);
        assertEquals(0f, bar.getTranslationY(), 1f);
    }

    @Test
    public void theAutoHideSettingRestoresTheOldCollapse() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        bar.setAutoHide(true);
        bar.onStreamStarted();
        idle(10);
        assertTrue(!QuickBarAlwaysVisibleTest.shownWithin(findByDescription(bar, "Keyboard"), bar));
    }

    @Test
    public void aTwoFingerToggleStillHidesAndShows() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        bar.onStreamStarted();
        bar.toggleFromGesture();
        idle(1);
        assertTrue(!bar.isBarShown());
        bar.toggleFromGesture();
        idle(1);
        assertTrue(bar.isBarShown());
    }

    @Test
    public void aShownPermanentBarIsAnObstructionAndAnAutoHidingOneIsNot() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        bar.onStreamStarted();
        idle(1);
        laidOut(bar, 1080, 2400);
        android.graphics.Rect r = new android.graphics.Rect();
        assertTrue(bar.obstructionInWindow(2400, r));
        assertTrue("at the bottom: " + r, r.bottom > 2200 && r.width() > r.height());
        assertTrue("placed above a keyboard too: " + r, bar.obstructionInWindow(1400, r) && r.bottom <= 1400 + 20);
        bar.setAutoHide(true);
        assertTrue(!bar.obstructionInWindow(2400, r));
        bar.setAutoHide(false);
        bar.hideImmediately();
        assertTrue(!bar.obstructionInWindow(2400, r));
    }

    @Test
    public void obstructionChangesAreReported() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        int[] calls = {0};
        bar.setObstructionChangedListener(() -> calls[0]++);
        bar.onStreamStarted();
        bar.hideImmediately();
        idle(1);
        assertTrue(calls[0] >= 2);
    }

    @Test
    @Config(sdk = {33}, qualifiers = "land")
    public void inLandscapeTheBarStandsDownTheRightSide() {
        QuickBarView bar = laidOut(bar(), 2400, 1080);
        bar.onStreamStarted();
        idle(1);
        laidOut(bar, 2400, 1080);
        android.graphics.Rect r = new android.graphics.Rect();
        assertTrue(bar.obstructionInWindow(1080, r));
        assertTrue("vertical, at the right edge: " + r, r.height() > r.width() && r.right >= 2400 - 60);
    }
}
