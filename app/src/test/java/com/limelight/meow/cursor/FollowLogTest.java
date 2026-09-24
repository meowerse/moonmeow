package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.meow.SourceFiles;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FollowLogTest {

    private final List<String> lines = new ArrayList<>();
    private final FollowLog log = new FollowLog(lines::add);

    @Test
    public void stateLinesAreAlwaysWritten() {
        log.state("a");
        log.state("b");
        assertEquals(2, lines.size());
    }

    @Test
    public void activityIsRateLimitedAndReportsWhatItDropped() {
        assertTrue(log.activityAllowed(1000));
        log.activity("first");
        for (int t = 1010; t < 1250; t += 10) {
            assertFalse(log.activityAllowed(t));
        }
        assertTrue(log.activityAllowed(1000 + FollowLog.ACTIVITY_INTERVAL_MS));
        log.activity("second");
        assertEquals("first", lines.get(0));
        assertEquals("second (+24 suppressed)", lines.get(1));
    }

    @Test
    public void releaseBuildsKeepTheseLines() throws IOException {
        // An -assumenosideeffects rule on android.util.Log would strip every line from the
        // release APK, which is the one the user runs.
        String rules = SourceFiles.stripComments(SourceFiles.read("app/proguard-rules.pro"))
                .replaceAll("#[^\\n]*", "");
        assertFalse(rules.contains("assumenosideeffects"));
        assertEquals("MeowFollow", FollowLog.TAG);
    }
}
