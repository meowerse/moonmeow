package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class KeyboardVisibleAreaTest {

    @Test
    public void foundFromAnyViewInTheWindow() {
        FrameLayout root = new FrameLayout(ApplicationProvider.getApplicationContext());
        FrameLayout child = new FrameLayout(root.getContext());
        root.addView(child);
        assertNull(KeyboardVisibleArea.of(child));
        KeyboardVisibleArea area = KeyboardVisibleArea.install(child);
        assertSame(area, KeyboardVisibleArea.of(root));
        assertSame(area, KeyboardVisibleArea.install(root));
    }

    @Test
    public void listenersHearRealChangesOnlyAndCatchUpOnRegistration() {
        KeyboardVisibleArea area = new KeyboardVisibleArea();
        List<String> heard = new ArrayList<>();
        area.addListener((l, t, r, b) -> heard.add(t + ".." + b));
        area.publish(0, 0, 1080, 2400);
        area.publish(0, 0, 1080, 2400);
        area.publish(0, 0, 1080, 1400);
        assertEquals("[0..2400, 0..1400]", heard.toString());
        List<String> late = new ArrayList<>();
        area.addListener((l, t, r, b) -> late.add(t + ".." + b));
        assertEquals("[0..1400]", late.toString());
        assertEquals(1400, area.visibleBottom());
        assertTrue(area.isKnown());
    }

    @Test
    public void focusMovesReachTheListener() {
        KeyboardVisibleArea area = new KeyboardVisibleArea();
        area.onFocusMoved();
        int[] calls = {0};
        area.setFocusMovedListener(() -> calls[0]++);
        area.onFocusMoved();
        assertEquals(1, calls[0]);
    }

    @Test
    public void focusSourceIsOptional() {
        KeyboardVisibleArea area = new KeyboardVisibleArea();
        assertTrue(Float.isNaN(area.focusY()));
        area.setFocusSource(() -> 320f);
        assertEquals(320f, area.focusY(), 0f);
    }
}
