package com.limelight.meow.ui;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Renders the always-visible quick bar over a mock 16:9 stream, in both orientations, to
 * {@code app/build/meow-renders/} for a human to look at, and checks it sits in the letterbox
 * rather than over the stream on a 20:9 phone.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class QuickBarRenderTest {

    private void render(String name, int w, int h) throws Exception {
        QuickBarView bar = new QuickBarView(ApplicationProvider.getApplicationContext(), new QuickBarView.Listener() {
            @Override public void onKeyboard() { }
            @Override public void onToggleLocalCursor() { }
            @Override public void onCycleMouseMode() { }
            @Override public void onTogglePerfOverlay() { }
            @Override public void onOpenMenu() { }
        });
        bar.onStreamStarted();
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS);
        bar.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, w, h);

        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        float sw = w;
        float sh = sw * 9f / 16f;
        if (sh > h) {
            sh = h;
            sw = sh * 16f / 9f;
        }
        float left = (w - sw) / 2f;
        float top = (h - sh) / 2f;
        Paint p = new Paint();
        p.setColor(0xFF1B2A3A);
        canvas.drawRect(left, top, left + sw, top + sh, p);
        bar.draw(canvas);

        File cwd = new File("").getAbsoluteFile();
        File dir = new File(new File(cwd, "src").isDirectory() ? new File(cwd, "build") : new File(cwd, "app/build"), "meow-renders");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }

        android.graphics.Rect r = new android.graphics.Rect();
        assertTrue(name + " bar is shown", bar.obstructionInWindow(h, r));
        boolean clear = r.top >= top + sh || r.left >= left + sw;
        assertTrue(name + " bar " + r + " is clear of the stream", clear);
    }

    @Test
    @Config(sdk = 33, qualifiers = "w412dp-h915dp-port-420dpi")
    public void portrait() throws Exception {
        render("quickbar-portrait", 1082, 2402);
    }

    @Test
    @Config(sdk = 33, qualifiers = "w915dp-h412dp-land-420dpi")
    public void landscape() throws Exception {
        render("quickbar-landscape", 2402, 1082);
    }
}
