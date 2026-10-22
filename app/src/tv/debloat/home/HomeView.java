package tv.debloat.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The home screen in rows, Apple TV style: the selected title's backdrop fills the screen with its logo (or name),
 * details and description; below, rows you scroll through. Rows come from HomeLayout (Stremio's Continue watching and
 * Library, Trakt's picks, your app rows, All apps, inputs) and are edited on the TV: hold OK (or press Menu) on any
 * card, or Settings (top right) → Home settings.
 */
final class HomeView extends FrameLayout implements Picks.Listener, Weather.Listener {
    static final int SIDE = 96;                  // left margin of the rows (on a 1920-wide screen)
    static final int HERO_SPACE = 600;           // the backdrop's text area above the selected row
    static final int CARD_W = 400, CARD_H = 225; // title cards (16:9)
    static final int APP_W = 288, APP_H = 162;   // app cards (16:9, the TV banner)
    static final int TAG_TITLE = 0x7f0f0001;     // view tag: the Title a card shows

    /** A title on a card and in the backdrop, from Stremio or Trakt. */
    static final class Title {
        String key, title, meta = "", overview = "", card, backdrop, logo, link, pkg, note;
        double progress;
        Stremio.Item stremio;   // details (description, genre) are fetched when it's selected
    }

    final Activity activity;
    final float u;                                // pixels per unit of a 1920-wide screen
    final Handler main = new Handler(Looper.getMainLooper());
    final Picks recs;
    final Thumbs thumbs;
    final HomeLayout layout;

    final ImageView backdrop, backdropNext, heroLogo;   // backdrop: the chosen background; backdropNext: the poster
    final ImageView posterNext;                          // the next poster, cross-fading in over the current one
    final LinearLayout heroText;
    final TextView heroTitle, heroMeta, heroOverview, clock, weather;
    final View wifiButtonIcon;
    IconView weatherIcon;
    final LinearLayout topBar;
    final Sheet sheet;
    final FirstRun firstRun;
    final Menus menus;
    final SettingsPages pages;
    final AppsGrid appsGrid;
    final ScrollView scroll;
    final LinearLayout rows;
    final List<View> rowViews = new ArrayList<>();
    final List<Integer> rowIndex = new ArrayList<>();   // each shown row's position in the layout
    String shownBackdrop = "", signature = "";
    Title heroTitleNow;
    volatile boolean fetchingStremio;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
            updateWifi();
            main.postDelayed(this, 20_000);
        }
    };
    final Runnable refresh = new Runnable() {
        @Override public void run() {
            recs.refreshIfStale();
            Weather.refresh(activity);
            refreshStremio();
            main.postDelayed(this, 3 * 60_000L);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        u = a.getResources().getDisplayMetrics().widthPixels / 1920f;
        recs = Picks.get(a);
        thumbs = Thumbs.get(a);
        layout = new HomeLayout(a);
        firstRun = new FirstRun(this);
        menus = new Menus(this);
        pages = new SettingsPages(this);
        setBackgroundColor(Color.rgb(10, 10, 14));

        backdrop = image();
        backdropNext = image();
        backdropNext.setAlpha(0f);
        addView(backdrop, match());
        addView(backdropNext, match());
        posterNext = image();
        posterNext.setAlpha(0f);
        addView(posterNext, match());

        View scrimLeft = new View(a);
        scrimLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF0000000, 0xA0000000, 0x00000000}));
        addView(scrimLeft, new LayoutParams(px(1300), ViewGroup.LayoutParams.MATCH_PARENT));
        View scrimBottom = new View(a);
        scrimBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xFF0A0A0E, 0xE00A0A0E, 0x000A0A0E}));
        LayoutParams sb = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(640));
        sb.gravity = Gravity.BOTTOM;
        addView(scrimBottom, sb);
        View scrimTop = new View(a);
        scrimTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x99000000, 0x00000000}));
        addView(scrimTop, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(220)));

        // The selected title: logo (or name), details and description, top left.
        heroText = new LinearLayout(a);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroLogo = new ImageView(a);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        heroLogo.setAdjustViewBounds(true);
        heroLogo.setMaxHeight(px(170));
        heroLogo.setMaxWidth(px(640));
        heroLogo.setVisibility(GONE);
        heroText.addView(heroLogo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        heroTitle = text(76, Color.WHITE, Fonts.display);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroMeta = text(28, 0xFFD0D0D8, Fonts.medium);
        heroMeta.setPadding(0, px(18), 0, 0);
        heroOverview = text(26, 0xFFB8B8C2, Fonts.regular);
        heroOverview.setMaxLines(3);
        heroOverview.setEllipsize(TextUtils.TruncateAt.END);
        heroOverview.setLineSpacing(px(6), 1f);
        heroOverview.setPadding(0, px(16), 0, 0);
        heroText.addView(heroTitle);
        heroText.addView(heroMeta);
        heroText.addView(heroOverview);
        LayoutParams ht = new LayoutParams(px(1050), ViewGroup.LayoutParams.WRAP_CONTENT);
        ht.leftMargin = px(SIDE);
        ht.topMargin = px(140);
        addView(heroText, ht);

        // Top right, tvOS style: one glass capsule with the time and weather, then round icon buttons
        // (Live TV, inputs, apps, Wi-Fi, settings). The selected button turns white.
        LinearLayout bar = new LinearLayout(a);
        topBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(px(34));
        glass.setColor(0x73141418);
        glass.setStroke(Math.max(1, px(1.5f)), 0x24FFFFFF);
        bar.setBackground(glass);
        bar.setPadding(px(6), px(6), px(6), px(6));
        LinearLayout time = new LinearLayout(a);
        time.setOrientation(LinearLayout.HORIZONTAL);
        time.setGravity(Gravity.CENTER_VERTICAL);
        time.setPadding(px(20), 0, px(14), 0);
        clock = text(29, 0xF2FFFFFF, Fonts.medium);
        weatherIcon = new IconView(a, null, px(26));
        LinearLayout.LayoutParams wip = new LinearLayout.LayoutParams(px(30), px(30));
        wip.leftMargin = px(22);
        wip.rightMargin = px(8);
        weather = text(29, 0xF2FFFFFF, Fonts.medium);
        time.addView(clock);
        time.addView(weatherIcon, wip);
        time.addView(weather);
        bar.addView(time);
        if (Apps.liveTv(a) != null) {
            bar.addView(button("live_tv", "Live TV",
                    () -> open(Apps.action(Apps.LIVE_TV, "live", "Live TV", "live_tv"), null)));
        }
        bar.addView(button("input", "Inputs", this::inputsMenu));
        bar.addView(button("apps", "Apps", () -> openApps(null)));
        View wifi = button("wifi", "Network", () -> settings(Settings.ACTION_WIFI_SETTINGS));
        wifiButtonIcon = ((FrameLayout) wifi).getChildAt(0);
        bar.addView(wifi);
        bar.addView(button("settings", "Settings", this::settingsMenu));
        LayoutParams bp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.END | Gravity.TOP;
        bp.rightMargin = px(SIDE - 16);
        bp.topMargin = px(46);
        addView(bar, bp);

        // The rows, scrolling under the backdrop's text.
        scroll = new ScrollView(a) {
            // Only our smooth scroll moves the rows: Android's own jump to the selected card made them flash.
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) { return 0; }
        };
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> fadeRowsAbove());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, px(HERO_SPACE), 0, px(500));
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        scroll.addView(rows, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Behind the bar: added before it would hide the buttons from the remote; the bar is drawn on top.
        addView(scroll, indexOfChild(bar), match());

        appsGrid = new AppsGrid(a);
        appsGrid.setActions((pkg, label, tile) -> open(Apps.item(pkg, label), tile),
                (pkg, label, tile) -> openSheet(b -> menus.gridAppPage(b, pkg, label)),
                this::appsClosed);
        addView(appsGrid, match());

        sheet = new Sheet(a);
        sheet.setOnClose(this::sheetClosed);
        addView(sheet, match());

        build();
        pages.applyBackground();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        recs.addListener(this);
        Weather.addListener(this);
        main.post(tick);
        main.post(refresh);
        rebuildIfChanged();
        updateWeather();
        if (!"1".equals(Store.get(activity, "setup_done", "")) || firstRun.signInPending()) main.postDelayed(firstRun::setupNext, 400);
    }

    // MARK: - First-run setup: what you watch with, then only the questions that apply

    void pause() {
        recs.removeListener(this);
        Weather.removeListener(this);
        main.removeCallbacks(tick);
        main.removeCallbacks(refresh);
    }

    void destroy() {
        pause();
    }

    /** Home pressed on the home screen: back to the top, on the first card. */
    void reset(boolean animated) {
        if (sheet.isOpen()) sheet.close();
        if (appsGrid.isOpen()) appsGrid.close();
        if (movingCard != null) endMove();
        if (movingRow != null) endRowMove();
        if (animated) scroll.smoothScrollTo(0, 0);
        else scroll.scrollTo(0, 0);
        focusFirst();
    }

    // MARK: - Background: the posters, or a fixed color or gradient (Home settings → Background)

    boolean onKey(KeyEvent e) {
        if (sheet.onKey(e)) return true;
        if (movingCard != null && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) endMove();
            return true;
        }
        if (movingRow != null && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) endRowMove();
            return true;
        }
        return appsGrid.onKey(e);
    }

    @Override public void onPicks() {
        rebuildIfChanged();
    }

    @Override public void onWeather() {
        updateWeather();
    }

    void refreshStremio() {
        if (fetchingStremio || !Stremio.signedIn(activity)) return;
        fetchingStremio = true;
        new Thread(() -> {
            try {
                if (Stremio.refresh(activity)) main.post(this::rebuildIfChanged);
            } catch (Exception e) {
                Logs.w("stremio: " + e.getMessage());
            } finally {
                fetchingStremio = false;
            }
        }, "stremio").start();
    }

    // MARK: - Building the rows

    /** The titles each title row shows (to rebuild only when they change). */
    String currentSignature() {
        StringBuilder sig = new StringBuilder();
        for (HomeLayout.Row r : layout.rows()) {
            if (r.hidden) continue;
            List<Title> titles = titles(r.type);
            if (titles == null) continue;
            for (Title t : titles) sig.append(t.key).append(t.note).append(Math.round(t.progress * 20)).append(',');
            sig.append('|');
        }
        sig.append(Stremio.signedIn(activity)).append(recs.traktMode());
        return sig.toString();
    }

    void rebuildIfChanged() {
        if ((sheet != null && sheet.isOpen()) || (appsGrid != null && appsGrid.isOpen()) || movingCard != null || movingRow != null) return;   // rebuilt when they close
        if (needsBuild()) build();
    }

    /** The titles of a title row, or null for app/input rows. */
    List<Title> titles(String type) {
        List<Title> out = new ArrayList<>();
        switch (type) {
            case HomeLayout.CONTINUE:
                for (Stremio.Item it : Stremio.continueWatching()) out.add(fromStremio(it, type));
                return out;
            case HomeLayout.LIBRARY:
                for (Stremio.Item it : Stremio.recentLibrary(30)) out.add(fromStremio(it, type));
                return out;
            case HomeLayout.UP_NEXT:
                for (Picks.Pick p : recs.group(Trakt.GROUP_UP_NEXT)) out.add(fromPick(p));
                return out;
            case HomeLayout.FOR_YOU:
                for (Picks.Pick p : recs.group(Trakt.GROUP_FOR_YOU)) out.add(fromPick(p));
                return out;
            case HomeLayout.TRENDING:
                for (Picks.Pick p : recs.group(Trakt.GROUP_TRENDING)) out.add(fromPick(p));
                return out;
            case HomeLayout.POPULAR:
                if (!firstRun.useStreaming()) return out;
                for (Picks.Pick p : recs.group(Picks.POPULAR)) out.add(fromPick(p));
                return out;
            case HomeLayout.NEW:
                if (!firstRun.useStreaming()) return out;
                for (Picks.Pick p : recs.group(Picks.NEW)) out.add(fromPick(p));
                return out;
            default:
                return null;
        }
    }

    static Title fromPick(Picks.Pick p) {
        Title t = new Title();
        t.key = "t:" + p.key;
        t.title = p.title;
        t.meta = p.meta;
        t.overview = p.overview;
        t.card = p.card;
        t.backdrop = p.backdrop;
        t.link = p.link;
        t.pkg = p.pkg;
        t.note = p.note;
        return t;
    }

    static Title fromStremio(Stremio.Item it, String row) {
        Title t = new Title();
        t.key = "s:" + row + ":" + it.id;
        t.title = it.name;
        t.card = it.background;
        t.backdrop = it.background;
        t.logo = it.logo;
        t.link = Stremio.link(it);
        t.pkg = Stremio.PKG;
        t.note = it.note.isEmpty() ? null : it.note;
        t.progress = it.continueWatching ? it.progress : 0;
        t.stremio = it;
        String[] d = Stremio.details(it);
        if (d != null) {
            t.overview = d[0];
            t.meta = d[1];
        }
        return t;
    }

    void build() {
        build(true);
    }

    int builtVersion = -1;   // the layout version the rows on screen were made from

    /** The rows on screen are out of date: the layout changed (rows, apps, hidden) or the titles did. */
    boolean needsBuild() {
        return layout.version != builtVersion || !currentSignature().equals(signature);
    }

    /** autoFocus false: the caller puts the selection back itself (after a menu or All Apps closes). */
    void build(boolean autoFocus) {
        View focused = findFocus();
        String keep = focused == null || !(focused.getTag() instanceof String) ? null : (String) focused.getTag();
        boolean keepInRows = focused != null && rowOf(focused) != null;
        // Still on the first card of the top row (nothing moved yet): stay at the top when rows load above it.
        boolean atTop = !rowViews.isEmpty() && focused != null && focused == firstCard(cards(rowViews.get(0)));
        if (atTop) keep = null;
        rows.removeAllViews();
        rowViews.clear();
        rowIndex.clear();
        signature = currentSignature();
        builtVersion = layout.version;

        List<String[]> installed = Apps.all(activity);
        List<HomeLayout.Row> list = layout.rows();
        for (int i = 0; i < list.size(); i++) {
            HomeLayout.Row r = list.get(i);
            if (r.hidden) continue;
            List<Title> titles = titles(r.type);
            if (titles != null) {
                if (titles.isEmpty()) continue;   // a row shows once it has titles
                LinearLayout cards = row(r.title(), i);
                for (Title t : titles) cards.addView(titleCard(t, i));
            } else if (r.isApps()) {
                LinearLayout cards = row(r.title(), i);
                for (String pkg : r.apps) {
                    if (HomeLayout.ALL_APPS_TILE.equals(pkg)) {
                        cards.addView(allAppsTile(i));
                        continue;
                    }
                    if (layout.hiddenApps().contains(pkg) || label(installed, pkg) == null) continue;
                    cards.addView(appCard(pkg, label(installed, pkg), i));
                }
                if (cards.getChildCount() == TOOLS) cards.addView(emptyCard(i));
            } else if (r.type.equals(HomeLayout.INPUTS)) {
                LinearLayout cards = row(r.title(), i);
                if (Apps.liveTv(activity) != null) {
                    cards.addView(shortcutCard(Apps.action(Apps.LIVE_TV, "live", "Live TV", "live_tv"), i));
                }
                for (Apps.Item it : Apps.inputs(activity)) if (it.kind == Apps.INPUT) cards.addView(shortcutCard(it, i));
                cards.addView(shortcutCard(Apps.action(Apps.TV_SETTINGS, "tv-settings", "TV Settings", "tv_settings"), i));
            }
        }

        if (keep != null && keepInRows) {
            View again = rows.findViewWithTag(keep);
            if (again != null) {
                again.requestFocus();
                return;
            }
        }
        if (autoFocus && (focused == null || keepInRows)) focusFirst();
    }

    void focusFirst() {
        main.post(() -> {
            for (View row : rowViews) {
                View first = firstCard(cards(row));
                if (first != null) {
                    first.requestFocus();
                    return;
                }
            }
        });
    }

    static String label(List<String[]> installed, String pkg) {
        for (String[] app : installed) if (app[0].equals(pkg)) return app[1];
        return null;
    }

    /** A row: its name and a horizontal list of cards (returned, to add the cards to). */
    LinearLayout row(String name, int layoutIndex) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        TextView title = text(30, 0xFFE8E8EE, Fonts.semibold);
        title.setText(name);
        title.setPadding(px(SIDE), 0, 0, px(14));
        row.addView(title);
        HorizontalScrollView h = new HorizontalScrollView(activity);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        LinearLayout cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setPadding(px(SIDE), px(16), px(SIDE), px(30));
        // Wide enough to scroll ⇅ and ⚙ out of view even when the row has only a few cards.
        cards.setMinimumWidth(getResources().getDisplayMetrics().widthPixels + toolsWidth());
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        h.addView(cards);
        row.addView(h);
        // Left of the first card: ⇅ moves the row with Up/Down, ⚙ opens its settings.
        cards.addView(rowTool(arrowsIcon(px(36), Color.WHITE), "move:" + layoutIndex, layoutIndex, true));
        cards.addView(rowTool(symbol("settings"), "gear:" + layoutIndex, layoutIndex, false));
        // Hide ⇅ and ⚙ off the left edge once the row has its size.
        h.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                h.removeOnLayoutChangeListener(this);
                h.scrollTo(toolsWidth(), 0);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(10);
        rows.addView(row, lp);
        rowViews.add(row);
        rowIndex.add(layoutIndex);
        return cards;
    }

    /** The row's ⇅ and ⚙ come before its cards. */
    static final int TOOLS = 2;

    int toolsWidth() {
        return 2 * px(76 + 24);
    }

    static View firstCard(LinearLayout cards) {
        return cards.getChildCount() > TOOLS ? cards.getChildAt(TOOLS) : null;
    }

    static LinearLayout cards(View row) {
        return (LinearLayout) ((HorizontalScrollView) ((LinearLayout) row).getChildAt(1)).getChildAt(0);
    }

    // MARK: - Cards

    FrameLayout card(int w, int h, String tag, int layoutIndex) {
        FrameLayout card = new FrameLayout(activity);
        card.setTag(tag);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(14));
            }
        });
        card.setBackgroundColor(0xFF22222A);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(14));
        ring.setStroke(px(4), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(w), px(h));
        lp.rightMargin = px(28);
        card.setLayoutParams(lp);
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? px(16) : 0).setDuration(140).start();
            v.setForeground(has ? ring : null);
            if (has) onCardFocused(v);
        });
        card.setOnKeyListener((v, code, e) -> {
            if (movingCard == v) return moveKey(v, code, e);
            if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && v.getTag(TAG_APP) != null) {
                menus.appMenu(v, layoutIndex);
                return true;
            }
            // Right on the last card of a row: stay there (a small nudge), don't jump to another row.
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && v.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) v.getParent();
                if (parent.indexOfChild(v) == parent.getChildCount() - 1) return true;
            }
            // Up from the top row: the buttons at the top right.
            if (code == KeyEvent.KEYCODE_DPAD_UP && e.getAction() == KeyEvent.ACTION_DOWN && rowViews.indexOf(rowOf(v)) == 0) {
                lastCard = v;
                View first = topBar.getChildAt(1);
                if (first != null) first.requestFocus();
                return true;
            }
            return false;
        });
        return card;
    }

    View lastCard;   // where Down from the top-right buttons goes back to

    View titleCard(Title t, int layoutIndex) {
        FrameLayout card = card(CARD_W, CARD_H, t.key, layoutIndex);
        ImageView img = image();
        card.addView(img, match());
        if (t.card != null && !t.card.isEmpty()) thumbs.into(img, t.card, px(CARD_W));
        View shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xE0000000, 0x00000000}));
        LayoutParams sp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(120));
        sp.gravity = Gravity.BOTTOM;
        card.addView(shade, sp);
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(22, Color.WHITE, Fonts.semibold);
        name.setText(t.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        if (t.note != null) {
            TextView note = text(18, 0xFFD0D0D8, Fonts.medium);
            note.setText(t.note);
            note.setPadding(0, px(4), 0, 0);
            labels.addView(note);
        }
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        labels.setPadding(px(18), 0, px(18), px(t.progress > 0 ? 20 : 14));
        card.addView(labels, lp);
        if (t.progress > 0) {
            // How far into the episode or movie, like Stremio's Continue watching.
            View track = new View(activity);
            track.setBackgroundColor(0x55FFFFFF);
            LayoutParams tp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6));
            tp.gravity = Gravity.BOTTOM;
            card.addView(track, tp);
            View done = new View(activity);
            done.setBackgroundColor(0xFF8B5CF6);
            LayoutParams dp = new LayoutParams(Math.max(px(6), Math.round(px(CARD_W) * (float) t.progress)), px(6));
            dp.gravity = Gravity.BOTTOM | Gravity.START;
            card.addView(done, dp);
        }
        card.setTag(TAG_TITLE, t);
        card.setOnClickListener(v -> open(Apps.item(t.pkg, t.title, t.link), v));
        return card;
    }

    View appCard(String pkg, String label, int layoutIndex) {
        FrameLayout card = card(APP_W, APP_H, "app:" + layoutIndex + ":" + pkg, layoutIndex);
        ImageView img = image();
        card.addView(img, match());
        Bitmap ready = Apps.cachedCard(pkg);
        if (ready != null) {
            img.setImageBitmap(ready);
        } else {
            new Thread(() -> {
                Bitmap tile = Apps.card(activity, pkg);
                main.post(() -> img.setImageBitmap(tile));
            }, "tile").start();
        }
        card.setContentDescription(label);
        card.setTag(TAG_APP, new String[] {pkg, label});
        card.setOnClickListener(v -> open(Apps.item(pkg, label), v));
        card.setOnLongClickListener(v -> {
            menus.appMenu(v, layoutIndex);
            return true;
        });
        return card;
    }

    static final int TAG_APP = 0x7f0f0002;   // view tag: {package, label} of an app card

    /** An empty app row still gets a card, so it can be edited. */
    View emptyCard(int layoutIndex) {
        FrameLayout card = card(APP_W, APP_H, "empty:" + layoutIndex, layoutIndex);
        TextView t = text(21, 0xFFA0A0AA, Fonts.medium);
        t.setText("Empty row. Hold OK on any app to move it here");
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(20), 0, px(20), 0);
        card.addView(t, match());
        card.setOnClickListener(v -> openSheet(p -> menus.rowSettingsPage(p, layoutIndex)));
        return card;
    }

    View shortcutCard(Apps.Item it, int layoutIndex) {
        FrameLayout card = card(APP_W, APP_H, "shortcut:" + it.key, layoutIndex);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(activity);
        Bitmap symbol = symbol(it.icon != null ? it.icon : "input");
        if (symbol != null) icon.setImageBitmap(symbol);
        icon.setColorFilter(Color.WHITE);
        box.addView(icon, new LinearLayout.LayoutParams(px(56), px(56)));
        TextView t = text(22, Color.WHITE, Fonts.medium);
        t.setText(it.label);
        t.setPadding(0, px(12), 0, 0);
        t.setGravity(Gravity.CENTER);
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(box, match());
        card.setOnClickListener(v -> open(it, v));
        return card;
    }

    /** ⇅ (move: Up/Down moves the row) or ⚙ (the row's settings), shown only while one of them is selected. */
    View rowTool(Bitmap icon, String tag, int layoutIndex, boolean move) {
        FrameLayout b = new FrameLayout(activity);
        b.setTag(tag);
        b.setFocusable(true);
        b.setClickable(true);
        b.setAlpha(0f);
        GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(0x40FFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(Color.WHITE);
        b.setBackground(idle);
        ImageView img = new ImageView(activity);
        if (icon != null) img.setImageBitmap(icon);
        img.setColorFilter(Color.WHITE);
        LayoutParams ip = new LayoutParams(px(38), px(38));
        ip.gravity = Gravity.CENTER;
        b.addView(img, ip);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? on : idle);
            img.setColorFilter(has ? Color.BLACK : Color.WHITE);
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(120).start();
            LinearLayout cards = (LinearLayout) v.getParent();
            main.post(() -> {
                View f = findFocus();
                boolean tools = f != null && f.getParent() == cards && cards.indexOfChild(f) < TOOLS;
                for (int i = 0; i < TOOLS; i++) cards.getChildAt(i).animate().alpha(tools ? 1f : 0f).setDuration(150).start();
            });
            if (has && movingRow == null) {
                ((HorizontalScrollView) cards.getParent()).smoothScrollTo(0, 0);
                onRowFocused(rowOf(v));
                leaveTitles();
            }
        });
        b.setOnClickListener(v -> {
            if (move) {
                if (movingRow == null) startRowMove(v);
                else endRowMove();
            } else {
                openSheet(p -> menus.rowSettingsPage(p, layoutIndex));
            }
        });
        b.setOnKeyListener((v, code, e) -> {
            if (move && movingRow != null) return rowMoveKey(code, e);
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (move && code == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP && rowViews.indexOf(rowOf(v)) == 0) {
                lastCard = v;
                View first = topBar.getChildAt(1);
                if (first != null) first.requestFocus();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(76), px(76));
        lp.rightMargin = px(24);
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        return b;
    }

    /** The tile at the end of Favorites that opens All Apps. */
    View allAppsTile(int layoutIndex) {
        FrameLayout card = card(APP_W, APP_H, "app:" + layoutIndex + ":" + HomeLayout.ALL_APPS_TILE, layoutIndex);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(activity);
        Bitmap symbol = symbol("apps");
        if (symbol != null) icon.setImageBitmap(symbol);
        icon.setColorFilter(Color.WHITE);
        box.addView(icon, new LinearLayout.LayoutParams(px(56), px(56)));
        TextView t = text(22, Color.WHITE, Fonts.medium);
        t.setText("All Apps");
        t.setPadding(0, px(12), 0, 0);
        t.setGravity(Gravity.CENTER);
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(box, match());
        card.setTag(TAG_APP, new String[] {HomeLayout.ALL_APPS_TILE, "All Apps"});
        card.setOnClickListener(v -> openApps(null));
        card.setOnLongClickListener(v -> {
            menus.appMenu(v, layoutIndex);
            return true;
        });
        return card;
    }

    /** ⇅: a small up arrow over a down arrow. */
    static Bitmap arrowsIcon(int size, int color) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.11f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float cx = size / 2f, w = size * 0.28f;
        android.graphics.Path up = new android.graphics.Path();
        up.moveTo(cx - w, size * 0.36f);
        up.lineTo(cx, size * 0.12f);
        up.lineTo(cx + w, size * 0.36f);
        c.drawPath(up, p);
        android.graphics.Path down = new android.graphics.Path();
        down.moveTo(cx - w, size * 0.64f);
        down.lineTo(cx, size * 0.88f);
        down.lineTo(cx + w, size * 0.64f);
        c.drawPath(down, p);
        return b;
    }

    /** A round button in the top bar. */
    View button(String symbol, String label, Runnable action) {
        FrameLayout b = new FrameLayout(activity);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(label);
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(0xFFF5F5F7);
        // An icon in a 56 px circle.
        View icon;
        if (symbol != null) {
            icon = new IconView(activity, symbol, px(29));
        } else {
            ImageView img = new ImageView(activity);   // the Wi-Fi symbol, drawn in code
            img.setColorFilter(0xF2FFFFFF);
            icon = img;
        }
        LayoutParams ip = symbol != null ? new LayoutParams(px(56), px(56)) : new LayoutParams(px(30), px(30));
        ip.gravity = Gravity.CENTER;
        b.addView(icon, ip);
        b.setOnFocusChangeListener((v, has) -> {
            if (has) leaveTitles();
            v.setBackground(has ? on : null);
            int ink = has ? 0xFF1C1C1E : 0xF2FFFFFF;
            if (icon instanceof IconView) ((IconView) icon).setColor(ink);
            else ((ImageView) icon).setColorFilter(ink);
        });
        b.setOnClickListener(v -> action.run());
        b.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN) {
                if (lastCard != null && lastCard.isAttachedToWindow()) lastCard.requestFocus();
                else focusFirst();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(56), px(56));
        lp.leftMargin = px(4);
        b.setLayoutParams(lp);
        return b;
    }

    // MARK: - Focus: the backdrop follows the selected title, the rows above it fade

    /** After the pause: only if that title is still the one selected (not if you moved on, e.g. up to the buttons). */
    final Runnable showHero = () -> {
        View f = findFocus();
        if (f != null && f.getTag(TAG_TITLE) == heroTitleNow) applyHero();
    };

    /** Selection left the titles (app rows, the top-right buttons, a row's ⇅ ⚙): no title info, no poster. */
    void leaveTitles() {
        main.removeCallbacks(showHero);
        heroText.animate().alpha(0f).setDuration(180).start();
        hidePoster();
    }

    void onCardFocused(View card) {
        if (movingRow != null) return;   // the row being moved is re-placed: nothing else should react
        if (!card.isLaidOut() || card.isLayoutRequested()) {   // just rebuilt: wait for the rows' places
            card.post(() -> { if (card.hasFocus() || card.isFocused()) onCardFocused(card); });
            return;
        }
        View row = rowOf(card);
        if (row != null) {
            LinearLayout cards = cards(row);
            HorizontalScrollView h = (HorizontalScrollView) cards.getParent();
            if (firstCard(cards) == card) {
                h.smoothScrollTo(toolsWidth(), 0);
            } else {
                // Keep the selected card (which grows a little) fully on screen, with the same margin as the left side.
                h.post(() -> {
                    int room = px(SIDE), x = h.getScrollX();
                    if (card.getRight() - x > h.getWidth() - room) h.smoothScrollTo(card.getRight() - h.getWidth() + room, 0);
                    else if (card.getLeft() - x < room) h.smoothScrollTo(Math.max(toolsWidth(), card.getLeft() - room), 0);
                });
            }
        }
        onRowFocused(row);
        Object t = card.getTag(TAG_TITLE);
        main.removeCallbacks(showHero);
        if (t instanceof Title) {
            heroTitleNow = (Title) t;
            // Only once you stop on a title: rows you just pass through leave the backdrop alone.
            main.postDelayed(showHero, 700);
        } else {
            leaveTitles();   // off the titles: the chosen background is back
        }
    }

    void hidePoster() {
        shownBackdrop = "";
        posterNext.animate().cancel();
        posterNext.animate().alpha(0f).setDuration(300).start();
        backdropNext.animate().alpha(0f).setDuration(300).start();
    }

    /** The rows above the selected one fade away, and the selected one sits under the backdrop's text. */
    void onRowFocused(View row) {
        if (row != null) scroll.smoothScrollTo(0, Math.max(0, row.getTop() - px(HERO_SPACE)));
        fadeRowsAbove();
    }

    /**
     * Rows fade as they slide up under the title and description, following the scroll itself (so going down is as
     * smooth as going up, with or without the TV's animations).
     */
    void fadeRowsAbove() {
        if (movingRow != null) return;   // while a row is lifted, dimOthers sets the look
        int line = px(HERO_SPACE), y0 = scroll.getScrollY();
        for (View r : rowViews) {
            float top = r.getTop() - y0, h = Math.max(1, r.getHeight());
            float a = (top - (line - h)) / h;   // 1 at the selected row's place, 0 once a whole row above it
            r.setAlpha(Math.max(0f, Math.min(1f, a)));
        }
    }

    View rowOf(View card) {
        View v = card;
        while (v != null && v.getParent() != rows) {
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return v;
    }

    void applyHero() {
        Title t = heroTitleNow;
        if (t == null) return;
        heroTitle.setText(t.title);
        heroMeta.setText(t.note != null && !t.meta.isEmpty() ? t.note + "  ·  " + t.meta : t.note != null ? t.note : t.meta);
        heroOverview.setText(t.overview);
        // The logo when there is one (Stremio), the name until it loads or when there is none.
        heroLogo.setVisibility(GONE);
        heroTitle.setVisibility(VISIBLE);
        if (t.logo != null && !t.logo.isEmpty()) {
            thumbs.load(t.logo, px(640), b -> {
                if (heroTitleNow != t) return;
                heroLogo.setImageBitmap(b);
                heroLogo.setVisibility(VISIBLE);
                heroTitle.setVisibility(GONE);
            });
        }
        if (t.stremio != null && Stremio.details(t.stremio) == null) {
            new Thread(() -> {
                String[] d = Stremio.fetchDetails(t.stremio);
                t.overview = d[0];
                t.meta = d[1];
                main.post(() -> { if (heroTitleNow == t) showHero.run(); });
            }, "details").start();
        }
        applyBackdrop(t);
    }

    void showHeroText() {
        heroText.animate().alpha(1f).setDuration(250).start();
    }

    /** The title's poster, and its name and details with it (together, once the poster is ready). */
    void applyBackdrop(Title t) {
        if (t.backdrop == null || t.backdrop.isEmpty()) {
            showHeroText();
            return;
        }
        if (t.backdrop.equals(shownBackdrop)) {
            showHeroText();
            if (backdropNext.getAlpha() < 1f) backdropNext.animate().alpha(1f).setDuration(350).start();
            return;
        }
        shownBackdrop = t.backdrop;
        String wanted = t.backdrop;
        thumbs.load(t.backdrop, px(1280), b -> {
            if (!wanted.equals(shownBackdrop)) return;
            View f = findFocus();
            if (f == null || f.getTag(TAG_TITLE) != t) return;   // moved on meanwhile
            showHeroText();
            if (backdropNext.getAlpha() < 0.01f) {
                backdropNext.setImageBitmap(b);   // nothing showing: just fade it in
                backdropNext.animate().alpha(1f).setDuration(350).start();
                return;
            }
            posterNext.setImageBitmap(b);
            posterNext.animate().alpha(1f).setDuration(350).withEndAction(() -> {
                backdropNext.setImageBitmap(b);
                posterNext.setAlpha(0f);
            }).start();
        });
    }

    // MARK: - Settings pages (Sheet)

    View focusBeforeSheet;

    void openSheet(Sheet.Page page) {
        focusBeforeSheet = findFocus();
        setSheetBehind(true);
        if (appsGrid.isOpen()) appsGrid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sheet.open(page);
    }

    // MARK: - All Apps

    View focusBeforeApps;

    void openApps(String select) {
        if (!appsGrid.isOpen()) {
            focusBeforeApps = findFocus();
            setSheetBehind(true);
        }
        List<String[]> apps = new ArrayList<>();
        for (String[] app : Apps.all(activity)) {
            if (layout.hiddenApps().contains(app[0]) || app[0].equals(activity.getPackageName())) continue;
            apps.add(app);
        }
        java.util.Collections.sort(apps, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        appsGrid.show(apps, select);
    }

    void appsClosed() {
        setSheetBehind(false);
        if (needsBuild()) build(false);   // nothing changed: the rows stay as they are
        View back = focusBeforeApps;
        String tag = back != null && back.getTag() instanceof String ? (String) back.getTag() : null;
        View again = tag == null ? null : rows.findViewWithTag(tag);
        if (again == null && back != null && back.isAttachedToWindow()) again = back;
        if (again != null) again.requestFocus();
        else focusFirst();
    }

    // MARK: - Moving a row (OK on ⇅ lifts it; Up/Down glides it past its neighbours; OK or Back puts it down)

    View movingRow, movingTool;
    GradientDrawable liftGlow;

    void startRowMove(View tool) {
        View row = rowOf(tool);
        if (row == null) return;
        movingRow = row;
        movingTool = tool;
        if (liftGlow == null) {
            liftGlow = new GradientDrawable();
            liftGlow.setCornerRadius(px(28));
            liftGlow.setColor(0x26FFFFFF);
            liftGlow.setStroke(px(2), 0x55FFFFFF);
        }
        // Like apps on an iPhone in edit mode: the row's cards rise (bigger, with a shadow) and gently wiggle.
        LinearLayout cards = cards(row);
        for (int i = TOOLS; i < cards.getChildCount(); i++) {
            View c = cards.getChildAt(i);
            c.animate().scaleX(1.06f).scaleY(1.06f).translationZ(px(22)).setDuration(180).start();
            wiggle(c, i % 2 == 0 ? 1 : -1);
        }
        ((TextView) ((LinearLayout) row).getChildAt(0)).setTextColor(Color.WHITE);
        heroText.animate().alpha(0f).setDuration(150).start();
        backdrop.animate().alpha(0.25f).setDuration(200).start();
        dimOthers();
        showHint("\u25b2 \u25bc to move the row   \u00b7   OK to put it down");
    }

    /** The lifted row stays bright and every other row dims, so you can watch it pass them. */
    void dimOthers() {
        for (View r : rowViews) r.animate().alpha(r == movingRow ? 1f : 0.28f).setDuration(180).start();
        // Scroll only when the row would leave the screen (top: under the clock; bottom: off the edge).
        int top = movingRow.getTop() - scroll.getScrollY(), bottom = top + movingRow.getHeight();
        if (top < px(170)) scroll.smoothScrollTo(0, Math.max(0, movingRow.getTop() - px(170)));
        else if (bottom > getHeight() - px(40)) scroll.smoothScrollTo(0, movingRow.getBottom() - getHeight() + px(40));
    }

    /** A small endless back-and-forth tilt while the row is lifted (stops when it's put down). */
    void wiggle(View v, int direction) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, -0.7f * direction, 0.7f * direction);
        a.setDuration(150);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        a.setStartDelay((long) (Math.random() * 120));
        v.setTag(TAG_WIGGLE, a);
        a.start();
    }

    static final int TAG_WIGGLE = 0x7f0f0003;

    void stopWiggle(View v) {
        Object a = v.getTag(TAG_WIGGLE);
        if (a instanceof android.animation.Animator) ((android.animation.Animator) a).cancel();
        v.setTag(TAG_WIGGLE, null);
        v.setRotation(0);
    }

    boolean rowMoveKey(int code, KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (!down) return true;
                int i = rowViews.indexOf(movingRow), j = i + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (j < 0 || j >= rowViews.size()) return true;
                View other = rowViews.get(j);
                slide(rows, () -> {
                    rows.removeView(movingRow);
                    rows.addView(movingRow, j);
                    rowViews.remove(i);
                    rowViews.add(j, movingRow);
                    rowIndex.add(j, rowIndex.remove(i));
                    movingTool.requestFocus();
                }, true, movingRow, other);
                main.post(this::dimOthers);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down) endRowMove();
                return true;
            default:
                return true;   // Left, Right and the rest wait until the row is put down
        }
    }

    void endRowMove() {
        View row = movingRow;
        if (row == null) return;
        int display = rowViews.indexOf(row);
        List<Integer> newPositions = layout.reorderShown(new ArrayList<>(rowIndex));
        movingRow = null;
        movingTool = null;
        hideHint();
        LinearLayout cards = cards(row);
        for (int i = TOOLS; i < cards.getChildCount(); i++) {
            View c = cards.getChildAt(i);
            stopWiggle(c);
            c.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(160).start();
        }
        row.animate().alpha(1f).setDuration(160).withEndAction(() -> {
            backdrop.animate().alpha(1f).setDuration(200).start();
            build();   // the rows' settings point at their new places
            main.post(this::fadeRowsAbove);
            View tool = rows.findViewWithTag("move:" + newPositions.get(display));
            if (tool != null) tool.requestFocus();
        }).start();
    }

    /**
     * Changes the order of views in a list and animates it: each view starts where it was and glides to its new place
     * (vertical for rows, horizontal for apps), like rearranging apps on an iPhone.
     */
    void slide(ViewGroup parent, Runnable change, boolean vertical, View... moving) {
        float[] before = new float[moving.length];
        for (int k = 0; k < moving.length; k++) before[k] = vertical ? moving[k].getTop() : moving[k].getLeft();
        change.run();
        parent.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                parent.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int k = 0; k < moving.length; k++) {
                    View v = moving[k];
                    float delta = before[k] - (vertical ? v.getTop() : v.getLeft());
                    if (vertical) v.setTranslationY(delta);
                    else v.setTranslationX(delta);
                    android.view.ViewPropertyAnimator a = v.animate().setDuration(260)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
                    if (vertical) a.translationY(0);
                    else a.translationX(0);
                    a.start();
                }
                return true;
            }
        });
    }

    void showHint(String text) {
        if (moveHint == null) {
            moveHint = text(26, Color.WHITE, Fonts.medium);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(px(40));
            pill.setColor(0xE0202028);
            moveHint.setBackground(pill);
            moveHint.setPadding(px(34), px(18), px(34), px(18));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.topMargin = px(56);
            lp.leftMargin = px(SIDE);
            addView(moveHint, indexOfChild(appsGrid), lp);
        }
        moveHint.setText(text);
        moveHint.setVisibility(VISIBLE);
    }

    void hideHint() {
        if (moveHint != null) moveHint.setVisibility(GONE);
    }

    // MARK: - Move (an app slides along its row with ◀ ▶; OK or Back drops it)

    View movingCard;
    TextView moveHint;

    void startMove(View card) {
        if (card == null || !card.isAttachedToWindow()) return;
        movingCard = card;
        card.requestFocus();
        card.animate().scaleX(1.16f).scaleY(1.16f).translationZ(px(30)).setDuration(160).start();
        showHint("\u25c0 \u25b6 to move   \u00b7   OK to finish");
    }

    void endMove() {
        View card = movingCard;
        movingCard = null;
        hideHint();
        if (card != null) card.animate().scaleX(1.08f).scaleY(1.08f).translationZ(px(16)).setDuration(140).start();
    }

    boolean moveKey(View card, int code, KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (!down) return true;
                LinearLayout cards = (LinearLayout) card.getParent();
                int i = cards.indexOfChild(card), j = i + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (j < TOOLS || j >= cards.getChildCount() || cards.getChildAt(j).getTag(TAG_APP) == null) return true;
                Object app = card.getTag(TAG_APP);
                View row = rowOf(card);
                int layoutIndex = rowIndex.get(rowViews.indexOf(row));
                layout.moveApp(layoutIndex, ((String[]) app)[0], j - i);
                View other = cards.getChildAt(j);
                slide(cards, () -> {
                    cards.removeViewAt(i);
                    cards.addView(card, j);
                    card.requestFocus();
                }, false, card, other);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down) endMove();
                return true;
            default:
                return true;   // Up, Down and the rest wait until the app is dropped
        }
    }

    /** Blurs and freezes the home screen while a page is open. */
    void setSheetBehind(boolean open) {
        int block = open ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS;
        scroll.setDescendantFocusability(block);
        topBar.setDescendantFocusability(block);
        blurBehind(open);
    }

    /** Blurs the home screen behind a menu or All Apps (instant: animating a blur is too heavy for the TV). */
    void blurBehind(boolean on) {
        topBar.animate().alpha(on ? 0f : 1f).setDuration(150).start();
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        android.graphics.RenderEffect e = on
                ? android.graphics.RenderEffect.createBlurEffect(px(40), px(40), android.graphics.Shader.TileMode.CLAMP) : null;
        backdrop.setRenderEffect(e);
        backdropNext.setRenderEffect(e);
        posterNext.setRenderEffect(e);
        scroll.setRenderEffect(e);
        heroText.setRenderEffect(e);
    }

    int pendingFocusRow = -1;   // after moving a row: the row to land on when the sheet closes

    void sheetClosed() {
        if (!"1".equals(Store.get(activity, "setup_done", ""))) {
            if (!firstRun.setupHandled) firstRun.setupStep++;   // Back on a setup page skips that question
            firstRun.setupHandled = false;
            main.postDelayed(firstRun::setupNext, 200);
        }
        if (appsGrid.isOpen()) {
            // Back to All Apps (an app may have been hidden or added to a row).
            appsGrid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (menus.gridChanged) {
                menus.gridChanged = false;
                Object tag = focusBeforeSheet == null ? null : focusBeforeSheet.getTag();
                openApps(tag instanceof String && ((String) tag).startsWith("grid:") ? ((String) tag).substring(5) : null);
            } else if (focusBeforeSheet != null && focusBeforeSheet.isAttachedToWindow()) {
                focusBeforeSheet.requestFocus();   // nothing changed in the grid: just back where you were
            }
            return;
        }
        if (menus.startMoveAfterSheet) {
            menus.startMoveAfterSheet = false;
            setSheetBehind(false);
            startMove(menus.moveTarget);
            return;
        }
        View back = focusBeforeSheet;
        String tag = back != null && back.getTag() instanceof String ? (String) back.getTag() : null;
        setSheetBehind(false);
        if (needsBuild()) build(false);   // nothing changed: the rows stay as they are
        if (pendingFocusRow >= 0) {
            focusRow(pendingFocusRow);
            pendingFocusRow = -1;
            return;
        }
        View again = tag == null ? null : rows.findViewWithTag(tag);
        if (again == null && back != null && back.isAttachedToWindow()) again = back;
        if (again != null) again.requestFocus();
        else focusFirst();
    }

    /** A remote button set to one of the home screen's places. */
    void openPlace(String place) {
        switch (place) {
            case Buttons.ALL_APPS:
                if (sheet.isOpen()) sheet.close();
                if (appsGrid.isOpen()) appsGrid.close();
                else openApps(null);
                break;
            case Buttons.HOME:
                reset(true);
                break;
            default:
                openSettings();
        }
    }

    /** The Settings menu (or closes it when it's already open). */
    void openSettings() {
        if (appsGrid.isOpen()) appsGrid.close();
        if (sheet.isOpen()) sheet.close();
        else openSheet(pages::settingsPage);
    }

    void settingsMenu() {
        openSheet(pages::settingsPage);
    }

    // MARK: - Streaming apps (TMDB), weather, Live TV

    // MARK: - Remote buttons

    void inputsMenu() {
        openSheet(pages::inputsPage);
    }

    void focusRow(int layoutIndex) {
        main.post(() -> {
            int i = rowIndex.indexOf(layoutIndex);
            if (i < 0) return;
            View first = firstCard(cards(rowViews.get(i)));
            if (first != null) first.requestFocus();
        });
    }

    // MARK: - Helpers

    void open(Apps.Item it, View from) {
        Apps.open(activity, it, from);
    }

    void settings(String action) {
        try {
            activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    void updateWeather() {
        if (!Weather.known()) {
            weather.setText("");
            weatherIcon.setVisibility(GONE);
            return;
        }
        weatherIcon.setIcon(Icons.weather(Weather.code, Weather.day));
        weatherIcon.setVisibility(VISIBLE);
        weather.setText(Weather.degrees(activity));
    }

    /** The Wi-Fi button is dimmed while the TV has no internet. */
    void updateWifi() {
        boolean online = false;
        try {
            ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) {
        }
        wifiButtonIcon.setAlpha(online ? 1f : 0.35f);
    }

    Bitmap symbol(String name) {
        return Icons.white(activity, name);
    }

    ImageView image() {
        ImageView v = new ImageView(activity);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    TextView text(int size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(activity);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    int px(float units) {
        return Math.round(units * u);
    }
}
