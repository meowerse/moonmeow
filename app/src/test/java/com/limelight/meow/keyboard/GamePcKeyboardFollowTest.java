package com.limelight.meow.keyboard;

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
import com.limelight.meow.cursor.AutoCursorZoom;
import com.limelight.meow.cursor.CursorFollowController;
import com.limelight.meow.cursor.CursorFollowPreference;
import com.limelight.meow.cursor.CursorInputTap;
import com.limelight.meow.stream.MeowStreamBridge;
import com.limelight.meow.viewport.StreamViewportBinder;
import com.limelight.meow.viewport.ViewportPreference;
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
 * The PC keyboard and the cursor follower, together, through {@code Game}: with the keyboard
 * open, the visible rectangle the follower keeps the cursor in (and the host crops to) ends
 * above the keyboard, so a cursor driven down the desktop stays where the user can see it.
 * Same fake meow host as {@code GameCursorFollowModesTest}.
 */
@Config(sdk = {33}, qualifiers = "w1920dp-h1080dp-land-mdpi",
        shadows = {ShadowMoonBridgeWithHost.class, ShadowGameManager.class,
                com.limelight.shadows.ShadowMeowViewportBridge.class})
@RunWith(RobolectricTestRunner.class)
public class GamePcKeyboardFollowTest {

    private static final int W = 1920;
    private static final int H = 1080;

    private ActivityController<Game> controller;
    private Game game;
    private StreamViewportBinder binder;
    private StreamContainer container;
    private PanZoomHandler panZoom;
    private long eventTime;
    /** The window; the stream container fills it (no letterbox), as with auto cursor zoom. */
    private int winW = W;
    private int winH = H;
    private boolean withQuickBar;
    private com.limelight.meow.ui.QuickBarView quickBar;

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

    @SuppressWarnings("unchecked")
    private <T> T field(String name) throws Exception {
        Field f = Game.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(game);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void settle() {
        for (int i = 0; i < 90; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17));
        }
    }

    private MotionEvent finger(int action, float x, float y) {
        eventTime += 8;
        MotionEvent e = MotionEvent.obtain(eventTime - 8, eventTime, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    private void launch() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear()
                .putString("mouse_mode_list", "2")          // touch trackpad, natural
                .putBoolean(CursorFollowPreference.KEY, true)
                .putBoolean(ViewportPreference.KEY, true)
                .putBoolean(AutoCursorZoom.KEY, false)
                .commit();
        Shadows.shadowOf((Application) context).declareComponentUnbindable(new ComponentName(
                "com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));

        Intent intent = new Intent(context, Game.class);
        intent.putExtra(Game.EXTRA_HOST, "127.0.0.1");
        intent.putExtra(Game.EXTRA_APP_ID, 1);
        intent.putExtra(Game.EXTRA_APP_NAME, "Desktop");
        intent.putExtra(Game.EXTRA_PC_NAME, "pc");
        intent.putExtra(Game.EXTRA_PC_UUID, "uuid-pckb");
        intent.putExtra(Game.EXTRA_UNIQUEID, "0123456789ABCDEF");
        // A real, attached window: getLocationInWindow (which the binder's visible window and
        // the stream lift both depend on) reports zeros for a detached view hierarchy.
        controller = Robolectric.buildActivity(Game.class, intent).create().start().resume().visible();
        game = controller.get();
        layoutWindow();

        binder = field("viewportBinder");
        container = field("streamContainer");
        panZoom = field("panZoomHandler");
        assertNotNull(binder);
        if (withQuickBar && field("quickBarView") == null) {
            // Same early return: build the quick bar as Game.onCreate's block does, before the
            // keyboard attaches (it finds the bar among the content's children).
            quickBar = new com.limelight.meow.ui.QuickBarView(game,
                    new com.limelight.meow.ui.QuickBarView.Listener() {
                        @Override public void onKeyboard() { }
                        @Override public void onToggleLocalCursor() { }
                        @Override public void onCycleMouseMode() { }
                        @Override public void onTogglePerfOverlay() { }
                        @Override public void onOpenMenu() { }
                    });
            ((android.view.ViewGroup) game.findViewById(android.R.id.content)).addView(quickBar);
            Field qb = Game.class.getDeclaredField("quickBarView");
            qb.setAccessible(true);
            qb.set(game, quickBar);
        }
        if (field("pcKeyboard") == null) {
            // Robolectric has no AVC decoder, so Game.onCreate returns before its attach
            // line (pinned by PcKeyboardWiringTest). Attach exactly as that line does.
            Field f = Game.class.getDeclaredField("pcKeyboard");
            f.setAccessible(true);
            f.set(game, PcKeyboardController.attach(game, container, field("prefConfig")));
            idle();
        }
        assertNotNull("the PC keyboard is attached", field("pcKeyboard"));

        ShadowMoonBridgeWithHost.reset(true);
        ShadowMoonBridgeWithHost.configure(W, H, W, H, 1f);
        binder.onStreamStarted(W, H);
        idle();
        binder.onViewportApplied(0, 0, W, H, W, H, 0);
        Field handler = StreamViewportBinder.class.getDeclaredField("handler");
        handler.setAccessible(true);
        Shadows.shadowOf(((android.os.Handler) handler.get(binder)).getLooper()).idle();
        idle();
        ShadowMoonBridgeWithHost.reportOnSubscribe(true);
        idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(CursorFollowController.FIRST_REPORT_WAIT_MS + 100));
        eventTime = SystemClock.uptimeMillis();
        // Zoomed in, so the follower has something to pan.
        panZoom.pinchBy(3f, container.getWidth() / 2f, container.getHeight() / 2f);
        idle();
    }

    private void layoutWindow() {
        View decor = game.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(winW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(winH, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, winW, winH);
    }

    /** The host cursor's y in window pixels, through the binder's live transform. */
    private float cursorWindowY() {
        float[] t = new float[5];
        assertTrue(binder.transform(t));
        int[] at = new int[2];
        container.getLocationInWindow(at);
        return at[1] + t[1] + ShadowMoonBridgeWithHost.referenceY() * t[3];
    }

    @Test
    @Config(qualifiers = "w1080dp-h2400dp-port-mdpi")
    public void withTheStreamFillingTheViewTheQuickBarStaysShownAndTheCursorAboveIt() throws Exception {
        // The owner: "why also buttons bar is still hiding to this line? let's always show it".
        // With auto cursor zoom the stream fills the view: there is no letterbox to stand in.
        winW = 1080;
        winH = 2400;
        withQuickBar = true;
        launch();
        quickBar.onStreamStarted();
        layoutWindow();
        PcKeyboardController pc = field("pcKeyboard");
        pc.update();
        layoutWindow();
        pc.update();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        settle();

        assertTrue("the bar is shown, not collapsed to its handle", quickBar.isBarShown());
        android.graphics.Rect bar = new android.graphics.Rect();
        assertTrue("and it is kept clear of: an obstruction", quickBar.obstructionInWindow(winH, bar));
        Field obstruction = StreamViewportBinder.class.getDeclaredField("bottomObstructionPx");
        obstruction.setAccessible(true);
        assertEquals("the binder's obstruction is the bar's cover", winH - bar.top, obstruction.getInt(binder));
        assertEquals("the stream is not moved for the bar alone", 0f, pc.liftTarget(), 0f);

        float x = container.getWidth() / 2f;
        for (int stroke = 0; stroke < 8; stroke++) {
            game.onTouch(container, finger(MotionEvent.ACTION_DOWN, x, 200f));
            for (int i = 1; i <= 40; i++) {
                game.onTouch(container, finger(MotionEvent.ACTION_MOVE, x, 200f + i * 15f));
            }
            game.onTouch(container, finger(MotionEvent.ACTION_UP, x, 800f));
            settle();
        }
        settle();
        float y = cursorWindowY();
        assertTrue("the host cursor (window y " + y + ") ends above the bar (top " + bar.top + ")",
                y <= bar.top);
    }

    @Test
    public void inLandscapeWithTheStreamFillingTheViewTheSideBarStaysShownAndTheCursorLeftOfIt()
            throws Exception {
        withQuickBar = true;
        launch();
        quickBar.onStreamStarted();
        layoutWindow();
        PcKeyboardController pc = field("pcKeyboard");
        pc.update();
        layoutWindow();
        pc.update();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        settle();

        assertTrue("the bar is shown", quickBar.isBarShown());
        android.graphics.Rect bar = new android.graphics.Rect();
        assertTrue(quickBar.obstructionInWindow(winH, bar));
        assertTrue("down the right side: " + bar, bar.height() > bar.width());
        Field right = StreamViewportBinder.class.getDeclaredField("rightObstructionPx");
        right.setAccessible(true);
        assertEquals("the binder's right obstruction is the bar's cover", winW - bar.left, right.getInt(binder));
        Field bottom = StreamViewportBinder.class.getDeclaredField("bottomObstructionPx");
        bottom.setAccessible(true);
        assertEquals("nothing covers the bottom", 0, bottom.getInt(binder));

        // Drive the cursor to the desktop's right edge: the last columns no pan reaches.
        float y = container.getHeight() / 2f;
        for (int stroke = 0; stroke < 12; stroke++) {
            game.onTouch(container, finger(MotionEvent.ACTION_DOWN, 100f, y));
            for (int i = 1; i <= 40; i++) {
                game.onTouch(container, finger(MotionEvent.ACTION_MOVE, 100f + i * 30f, y));
            }
            game.onTouch(container, finger(MotionEvent.ACTION_UP, 1300f, y));
            settle();
        }
        settle();
        float[] t = new float[5];
        assertTrue(binder.transform(t));
        int[] at = new int[2];
        container.getLocationInWindow(at);
        float x = at[0] + t[0] + ShadowMoonBridgeWithHost.referenceX() * t[2];
        assertTrue("the host cursor (window x " + x + ") ends left of the bar (left " + bar.left + ")",
                x <= bar.left);
    }

    @Test
    public void withThePcKeyboardOpenTheFollowedCursorStaysAboveIt() throws Exception {
        launch();
        PcKeyboardController pc = field("pcKeyboard");
        pc.show();
        layoutWindow();
        pc.update();
        settle();

        KeyboardVisibleArea area = KeyboardVisibleArea.of(container);
        assertTrue(area.isKnown());
        int keyboardTop = area.visibleBottom();
        int windowHeight = container.getRootView().getHeight();
        assertTrue("the keyboard covers part of the window: top " + keyboardTop + " of " + windowHeight,
                keyboardTop < windowHeight);
        Field obstruction = StreamViewportBinder.class.getDeclaredField("bottomObstructionPx");
        obstruction.setAccessible(true);
        assertEquals("the keyboard area drives the binder's bottom obstruction",
                windowHeight - keyboardTop, obstruction.getInt(binder));

        // Drive the cursor down the desktop with trackpad strokes.
        float x = container.getWidth() / 2f;
        for (int stroke = 0; stroke < 6; stroke++) {
            game.onTouch(container, finger(MotionEvent.ACTION_DOWN, x, 20f));
            for (int i = 1; i <= 40; i++) {
                game.onTouch(container, finger(MotionEvent.ACTION_MOVE, x, 20f + i * 6f));
            }
            game.onTouch(container, finger(MotionEvent.ACTION_UP, x, 260f));
            settle();
        }
        settle();
        assertTrue("the stream lifted above the keyboard: " + pc.liftTarget(), pc.liftTarget() < 0f);
        float y = cursorWindowY();
        assertTrue("the host cursor (window y " + y + ") stays above the PC keyboard (top "
                + keyboardTop + ")", y <= keyboardTop);

        pc.hide();
        layoutWindow();
        pc.update();
        settle();
        assertEquals("closing it releases the obstruction", 0, obstruction.getInt(binder));
    }
}
