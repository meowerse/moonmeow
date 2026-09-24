package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.keyboard.KeyboardVisibleArea;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The binder is where every cursor position already arrives, so it is the PC keyboard's
 * focus source: the lift keeps the host cursor above a keyboard in landscape.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class StreamViewportBinderKeyboardFocusTest {

    private FrameLayout parent;
    private View streamView;
    private StreamViewportBinder binder;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        parent = new FrameLayout(context);
        streamView = new View(context);
        parent.addView(streamView, new FrameLayout.LayoutParams(1920, 1080));
        parent.layout(0, 0, 1920, 1080);
        streamView.layout(0, 0, 1920, 1080);
        binder = new StreamViewportBinder(streamView, parent, null, new Handler(Looper.getMainLooper()));
    }

    @Test
    public void theBinderIsTheKeyboardsFocusSource() {
        KeyboardVisibleArea area = KeyboardVisibleArea.of(parent);
        assertNotNull(area);
        assertTrue("unknown until the cursor is seen", Float.isNaN(area.focusY()));
        binder.handleCursorViewPosition(400f, 700f, null);
        assertEquals(700f, area.focusY(), 0f);
    }

    @Test
    public void aHostPositionIsMappedIntoContainerPixels() {
        binder.onStreamStarted(1920, 1080);
        binder.handleCursorHostPosition(100, 540, null);
        assertEquals(540f, KeyboardVisibleArea.of(parent).focusY(), 0.5f);
    }
}
