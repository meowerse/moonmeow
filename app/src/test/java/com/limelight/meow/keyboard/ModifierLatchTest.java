package com.limelight.meow.keyboard;

import static com.limelight.meow.keyboard.ModifierLatch.LATCHED;
import static com.limelight.meow.keyboard.ModifierLatch.LOCKED;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_ALT;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_CTRL;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_FN;
import static com.limelight.meow.keyboard.ModifierLatch.MOD_SHIFT;
import static com.limelight.meow.keyboard.ModifierLatch.OFF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModifierLatchTest {

    private static final long DOUBLE_TAP = 300;
    private final ModifierLatch latch = new ModifierLatch(DOUBLE_TAP);

    private void tap(int mod, long at) {
        latch.press(mod);
        latch.release(mod, at);
    }

    @Test
    public void aTapLatches() {
        tap(MOD_CTRL, 1000);
        assertEquals(LATCHED, latch.state(MOD_CTRL));
        assertTrue(latch.isActive(MOD_CTRL));
    }

    @Test
    public void aSecondTapWithinTheDoubleTapTimeoutLocks() {
        tap(MOD_CTRL, 1000);
        tap(MOD_CTRL, 1000 + DOUBLE_TAP);
        assertEquals(LOCKED, latch.state(MOD_CTRL));
    }

    @Test
    public void aSecondTapAfterTheTimeoutTurnsItOff() {
        tap(MOD_CTRL, 1000);
        tap(MOD_CTRL, 1000 + DOUBLE_TAP + 1);
        assertEquals(OFF, latch.state(MOD_CTRL));
    }

    @Test
    public void tappingALockedModifierTurnsItOff() {
        tap(MOD_ALT, 0);
        tap(MOD_ALT, 100);
        tap(MOD_ALT, 5000);
        assertEquals(OFF, latch.state(MOD_ALT));
    }

    @Test
    public void aLongPressLocksAndLiftingDoesNotCountAsATap() {
        latch.press(MOD_SHIFT);
        assertTrue(latch.longPress(MOD_SHIFT));
        latch.release(MOD_SHIFT, 900);
        assertEquals(LOCKED, latch.state(MOD_SHIFT));
    }

    @Test
    public void aLongPressAfterAChordDoesNothing() {
        latch.press(MOD_CTRL);
        latch.onKeyUsed();
        assertFalse(latch.longPress(MOD_CTRL));
        latch.release(MOD_CTRL, 900);
        assertEquals(OFF, latch.state(MOD_CTRL));
    }

    @Test
    public void heldIsActiveBeforeItIsReleased() {
        latch.press(MOD_CTRL);
        assertTrue(latch.isActive(MOD_CTRL));
        assertEquals(OFF, latch.state(MOD_CTRL));
        assertEquals(1 << MOD_CTRL, latch.activeHostMask());
    }

    @Test
    public void aChordOnALatchedModifierSpendsIt() {
        tap(MOD_CTRL, 0);
        latch.press(MOD_CTRL);
        latch.onKeyUsed();
        latch.release(MOD_CTRL, 50);
        assertEquals(OFF, latch.state(MOD_CTRL));
    }

    @Test
    public void aChordLeavesALockAlone() {
        tap(MOD_CTRL, 0);
        tap(MOD_CTRL, 10);
        latch.press(MOD_CTRL);
        latch.onKeyUsed();
        latch.release(MOD_CTRL, 50);
        assertEquals(LOCKED, latch.state(MOD_CTRL));
    }

    @Test
    public void consumingSpendsLatchesButNotLocksOrHeld() {
        tap(MOD_CTRL, 0);
        tap(MOD_ALT, 0);
        tap(MOD_ALT, 10);
        latch.press(MOD_SHIFT);
        tap(MOD_FN, 0);
        assertTrue(latch.consumeLatches());
        assertEquals(OFF, latch.state(MOD_CTRL));
        assertEquals(LOCKED, latch.state(MOD_ALT));
        assertTrue(latch.isHeld(MOD_SHIFT));
        assertEquals(OFF, latch.state(MOD_FN));
    }

    @Test
    public void severalModifiersCombine() {
        tap(MOD_CTRL, 0);
        tap(MOD_SHIFT, 0);
        assertEquals((1 << MOD_CTRL) | (1 << MOD_SHIFT), latch.activeHostMask());
    }

    @Test
    public void fnIsNotAHostModifier() {
        tap(MOD_FN, 0);
        assertEquals(0, latch.activeHostMask());
        assertTrue(latch.isActive(MOD_FN));
    }

    @Test
    public void clearDropsEverything() {
        tap(MOD_CTRL, 0);
        tap(MOD_CTRL, 1);
        latch.press(MOD_SHIFT);
        assertTrue(latch.clear());
        assertEquals(0, latch.activeHostMask());
        assertFalse(latch.clear());
        // A release after a clear is not a tap: no finger was down any more.
        assertFalse(latch.release(MOD_SHIFT, 5));
        assertEquals(OFF, latch.state(MOD_SHIFT));
    }
}
