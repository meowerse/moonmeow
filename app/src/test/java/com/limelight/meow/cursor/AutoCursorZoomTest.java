package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.SourceFiles;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class AutoCursorZoomTest {

    // The owner's phone: a 5360x1440 desktop in a 2160x3840 portrait stream, 1220x2169 view.
    private static final float PX = 1220f / 2160f;
    private static final float CONTENT_H = 580f * PX;
    private static final float PER_DESKTOP = PX * 2160f / 5360f;

    @Test
    public void aWideDesktopOnAnUprightPhoneFillsTheHeight() {
        float zoom = AutoCursorZoom.targetZoom(1220f, CONTENT_H, 1220f, 2169f,
                PER_DESKTOP, PER_DESKTOP);
        assertEquals(2169f / CONTENT_H, zoom, 0.001f);
        assertTrue("under the pixel cap", zoom * PER_DESKTOP <= 2f);
    }

    @Test
    public void aDesktopThatFillsMostOfTheViewIsLeftAlone() {
        assertEquals(1f, AutoCursorZoom.targetZoom(1920f, 1080f, 1920f, 1080f, 1f, 1f), 0f);
        // 16:10 in 16:9: 0.9 of the width.
        assertEquals(1f, AutoCursorZoom.targetZoom(1728f, 1080f, 1920f, 1080f, 1f, 1f), 0f);
    }

    @Test
    public void neverMagnifiesADesktopPixelPastTwoScreenPixels() {
        // A small desktop (1280x720 in a 2160-wide stream) on an upright phone: filling the
        // height would need 3.1x, which puts one desktop pixel over 3 screen pixels.
        float perDesktop = 1220f / 1280f;
        float zoom = AutoCursorZoom.targetZoom(1220f, 686f, 1220f, 2169f,
                perDesktop, perDesktop);
        assertEquals(2f / perDesktop, zoom, 0.001f);
    }

    @Test
    public void neverBelowOne() {
        assertEquals(1f, AutoCursorZoom.targetZoom(1220f, 300f, 1220f, 2169f, 4f, 4f), 0f);
    }

    @Test
    public void nothingMeasurableMeansNoZoom() {
        assertEquals(1f, AutoCursorZoom.targetZoom(0f, 300f, 1220f, 2169f, 1f, 1f), 0f);
        assertEquals(1f, AutoCursorZoom.targetZoom(1220f, 300f, 0f, 2169f, 1f, 1f), 0f);
        assertEquals(1f, AutoCursorZoom.targetZoom(Float.NaN, 300f, 1220f, 2169f, 1f, 1f), 0f);
    }

    @Test
    public void onByDefaultWithAnOffSwitch() {
        Context context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        assertTrue(AutoCursorZoom.DEFAULT);
        assertTrue(AutoCursorZoom.isEnabled(context));
        assertTrue(AutoCursorZoom.isEnabled(null));
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(AutoCursorZoom.KEY, false).commit();
        assertFalse(AutoCursorZoom.isEnabled(context));
    }

    @Test
    public void theXmlDefaultMatchesTheConstant() throws IOException {
        String xml = SourceFiles.read("app/src/main/res/xml/preferences.xml");
        int key = xml.indexOf("android:key=\"" + AutoCursorZoom.KEY + "\"");
        assertTrue(key > 0);
        String element = xml.substring(xml.lastIndexOf("<CheckBoxPreference", key), key);
        assertTrue(element.contains("android:defaultValue=\"true\""));
    }
}
