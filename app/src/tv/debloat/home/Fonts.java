package tv.debloat.home;

import android.content.Context;
import android.graphics.Typeface;

/** Inter (variable font) at the weights the app uses. */
final class Fonts {
    private Fonts() {}

    static Typeface regular = Typeface.DEFAULT, medium = Typeface.DEFAULT, semibold = Typeface.DEFAULT_BOLD,
            display = Typeface.DEFAULT_BOLD;

    static void load(Context c) {
        regular = weight(c, 400);
        medium = weight(c, 500);
        semibold = weight(c, 600);
        display = weight(c, 650);
    }

    private static Typeface weight(Context c, int w) {
        try {
            return new Typeface.Builder(c.getAssets(), "fonts/Inter.ttf").setFontVariationSettings("'wght' " + w).build();
        } catch (RuntimeException e) {
            return w >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        }
    }
}
