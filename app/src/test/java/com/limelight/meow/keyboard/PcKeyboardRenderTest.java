package com.limelight.meow.keyboard;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Renders the keyboard, in each orientation, size and interesting state, to PNGs under
 * {@code app/build/meow-renders/} for a human to look at. It also asserts the renders are not
 * blank, so a drawing regression that paints nothing fails here.
 *
 * <p>Each render is a whole window: a mock desktop where the stream would be, lifted by
 * {@link StreamLift} exactly as the controller would, and the keyboard at the bottom.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PcKeyboardRenderTest {

    private static final int NAV_BAR_DP = 48;

    private File outDir() {
        File cwd = new File("").getAbsoluteFile();
        File build = new File(cwd, "build");
        if (!new File(cwd, "src").isDirectory()) {
            build = new File(cwd, "app/build");
        }
        File dir = new File(build, "meow-renders");
        dir.mkdirs();
        return dir;
    }

    private interface Setup {
        void apply(PcKeyboardView view, PcKeyboardEngine engine);
    }

    private void render(String name, int widthPx, int heightPx, int mode, Setup setup) throws IOException {
        Context ctx = ApplicationProvider.getApplicationContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        PcKeyboardEngine engine = new PcKeyboardEngine(new RecordingSink(), 300);
        PcKeyboardView view = new PcKeyboardView(ctx, engine);
        view.setMode(mode);
        int navPx = Math.round(NAV_BAR_DP * density);
        int contentHeight = heightPx - navPx;
        view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(contentHeight, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, widthPx, view.getMeasuredHeight());
        if (setup != null) {
            setup.apply(view, engine);
        }

        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);

        int imeHeight = mode == PcKeyboardView.MODE_STRIP ? Math.round(heightPx * 0.36f) : 0;
        int keyboardTop = contentHeight - imeHeight - view.getMeasuredHeight();

        // The stream: a 16:9 desktop fitted to the content area, lifted above the keyboard.
        float streamW = widthPx;
        float streamH = streamW * 9f / 16f;
        if (streamH > contentHeight) {
            streamH = contentHeight;
            streamW = streamH * 16f / 9f;
        }
        float left = (widthPx - streamW) / 2f;
        float top = (contentHeight - streamH) / 2f;
        float lift = StreamLift.liftFor(top, top + streamH, 0, keyboardTop, Float.NaN);
        drawDesktop(canvas, left, top + lift, streamW, streamH, density);

        if (imeHeight > 0) {
            Paint ime = new Paint();
            ime.setColor(0xFF202124);
            canvas.drawRect(0, contentHeight - imeHeight, widthPx, contentHeight, ime);
            ime.setColor(0xFF9AA0A6);
            ime.setTextSize(14 * density);
            ime.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("system keyboard", widthPx / 2f, contentHeight - imeHeight / 2f, ime);
        }
        canvas.save();
        canvas.translate(0, keyboardTop);
        view.draw(canvas);
        canvas.restore();

        Paint nav = new Paint();
        nav.setColor(0xFF000000);
        canvas.drawRect(0, contentHeight, widthPx, heightPx, nav);
        nav.setColor(0x99FFFFFF);
        canvas.drawRoundRect(widthPx / 2f - 50 * density, contentHeight + navPx / 2f - 2 * density,
                widthPx / 2f + 50 * density, contentHeight + navPx / 2f + 2 * density, 2 * density, 2 * density, nav);

        File out = new File(outDir(), name + ".png");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        // Not blank: the keyboard row must hold key colours and label colours.
        int y = keyboardTop + view.getMeasuredHeight() / 2;
        int distinct = 0;
        int last = 0;
        for (int x = 0; x < widthPx; x += 2) {
            int c = bitmap.getPixel(x, y);
            if (c != last) {
                distinct++;
                last = c;
            }
        }
        assertTrue(name + " rendered something: " + distinct, distinct > 10);
    }

    private static void drawDesktop(Canvas canvas, float x, float y, float w, float h, float density) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1B2A3A);
        canvas.drawRect(x, y, x + w, y + h, p);
        p.setColor(0xFF263B52);
        canvas.drawRect(x, y + h - h * 0.06f, x + w, y + h, p);
        p.setColor(0xFFECEFF4);
        canvas.drawRect(x + w * 0.08f, y + h * 0.1f, x + w * 0.62f, y + h * 0.82f, p);
        p.setColor(0xFF3B4252);
        p.setTextSize(Math.max(8f, h * 0.035f));
        for (int i = 0; i < 9; i++) {
            canvas.drawText("line " + (i + 1) + ": the quick brown fox", x + w * 0.1f,
                    y + h * (0.18f + i * 0.07f), p);
        }
    }

    private static float[] centre(PcKeyboardView view, java.util.function.Predicate<PcKey> which) {
        KeyGrid g = view.currentGrid();
        for (int i = 0; i < g.size(); i++) {
            if (which.test(g.key(i))) {
                return new float[]{(Math.max(0, g.left(i)) + Math.min(g.metrics.widthPx, g.right(i))) / 2f,
                        (g.top(i) + g.bottom(i)) / 2f};
            }
        }
        throw new AssertionError();
    }

    private static void tapModifier(PcKeyboardEngine e, int mod, int times) {
        for (int i = 0; i < times; i++) {
            e.modifierDown(mod);
            e.modifierUp(mod, 1000 + i * 100L);
        }
    }

    private static final Setup STATES = (view, engine) -> {
        tapModifier(engine, ModifierLatch.MOD_CTRL, 1);      // latched
        tapModifier(engine, ModifierLatch.MOD_SHIFT, 2);     // locked
        float[] a = centre(view, k -> k.kind == PcKey.KIND_KEY && k.code == KeyEvent.KEYCODE_S);
        view.pointerDown(0, a[0], a[1]);                     // pressed, with preview
    };

    private static final Setup FN = (view, engine) -> {
        tapModifier(engine, ModifierLatch.MOD_ALT, 2);       // Alt locked, carried to Fn
        tapModifier(engine, ModifierLatch.MOD_FN, 1);
    };

    @Test
    @Config(sdk = 33, qualifiers = "w412dp-h915dp-port-420dpi")
    public void portraitPhone() throws IOException {
        render("portrait-main", 1082, 2402, PcKeyboardView.MODE_FULL, null);
        render("portrait-states", 1082, 2402, PcKeyboardView.MODE_FULL, STATES);
        render("portrait-fn", 1082, 2402, PcKeyboardView.MODE_FULL, FN);
        render("portrait-ime-strip", 1082, 2402, PcKeyboardView.MODE_STRIP, (v, e) ->
                tapModifier(e, ModifierLatch.MOD_CTRL, 1));
    }

    @Test
    @Config(sdk = 33, qualifiers = "w915dp-h412dp-land-420dpi")
    public void landscapePhone() throws IOException {
        render("landscape-main", 2402, 1082, PcKeyboardView.MODE_FULL, null);
        render("landscape-states", 2402, 1082, PcKeyboardView.MODE_FULL, STATES);
        render("landscape-fn", 2402, 1082, PcKeyboardView.MODE_FULL, FN);
    }

    @Test
    @Config(sdk = 33, qualifiers = "w360dp-h640dp-port-xhdpi")
    public void smallPhone() throws IOException {
        render("small-portrait-main", 720, 1280, PcKeyboardView.MODE_FULL, null);
        render("small-portrait-fn", 720, 1280, PcKeyboardView.MODE_FULL, FN);
    }

    @Test
    @Config(sdk = 33, qualifiers = "w1280dp-h800dp-land-xhdpi")
    public void tablet() throws IOException {
        render("tablet-landscape-main", 2560, 1600, PcKeyboardView.MODE_FULL, null);
    }
}
