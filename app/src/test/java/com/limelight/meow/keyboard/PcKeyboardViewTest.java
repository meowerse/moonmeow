package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, qualifiers = "w412dp-h915dp-xxhdpi")
public class PcKeyboardViewTest {

    private final RecordingSink sink = new RecordingSink();
    private PcKeyboardEngine engine;
    private PcKeyboardView view;
    private int lastAction;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        engine = new PcKeyboardEngine(sink, 300);
        view = new PcKeyboardView(ctx, engine);
        view.setActions(new PcKeyboardView.Actions() {
            @Override
            public void onKeyboardAction(int action) {
                lastAction = action;
            }

            @Override
            public void onKeyboardConfigurationChanged(android.content.res.Configuration config) {
            }
        });
        layout(1080, 2400);
    }

    private void layout(int width, int windowHeight) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(windowHeight, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }

    /** Centre of the first key in the current grid matching the predicate. */
    private float[] centreOf(java.util.function.Predicate<PcKey> which) {
        KeyGrid g = view.currentGrid();
        for (int i = 0; i < g.size(); i++) {
            if (which.test(g.key(i))) {
                return new float[]{(Math.max(0, g.left(i)) + Math.min(g.metrics.widthPx, g.right(i))) / 2f,
                        (g.top(i) + g.bottom(i)) / 2f};
            }
        }
        throw new AssertionError("no such key");
    }

    private float[] key(int code) {
        return centreOf(k -> k.kind == PcKey.KIND_KEY && k.code == code);
    }

    private float[] modifier(int mod) {
        return centreOf(k -> k.isModifier() && k.code == mod);
    }

    private void tap(int pointer, float[] at) {
        view.pointerDown(pointer, at[0], at[1]);
        view.pointerUp(pointer);
    }

    @Test
    public void portraitHeightFollowsTheMetrics() {
        assertTrue(view.getMeasuredHeight() > 0);
        assertEquals(view.getMeasuredHeight(), view.keyboardHeight());
        assertTrue("under 42% of a 2400 px window", view.getMeasuredHeight() <= 0.421f * 2400);
    }

    @Test
    public void aTapSendsDownThenUp() {
        tap(0, key(KeyEvent.KEYCODE_Q));
        assertEquals("+Q -Q", sink.joined());
    }

    @Test
    public void twoFingersChordShiftAndALetter() {
        view.pointerDown(0, modifier(ModifierLatch.MOD_SHIFT)[0], modifier(ModifierLatch.MOD_SHIFT)[1]);
        float[] a = key(KeyEvent.KEYCODE_A);
        view.pointerDown(1, a[0], a[1]);
        view.pointerUp(1);
        view.pointerUp(0);
        assertEquals("+SHIFT +A -A -SHIFT", sink.joined());
        assertEquals(ModifierLatch.OFF, engine.modifierState(ModifierLatch.MOD_SHIFT));
    }

    @Test
    public void aHeldModifierLocksAfterTheLongPressTimeout() {
        float[] ctrl = modifier(ModifierLatch.MOD_CTRL);
        view.pointerDown(0, ctrl[0], ctrl[1]);
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
        view.pointerUp(0);
        assertEquals(ModifierLatch.LOCKED, engine.modifierState(ModifierLatch.MOD_CTRL));
        assertEquals("+CTRL", sink.joined());
    }

    @Test
    public void fnSwitchesTheGridAndBack() {
        KeyGrid main = view.currentGrid();
        tap(0, modifier(ModifierLatch.MOD_FN));
        KeyGrid fn = view.currentGrid();
        assertNotEquals(main, fn);
        tap(1, key(KeyEvent.KEYCODE_F5));
        assertEquals("+F5 -F5", sink.joined());
        assertSame("back to main after one Fn key", main, view.currentGrid());
    }

    @Test
    public void aFingerKeepsItsKeyAcrossALayerChange() {
        float[] a = key(KeyEvent.KEYCODE_A);
        view.pointerDown(0, a[0], a[1]);
        tap(1, modifier(ModifierLatch.MOD_FN));
        view.pointerUp(0);
        assertEquals("+A -A", sink.joined());
    }

    @Test
    public void cancelReleasesEveryHeldKey() {
        float[] a = key(KeyEvent.KEYCODE_A);
        float[] b = key(KeyEvent.KEYCODE_B);
        view.pointerDown(0, a[0], a[1]);
        view.pointerDown(1, b[0], b[1]);
        view.releasePointers();
        assertEquals("+A +B -A -B", sink.joined());
    }

    @Test
    public void toolbarActionsFireOnRelease() {
        float[] hide = centreOf(k -> k.kind == PcKey.KIND_ACTION && k.code == PcKey.ACTION_HIDE);
        view.pointerDown(0, hide[0], hide[1]);
        assertEquals(0, lastAction);
        view.pointerUp(0);
        assertEquals(PcKey.ACTION_HIDE, lastAction);
    }

    @Test
    public void aCancelledActionDoesNothing() {
        float[] hide = centreOf(k -> k.kind == PcKey.KIND_ACTION && k.code == PcKey.ACTION_HIDE);
        view.pointerDown(0, hide[0], hide[1]);
        view.releasePointers();
        assertEquals(0, lastAction);
    }

    @Test
    public void aToolbarChipSendsItsShortcut() {
        tap(0, centreOf(k -> k == PcKeyboardLayout.COPY));
        assertEquals("+CTRL +C -C -CTRL", sink.joined());
    }

    @Test
    public void landscapeUsesTheAnsiLayout() {
        layout(2400, 1080);
        assertSame(PcKeyboardLayout.LANDSCAPE_MAIN, view.currentGrid().layout);
        // 360 dp tall: the 44 dp row floor wins over the 60% share.
        assertEquals(Math.round((5 * 44 + 12) * 3f), view.getMeasuredHeight());
    }

    @Test
    public void stripModeIsOneRow() {
        view.setMode(PcKeyboardView.MODE_STRIP);
        layout(1080, 2400);
        assertSame(PcKeyboardLayout.IME_STRIP, view.currentGrid().layout);
        assertEquals(Math.round(44 * 3f + 12 * 3f), view.getMeasuredHeight());
        float[] pc = centreOf(k -> k.kind == PcKey.KIND_ACTION);
        tap(0, pc);
        assertEquals(PcKey.ACTION_PC_KEYBOARD, lastAction);
    }

    @Test
    public void accessibilityDescribesModifierStates() {
        PcKey ctrl = PcKeyboardLayout.MOD_CTRL;
        assertEquals("Control", view.describe(ctrl));
        tap(0, modifier(ModifierLatch.MOD_CTRL));
        assertTrue(view.describe(ctrl), view.describe(ctrl).contains("next key"));
        tap(0, modifier(ModifierLatch.MOD_CTRL));
        assertTrue(view.describe(ctrl), view.describe(ctrl).contains("locked"));
    }

    @Test
    public void accessibilityClickIsATap() {
        KeyGrid g = view.currentGrid();
        int index = -1;
        for (int i = 0; i < g.size(); i++) {
            if (g.key(i).kind == PcKey.KIND_KEY && g.key(i).code == KeyEvent.KEYCODE_ENTER) {
                index = i;
            }
        }
        view.tapIndex(index);
        assertEquals("+ENTER -ENTER", sink.joined());
    }

    @Test
    public void releasingOnHideKeepsNothingDown() {
        float[] a = key(KeyEvent.KEYCODE_A);
        view.pointerDown(0, a[0], a[1]);
        view.setVisibility(View.GONE);
        view.onVisibilityChanged(view, View.GONE);
        assertEquals("+A -A", sink.joined());
        assertFalse(engine.isModifierActive(ModifierLatch.MOD_CTRL));
    }
}
