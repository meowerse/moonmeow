package com.limelight.meow.bitrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.SourceFiles;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class BitratePreferencesAndMemoryTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        context.getSharedPreferences(BitrateMemory.FILE, Context.MODE_PRIVATE).edit().clear()
                .commit();
    }

    @Test
    public void automaticBitrateIsOnByDefaultAndCanBeTurnedOff() {
        assertTrue(AutoBitratePreference.isEnabled(context));
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(AutoBitratePreference.KEY, false).commit();
        assertFalse(AutoBitratePreference.isEnabled(context));
        assertTrue(AutoBitratePreference.isEnabled(null));
    }

    @Test
    public void theXmlDefaultMatchesTheConstant() throws IOException {
        String xml = SourceFiles.read("app/src/main/res/xml/preferences.xml");
        int key = xml.indexOf("android:key=\"" + AutoBitratePreference.KEY + "\"");
        assertTrue(key > 0);
        String element = xml.substring(xml.lastIndexOf("<CheckBoxPreference", key), key);
        assertTrue(element.contains("android:defaultValue=\"" + AutoBitratePreference.DEFAULT + "\""));
    }

    @Test
    public void theStableBitrateIsRememberedPerHost() {
        BitrateMemory memory = new BitrateMemory(context);
        memory.put("host-a", false, 12000);
        memory.put("host-b", false, 7000);
        memory.put("host-a", true, 3000);
        assertEquals(12000, new BitrateMemory(context).get("host-a", false));
        assertEquals("metered sessions are remembered apart",
                3000, new BitrateMemory(context).get("host-a", true));
        assertEquals(7000, new BitrateMemory(context).get("host-b", false));
        assertEquals(0, memory.get("host-c", false));
        memory.clear("host-a", false);
        assertEquals(0, memory.get("host-a", false));
        assertEquals(3000, memory.get("host-a", true));
    }

    @Test
    public void nonsenseIsNeverRemembered() {
        BitrateMemory memory = new BitrateMemory(context);
        memory.put("host-a", false, 12000);
        memory.put("host-a", false, 0);
        memory.put(null, false, 5000);
        memory.put("", false, 5000);
        memory.clear(null, false);
        assertEquals(12000, memory.get("host-a", false));
        assertEquals(0, memory.get(null, false));
        assertEquals(0, memory.get("", false));
    }

    @Test
    public void theOverlayShowsTheAppliedBitrateOnceThereIsOne() {
        StringBuilder big = new StringBuilder("Decode: 5 ms");
        BitrateOverlay.clear();
        BitrateOverlay.append(big, context, false);
        assertEquals("Decode: 5 ms", big.toString());

        BitrateOverlay.publish(12500, true);
        BitrateOverlay.append(big, context, false);
        assertEquals("Decode: 5 ms\nBitrate: applied 12.5 Mbps (auto)", big.toString());

        StringBuilder lite = new StringBuilder("FPS 60");
        BitrateOverlay.publish(8000, false);
        BitrateOverlay.append(lite, context, true);
        assertEquals("FPS 60\tBitrate: applied 8.0 Mbps (host)", lite.toString());
        BitrateOverlay.clear();
    }
}
