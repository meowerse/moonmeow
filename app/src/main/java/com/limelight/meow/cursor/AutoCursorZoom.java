package com.limelight.meow.cursor;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.profiles.ProfilesManager;

/**
 * Auto cursor zoom: when the desktop only fills a thin strip of the view, start zoomed so it
 * fills the view instead, centred on the cursor. {@link CursorFollowController} applies it.
 *
 * <p>Why it exists: on 2026-09-24 the owner streamed a 5360x1440 desktop to a portrait phone.
 * FIT put it in a 1220x330 px strip -- unreadable -- and cursor follow, which only acts once
 * zoomed in, was inert because they never pinched. Starting zoomed makes the desktop readable
 * and follow useful from the first frame.
 *
 * <p>The rule, in one place: the desktop's box at zoom 1, in view pixels, against the visible
 * window. If it fills less than {@link #FILL_THRESHOLD} of the window in either axis, zoom by
 * the reciprocal of the smaller fill, so the desktop covers the window and scrolls along the
 * other axis. Capped where one desktop pixel would become more than
 * {@link #MAX_SCREEN_PX_PER_DESKTOP_PX} screen pixels -- past that the user sees blur, not
 * detail -- and never below 1. The user's own pinch always wins; the controller stops
 * auto-zooming for the rest of the stream once they have zoomed.
 */
public final class AutoCursorZoom {

    public static final String KEY = "checkbox_meow_auto_cursor_zoom";
    /** Must stay in step with {@code android:defaultValue} in {@code res/xml/preferences.xml}. */
    public static final boolean DEFAULT = true;

    /** Below this fraction of the window in either axis the desktop counts as a strip. */
    static final float FILL_THRESHOLD = 0.6f;
    /** Auto zoom stops where one desktop pixel spans this many screen pixels. */
    static final float MAX_SCREEN_PX_PER_DESKTOP_PX = 2f;
    /** {@code PanZoomHandler.MAX_SCALE}: a target past it could never be reached. */
    static final float MAX_ZOOM = 10f;

    private AutoCursorZoom() {
    }

    /** Read once in {@code Game.onCreate}; a change applies to the next stream. */
    public static boolean isEnabled(Context context) {
        if (context == null) {
            return DEFAULT;
        }
        try {
            SharedPreferences prefs =
                    ProfilesManager.getInstance().getOverlayingSharedPreferences(context);
            return prefs != null ? prefs.getBoolean(KEY, DEFAULT) : DEFAULT;
        } catch (RuntimeException e) {
            // A preference read must never take the stream down with it.
            return DEFAULT;
        }
    }

    /**
     * The zoom to start at, 1 for none.
     *
     * @param contentWidth  the desktop's width on screen at zoom 1, pixels
     * @param contentHeight its height
     * @param windowWidth   the visible window's width, pixels
     * @param windowHeight  its height
     * @param screenPerDesktopX screen pixels per desktop pixel at zoom 1, horizontally
     * @param screenPerDesktopY the same vertically
     */
    public static float targetZoom(float contentWidth, float contentHeight,
                                   float windowWidth, float windowHeight,
                                   float screenPerDesktopX, float screenPerDesktopY) {
        if (!(contentWidth > 0f) || !(contentHeight > 0f)
                || !(windowWidth > 0f) || !(windowHeight > 0f)) {
            return 1f;
        }
        float fill = Math.min(contentWidth / windowWidth, contentHeight / windowHeight);
        if (fill >= FILL_THRESHOLD) {
            return 1f;
        }
        float zoom = 1f / fill;
        float densest = Math.max(screenPerDesktopX, screenPerDesktopY);
        if (densest > 0f) {
            zoom = Math.min(zoom, MAX_SCREEN_PX_PER_DESKTOP_PX / densest);
        }
        return Math.max(1f, Math.min(zoom, MAX_ZOOM));
    }
}
