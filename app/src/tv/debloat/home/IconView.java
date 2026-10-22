package tv.debloat.home;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/** One icon, painted as a mask in a color (sharper at small sizes than an ImageView with a color filter). */
final class IconView extends View {
    private String name;
    private int color = 0xF2FFFFFF;
    private final float size;

    IconView(Context c, String name, float size) {
        super(c);
        this.name = name;
        this.size = size;
    }

    void setIcon(String name) {
        this.name = name;
        invalidate();
    }

    void setColor(int color) {
        this.color = color;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (name != null) Icons.draw(getContext(), c, name, getWidth() / 2f, getHeight() / 2f, size, color);
    }
}
