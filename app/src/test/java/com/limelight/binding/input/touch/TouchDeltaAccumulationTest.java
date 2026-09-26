package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doAnswer;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pointer motion must be neither lost nor lopsided: the sum of the deltas a touch context
 * sends has to match the finger's travel times the configured sensitivity, the same in
 * either direction, however finely the finger's motion is sampled. Truncating each event's
 * scaled delta without carrying the remainder loses up to a pixel per event -- a third of all
 * motion at 1.5x sensitivity when the finger moves a pixel per sample -- which is felt as a
 * sticky, "druggy" pointer at slow speeds.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class TouchDeltaAccumulationTest {

    private NvConnection conn;
    private final int[] sent = new int[2];
    private View view;

    @Before
    public void setUp() {
        conn = Mockito.mock(NvConnection.class);
        doAnswer(inv -> {
            sent[0] += (Short) inv.getArgument(0);
            sent[1] += (Short) inv.getArgument(1);
            return null;
        }).when(conn).sendMouseMove(anyShort(), anyShort());
        Context context = ApplicationProvider.getApplicationContext();
        view = new View(context);
        view.layout(0, 0, 1280, 720);
    }

    private int[] drag(TouchContext context, int fromX, int toX, int step) {
        sent[0] = 0;
        sent[1] = 0;
        long t = 1000;
        context.touchDownEvent(fromX, 300, t, true);
        int x = fromX;
        while (x != toX) {
            x += Integer.signum(toX - x) * Math.min(step, Math.abs(toX - x));
            t += 8;
            context.touchMoveEvent(x, 300, t);
        }
        context.touchUpEvent(toX, 300, t + 8);
        context.cancelTouch();
        return new int[] {sent[0], sent[1]};
    }

    private PreferenceConfiguration prefs(int sensitivity) {
        PreferenceConfiguration p =
                PreferenceConfiguration.readPreferences(ApplicationProvider.getApplicationContext());
        p.touchPadSensitivity = sensitivity;
        p.touchPadYSensitity = sensitivity;
        p.absoluteMouseMode = false;
        return p;
    }

    @Test
    public void gamingTouchModeLosesNoMotionAtAnySensitivity() {
        for (int sensitivity : new int[] {100, 150, 70}) {
            RelativeTouchContext right =
                    new RelativeTouchContext(conn, 0, 1280, 720, view, prefs(sensitivity));
            int[] r = drag(right, 200, 800, 1);
            assertEquals("rightward at " + sensitivity + "%", 600 * sensitivity / 100, r[0], 1);

            RelativeTouchContext left =
                    new RelativeTouchContext(conn, 0, 1280, 720, view, prefs(sensitivity));
            int[] l = drag(left, 800, 200, 1);
            assertEquals("leftward at " + sensitivity + "%", -600 * sensitivity / 100, l[0], 1);
        }
    }

    @Test
    public void naturalTrackpadModeIsSymmetric() {
        TrackpadContext right = new TrackpadContext(conn, 0, false, 150, 150);
        int[] r = drag(right, 200, 800, 3);
        TrackpadContext left = new TrackpadContext(conn, 0, false, 150, 150);
        int[] l = drag(left, 800, 200, 3);
        assertEquals("the same stroke moves the same distance either way", r[0], -l[0], 1);
    }
}
