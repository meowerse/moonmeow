package com.limelight.utils;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.meow.gesture.InlinePinchZoomController;
import com.limelight.meow.viewport.ZoomTransformObserver;
import com.limelight.preferences.PreferenceConfiguration;

// MEOW-TOUCH(inline-pinch-zoom): implement the meow ZoomTarget seam so the inline
// (modeless) pinch path drives exactly the same transform as the explicit Pan/Zoom mode.
public class PanZoomHandler implements InlinePinchZoomController.ZoomTarget {
    static private final float MAX_SCALE = 10.0f;

    private final Game game;
    private final View streamView;
    private final PreferenceConfiguration prefConfig;
    private final boolean isTopMode;
    private final ScaleGestureDetector scaleGestureDetector;
    private final GestureDetector gestureDetector;
    private View parent;
    private float scaleFactor = 1.0f;
    private float childX, childY = 0;
    private float parentWidth, parentHeight = 0;
    private float childWidth, childHeight = 0;
    // MEOW-TOUCH(viewport-follow): observers so viewport reporting and cursor scaling
    // share the same transform without duplicating it. Single-slot originally; now a list
    // because a second feature (cursor enlargement at low zoom) needs it too.
    private final java.util.List<ZoomTransformObserver> zoomTransformObservers = new java.util.ArrayList<>();
    @Deprecated
    private ZoomTransformObserver zoomTransformObserver;

    public PanZoomHandler(Context context, Game game, View streamView, View parent, PreferenceConfiguration prefConfig) {
        this.game = game;
        this.streamView = streamView;
        this.parent = parent;
        this.prefConfig = prefConfig;
        this.isTopMode = prefConfig.alignDisplayTopCenter;
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());

        // Everything gets easier with 0,0 as the pivot point
        streamView.setPivotX(0);
        streamView.setPivotY(0);
    }

    /**
     * MEOW-TOUCH(viewport-follow): install the single zoom-transform observer.
     *
     * <p>Now delegates to the observer list; kept for backward compat and tests that
     * reflect on this method name. Calling this clears the list and installs exactly
     * this one observer (pass null to clear).
     */
    public void setZoomTransformObserver(ZoomTransformObserver observer) {
        this.zoomTransformObserver = observer;
        zoomTransformObservers.clear();
        if (observer != null) {
            zoomTransformObservers.add(observer);
        }
    }

    /** Add an additional observer without clearing existing ones. */
    public void addZoomTransformObserver(ZoomTransformObserver observer) {
        if (observer != null && !zoomTransformObservers.contains(observer)) {
            zoomTransformObservers.add(observer);
        }
        // Keep deprecated single field in sync with first observer for reflection checks
        if (zoomTransformObservers.isEmpty()) {
            this.zoomTransformObserver = null;
        } else {
            this.zoomTransformObserver = zoomTransformObservers.get(0);
        }
    }

    public void removeZoomTransformObserver(ZoomTransformObserver observer) {
        zoomTransformObservers.remove(observer);
        if (zoomTransformObservers.isEmpty()) {
            this.zoomTransformObserver = null;
        } else {
            this.zoomTransformObserver = zoomTransformObservers.get(0);
        }
    }

    private void notifyZoomTransformChanged() {
        // Iterate over snapshot to allow observer adding/removing during callback
        java.util.List<ZoomTransformObserver> snapshot = new java.util.ArrayList<>(zoomTransformObservers);
        // Fallback: if list empty but deprecated field set (set via reflection in tests)
        if (snapshot.isEmpty() && zoomTransformObserver != null) {
            snapshot.add(zoomTransformObserver);
        }
        for (ZoomTransformObserver o : snapshot) {
            if (o != null) o.onZoomTransformChanged();
        }
    }

    public void handleTouchEvent(MotionEvent motionEvent) {
        scaleGestureDetector.onTouchEvent(motionEvent);
        gestureDetector.onTouchEvent(motionEvent);
    }

    private void updateDimensions() {
        childHeight = streamView.getHeight() * scaleFactor;
        childWidth = streamView.getWidth() * scaleFactor;
        parentWidth = parent.getWidth();
        parentHeight = parent.getHeight();
    }

    private void constrainToBounds() {
        updateDimensions();

        if (parentWidth >= childWidth) {
            childX = (parentWidth - childWidth) / 2;
        } else {
            float boundaryX = parentWidth - childWidth;
            childX = Math.max(boundaryX, Math.min(childX, 0));
        }

        if (parentHeight >= childHeight) {
            if (isTopMode) {
                childY = 0;
            } else {
                childY = (parentHeight - childHeight) / 2;
            }
        } else {
            float boundaryY = parentHeight - childHeight;
            childY = Math.max(boundaryY, Math.min(childY, 0));
        }

        streamView.setX(childX);
        streamView.setY(childY);

        // MEOW-TOUCH(viewport-follow): every transform ends here -- pinchBy, panBy and
        // handleSurfaceChange all funnel through constrainToBounds -- so this is the one
        // place the visible rectangle can change. See docs/meow/TOUCHPOINTS.md
        notifyZoomTransformChanged();
    }

    public void handleSurfaceChange() {
        if (childWidth == 0 || parent == null) {
            // Retrieve parent, should handle both built-in display and external display
            parent = (View)streamView.getParent();
            return;
        }

        float prevChildWidth = childWidth;
        float prevChildHeight = childHeight;
        float prevParentWidth = parentWidth;
        float prevParentHeight = parentHeight;

        updateDimensions();

        float viewScaleX = childWidth / prevChildWidth;
        float viewScaleY = childHeight / prevChildHeight;

        float dPivotX1 = childX - prevParentWidth / 2;
        float dPivotY1 = childY - prevParentHeight / 2;

        float dPivotX2 = dPivotX1 * viewScaleX;
        float dPivotY2 = dPivotY1 * viewScaleY;

        childX = dPivotX2 + parentWidth / 2;
        childY = dPivotY2 + parentHeight / 2;

        streamView.setX(childX);
        streamView.setY(childY);

        constrainToBounds();
    }

    // MEOW-TOUCH(inline-pinch-zoom): the scale transform, extracted verbatim from
    // ScaleListener.onScale so the inline pinch path and the explicit Pan/Zoom mode
    // share one implementation instead of drifting apart.
    @Override
    public void pinchBy(float scaleDelta, float focusX, float focusY) {
        float newScaleFactor = scaleFactor * scaleDelta;
        newScaleFactor = Math.max(1, Math.min(newScaleFactor, MAX_SCALE)); // Apply minimum scale

        float dPivotX = (childX - focusX) / scaleFactor * newScaleFactor;
        float dPivotY = (childY - focusY) / scaleFactor * newScaleFactor;

        childX = focusX + dPivotX;
        childY = focusY + dPivotY;

        scaleFactor = newScaleFactor;

        streamView.setScaleX(scaleFactor);
        streamView.setScaleY(scaleFactor);

        streamView.setX(childX);
        streamView.setY(childY);

        constrainToBounds();
    }

    // MEOW-TOUCH(inline-pinch-zoom): the pan transform, extracted verbatim from
    // GestureListener.onScroll. Note the sign: onScroll reports a scroll *distance*,
    // which is the negation of the movement delta this takes.
    @Override
    public void panBy(float dx, float dy) {
        childX = streamView.getX() + dx;
        childY = streamView.getY() + dy;

        streamView.setX(childX);
        streamView.setY(childY);

        constrainToBounds();
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            pinchBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            game.updatePipAutoEnter();
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            panBy(-distanceX, -distanceY);
            return true;
        }
    }

    public void setInitialZoomAndPan(float scale, float offsetX, float offsetY) {
        this.scaleFactor = scale;
        // apply to view
        streamView.setScaleX(scaleFactor);
        streamView.setScaleY(scaleFactor);
        this.childX = offsetX;
        this.childY = offsetY;
        streamView.setX(childX);
        streamView.setY(childY);

        // MEOW-TOUCH(viewport-follow): the one transform that does not go through
        // constrainToBounds. Without this a restored zoom (rememberZoomPan) would leave the
        // host uncropped until the user next moved.
        notifyZoomTransformChanged();
    }

    public float getScaleFactor() { return scaleFactor; }
    public float getChildX() { return childX; }
    public float getChildY() { return childY; }
}
