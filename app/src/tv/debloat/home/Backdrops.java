package tv.debloat.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's background pictures with the dark shading for its text already painted in: the chosen
 * background, and each title's poster. Drawing the shading as three see-through layers over the whole screen cost a
 * TV's graphics chip about 15 ms of every frame (most of it); painted in once, a frame draws one picture.
 * Made at 1280 pixels wide (shown scaled to the screen, like the posters always were).
 */
final class Backdrops {
    interface Done { void on(Bitmap b); }

    private static final int WIDTH = 1280;
    private final int screenW, screenH;
    private final float u;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> made = new LruCache<>(4);   // the last few posters, ready to show again

    Backdrops(int screenW, int screenH, float u) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.u = u;
    }

    /** The chosen background (a color or gradient) with the shading (main thread: one small picture, once). */
    Bitmap background(Drawable base) {
        return shownOnly(make(null, base));
    }

    /** A poster with the shading, made off the main thread; calls back on the main thread. */
    void poster(String url, Bitmap image, Done done) {
        Bitmap ready = made.get(url);
        if (ready != null) {
            done.on(ready);
            return;
        }
        worker.execute(() -> {
            try {
                Bitmap b = shownOnly(make(image, null));
                made.put(url, b);
                main.post(() -> done.on(b));
            } catch (RuntimeException | OutOfMemoryError e) {
                Logs.w("backdrop: " + e.getMessage());
                main.post(() -> done.on(image));   // the plain poster is better than nothing
            }
        });
    }

    /** Only shown from now on: kept in the graphics chip's memory alone (see Images.decodeToWidth). */
    private static Bitmap shownOnly(Bitmap b) {
        Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
        if (hw == null) return b;
        b.recycle();
        return hw;
    }

    private Bitmap make(Bitmap image, Drawable base) {
        int w = WIDTH, h = Math.max(1, Math.round(WIDTH * (float) screenH / screenW));
        float k = w / (float) screenW;   // screen pixels → this picture's pixels
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF0A0A0E);   // the window's color, as before
        if (base != null) {
            base.setBounds(0, 0, w, h);
            base.draw(c);
        }
        if (image != null) {   // centre-cropped, like the ImageView did
            float scale = Math.max(w / (float) image.getWidth(), h / (float) image.getHeight());
            int cw = Math.round(w / scale), ch = Math.round(h / scale);
            int x = (image.getWidth() - cw) / 2, y = (image.getHeight() - ch) / 2;
            c.drawBitmap(image, new Rect(x, y, x + cw, y + ch), new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        // The same three shades the home screen had as views: left (behind the title), bottom (behind the rows), top.
        shade(c, GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF0000000, 0xA0000000, 0x00000000},
                0, 0, Math.round(1300 * u * k), h);
        shade(c, GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xFF0A0A0E, 0xE00A0A0E, 0x000A0A0E},
                0, h - Math.round(640 * u * k), w, h);
        shade(c, GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x99000000, 0x00000000},
                0, 0, w, Math.round(220 * u * k));
        return out;
    }

    private static void shade(Canvas c, GradientDrawable.Orientation o, int[] colors, int l, int t, int r, int b) {
        GradientDrawable g = new GradientDrawable(o, colors);
        g.setBounds(l, t, r, b);
        g.draw(c);
    }
}
