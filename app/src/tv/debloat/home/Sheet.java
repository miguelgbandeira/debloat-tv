package tv.debloat.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings pages, tvOS style: the home screen blurred behind, a big icon with a title
 * and a line about it on the left, and a list on the right where the selected entry becomes a white pill. Pages stack:
 * Back returns to the previous one, and closes the sheet from the first.
 */
final class Sheet extends FrameLayout {
    /** A page: fills the builder; called again when the page is refreshed (after a change). */
    interface Page { void build(Builder b); }

    interface Text { void on(String value); }
    interface Toggle { void on(boolean value); }

    private final Activity activity;
    private final float u;
    private final List<Page> stack = new ArrayList<>();
    private final List<Integer> selected = new ArrayList<>();   // for each page under the top one: its selected entry
    private final ImageView icon;
    private final TextView title, subtitle;
    private final LinearLayout list;
    private final ScrollView listScroll;
    private Runnable onClose;

    Sheet(Activity a) {
        super(a);
        activity = a;
        u = a.getResources().getDisplayMetrics().widthPixels / 1920f;
        setBackgroundColor(0xB0080A12);
        setVisibility(GONE);
        setClickable(true);

        // Left: icon, title and the line about the page.
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        icon = new ImageView(a);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        left.addView(icon, new LinearLayout.LayoutParams(px(260), px(200)));
        title = text(52, Color.WHITE, Fonts.semibold);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, px(36), 0, 0);
        left.addView(title);
        subtitle = text(28, 0xB3FFFFFF, Fonts.regular);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(px(40), px(14), px(40), 0);
        left.addView(subtitle);
        LayoutParams lp = new LayoutParams(px(860), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = px(80);
        addView(left, lp);

        // Right: the entries.
        listScroll = new ScrollView(a);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setClipToPadding(false);
        listScroll.setPadding(0, px(130), 0, px(130));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams rp = new LayoutParams(px(860), ViewGroup.LayoutParams.MATCH_PARENT);
        rp.gravity = Gravity.END;
        rp.rightMargin = px(90);
        addView(listScroll, rp);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** The page on screen (null when closed). */
    Page top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    void setOnClose(Runnable r) { onClose = r; }

    private Runnable onClosing;

    /** Called as the sheet starts to fade out (onClose comes once it's gone). */
    void setOnClosing(Runnable r) { onClosing = r; }

    /** Opens the sheet on this page (forgetting any pages that were open). */
    void open(Page page) {
        stack.clear();
        selected.clear();
        stack.add(page);
        show(true);
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(160).start();
    }

    /** A page on top, with the selection on the entry with this key (the current choice in a list). */
    void push(Page page, String key) {
        focusKey = key;
        push(page);
    }

    /** A page on top of the current one (Back comes back). */
    void push(Page page) {
        View f = findFocus();
        selected.add(f == null ? -1 : list.indexOfChild(f));
        stack.add(page);
        show(true);
    }

    /** Swaps the page on top for another (a search that finished), keeping the way back. */
    void replaceTop(Page page) {
        if (stack.isEmpty()) return;
        stack.set(stack.size() - 1, page);
        show(true);
    }

    /** The current page again, after a change (keeps the selected entry when it's still there). */
    void refresh() {
        if (!stack.isEmpty()) show(false);
    }

    private String focusKey;

    /** The current page again, with the selection on the entry with this key (an entry that moved). */
    void refresh(String key) {
        focusKey = key;
        refresh();
    }

    /** Back: the previous page, or closes. */
    boolean back() {
        if (!isOpen()) return false;
        stack.remove(stack.size() - 1);
        if (stack.isEmpty()) {
            close();
        } else {
            int at = selected.isEmpty() ? -1 : selected.remove(selected.size() - 1);
            restoreAt = at;   // back on the entry you opened the page from
            show(true);
        }
        return true;
    }

    void close() {
        stack.clear();
        selected.clear();
        hideKeyboard();
        if (onClosing != null) onClosing.run();
        animate().alpha(0f).setDuration(140).withEndAction(() -> {
            setVisibility(GONE);
            if (onClose != null) onClose.run();
        }).start();
    }

    boolean onKey(KeyEvent e) {
        if (!isOpen()) return false;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) back();
            return true;
        }
        return false;
    }

    private int restoreAt = -1;

    private void show(boolean top) {
        View focused = findFocus();
        int keep = focused == null || top ? -1 : list.indexOfChild(focused);
        if (restoreAt >= 0) {
            keep = restoreAt;
            restoreAt = -1;
        }
        list.removeAllViews();
        icon.setImageDrawable(null);
        icon.setVisibility(GONE);
        subtitle.setText("");
        Builder b = new Builder();
        stack.get(stack.size() - 1).build(b);
        View target = keep >= 0 && keep < list.getChildCount() ? list.getChildAt(keep) : null;
        if (focusKey != null) {
            View byKey = list.findViewWithTag(focusKey);
            if (byKey != null) target = byKey;
            focusKey = null;
        }
        if (target == null || !target.isFocusable()) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) {
                    target = list.getChildAt(i);
                    break;
                }
            }
        }
        if (top) listScroll.scrollTo(0, 0);
        View first = target;
        post(() -> {
            if (first == null) return;
            first.requestFocus();
            // A choice far down a long list: bring it to the middle, not the bottom edge.
            listScroll.scrollTo(0, Math.max(0, first.getTop() - (listScroll.getHeight() - first.getHeight()) / 2 + px(130)));
        });
    }

    // MARK: - Building a page

    final class Builder {
        Builder icon(Bitmap b, boolean tint) {
            if (b == null) return this;
            Sheet.this.icon.setImageBitmap(b);
            Sheet.this.icon.setColorFilter(tint ? Color.WHITE : 0);
            if (!tint) Sheet.this.icon.clearColorFilter();
            Sheet.this.icon.setVisibility(VISIBLE);
            return this;
        }

        Builder title(String s) {
            title.setText(s);
            return this;
        }

        Builder subtitle(String s) {
            subtitle.setText(s);
            return this;
        }

        /** The list's title, above the entries. */
        Builder heading(String s) {
            TextView t = text(34, Color.WHITE, Fonts.display);
            t.setText(s);
            t.setPadding(px(26), 0, 0, px(18));
            list.addView(t);
            return this;
        }

        /** One line under the entries explaining the keys ("OK shows or hides. ← → change the order."). */
        Builder hint(String s) {
            TextView t = text(23, 0x8CFFFFFF, Fonts.regular);
            t.setText(s);
            t.setPadding(px(26), px(20), px(26), 0);
            list.addView(t);
            return this;
        }

        /**
         * An entry that also answers ← and → (to change its value or its place), dimmed when `dim` (off, hidden).
         * `key` keeps the selection on it after the page is refreshed.
         */
        Builder row(String key, String label, String value, boolean dim, Runnable ok, Runnable left, Runnable right) {
            View e = entry(label, value, false, ok, dim);
            e.setTag(key);
            e.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (left != null) left.run(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (right != null) right.run(); return true; }
                return false;
            });
            list.addView(e);
            return this;
        }

        /** A small gray caption over a group of entries. */
        Builder header(String s) {
            TextView t = text(22, 0x99FFFFFF, Fonts.semibold);
            t.setText(s.toUpperCase(java.util.Locale.getDefault()));
            t.setLetterSpacing(0.08f);
            t.setPadding(px(26), px(28), 0, px(10));
            list.addView(t);
            return this;
        }

        /** A line of text that can't be selected (a note under a group). */
        Builder note(String s) {
            TextView t = text(24, 0x99FFFFFF, Fonts.regular);
            t.setText(s);
            t.setPadding(px(26), px(8), px(26), px(8));
            list.addView(t);
            return this;
        }

        /** An entry: its name, a value on the right (or null) and, when it opens another page, an arrow. */
        Builder item(String label, String value, boolean opens, Runnable action) {
            list.addView(entry(label, value, opens, action, false));
            return this;
        }

        /** An entry that switches something on and off. */
        Builder toggle(String label, boolean on, Toggle changed) {
            list.addView(entry(label, on ? "On" : "Off", false, () -> {
                changed.on(!on);
                refresh();
            }, !on));
            return this;
        }

        /** A text field (the TV's keyboard opens on it) with a button that saves what's typed. */
        Builder input(String hint, String current, String button, Text done) {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setText(current);
            field.setSelection(current.length());
            field.setHint(hint);
            field.setTextColor(Color.WHITE);
            field.setHintTextColor(0x80FFFFFF);
            field.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32));
            field.setTypeface(Fonts.medium);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(18));
            bg.setColor(0x33FFFFFF);
            field.setBackground(bg);
            field.setPadding(px(26), px(20), px(26), px(20));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = px(18);
            list.addView(field, lp);
            Runnable save = () -> {
                String v = field.getText().toString().trim();
                hideKeyboard();
                if (!v.isEmpty()) done.on(v);
            };
            field.setOnEditorActionListener((v, id, e) -> {
                save.run();
                return true;
            });
            list.addView(entry(button, null, false, save, false));
            return this;
        }
    }

    private View entry(String label, String value, boolean opens, Runnable action, boolean dim) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(px(26), 0, px(26), 0);
        int nameColor = dim ? 0x73FFFFFF : 0xF2FFFFFF, valueColor = dim ? 0x59FFFFFF : 0xB3FFFFFF;
        TextView name = text(30, nameColor, Fonts.medium);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView val = text(27, valueColor, Fonts.regular);
        val.setSingleLine(true);
        if (value != null) val.setText(value);
        row.addView(val);
        TextView arrow = text(27, 0x80FFFFFF, Fonts.regular);
        arrow.setText(opens ? "  ›" : "");
        row.addView(arrow);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(px(16));
        pill.setColor(0xFFF4F4F6);
        row.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(18));
            }
        });
        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? pill : null);
            v.setElevation(has ? px(10) : 0);
            name.setTextColor(has ? (dim ? 0x99111114 : 0xFF111114) : nameColor);
            val.setTextColor(has ? 0x99111114 : valueColor);
            arrow.setTextColor(has ? 0x80111114 : 0x80FFFFFF);
        });
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(70));
        lp.bottomMargin = px(4);
        row.setLayoutParams(lp);
        return row;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    private TextView text(int size, int color, Typeface face) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float units) {
        return Math.round(units * u);
    }
}
