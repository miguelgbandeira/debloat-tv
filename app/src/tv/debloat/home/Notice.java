package tv.debloat.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small glass capsule in the top right corner, over whatever app is open ("● VPN on: Singapore"), for a few seconds.
 * Drawn by the key service as an accessibility overlay: TVs like TCL hide toasts from apps in the background, and
 * toasts can't be placed. Same look as the home screen's clock capsule.
 */
final class Notice {
    private Notice() {}

    private static final long SHOW_MS = 3000, FADE_MS = 250;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View current;
    private static boolean fontsLoaded;

    /** Shows the notice (main thread). False when the key service isn't running, so the caller can fall back. */
    static boolean show(String text, boolean on) {
        AccessibilityService s = KeyService.running;
        if (s == null) return false;
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return false;
        if (!fontsLoaded) {
            Fonts.load(s);
            fontsLoaded = true;
        }
        hide(wm, 0);
        float u = s.getResources().getDisplayMetrics().widthPixels / 1920f;

        LinearLayout pill = new LinearLayout(s);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(34 * u);
        glass.setColor(0xB3141418);   // a bit darker than at home: it sits over video
        glass.setStroke(Math.max(1, Math.round(1.5f * u)), 0x24FFFFFF);
        pill.setBackground(glass);
        pill.setPadding(px(22, u), px(14, u), px(26, u), px(14, u));

        View dot = new View(s);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(on ? 0xFF34C759 : 0x80FFFFFF);
        dot.setBackground(d);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(px(12, u), px(12, u));
        dp.rightMargin = px(14, u);
        pill.addView(dot, dp);

        TextView t = new TextView(s);
        t.setText(text);
        t.setTextColor(0xF2FFFFFF);
        t.setTypeface(Fonts.medium);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * u);
        t.setIncludeFontPadding(false);
        pill.addView(t);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = px(HomeView.SIDE - 16, u);
        lp.y = px(136, u);   // under the home screen's top bar and most apps' clocks
        try {
            wm.addView(pill, lp);
        } catch (RuntimeException e) {
            Logs.w("notice: " + e.getMessage());
            return false;
        }
        current = pill;
        pill.setAlpha(0f);
        pill.setTranslationY(-12 * u);
        pill.animate().alpha(1f).translationY(0).setDuration(FADE_MS).start();
        final View mine = pill;
        MAIN.postDelayed(() -> { if (current == mine) hide(wm, FADE_MS); }, SHOW_MS);
        return true;
    }

    private static void hide(WindowManager wm, long fadeMs) {
        View v = current;
        if (v == null) return;
        current = null;
        Runnable remove = () -> {
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
            }
        };
        if (fadeMs == 0) remove.run();
        else v.animate().alpha(0f).setDuration(fadeMs).withEndAction(remove).start();
    }

    private static int px(float units, float u) {
        return Math.round(units * u);
    }
}
