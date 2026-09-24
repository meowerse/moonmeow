package com.limelight.meow.keyboard;

import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * The key-press path allocates nothing: hit test, modifier state machine, chords, keys from
 * other keyboards. Measured with the JVM's per-thread allocation counter, on a plain JVM (no
 * Robolectric instrumentation in the way). Reached by reflection because the unit-test
 * compile classpath is android.jar, which has no java.lang.management.
 */
public class PcKeyboardAllocationTest {

    private static final HostKeySink QUIET = new HostKeySink() {
        @Override
        public void sendKey(int androidKeyCode, boolean down) {
        }

        @Override
        public void tapVirtualKey(short virtualKey) {
        }
    };

    @Test
    public void thePressPathDoesNotAllocate() throws Exception {
        Object bean;
        Method allocated;
        try {
            bean = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            allocated = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getThreadAllocatedBytes", long.class);
        } catch (ReflectiveOperationException e) {
            Assume.assumeNoException("no allocation counter on this JVM", e);
            return;
        }
        long tid = Thread.currentThread().getId();

        PcKeyboardEngine engine = new PcKeyboardEngine(QUIET, 300);
        PcKeyboardMetrics m = PcKeyboardMetrics.forKeyboard(1080, 2400, 2.625f, false, 6);
        KeyGrid grid = m.grid(PcKeyboardLayout.PORTRAIT_MAIN);
        float x = m.widthPx * 0.1f;
        float y = m.keysTopPx() + m.rowHeightPx * 2.5f;

        for (int i = 0; i < 50_000; i++) {
            cycle(engine, grid, x, y);
        }
        long before = (Long) allocated.invoke(bean, tid);
        for (int i = 0; i < 10_000; i++) {
            cycle(engine, grid, x, y);
        }
        long bytes = (Long) allocated.invoke(bean, tid) - before;
        // The reflective read itself costs a few hundred bytes; any per-press allocation
        // over 10,000 presses would cost hundreds of kilobytes.
        assertTrue("allocated " + bytes + " bytes over 10,000 press cycles", bytes < 4096);
    }

    private static void cycle(PcKeyboardEngine e, KeyGrid g, float x, float y) {
        PcKey key = g.key(g.hitTest(x, y));
        e.modifierDown(ModifierLatch.MOD_CTRL);
        e.modifierUp(ModifierLatch.MOD_CTRL, 0);
        e.keyDown(key.code);
        e.keyUp(key.code);
        e.modifierDown(ModifierLatch.MOD_SHIFT);
        e.modifierLongPress(ModifierLatch.MOD_SHIFT);
        e.modifierUp(ModifierLatch.MOD_SHIFT, 0);
        e.chordDown(PcKeyboardLayout.COPY.chord);
        e.chordUp(PcKeyboardLayout.COPY.chord);
        e.modifierDown(ModifierLatch.MOD_SHIFT);
        e.modifierUp(ModifierLatch.MOD_SHIFT, 0);
        e.beforeExternalKey(KeyEvent.KEYCODE_S);
        e.afterExternalKey(KeyEvent.KEYCODE_S);
        e.releaseAll();
    }
}
