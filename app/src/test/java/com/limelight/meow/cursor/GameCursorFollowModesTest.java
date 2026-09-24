package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.Game;
import com.limelight.TestLogSuppressor;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.meow.stream.MeowStreamBridge;
import com.limelight.meow.stream.MeowStreamBridgeAccess;
import com.limelight.meow.viewport.StreamViewportBinder;
import com.limelight.meow.viewport.ViewportPreference;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowGameManager;
import com.limelight.shadows.ShadowMoonBridgeWithHost;
import com.limelight.ui.StreamContainer;
import com.limelight.utils.PanZoomHandler;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

/**
 * C8: every mouse and touch mode, driven with real {@code MotionEvent}s through {@code Game}'s
 * own handlers, against a fake meow host (behind the natives) that moves its cursor from what
 * it receives and reports it back over 0x3004. The assertion is what the user sees: after the
 * follower has run, the host cursor is on screen.
 *
 * <p>The stream is started by hand (the binder's {@code onStreamStarted} and a viewport echo
 * that proves the host), exactly what {@code connectionStarted()} and the first echo do.
 */
@Config(sdk = {33}, shadows = {ShadowMoonBridgeWithHost.class, ShadowGameManager.class,
        com.limelight.shadows.ShadowMeowViewportBridge.class})
@RunWith(RobolectricTestRunner.class)
public class GameCursorFollowModesTest {

    private static final int W = 1920;
    private static final int H = 1080;

    /** The fake host's desktop and pointer acceleration; the default fills the stream. */
    private int desktopW = W;
    private int desktopH = H;
    private float hostAcceleration = 1f;

    private ActivityController<Game> controller;
    private Game game;
    private PanZoomHandler panZoom;
    private StreamViewportBinder binder;
    private StreamContainer container;
    private long eventTime;

    @BeforeClass
    public static void quiet() {
        TestLogSuppressor.install();
    }

    @After
    public void tearDown() {
        if (binder != null) {
            binder.release();
        }
        if (controller != null) {
            controller.destroy();
        }
        MeowStreamBridge.setCursorListener(null);
        CursorInputTap.install(null);
    }

    // ---- harness -----------------------------------------------------------------------

    private void launch(String mouseMode, boolean absoluteMouse, boolean hostReports)
            throws Exception {
        launch(mouseMode, absoluteMouse, hostReports, true);
    }

    private void launch(String mouseMode, boolean absoluteMouse, boolean hostReports,
                        boolean zoomToCentre) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear()
                .putString("mouse_mode_list", mouseMode)
                .putBoolean("checkbox_absolute_mouse_mode", absoluteMouse)
                .putBoolean(CursorFollowPreference.KEY, true)
                .putBoolean(ViewportPreference.KEY, true)
                .commit();

        // The Shield controller extension binds a service Robolectric answers with a null
        // binder; the real one does not exist off an NVIDIA device either.
        Shadows.shadowOf((Application) context).declareComponentUnbindable(new ComponentName(
                "com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));

        Intent intent = new Intent(context, Game.class);
        intent.putExtra(Game.EXTRA_HOST, "127.0.0.1");
        intent.putExtra(Game.EXTRA_APP_ID, 1);
        intent.putExtra(Game.EXTRA_APP_NAME, "Desktop");
        intent.putExtra(Game.EXTRA_PC_NAME, "pc");
        intent.putExtra(Game.EXTRA_PC_UUID, "uuid-modes");
        intent.putExtra(Game.EXTRA_UNIQUEID, "0123456789ABCDEF");
        controller = Robolectric.buildActivity(Game.class, intent).create();
        game = controller.get();

        panZoom = field("panZoomHandler");
        binder = field("viewportBinder");
        container = field("streamContainer");
        assertNotNull("2D render mode builds the binder", binder);

        View surface = container.getSurfaceView();
        container.layout(0, 0, W, H);
        surface.layout(0, 0, W, H);

        InputCaptureProvider capture = field("inputCaptureProvider");
        capture.enableCapture();

        // connectionStarted() and a meow host's first echo.
        ShadowMoonBridgeWithHost.reset(hostReports);
        ShadowMoonBridgeWithHost.configure(desktopW, desktopH, W, H, hostAcceleration);
        binder.onStreamStarted(W, H);
        idle();
        // The echo of a full-frame probe: the desktop's content box, and its size.
        float scalar = Math.min((float) W / desktopW, (float) H / desktopH);
        int contentW = (int) (desktopW * scalar);
        int contentH = (int) (desktopH * scalar);
        binder.onViewportApplied((W - contentW) / 2, (H - contentH) / 2, contentW, contentH,
                desktopW, desktopH, 0);
        drainBinder();
        idle();
        if (hostReports) {
            // A reporting host answers the subscription with its position at once.
            MeowStreamBridgeAccess.cursor(Math.round(ShadowMoonBridgeWithHost.referenceX()),
                    Math.round(ShadowMoonBridgeWithHost.referenceY()), 1);
            idle();
        }
        // Let the first-report wait pass, as it does in the first seconds of a real session.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(CursorFollowController.FIRST_REPORT_WAIT_MS + 100));

        eventTime = SystemClock.uptimeMillis();
        if (zoomToCentre) {
            // The user pinches to 4x about the centre: the visible box is (720, 405, 480, 270).
            panZoom.pinchBy(4f, W / 2f, H / 2f);
            idle();
        }
    }

    /** Runs the binder's reporter thread, where the host-proven tasks (subscribe) run. */
    private void drainBinder() throws Exception {
        Field handler = StreamViewportBinder.class.getDeclaredField("handler");
        handler.setAccessible(true);
        Looper looper = ((android.os.Handler) handler.get(binder)).getLooper();
        Shadows.shadowOf(looper).idle();
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) throws Exception {
        Field f = Game.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(game);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Let the follower run: a second of vsyncs. */
    private static void settle() {
        for (int i = 0; i < 60; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17));
        }
    }

    private MotionEvent event(int action, int source, int toolType, float x, float y,
                              float axisX, float axisY) {
        eventTime += 8;
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = toolType;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        if (axisX != 0f || axisY != 0f) {
            coords[0].setAxisValue(MotionEvent.AXIS_X, axisX);
            coords[0].setAxisValue(MotionEvent.AXIS_Y, axisY);
            coords[0].setAxisValue(MotionEvent.AXIS_RELATIVE_X, axisX);
            coords[0].setAxisValue(MotionEvent.AXIS_RELATIVE_Y, axisY);
        }
        return MotionEvent.obtain(eventTime - 8, eventTime, action, 1, props, coords, 0, 0,
                1f, 1f, 0, 0, source, 0);
    }

    private MotionEvent twoFingers(int action, float x0, float y0, float x1, float y1) {
        eventTime += 8;
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties(),
                new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[1].id = 1;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        props[1].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(),
                new MotionEvent.PointerCoords()};
        coords[0].x = x0;
        coords[0].y = y0;
        coords[1].x = x1;
        coords[1].y = y1;
        for (MotionEvent.PointerCoords c : coords) {
            c.pressure = 1f;
            c.size = 1f;
        }
        return MotionEvent.obtain(eventTime - 8, eventTime, action, 2, props, coords, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /**
     * A real two-finger pinch through Game.onTouch: the fingers spread symmetrically about
     * (cx, cy) from {@code fromHalfSpan} to {@code toHalfSpan}, and may drift by (dx, dy).
     */
    private void pinch(float cx, float cy, float fromHalfSpan, float toHalfSpan,
                       float dx, float dy, int steps) {
        game.onTouch(container, event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, cx - fromHalfSpan, cy, 0f, 0f));
        game.onTouch(container, twoFingers(MotionEvent.ACTION_POINTER_DOWN
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                cx - fromHalfSpan, cy, cx + fromHalfSpan, cy));
        for (int i = 1; i <= steps; i++) {
            float h = fromHalfSpan + (toHalfSpan - fromHalfSpan) * i / steps;
            float ox = dx * i / steps;
            float oy = dy * i / steps;
            game.onTouch(container, twoFingers(MotionEvent.ACTION_MOVE,
                    cx - h + ox, cy + oy, cx + h + ox, cy + oy));
        }
        float ox = dx;
        float oy = dy;
        game.onTouch(container, twoFingers(MotionEvent.ACTION_POINTER_UP
                        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                cx - toHalfSpan + ox, cy + oy, cx + toHalfSpan + ox, cy + oy));
        game.onTouch(container, event(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, cx - toHalfSpan + ox, cy + oy, 0f, 0f));
        idle();
    }

    /**
     * Captured-mouse movement at a realistic rate: one relative event per display frame, so
     * the follower runs between them as it does on a device.
     */
    private void mouseMoves(int count, float dx, float dy) {
        for (int i = 0; i < count; i++) {
            game.onGenericMotionEvent(event(MotionEvent.ACTION_MOVE,
                    InputDevice.SOURCE_MOUSE_RELATIVE, MotionEvent.TOOL_TYPE_MOUSE,
                    dx, dy, dx, dy));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17));
        }
    }

    /** A one-finger drag on the touchscreen, through Game.onTouch. */
    private void touchDrag(float fromX, float y, float toX, int steps) {
        game.onTouch(container, event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, fromX, y, 0f, 0f));
        for (int i = 1; i <= steps; i++) {
            float x = fromX + (toX - fromX) * i / steps;
            game.onTouch(container, event(MotionEvent.ACTION_MOVE,
                    InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, x, y, 0f, 0f));
        }
        game.onTouch(container, event(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, toX, y, 0f, 0f));
    }

    private float[] visible() {
        float[] v = new float[4];
        assertTrue(binder.visibleReferenceRect(v));
        return v;
    }

    private void assertHostCursorOnScreen() {
        float x = ShadowMoonBridgeWithHost.referenceX();
        float y = ShadowMoonBridgeWithHost.referenceY();
        float[] v = visible();
        assertTrue("host cursor " + x + "," + y + " off screen: visible " + v[0] + "+" + v[2]
                        + ", " + v[1] + "+" + v[3],
                x >= v[0] && x <= v[0] + v[2] && y >= v[1] && y <= v[1] + v[3]);
    }

    /** The host cursor left the initial view to the right, and the view went after it. */
    private void assertFollowedRight(float childXBefore) {
        assertTrue("the input must have reached the host: " + ShadowMoonBridgeWithHost.sends,
                ShadowMoonBridgeWithHost.sends > 0);
        assertTrue("host cursor should be right of the initial view, is at "
                + ShadowMoonBridgeWithHost.cursorX, ShadowMoonBridgeWithHost.cursorX > 1200f);
        assertTrue("the view must have panned right", panZoom.getChildX() < childXBefore);
        assertHostCursorOnScreen();
    }

    // ---- relative modes: the cursor runs off the view and must be chased -----------------

    @Test
    public void touchTrackpadNatural() throws Exception {
        launch("2", false, true);
        float before = panZoom.getChildX();
        for (int stroke = 0; stroke < 4; stroke++) {
            touchDrag(200f, 540f, 1700f, 60);
            idle();
        }
        settle();
        assertFollowedRight(before);
    }

    @Test
    public void touchTrackpadGaming() throws Exception {
        launch("3", false, true);
        float before = panZoom.getChildX();
        for (int stroke = 0; stroke < 4; stroke++) {
            touchDrag(200f, 540f, 1700f, 60);
            idle();
        }
        settle();
        assertFollowedRight(before);
    }

    @Test
    public void physicalTouchpad() throws Exception {
        launch("1", false, true);
        float before = panZoom.getChildX();
        for (int stroke = 0; stroke < 4; stroke++) {
            game.handleMotionEvent(container, event(MotionEvent.ACTION_DOWN,
                    InputDevice.SOURCE_TOUCHPAD, MotionEvent.TOOL_TYPE_FINGER, 100f, 300f, 0f, 0f));
            for (int i = 1; i <= 60; i++) {
                game.handleMotionEvent(container, event(MotionEvent.ACTION_MOVE,
                        InputDevice.SOURCE_TOUCHPAD, MotionEvent.TOOL_TYPE_FINGER,
                        100f + i * 20f, 300f, 0f, 0f));
            }
            game.handleMotionEvent(container, event(MotionEvent.ACTION_UP,
                    InputDevice.SOURCE_TOUCHPAD, MotionEvent.TOOL_TYPE_FINGER, 1300f, 300f, 0f, 0f));
            idle();
        }
        settle();
        assertFollowedRight(before);
    }

    @Test
    public void capturedMouseRelative() throws Exception {
        launch("1", false, true);
        float before = panZoom.getChildX();
        mouseMoves(40, 20f, 0f);
        settle();
        assertFollowedRight(before);
    }

    @Test
    public void capturedMouseInAbsoluteMouseMode() throws Exception {
        launch("1", true, true);
        float before = panZoom.getChildX();
        mouseMoves(40, 20f, 0f);
        settle();
        assertFollowedRight(before);
    }

    @Test
    public void capturedMouseAgainstAHostThatDoesNotReport() throws Exception {
        // No 0x3004: the estimate fed by the NvConnection tap is all there is.
        launch("1", false, false);
        float before = panZoom.getChildX();
        mouseMoves(40, 20f, 0f);
        settle();
        assertFollowedRight(before);
    }

    // ---- absolute modes: the cursor is under the finger; only the edge scrolls -----------

    private void assertEdgeScrolled(float childXBefore) {
        assertTrue(ShadowMoonBridgeWithHost.sends > 0);
        assertTrue("an input at the edge must scroll the view", panZoom.getChildX() < childXBefore);
        assertHostCursorOnScreen();
    }

    @Test
    public void absoluteTouch() throws Exception {
        launch("1", false, true);
        float before = panZoom.getChildX();
        touchDrag(1600f, 540f, W - 2f, 30);
        settle();
        assertEdgeScrolled(before);
    }

    @Test
    public void localCursorHover() throws Exception {
        launch("1", false, true);
        float before = panZoom.getChildX();
        for (int i = 0; i < 20; i++) {
            game.handleMotionEvent(container, event(MotionEvent.ACTION_HOVER_MOVE,
                    InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE,
                    1800f + i * 6f, 540f, 0f, 0f));
        }
        settle();
        assertEdgeScrolled(before);
    }

    @Test
    public void multiTouch() throws Exception {
        launch("0", false, true);
        float before = panZoom.getChildX();
        touchDrag(1600f, 540f, W - 2f, 30);
        settle();
        assertEdgeScrolled(before);
    }

    @Test
    public void aTapInsideTheEdgeBandDoesNotMoveTheView() throws Exception {
        launch("1", false, true);
        float before = panZoom.getChildX();
        touchDrag(1500f, 540f, 1500f, 1);
        settle();
        assertEquals(before, panZoom.getChildX(), 0f);
    }

    // ---- the cursor, not the finger ------------------------------------------------------

    @Test
    public void aTrackpadStrokeNearTheScreenEdgeFollowsTheCursorNotTheFinger() throws Exception {
        // The finger sits near the right edge of the screen but moves left, so the cursor
        // moves left inside the view. The old follower chased the finger and panned right.
        launch("2", false, true);
        float before = panZoom.getChildX();
        touchDrag(1900f, 540f, 1780f, 20);
        settle();
        assertTrue("host cursor moved left: " + ShadowMoonBridgeWithHost.cursorX,
                ShadowMoonBridgeWithHost.cursorX < W / 2f);
        assertTrue("the view must not chase the finger to the right",
                panZoom.getChildX() >= before);
        assertHostCursorOnScreen();
    }

    // ---- the user's report: "I zoomed in another place and when I move, the view moves and
    // ---- the mouse is not visible" (dead-reckoning host, touch trackpad) ------------------

    @Test
    public void zoomElsewhereThenMoveKeepsTheCursorVisible() throws Exception {
        launch("2", false, false, false);
        // The host cursor sits in the middle of the desktop. The user pinches to ~4x over
        // the top-left of the screen, far from it.
        pinch(300f, 250f, 60f, 240f, 0f, 0f, 20);
        assertTrue("the pinch must have zoomed: " + panZoom.getScaleFactor(),
                panZoom.getScaleFactor() > 3f);
        settle();
        assertHostCursorOnScreen();

        // Then moves the cursor with a few trackpad strokes.
        for (int stroke = 0; stroke < 3; stroke++) {
            touchDrag(500f, 600f, 900f, 20);
            idle();
            settle();
            assertHostCursorOnScreen();
        }
    }

    // ---- zoom and pan keep the cursor where the user sees it -----------------------------

    /** The host reports its cursor at (x, y); drained on the UI thread like the real one. */
    private void hostCursorAt(int x, int y) {
        ShadowMoonBridgeWithHost.cursorX = x;
        ShadowMoonBridgeWithHost.cursorY = y;
        MeowStreamBridgeAccess.cursor(x, y, 1000 + x);
        idle();
    }

    private float cursorScreenX() {
        return panZoom.getChildX() + ShadowMoonBridgeWithHost.cursorX * panZoom.getScaleFactor();
    }

    private float cursorScreenY() {
        return panZoom.getChildY() + ShadowMoonBridgeWithHost.cursorY * panZoom.getScaleFactor();
    }

    @Test
    public void aPinchInATrackpadModeAnchorsOnTheCursorNotTheFingers() throws Exception {
        launch("2", false, true, false);
        hostCursorAt(1400, 700);
        float sx = cursorScreenX();
        float sy = cursorScreenY();
        // Fingers over the top-left, far from the cursor.
        pinch(300f, 250f, 60f, 200f, 0f, 0f, 20);
        assertTrue(panZoom.getScaleFactor() > 2.5f);
        assertEquals("the cursor keeps its screen position", sx, cursorScreenX(), 2f);
        assertEquals(sy, cursorScreenY(), 2f);
        settle();
        assertHostCursorOnScreen();
    }

    @Test
    public void aPinchInADirectTouchModeAnchorsOnTheFingers() throws Exception {
        // Mode 1: a tap clicks under the finger, so the finger is the pointer.
        launch("1", false, true, false);
        hostCursorAt(1400, 700);
        int sendsBefore = ShadowMoonBridgeWithHost.sends;
        pinch(300f, 250f, 60f, 200f, 0f, 0f, 20);
        assertTrue(panZoom.getScaleFactor() > 2.5f);
        // The desktop point under the fingers stayed under them...
        float underFingers = (300f - panZoom.getChildX()) / panZoom.getScaleFactor();
        assertEquals(300f, underFingers, 8f);
        // ...and the host pointer was not moved by the zoom.
        assertEquals(sendsBefore, ShadowMoonBridgeWithHost.sends);
    }

    @Test
    public void aTwoFingerPanCarriesTheCursorAlong() throws Exception {
        launch("2", false, true);
        hostCursorAt(960, 540);
        float sx = cursorScreenX();
        float zoomBefore = panZoom.getScaleFactor();
        // Pinch-and-drag: the fingers spread a little and drag left, so the view zooms and
        // pans under them.
        pinch(900f, 500f, 100f, 260f, -150f, 0f, 30);
        assertTrue("the gesture must have zoomed", panZoom.getScaleFactor() > zoomBefore);
        assertEquals("the cursor stays where the user sees it", sx, cursorScreenX(), 3f);
        settle();
        assertHostCursorOnScreen();
    }

    @Test
    public void aHostTeleportToTheOtherEndOfTheDesktopIsFollowedQuickly() throws Exception {
        launch("2", false, true);
        hostCursorAt(960, 540);
        // A dialog grabs the pointer on the far monitor, or the user flicks the host mouse.
        hostCursorAt(60, 1000);
        for (int i = 0; i < 45; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17));
        }
        assertHostCursorOnScreen();
    }

    @Test
    public void aDesktopCornerIsReachableWithoutHidingTheCursor() throws Exception {
        launch("2", false, true, false);
        hostCursorAt(4, 4);
        pinch(W / 2f, H / 2f, 60f, 240f, 0f, 0f, 20);
        settle();
        assertTrue(panZoom.getScaleFactor() > 3f);
        assertHostCursorOnScreen();
    }

    @Test
    public void aResizeBringsTheCursorBack() throws Exception {
        launch("2", false, true);
        hostCursorAt(1150, 650);
        // PiP / split screen: the stream shrinks to a quarter.
        container.layout(0, 0, W / 2, H / 2);
        container.getSurfaceView().layout(0, 0, W / 2, H / 2);
        panZoom.handleSurfaceChange();
        idle();
        settle();
        assertHostCursorOnScreen();
    }

    @Test
    public void afterACaptureToggleTheNextMovePlacesTheCursorInView() throws Exception {
        // Dead-reckoning host: whatever moved the pointer while capture was off is unknown.
        launch("1", false, false);
        mouseMoves(5, 10f, 0f);
        Method grab = Game.class.getDeclaredMethod("setInputGrabState", boolean.class);
        grab.setAccessible(true);
        grab.invoke(game, false);
        ShadowMoonBridgeWithHost.cursorX = 50;   // moved by something we did not see
        ShadowMoonBridgeWithHost.cursorY = 50;
        grab.invoke(game, true);
        mouseMoves(3, 5f, 5f);
        settle();
        assertHostCursorOnScreen();
    }

    @Test
    public void aDeadReckoningHostCursorNeverLeavesTheViewEvenOnAFlick() throws Exception {
        launch("2", false, false);
        // Place it once, then flick far right with no frames in between.
        mouseMoves(1, 1f, 0f);
        for (int i = 0; i < 30; i++) {
            game.onGenericMotionEvent(event(MotionEvent.ACTION_MOVE,
                    InputDevice.SOURCE_MOUSE_RELATIVE, MotionEvent.TOOL_TYPE_MOUSE,
                    200f, 0f, 200f, 0f));
            assertHostCursorOnScreen();
        }
    }

    @Test
    public void aDeadReckoningHostZoomsWhereTheCursorIsNotToTheMiddle() throws Exception {
        // The user's host: a proven meow host that does not report its cursor.
        launch("2", false, false, false);
        // Unzoomed, the user moves the cursor to the top-left with the trackpad.
        touchDrag(1500f, 800f, 1300f, 20);
        idle();
        float cx = ShadowMoonBridgeWithHost.cursorX;
        float cy = ShadowMoonBridgeWithHost.cursorY;
        assertTrue("cursor went left: " + cx, cx < 800f && cx > 50f);
        // Then pinches somewhere else entirely.
        pinch(1500f, 700f, 60f, 240f, 0f, 0f, 20);
        settle();
        assertTrue(panZoom.getScaleFactor() > 3f);
        assertEquals("the host pointer was not dragged to the middle",
                cx, ShadowMoonBridgeWithHost.cursorX, 30f);
        assertEquals(cy, ShadowMoonBridgeWithHost.cursorY, 30f);
        assertHostCursorOnScreen();
    }

    // ---- the second report: "when I move to the left I need to move more for it to move so
    // ---- it hides behind the screen, when right it moves not in the corner" ---------------

    /** One trackpad stroke of (dx, dy) screen pixels, one sample per frame. */
    private void trackpadStroke(float dx, float dy) {
        float x0 = W / 2f - dx / 2f;
        float y0 = H / 2f - dy / 2f;
        game.onTouch(container, event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, x0, y0, 0f, 0f));
        for (int i = 1; i <= 20; i++) {
            game.onTouch(container, event(MotionEvent.ACTION_MOVE,
                    InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER,
                    x0 + dx * i / 20, y0 + dy * i / 20, 0f, 0f));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17));
        }
        game.onTouch(container, event(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, x0 + dx, y0 + dy, 0f, 0f));
        idle();
    }

    /**
     * Drives the cursor to one edge of the desktop with trackpad strokes and checks, after
     * every stroke, that it is on screen -- and at the end that the view reached that edge.
     */
    private void pushToEdge(float dx, float dy) {
        for (int stroke = 0; stroke < 25; stroke++) {
            trackpadStroke(dx, dy);
            settle();
            assertHostCursorOnScreen();
        }
    }

    @Test
    public void everyEdgeAndCornerIsReachedWithTheCursorOnScreen() throws Exception {
        // The user's topology: a 5360x1440 desktop letterboxed into the stream, and a host
        // that accelerates relative motion and does not report its cursor.
        desktopW = 5360;
        desktopH = 1440;
        hostAcceleration = 1.8f;
        launch("2", false, false);
        float[] v;

        pushToEdge(-400f, 0f);
        v = visible();
        assertTrue("left: the view reached the desktop's left edge, at " + v[0], v[0] <= 1f);
        assertTrue("left: and so did the cursor", ShadowMoonBridgeWithHost.cursorX <= 20f);

        pushToEdge(400f, 0f);
        v = visible();
        assertTrue("right: the view reached the right edge", v[0] + v[2] >= W - 1f);
        assertTrue("right: and so did the cursor, at " + ShadowMoonBridgeWithHost.cursorX,
                ShadowMoonBridgeWithHost.cursorX >= desktopW - 60f);

        pushToEdge(0f, -400f);
        assertTrue("top: the cursor reached the top", ShadowMoonBridgeWithHost.cursorY <= 20f);
        pushToEdge(0f, 400f);
        assertTrue("bottom: the cursor reached the bottom, at " + ShadowMoonBridgeWithHost.cursorY,
                ShadowMoonBridgeWithHost.cursorY >= desktopH - 60f);

        // And the two corners the report is about.
        pushToEdge(-400f, -400f);
        assertTrue(ShadowMoonBridgeWithHost.cursorX <= 20f && ShadowMoonBridgeWithHost.cursorY <= 20f);
        pushToEdge(400f, 400f);
        assertTrue(ShadowMoonBridgeWithHost.cursorX >= desktopW - 60f
                && ShadowMoonBridgeWithHost.cursorY >= desktopH - 60f);
    }
}
