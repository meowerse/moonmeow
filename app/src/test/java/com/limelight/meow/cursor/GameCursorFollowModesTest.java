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
@Config(sdk = {33}, shadows = {ShadowMoonBridgeWithHost.class, ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class GameCursorFollowModesTest {

    private static final int W = 1920;
    private static final int H = 1080;

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
        binder.onStreamStarted(W, H);
        idle();
        binder.onViewportApplied(0, 0, W, H, W, H, 0);
        idle();

        // The user pinches to 4x about the centre: the visible box is (720, 405, 480, 270).
        panZoom.pinchBy(4f, W / 2f, H / 2f);
        idle();
        eventTime = SystemClock.uptimeMillis();
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
        float x = ShadowMoonBridgeWithHost.cursorX;
        float y = ShadowMoonBridgeWithHost.cursorY;
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
        for (int i = 0; i < 40; i++) {
            game.onGenericMotionEvent(event(MotionEvent.ACTION_MOVE,
                    InputDevice.SOURCE_MOUSE_RELATIVE, MotionEvent.TOOL_TYPE_MOUSE,
                    20f, 0f, 20f, 0f));
        }
        settle();
        assertFollowedRight(before);
    }

    @Test
    public void capturedMouseInAbsoluteMouseMode() throws Exception {
        launch("1", true, true);
        float before = panZoom.getChildX();
        for (int i = 0; i < 40; i++) {
            game.onGenericMotionEvent(event(MotionEvent.ACTION_MOVE,
                    InputDevice.SOURCE_MOUSE_RELATIVE, MotionEvent.TOOL_TYPE_MOUSE,
                    20f, 0f, 20f, 0f));
        }
        settle();
        assertFollowedRight(before);
    }

    @Test
    public void capturedMouseAgainstAHostThatDoesNotReport() throws Exception {
        // No 0x3004: the estimate fed by the NvConnection tap is all there is.
        launch("1", false, false);
        float before = panZoom.getChildX();
        for (int i = 0; i < 40; i++) {
            game.onGenericMotionEvent(event(MotionEvent.ACTION_MOVE,
                    InputDevice.SOURCE_MOUSE_RELATIVE, MotionEvent.TOOL_TYPE_MOUSE,
                    20f, 0f, 20f, 0f));
        }
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
}
