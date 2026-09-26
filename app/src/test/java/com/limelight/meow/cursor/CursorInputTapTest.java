package com.limelight.meow.cursor;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CursorInputTapTest {

    private static final class Recorder implements CursorInputTap.Listener {
        final List<String> calls = new ArrayList<>();

        boolean consume;

        @Override public boolean onRelativeMove(int dx, int dy) {
            calls.add("rel " + dx + "," + dy);
            return consume;
        }

        @Override public void onAbsolutePosition(int x, int y, int w, int h) {
            calls.add("abs " + x + "," + y + " " + w + "x" + h);
        }

        @Override public void onMoveAsPosition(int dx, int dy, int w, int h) {
            calls.add("as " + dx + "," + dy + " " + w + "x" + h);
        }

        @Override public void onDirectPointing(byte type, float x, float y) {
            calls.add("touch" + type + " " + (Float.isNaN(x) ? "-" : x + "," + y));
        }
    }

    @After
    public void tearDown() {
        CursorInputTap.install(null);
    }

    @Test
    public void everySendReachesTheListener() {
        Recorder r = new Recorder();
        CursorInputTap.install(r);
        CursorInputTap.relative((short) -3, (short) 4);
        CursorInputTap.absolute((short) 10, (short) 20, (short) 1920, (short) 1080);
        CursorInputTap.moveAsPosition((short) 5, (short) 6, (short) 2712, (short) 1220);
        CursorInputTap.touch((byte) 0x01, 0, 0.2f, 0.5f);    // first finger down
        CursorInputTap.touch((byte) 0x03, 0, 0.25f, 0.5f);   // first finger moves
        CursorInputTap.touch((byte) 0x03, 1, 0.75f, 0.5f);   // a second finger: no position
        CursorInputTap.touch((byte) 0x02, 0, 0.25f, 0.5f);   // lift: no position
        assertEquals("[rel -3,4, abs 10,20 1920x1080, as 5,6 2712x1220, touch1 0.2,0.5, "
                + "touch3 0.25,0.5, touch3 -, touch2 -]", r.calls.toString());
    }

    @Test
    public void aConsumedRelativeMoveIsReportedToTheSender() {
        Recorder r = new Recorder();
        CursorInputTap.install(r);
        assertEquals(false, CursorInputTap.relative((short) 1, (short) 1));
        r.consume = true;
        assertEquals(true, CursorInputTap.relative((short) 1, (short) 1));
        CursorInputTap.install(null);
        assertEquals("nothing installed: send as usual",
                false, CursorInputTap.relative((short) 1, (short) 1));
    }

    @Test
    public void withoutAListenerSendsAreHarmless() {
        CursorInputTap.relative((short) 1, (short) 1);
    }

    @Test
    public void uninstallingSomeoneElsesListenerLeavesTheLiveOne() {
        Recorder live = new Recorder();
        CursorInputTap.install(live);
        CursorInputTap.uninstall(new Recorder());
        CursorInputTap.relative((short) 1, (short) 1);
        assertEquals(1, live.calls.size());
        CursorInputTap.uninstall(live);
        CursorInputTap.relative((short) 1, (short) 1);
        assertEquals(1, live.calls.size());
    }
}
