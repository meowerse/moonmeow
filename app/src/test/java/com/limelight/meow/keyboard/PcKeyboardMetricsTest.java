package com.limelight.meow.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Sizing rules across real screens, in dp converted at each device's density. */
public class PcKeyboardMetricsTest {

    private static final KeyGrid.TextMeasurer MEASURER = s -> s.length() * 7.5f * 2.625f;

    private static final class Screen {
        final String name;
        final int widthDp;
        final int heightDp;
        final float density;

        Screen(String name, int widthDp, int heightDp, float density) {
            this.name = name;
            this.widthDp = widthDp;
            this.heightDp = heightDp;
            this.density = density;
        }

        int w() {
            return Math.round(widthDp * density);
        }

        int h() {
            return Math.round(heightDp * density);
        }
    }

    private static final Screen[] PORTRAIT = {
            new Screen("small phone", 360, 640, 2f),
            new Screen("pixel 7", 412, 915, 2.625f),
            new Screen("narrow tall", 384, 854, 2.8125f),
            new Screen("tablet", 800, 1280, 2f),
            new Screen("split-screen half", 412, 440, 2.625f),
    };

    private static PcKeyboardMetrics full(Screen s, boolean landscape) {
        int w = landscape ? s.h() : s.w();
        int h = landscape ? s.w() : s.h();
        PcKeyboardLayout layout = PcKeyboardLayout.main(landscape);
        return PcKeyboardMetrics.forKeyboard(w, h, s.density, landscape, layout.rows.length);
    }

    @Test
    public void rowsAreNeverShorterThan44dp() {
        for (Screen s : PORTRAIT) {
            for (boolean landscape : new boolean[]{false, true}) {
                PcKeyboardMetrics m = full(s, landscape);
                assertTrue(s.name + " " + landscape + ": " + m.rowHeightPx / s.density,
                        m.rowHeightPx / s.density >= 44f - 1e-3f);
            }
        }
    }

    @Test
    public void rowsNeverGrowPastTheirCap() {
        for (Screen s : PORTRAIT) {
            assertTrue(s.name, full(s, false).rowHeightPx / s.density <= 56f + 1e-3f);
            assertTrue(s.name, full(s, true).rowHeightPx / s.density <= 50f + 1e-3f);
        }
    }

    @Test
    public void pixel7PortraitUsesAboutTwoFifthsOfTheScreen() {
        Screen s = PORTRAIT[1];
        PcKeyboardMetrics m = full(s, false);
        float share = m.heightPx / (float) s.h();
        assertTrue("share " + share, share <= 0.421f && share >= 0.35f);
        assertTrue("toolbar in portrait", m.hasToolbar);
        // 11 units across 412 dp: keys ~37 dp wide, rows ~52 dp tall.
        float unitDp = m.unitPx(PcKeyboardLayout.PORTRAIT_MAIN) / s.density;
        assertEquals(37f, unitDp, 1f);
    }

    @Test
    public void pixel7LandscapeKeepsTheFloorAndDropsTheToolbar() {
        Screen s = PORTRAIT[1];
        PcKeyboardMetrics m = full(s, true);
        assertTrue(!m.hasToolbar);
        float share = m.heightPx / (float) s.w();
        assertTrue("share " + share, share <= 0.61f);
        float unitDp = m.unitPx(PcKeyboardLayout.LANDSCAPE_MAIN) / s.density;
        assertTrue("ANSI keys are comfortably wide: " + unitDp, unitDp >= 55f && unitDp <= 64f);
    }

    @Test
    public void tabletKeysAreCappedAndCentred() {
        Screen s = PORTRAIT[3];
        PcKeyboardMetrics m = full(s, true);
        float unit = m.unitPx(PcKeyboardLayout.LANDSCAPE_MAIN);
        assertEquals(64f, unit / s.density, 1e-3f);
        float left = m.leftPx(PcKeyboardLayout.LANDSCAPE_MAIN);
        float right = left + unit * PcKeyboardLayout.LANDSCAPE_MAIN.units;
        assertEquals("centred", m.widthPx - right, left, 1f);
    }

    @Test
    public void everyPointOfTheKeyboardHitsAKey() {
        for (Screen s : PORTRAIT) {
            for (boolean landscape : new boolean[]{false, true}) {
                PcKeyboardMetrics m = full(s, landscape);
                for (PcKeyboardLayout layout : new PcKeyboardLayout[]{
                        PcKeyboardLayout.main(landscape), PcKeyboardLayout.fn(landscape)}) {
                    KeyGrid g = m.grid(layout);
                    for (float y = m.keysTopPx(); y < m.heightPx; y += 3f) {
                        for (float x = 0; x < m.widthPx; x += 3f) {
                            assertTrue(s.name + " " + layout.name + " " + x + "," + y, g.hitTest(x, y) >= 0);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void theCentreOfEveryKeyHitsThatKey() {
        PcKeyboardMetrics m = full(PORTRAIT[1], false);
        KeyGrid g = m.grid(PcKeyboardLayout.PORTRAIT_MAIN);
        g.addToolbar(PcKeyboardLayout.TOOLBAR_SYSTEM_KEYBOARD, PcKeyboardLayout.TOOLBAR_CHIPS,
                PcKeyboardLayout.TOOLBAR_HIDE, MEASURER);
        for (int i = 0; i < g.size(); i++) {
            float cx = (Math.max(0, g.left(i)) + Math.min(m.widthPx, g.right(i))) / 2f;
            float cy = (g.top(i) + g.bottom(i)) / 2f;
            assertEquals(g.key(i).label, i, g.hitTest(cx, cy));
        }
    }

    @Test
    public void outsideIsAMiss() {
        PcKeyboardMetrics m = full(PORTRAIT[1], false);
        KeyGrid g = m.grid(PcKeyboardLayout.PORTRAIT_MAIN);
        assertEquals(-1, g.hitTest(-1, 100));
        assertEquals(-1, g.hitTest(10, m.heightPx));
        assertEquals(-1, g.hitTest(m.widthPx, 100));
    }

    @Test
    public void theToolbarFitsTheChipsThatFitAndKeepsItsEnds() {
        PcKeyboardMetrics m = full(PORTRAIT[1], false);
        KeyGrid g = m.grid(PcKeyboardLayout.PORTRAIT_MAIN);
        g.addToolbar(PcKeyboardLayout.TOOLBAR_SYSTEM_KEYBOARD, PcKeyboardLayout.TOOLBAR_CHIPS,
                PcKeyboardLayout.TOOLBAR_HIDE, MEASURER);
        int toolbarKeys = g.size() - g.toolbarStart();
        assertTrue("both ends plus some chips: " + toolbarKeys, toolbarKeys >= 5);
        assertEquals(PcKeyboardLayout.TOOLBAR_SYSTEM_KEYBOARD, g.key(g.toolbarStart()));
        assertEquals(PcKeyboardLayout.TOOLBAR_HIDE, g.key(g.size() - 1));
        for (int i = g.toolbarStart(); i < g.size(); i++) {
            assertTrue("toolbar targets are 44 dp tall", (g.bottom(i) - g.top(i)) / m.density >= 44f + 6f - 1e-3f);
            assertTrue(g.key(i).label + " at least 48dp wide", (g.right(i) - g.left(i)) / m.density >= 48f);
        }
    }

    @Test
    public void noToolbarInLandscape() {
        PcKeyboardMetrics m = full(PORTRAIT[1], true);
        KeyGrid g = m.grid(PcKeyboardLayout.LANDSCAPE_MAIN);
        int before = g.size();
        g.addToolbar(PcKeyboardLayout.TOOLBAR_SYSTEM_KEYBOARD, PcKeyboardLayout.TOOLBAR_CHIPS,
                PcKeyboardLayout.TOOLBAR_HIDE, MEASURER);
        assertEquals(before, g.size());
    }

    @Test
    public void theStripIsOneRowOf44dp() {
        PcKeyboardMetrics m = PcKeyboardMetrics.forStrip(1080, 2.625f);
        assertEquals(1, m.rows);
        assertEquals(44f, m.rowHeightPx / 2.625f, 1e-3f);
        KeyGrid g = m.grid(PcKeyboardLayout.IME_STRIP);
        assertEquals(PcKeyboardLayout.IME_STRIP.keyCount(), g.size());
    }
}
