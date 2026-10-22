package tv.debloat.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * "All Apps": the home screen darkened behind, the title centered at the top and every app in a grid
 * of 5 columns (16:9 tiles with the app's TV art, the name under each, dimmed until selected). OK opens the app; hold
 * OK for its options (add to a row, hide). Back closes.
 */
final class AppsGrid extends FrameLayout {
    interface AppAction { void on(String pkg, String label, View tile); }

    private static final int COLUMNS = 5;
    private static final int TILE_W = 300, TILE_H = 169, GAP = 40, RADIUS = 22;

    private final Activity activity;
    private final float u;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScrollView scroll;
    private final LinearLayout grid;
    private AppAction onOpen, onHold;
    private Runnable onClose;

    AppsGrid(Activity a) {
        super(a);
        activity = a;
        u = a.getResources().getDisplayMetrics().widthPixels / 1920f;
        setBackgroundColor(0xA8000000);
        setVisibility(GONE);
        setClickable(true);

        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(0, px(80), 0, px(200));
        column.setClipChildren(false);
        TextView title = new TextView(a);
        title.setText("All Apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(52));
        title.setTypeface(Fonts.semibold);
        title.setIncludeFontPadding(false);
        title.setPadding(0, 0, 0, px(60));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(px(COLUMNS * (TILE_W + GAP)), ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(grid, gp);
        scroll.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setActions(AppAction open, AppAction hold, Runnable closed) {
        onOpen = open;
        onHold = hold;
        onClose = closed;
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Shows the grid with these apps ({package, name}, in order); the selection stays on `select` if it's there. */
    void show(List<String[]> apps, String select) {
        grid.removeAllViews();
        LinearLayout line = null;
        View target = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                line = new LinearLayout(activity);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                grid.addView(line);
            }
            View tile = tile(apps.get(i)[0], apps.get(i)[1]);
            line.addView(tile);
            if (apps.get(i)[0].equals(select) || (target == null && select == null)) target = tile;
        }
        if (target == null && grid.getChildCount() > 0) target = ((ViewGroup) grid.getChildAt(0)).getChildAt(0);
        if (!isOpen()) {
            scroll.scrollTo(0, 0);
            setVisibility(VISIBLE);
            setAlpha(0f);
            animate().alpha(1f).setDuration(160).start();
        }
        View first = target;
        post(() -> { if (first != null) first.requestFocus(); });
    }

    private Runnable onClosing;

    /** Called as the grid starts to fade out. */
    void setOnClosing(Runnable r) { onClosing = r; }

    void close() {
        if (onClosing != null) onClosing.run();
        animate().alpha(0f).setDuration(140).withEndAction(() -> {
            setVisibility(GONE);
            if (onClose != null) onClose.run();
        }).start();
    }

    boolean onKey(KeyEvent e) {
        if (!isOpen()) return false;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return false;
    }

    private View tile(String pkg, String label) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        cell.setTag("grid:" + pkg);
        cell.setFocusable(true);
        cell.setClickable(true);

        FrameLayout card = new FrameLayout(activity);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(RADIUS));
            }
        });
        card.setBackgroundColor(0xFF2A2A30);
        ImageView img = new ImageView(activity);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // A soft sheen on the selected tile.
        View sheen = new View(activity);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x22FFFFFF, 0x00FFFFFF}));
        sheen.setAlpha(0f);
        card.addView(sheen, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(TILE_H / 2)));
        new Thread(() -> {
            Bitmap b = Apps.card(activity, pkg);
            main.post(() -> img.setImageBitmap(b));
        }, "grid-tile").start();
        cell.addView(card, new LinearLayout.LayoutParams(px(TILE_W), px(TILE_H)));

        TextView name = new TextView(activity);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setTextColor(0x99FFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24));
        name.setTypeface(Fonts.medium);
        name.setIncludeFontPadding(false);
        name.setPadding(0, px(16), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(px(TILE_W), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px(GAP / 2f);
        lp.rightMargin = px(GAP / 2f);
        lp.bottomMargin = px(30);
        cell.setLayoutParams(lp);

        cell.setOnFocusChangeListener((v, has) -> {
            card.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? px(18) : 0).setDuration(140).start();
            sheen.animate().alpha(has ? 1f : 0f).setDuration(140).start();
            name.setTextColor(has ? Color.WHITE : 0x99FFFFFF);
            name.animate().translationY(has ? px(8) : 0).setDuration(140).start();
            if (has) {
                // Keep the selected row comfortably in view.
                View line = (View) v.getParent();
                int y = line.getTop() + grid.getTop() - px(260);
                scroll.smoothScrollTo(0, Math.max(0, y));
            }
        });
        cell.setOnClickListener(v -> { if (onOpen != null) onOpen.on(pkg, label, card); });
        cell.setOnLongClickListener(v -> {
            if (onHold != null) onHold.on(pkg, label, v);
            return true;
        });
        cell.setOnKeyListener((v, code, e) -> {
            // Left/Right stop at the ends of a line instead of jumping to another one.
            if ((code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) && v.getParent() instanceof ViewGroup) {
                ViewGroup line = (ViewGroup) v.getParent();
                int i = line.indexOfChild(v);
                if ((code == KeyEvent.KEYCODE_DPAD_RIGHT && i == line.getChildCount() - 1) || (code == KeyEvent.KEYCODE_DPAD_LEFT && i == 0)) return true;
            }
            if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && onHold != null) {
                onHold.on(pkg, label, v);
                return true;
            }
            return false;
        });
        return cell;
    }

    private int px(float units) {
        return Math.round(units * u);
    }
}
