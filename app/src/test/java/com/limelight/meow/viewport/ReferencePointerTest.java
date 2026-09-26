package com.limelight.meow.viewport;

import static org.junit.Assert.assertEquals;

import com.limelight.meow.gesture.InlinePinchZoomController;

import org.junit.After;
import org.junit.Test;

/** Absolute input must address the reference point under the finger, zoomed or not (C3). */
public class ReferencePointerTest {

    private static final int W = 2712;
    private static final int H = 1220;

    private static InlinePinchZoomController.ZoomTarget transform(float scale, float x, float y) {
        return new InlinePinchZoomController.ZoomTarget() {
            @Override public void pinchBy(float s, float fx, float fy) { }
            @Override public void panBy(float dx, float dy) { }
            @Override public float getScaleFactor() { return scale; }
            @Override public float getChildX() { return x; }
            @Override public float getChildY() { return y; }
        };
    }

    @After
    public void tearDown() {
        ReferencePointer.install(null);
    }

    @Test
    public void withoutATransformThePositionPassesThrough() {
        assertEquals(1234, ReferencePointer.x(1234.6f, W));
        assertEquals(987, ReferencePointer.y(987f, H));
    }

    @Test
    public void unzoomedTheMappingIsTheIdentity() {
        ReferencePointer.install(transform(1f, 0f, 0f));
        assertEquals(1234, ReferencePointer.x(1234f, W));
        assertEquals(987, ReferencePointer.y(987f, H));
    }

    @Test
    public void zoomedATapLandsOnTheFramePointUnderTheFinger() {
        // 4x, panned so the frame's (1000, 500) container point sits at the screen origin.
        ReferencePointer.install(transform(4f, -4000f, -2000f));
        // Tapping the screen centre (1356, 610) shows frame point 1000 + 1356/4 = 1339.
        assertEquals(1339, ReferencePointer.x(1356f, W));
        assertEquals(652, ReferencePointer.y(610f, H));
        // The screen's corners map to the visible sub-rectangle's corners, never beyond it.
        assertEquals(1000, ReferencePointer.x(0f, W));
        assertEquals(1678, ReferencePointer.x(W, W));
    }

    @Test
    public void theResultIsClampedToTheReferenceRange() {
        assertEquals(0f, ReferencePointer.map(-50f, 0f, 1f, W), 0f);
        assertEquals(W, ReferencePointer.map(W + 50f, 0f, 1f, W), 0f);
        assertEquals(42f, ReferencePointer.map(42f, 0f, 0f, W), 0f);
    }

    @Test
    public void uninstallingSomeoneElsesTransformLeavesTheLiveOne() {
        InlinePinchZoomController.ZoomTarget live = transform(2f, 0f, 0f);
        ReferencePointer.install(live);
        ReferencePointer.uninstall(transform(1f, 0f, 0f));
        assertEquals(500, ReferencePointer.x(1000f, W));
        ReferencePointer.uninstall(live);
        assertEquals(1000, ReferencePointer.x(1000f, W));
    }
}
