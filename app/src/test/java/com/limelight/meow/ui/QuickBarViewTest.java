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

    private static android.graphics.Rect rect(int l, int t, int r, int b) {
        return new android.graphics.Rect(l, t, r, b);
    }

    @Test
    @Config(sdk = {33}, qualifiers = "land")
    public void inLandscapeWithASideLetterboxTheBarStandsDownTheRightSide() {
        QuickBarView bar = laidOut(bar(), 2400, 1080);
        bar.onStreamStarted();
        bar.arrange(rect(240, 0, 2160, 1080), 2400, 1080, 1080);
        idle(1);
        laidOut(bar, 2400, 1080);
        android.graphics.Rect r = new android.graphics.Rect();
        assertTrue(bar.obstructionInWindow(1080, r));
        assertTrue("vertical, at the right edge: " + r, r.height() > r.width() && r.right >= 2400 - 60);
        assertTrue("clear of the stream: " + r, r.left >= 2160 - 8);
    }

    @Test
    @Config(sdk = {33}, qualifiers = "land")
    public void inLandscapeAKeyboardMakesTheBarHorizontalAboveItAndStillShown() {
        QuickBarView bar = laidOut(bar(), 2400, 1080);
        bar.onStreamStarted();
        bar.arrange(rect(240, 0, 2160, 1080), 2400, 1080, 1080);
        laidOut(bar, 2400, 1080);
        bar.arrange(rect(240, 0, 2160, 1080), 2400, 1080, 540);
        laidOut(bar, 2400, 1080);
        bar.placeAboveKeyboards(540);
        idle(1);
        android.graphics.Rect r = new android.graphics.Rect();
        assertTrue("an obstruction above the keyboard", bar.obstructionInWindow(540, r));
        assertTrue("horizontal, above the keyboard: " + r, r.width() > r.height() && r.bottom <= 540 + 20);
        View kb = findByDescription(bar, "Keyboard");
        float top = bar.getTop() + bar.getTranslationY();
        for (View v = kb; v != bar; v = (View) v.getParent()) {
            top += v.getTop();
        }
        assertTrue("the Keyboard button stays on screen: " + top, top >= 0 && top < 540);
        idle(10);
        assertTrue("and it stays shown", bar.isBarShown());
    }

    @Test
    @Config(sdk = {33}, qualifiers = "land")
    public void withNoLetterboxAnywhereTheBarStaysShownDownTheSideOverTheStream() {
        // Auto cursor zoom fills the view: no letterbox. The bar is still shown (the owner's
        // request), on the edge that hides least of a landscape stream, as an obstruction.
        QuickBarView bar = laidOut(bar(), 1920, 1080);
        bar.onStreamStarted();
        bar.arrange(rect(0, 0, 1920, 1080), 1920, 1080, 1080);
        laidOut(bar, 1920, 1080);
        idle(10);
        assertTrue(bar.isBarShown());
        android.graphics.Rect r = new android.graphics.Rect();
        assertTrue(bar.obstructionInWindow(1080, r));
        assertTrue("vertical at the right: " + r, r.height() > r.width() && r.right >= 1920 - 20);
    }

    @Test
    public void withNoLetterboxInPortraitTheBarStaysShownAcrossTheBottom() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        bar.onStreamStarted();
        bar.arrange(rect(0, 0, 1080, 2400), 1080, 2400, 2400);
        laidOut(bar, 1080, 2400);
        idle(10);
        assertTrue(bar.isBarShown());
        android.graphics.Rect r = new android.graphics.Rect();
        assertTrue(bar.obstructionInWindow(2400, r));
        assertTrue("across the bottom: " + r, r.width() > r.height() && r.bottom >= 2400 - 40);
    }

    @Test
    public void onlyTheSettingCollapsesTheBar() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        bar.setAutoHide(true);
        bar.onStreamStarted();
        bar.arrange(rect(0, 0, 1080, 2400), 1080, 2400, 2400);
        idle(10);
        assertTrue(!bar.isBarShown());
    }

    @Test
    public void inPortraitTheBarUsesTheBottomLetterbox() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        bar.onStreamStarted();
        bar.arrange(rect(0, 896, 1080, 1504), 1080, 2400, 2400);
        laidOut(bar, 1080, 2400);
        android.graphics.Rect r = new android.graphics.Rect();
        assertTrue(bar.obstructionInWindow(2400, r));
        assertTrue("below the stream: " + r, r.top >= 1504 && r.width() > r.height());
    }

    @Test
    @Config(sdk = {33}, qualifiers = "land")
    public void leavingATransientStateCancelsItsPendingCollapse() {
        // Open the PC keyboard in landscape (the bar turns transient and arms its timer),
        // close it within 3 s: the bar is permanent again and must stay.
        QuickBarView bar = laidOut(bar(), 2400, 1080);
        bar.onStreamStarted();
        bar.arrange(rect(240, 0, 2160, 1080), 2400, 1080, 540);
        idle(1);
        bar.arrange(rect(240, 0, 2160, 1080), 2400, 1080, 1080);
        idle(10);
        assertTrue(bar.isBarShown());
    }

    @Test
    public void aBarTheUserHidDoesNotComeBackOnItsOwn() {
        QuickBarView bar = laidOut(bar(), 1080, 2400);
        bar.onStreamStarted();
        bar.toggleFromGesture();
        idle(1);
        bar.arrange(rect(0, 0, 1080, 2400), 1080, 2400, 2400);   // transient
        bar.arrange(rect(0, 896, 1080, 1504), 1080, 2400, 2400); // permanent again
        idle(1);
        assertTrue(!bar.isBarShown());
    }
}
