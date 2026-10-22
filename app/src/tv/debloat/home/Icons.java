package tv.debloat.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The icons in assets/icons (white glyphs from Material Symbols, made by tools/icons.py), kept as alpha masks and
 * painted in any color. Masks with mipmaps shrink smoothly to the small sizes a TV screen shows them at.
 */
final class Icons {
    private Icons() {}

    private static final Map<String, Bitmap> MASKS = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF BOX = new RectF();

    static Bitmap mask(Context c, String name) {
        synchronized (MASKS) {
            if (MASKS.containsKey(name)) return MASKS.get(name);
            Bitmap m = null;
            try (InputStream in = c.getAssets().open("icons/" + name + ".png")) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    m = b.extractAlpha();
                    m.setHasMipMap(true);
                    b.recycle();
                }
            } catch (Exception ignored) {
            }
            MASKS.put(name, m);
            return m;
        }
    }

    /** The plain white icon (for places that want a Bitmap, like an ImageView). */
    static Bitmap white(Context c, String name) {
        try (InputStream in = c.getAssets().open("icons/" + name + ".png")) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b != null) b.setHasMipMap(true);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** Draws the icon centered on (cx, cy), fitted inside a size × size square. */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float size, int color) {
        Bitmap m = mask(c, name);
        if (m == null) return;
        float s = size / Math.max(m.getWidth(), m.getHeight());
        float w = m.getWidth() * s, h = m.getHeight() * s;
        synchronized (PAINT) {
            PAINT.setColor(color);
            BOX.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            canvas.drawBitmap(m, null, BOX, PAINT);
        }
    }

    /** The icon for a weather code (WMO codes, as Open-Meteo sends them). */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
