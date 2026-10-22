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
    private static final int SIDE = 96;                  // left margin of the rows (on a 1920-wide screen)
    private static final int HERO_SPACE = 600;           // the backdrop's text area above the selected row
    private static final int CARD_W = 400, CARD_H = 225; // title cards (16:9)
    private static final int APP_W = 288, APP_H = 162;   // app cards (16:9, the TV banner)
    private static final int TAG_TITLE = 0x7f0f0001;     // view tag: the Title a card shows

    /** A title on a card and in the backdrop, from Stremio or Trakt. */
    static final class Title {
        String key, title, meta = "", overview = "", card, backdrop, logo, link, pkg, note;
        double progress;
        Stremio.Item stremio;   // details (description, genre) are fetched when it's selected
    }

    private final Activity activity;
    private final float u;                                // pixels per unit of a 1920-wide screen
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Picks recs;
    private final Thumbs thumbs;
    private final HomeLayout layout;

    private final ImageView backdrop, backdropNext, heroLogo;   // backdrop: the chosen background; backdropNext: the poster
    private final ImageView posterNext;                          // the next poster, cross-fading in over the current one
    private final LinearLayout heroText;
    private final TextView heroTitle, heroMeta, heroOverview, clock, weather;
    private final View wifiButtonIcon;
    private IconView weatherIcon;
    private final LinearLayout topBar;
    private final Sheet sheet;
    private final AppsGrid appsGrid;
    private final ScrollView scroll;
    private final LinearLayout rows;
    private final List<View> rowViews = new ArrayList<>();
    private final List<Integer> rowIndex = new ArrayList<>();   // each shown row's position in the layout
    private String shownBackdrop = "", signature = "";
    private Title heroTitleNow;
    private volatile boolean fetchingStremio;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
            updateWifi();
            main.postDelayed(this, 20_000);
        }
    };
    private final Runnable refresh = new Runnable() {
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
                (pkg, label, tile) -> openSheet(b -> gridAppPage(b, pkg, label)),
                this::appsClosed);
        addView(appsGrid, match());

        sheet = new Sheet(a);
        sheet.setOnClose(this::sheetClosed);
        addView(sheet, match());

        build();
        applyBackground();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        recs.addListener(this);
        Weather.addListener(this);
        main.post(tick);
        main.post(refresh);
        rebuildIfChanged();
        updateWeather();
        if (!"1".equals(Store.get(activity, "setup_done", "")) || signInPending()) main.postDelayed(this::setupNext, 400);
    }

    /** Stremio is used but not signed in, and its sign-in hasn't been offered on this TV yet. */
    private boolean signInPending() {
        return useStremio() && !Stremio.signedIn(activity) && !"1".equals(Store.get(activity, "stremio_asked", ""));
    }

    // MARK: - First-run setup: what you watch with, then only the questions that apply

    private int setupStep;   // 0 where, 1 TMDB, 2 Stremio, 3 Trakt, 4 weather, 5 done

    /** The next setup page that applies (steps already answered, or sent by the installer, are skipped). */
    private void setupNext() {
        if (sheet.isOpen()) return;
        boolean answered = "1".equals(Store.get(activity, "setup_done", ""));   // answered on the computer
        if (answered && !signInPending()) return;
        if (answered && setupStep < 2) setupStep = 2;   // only the sign-in is left
        boolean stremioApp = Apps.installed(activity, Stremio.PKG);
        boolean streaming = !foundStreamingApps().isEmpty();
        while (true) {
            switch (setupStep) {
                case 0:
                    if (stremioApp || streaming) { openSheet(this::setupWherePage); return; }
                    break;
                case 1:
                    if (useStreaming() && Store.tmdbKey(activity).isEmpty()) { openSheet(this::setupTmdbPage); return; }
                    break;
                case 2:
                    if (useStremio() && !Stremio.signedIn(activity) && !"1".equals(Store.get(activity, "stremio_asked", ""))) {
                        openSheet(this::setupStremioPage);
                        return;
                    }
                    break;
                case 3:
                    if (answered) { setupStep = 5; continue; }
                    if (!recs.traktMode() && !"0".equals(Store.get(activity, "use_trakt", ""))) { openSheet(this::setupTraktPage); return; }
                    break;
                case 4:
                    if (Store.get(activity, Store.CITY, "").isEmpty()) { openSheet(this::setupWeatherPage); return; }
                    break;
                default:
                    Store.put(activity, "setup_done", "1");
                    recs.refreshNow();
                    build();
                    return;
            }
            setupStep++;
        }
    }

    private boolean setupHandled;   // the page closed by a choice (not by Back)

    private void setupDone(int step) {
        setupStep = step + 1;
        setupHandled = true;
        sheet.close();   // its close handler shows the next page
    }

    private boolean useStremio() {
        return Apps.installed(activity, Stremio.PKG) && !"0".equals(Store.get(activity, "use_stremio", ""));
    }

    private boolean useStreaming() {
        return !foundStreamingApps().isEmpty() && !"0".equals(Store.get(activity, "use_streaming", ""));
    }

    /** The streaming apps on this TV that TMDB knows, by name. */
    private List<String> foundStreamingApps() {
        String[][] known = {{"com.netflix.ninja", "Netflix"}, {"com.amazon.amazonvideo.livingroom", "Prime Video"},
                {"com.disney.disneyplus", "Disney+"}, {"com.wbd.stream", "Max"}, {"com.hbo.hbonow", "Max"},
                {"com.apple.atve.androidtv.appletv", "Apple TV"}, {"com.hulu.livingroomplus", "Hulu"},
                {"com.cbs.ott", "Paramount+"}, {"com.peacocktv.peacockandroid", "Peacock"},
                {"com.skyshowtime.skyshowtime.google", "SkyShowtime"}, {"com.crunchyroll.crunchyroid", "Crunchyroll"},
                {"pt.rtp.play", "RTP Play"}};
        List<String> out = new ArrayList<>();
        for (String[] k : known) if (Apps.installed(activity, k[0]) && !out.contains(k[1])) out.add(k[1]);
        return out;
    }

    private void setupWherePage(Sheet.Builder b) {
        b.icon(symbol("tv"), true).title("Welcome to Debloat TV").subtitle("A few questions, then your home screen is ready.");
        b.heading("Where do you watch movies and shows?");
        if (Apps.installed(activity, Stremio.PKG)) {
            b.row("where:stremio", "Stremio", useStremio() ? "\u2713" : null, !useStremio(), () -> {
                Store.put(activity, "use_stremio", useStremio() ? "0" : "1");
                sheet.refresh("where:stremio");
            }, null, null);
        }
        List<String> found = foundStreamingApps();
        if (!found.isEmpty()) {
            String names = found.size() <= 3 ? String.join(", ", found) : String.join(", ", found.subList(0, 3)) + "\u2026";
            b.row("where:streaming", "Streaming apps", useStreaming() ? names + "  \u2713" : names, !useStreaming(), () -> {
                Store.put(activity, "use_streaming", useStreaming() ? "0" : "1");
                sheet.refresh("where:streaming");
            }, null, null);
        }
        b.item("Continue", null, true, () -> setupDone(0));
        b.hint("OK ticks or unticks. You can change all of this later in Settings \u2192 Home settings.");
    }

    private void setupTmdbPage(Sheet.Builder b) {
        b.icon(symbol("sparkles"), true).title("Streaming apps").subtitle("Popular and new titles on your apps, in your country.");
        b.heading("Add a free TMDB key");
        b.note("Scan this with your phone, sign up at The Movie Database (free), and copy your API key:");
        b.image("https://api.qrserver.com/v1/create-qr-code/?size=360x360&margin=0&data="
                + android.net.Uri.encode("https://www.themoviedb.org/settings/api"), 220);
        b.input("TMDB API key", "", "Save", key -> { Store.put(activity, Store.TMDB_KEY, key); setupDone(1); });
        b.item("Skip", null, false, () -> { Store.put(activity, "use_streaming", "0"); setupDone(1); });
        b.hint("themoviedb.org/settings/api \u00b7 This product uses the TMDB API but is not endorsed or certified by TMDB.");
    }

    private void setupStremioPage(Sheet.Builder b) {
        b.icon(symbol("play"), true).title("Stremio").subtitle("Continue watching and your Library, from your Stremio account.");
        b.heading("Sign in to Stremio");
        b.item("Sign in with your phone", null, true, () -> {
            setupHandled = true;
            Store.put(activity, "stremio_asked", "1");
            sheet.close();
            activity.startActivity(new Intent(activity, StremioLoginActivity.class));   // setup goes on when it's done
        });
        b.item("Later", null, false, () -> { Store.put(activity, "stremio_asked", "1"); setupDone(2); });
    }

    private void setupTraktPage(Sheet.Builder b) {
        b.icon(symbol("sparkles"), true).title("Trakt (optional)").subtitle("Recommendations from your Trakt history.");
        b.heading("Use Trakt?");
        if (Trakt.configured()) {
            b.item("Sign in with your phone", null, true, () -> {
                setupHandled = true;
                sheet.close();
                activity.startActivity(new Intent(activity, TraktLoginActivity.class));
            });
        } else {
            b.note("Trakt needs your own free Trakt app: trakt.tv/oauth/applications \u2192 New application, redirect URI http://localhost. Type its Client ID:");
            b.input("Trakt Client ID", "", "Save", id -> { Store.put(activity, Store.TRAKT_ID, id); sheet.refresh(); });
        }
        b.item("Not now", null, false, () -> { Store.put(activity, "use_trakt", "0"); setupDone(3); });
    }

    private void setupWeatherPage(Sheet.Builder b) {
        b.icon(symbol("location"), true).title("Weather").subtitle("The temperature at the top of the screen.");
        b.heading("Your city");
        b.input("Type a city", "", "Search", name -> sheet.push(r -> cityResults(r, name)));
        b.item("Skip", null, false, () -> { Store.put(activity, Store.CITY, ""); setupDone(4); });
    }

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

    /** {key, name, colors...}: "posters" follows the titles; the rest stay put. */
    private static final String[][] BACKGROUNDS = {
        {"black", "Black", "#0A0A0E"},
        {"graphite", "Graphite", "#2A2B31"},
        {"midnight", "Midnight blue", "#0B1631"},
        {"plum", "Plum", "#2A1034"},
        {"forest", "Forest", "#0E2A21"},
        {"sunset", "Sunset", "#2B1240", "#8E2F4F", "#D0683A"},
        {"ocean", "Ocean", "#04192E", "#0B4F6E", "#1A8C9C"},
        {"aurora", "Aurora", "#0A1A2A", "#14594C", "#4E3A8C"},
    };

    private android.content.SharedPreferences look() {
        return activity.getSharedPreferences("look", android.content.Context.MODE_PRIVATE);
    }

    private String background() {
        String key = look().getString("background", "aurora");
        for (String[] b : BACKGROUNDS) if (b[0].equals(key)) return key;
        return "aurora";   // e.g. "posters", from before posters became the on-hover layer
    }


    private String backgroundName() {
        for (String[] b : BACKGROUNDS) if (b[0].equals(background())) return b[1];
        return "Aurora";
    }

    /** Paints the chosen fixed background (or brings the posters back). */
    private void applyBackground() {
        String key = background();
        shownBackdrop = "";
        for (String[] b : BACKGROUNDS) {
            if (!b[0].equals(key) || b.length < 3) continue;
            int[] colors = new int[b.length - 2];
            for (int i = 2; i < b.length; i++) colors[i - 2] = Color.parseColor(b[i]);
            if (colors.length == 1) {
                backdrop.setImageDrawable(new android.graphics.drawable.ColorDrawable(colors[0]));
            } else {
                backdrop.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors));
            }
            return;
        }
    }

    private void backgroundPage(Sheet.Builder b) {
        b.icon(symbol("sparkles"), true).title("Background").subtitle("What you see when no title is selected.");
        b.heading("Background");
        String now = background();
        for (String[] bg : BACKGROUNDS) {
            String key = bg[0];
            b.row("bg:" + key, bg[1], key.equals(now) ? "\u2713" : null, false, () -> {
                look().edit().putString("background", key).apply();
                applyBackground();
                sheet.refresh("bg:" + key);
            }, null, null);
        }
        b.hint("A title's poster shows while you're on it, then fades back to this.");
    }

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

    private void refreshStremio() {
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
    private String currentSignature() {
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

    private void rebuildIfChanged() {
        if ((sheet != null && sheet.isOpen()) || (appsGrid != null && appsGrid.isOpen()) || movingCard != null || movingRow != null) return;   // rebuilt when they close
        if (needsBuild()) build();
    }

    /** The titles of a title row, or null for app/input rows. */
    private List<Title> titles(String type) {
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
                if (!useStreaming()) return out;
                for (Picks.Pick p : recs.group(Picks.POPULAR)) out.add(fromPick(p));
                return out;
            case HomeLayout.NEW:
                if (!useStreaming()) return out;
                for (Picks.Pick p : recs.group(Picks.NEW)) out.add(fromPick(p));
                return out;
            default:
                return null;
        }
    }

    private static Title fromPick(Picks.Pick p) {
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

    private static Title fromStremio(Stremio.Item it, String row) {
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

    private void build() {
        build(true);
    }

    private int builtVersion = -1;   // the layout version the rows on screen were made from

    /** The rows on screen are out of date: the layout changed (rows, apps, hidden) or the titles did. */
    private boolean needsBuild() {
        return layout.version != builtVersion || !currentSignature().equals(signature);
    }

    /** autoFocus false: the caller puts the selection back itself (after a menu or All Apps closes). */
    private void build(boolean autoFocus) {
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

    private void focusFirst() {
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

    private static String label(List<String[]> installed, String pkg) {
        for (String[] app : installed) if (app[0].equals(pkg)) return app[1];
        return null;
    }

    /** A row: its name and a horizontal list of cards (returned, to add the cards to). */
    private LinearLayout row(String name, int layoutIndex) {
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
    private static final int TOOLS = 2;

    private int toolsWidth() {
        return 2 * px(76 + 24);
    }

    private static View firstCard(LinearLayout cards) {
        return cards.getChildCount() > TOOLS ? cards.getChildAt(TOOLS) : null;
    }

    private static LinearLayout cards(View row) {
        return (LinearLayout) ((HorizontalScrollView) ((LinearLayout) row).getChildAt(1)).getChildAt(0);
    }

    // MARK: - Cards

    private FrameLayout card(int w, int h, String tag, int layoutIndex) {
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
                appMenu(v, layoutIndex);
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

    private View lastCard;   // where Down from the top-right buttons goes back to

    private View titleCard(Title t, int layoutIndex) {
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

    private View appCard(String pkg, String label, int layoutIndex) {
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
            appMenu(v, layoutIndex);
            return true;
        });
        return card;
    }

    private static final int TAG_APP = 0x7f0f0002;   // view tag: {package, label} of an app card

    /** An empty app row still gets a card, so it can be edited. */
    private View emptyCard(int layoutIndex) {
        FrameLayout card = card(APP_W, APP_H, "empty:" + layoutIndex, layoutIndex);
        TextView t = text(21, 0xFFA0A0AA, Fonts.medium);
        t.setText("Empty row. Hold OK on any app to move it here");
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(20), 0, px(20), 0);
        card.addView(t, match());
        card.setOnClickListener(v -> openSheet(p -> rowSettingsPage(p, layoutIndex)));
        return card;
    }

    private View shortcutCard(Apps.Item it, int layoutIndex) {
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
    private View rowTool(Bitmap icon, String tag, int layoutIndex, boolean move) {
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
                openSheet(p -> rowSettingsPage(p, layoutIndex));
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
    private View allAppsTile(int layoutIndex) {
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
            appMenu(v, layoutIndex);
            return true;
        });
        return card;
    }

    /** ⇅: a small up arrow over a down arrow. */
    private static Bitmap arrowsIcon(int size, int color) {
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
    private View button(String symbol, String label, Runnable action) {
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
    private final Runnable showHero = () -> {
        View f = findFocus();
        if (f != null && f.getTag(TAG_TITLE) == heroTitleNow) applyHero();
    };

    /** Selection left the titles (app rows, the top-right buttons, a row's ⇅ ⚙): no title info, no poster. */
    private void leaveTitles() {
        main.removeCallbacks(showHero);
        heroText.animate().alpha(0f).setDuration(180).start();
        hidePoster();
    }

    private void onCardFocused(View card) {
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

    private void hidePoster() {
        shownBackdrop = "";
        posterNext.animate().cancel();
        posterNext.animate().alpha(0f).setDuration(300).start();
        backdropNext.animate().alpha(0f).setDuration(300).start();
    }


    /** The rows above the selected one fade away, and the selected one sits under the backdrop's text. */
    private void onRowFocused(View row) {
        if (row != null) scroll.smoothScrollTo(0, Math.max(0, row.getTop() - px(HERO_SPACE)));
        fadeRowsAbove();
    }

    /**
     * Rows fade as they slide up under the title and description, following the scroll itself (so going down is as
     * smooth as going up, with or without the TV's animations).
     */
    private void fadeRowsAbove() {
        if (movingRow != null) return;   // while a row is lifted, dimOthers sets the look
        int line = px(HERO_SPACE), y0 = scroll.getScrollY();
        for (View r : rowViews) {
            float top = r.getTop() - y0, h = Math.max(1, r.getHeight());
            float a = (top - (line - h)) / h;   // 1 at the selected row's place, 0 once a whole row above it
            r.setAlpha(Math.max(0f, Math.min(1f, a)));
        }
    }

    private View rowOf(View card) {
        View v = card;
        while (v != null && v.getParent() != rows) {
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return v;
    }

    private void applyHero() {
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

    private void showHeroText() {
        heroText.animate().alpha(1f).setDuration(250).start();
    }

    /** The title's poster, and its name and details with it (together, once the poster is ready). */
    private void applyBackdrop(Title t) {
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

    private View focusBeforeSheet;

    private void openSheet(Sheet.Page page) {
        focusBeforeSheet = findFocus();
        setSheetBehind(true);
        if (appsGrid.isOpen()) appsGrid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sheet.open(page);
    }

    // MARK: - All Apps

    private View focusBeforeApps;

    private void openApps(String select) {
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

    private void appsClosed() {
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

    private View movingRow, movingTool;
    private GradientDrawable liftGlow;

    private void startRowMove(View tool) {
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
    private void dimOthers() {
        for (View r : rowViews) r.animate().alpha(r == movingRow ? 1f : 0.28f).setDuration(180).start();
        // Scroll only when the row would leave the screen (top: under the clock; bottom: off the edge).
        int top = movingRow.getTop() - scroll.getScrollY(), bottom = top + movingRow.getHeight();
        if (top < px(170)) scroll.smoothScrollTo(0, Math.max(0, movingRow.getTop() - px(170)));
        else if (bottom > getHeight() - px(40)) scroll.smoothScrollTo(0, movingRow.getBottom() - getHeight() + px(40));
    }

    /** A small endless back-and-forth tilt while the row is lifted (stops when it's put down). */
    private void wiggle(View v, int direction) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, -0.7f * direction, 0.7f * direction);
        a.setDuration(150);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        a.setStartDelay((long) (Math.random() * 120));
        v.setTag(TAG_WIGGLE, a);
        a.start();
    }

    private static final int TAG_WIGGLE = 0x7f0f0003;

    private void stopWiggle(View v) {
        Object a = v.getTag(TAG_WIGGLE);
        if (a instanceof android.animation.Animator) ((android.animation.Animator) a).cancel();
        v.setTag(TAG_WIGGLE, null);
        v.setRotation(0);
    }

    private boolean rowMoveKey(int code, KeyEvent e) {
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

    private void endRowMove() {
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
    private void slide(ViewGroup parent, Runnable change, boolean vertical, View... moving) {
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

    private void showHint(String text) {
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

    private void hideHint() {
        if (moveHint != null) moveHint.setVisibility(GONE);
    }

    // MARK: - Move (an app slides along its row with ◀ ▶; OK or Back drops it)

    private View movingCard;
    private TextView moveHint;

    private void startMove(View card) {
        if (card == null || !card.isAttachedToWindow()) return;
        movingCard = card;
        card.requestFocus();
        card.animate().scaleX(1.16f).scaleY(1.16f).translationZ(px(30)).setDuration(160).start();
        showHint("\u25c0 \u25b6 to move   \u00b7   OK to finish");
    }

    private void endMove() {
        View card = movingCard;
        movingCard = null;
        hideHint();
        if (card != null) card.animate().scaleX(1.08f).scaleY(1.08f).translationZ(px(16)).setDuration(140).start();
    }

    private boolean moveKey(View card, int code, KeyEvent e) {
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
    private void setSheetBehind(boolean open) {
        int block = open ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS;
        scroll.setDescendantFocusability(block);
        topBar.setDescendantFocusability(block);
        blurBehind(open);
    }

    /** Blurs the home screen behind a menu or All Apps (instant: animating a blur is too heavy for the TV). */
    private void blurBehind(boolean on) {
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

    private int pendingFocusRow = -1;   // after moving a row: the row to land on when the sheet closes

    private void sheetClosed() {
        if (!"1".equals(Store.get(activity, "setup_done", ""))) {
            if (!setupHandled) setupStep++;   // Back on a setup page skips that question
            setupHandled = false;
            main.postDelayed(this::setupNext, 200);
        }
        if (appsGrid.isOpen()) {
            // Back to All Apps (an app may have been hidden or added to a row).
            appsGrid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (gridChanged) {
                gridChanged = false;
                Object tag = focusBeforeSheet == null ? null : focusBeforeSheet.getTag();
                openApps(tag instanceof String && ((String) tag).startsWith("grid:") ? ((String) tag).substring(5) : null);
            } else if (focusBeforeSheet != null && focusBeforeSheet.isAttachedToWindow()) {
                focusBeforeSheet.requestFocus();   // nothing changed in the grid: just back where you were
            }
            return;
        }
        if (startMoveAfterSheet) {
            startMoveAfterSheet = false;
            setSheetBehind(false);
            startMove(moveTarget);
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

    /** Hold OK (or Menu) on an app in a row: Move, Move to another row, Remove from row. */
    private void appMenu(View card, int row) {
        Object app = card.getTag(TAG_APP);
        if (!(app instanceof String[]) || row < 0 || row >= layout.rows().size()) return;
        String[] a = (String[]) app;
        moveTarget = card;
        openSheet(b -> appPage(b, a[0], a[1], row));
    }

    private View moveTarget;   // the card "Move" lifts once the sheet closes

    private void appPage(Sheet.Builder b, String pkg, String label, int row) {
        HomeLayout.Row r = layout.rows().get(row);
        boolean inRow = r.isApps();
        boolean tile = HomeLayout.ALL_APPS_TILE.equals(pkg);
        if (tile) b.icon(symbol("apps"), true);
        else b.icon(Apps.card(activity, pkg), false);
        b.title(label).subtitle(inRow ? T.f("In “{row}”", "row", r.title()) : r.title());
        if (!tile) {
            int fav = layout.favorites();
            if (fav >= 0) {
                String favName = layout.rows().get(fav).title();
                if (layout.rows().get(fav).apps.contains(pkg)) {
                    b.item(T.f("Remove from {row}", "row", favName), null, false, () -> { layout.removeApp(fav, pkg); sheet.close(); });
                } else {
                    b.item(T.f("Add to {row}", "row", favName), null, false, () -> { layout.moveAppTo(pkg, -1, fav); sheet.close(); });
                }
            }
        }
        if (inRow) b.item("Move", "◀ ▶", false, () -> { startMoveAfterSheet = true; sheet.close(); });
        b.item("Move to another row", null, true, () -> sheet.push(m -> moveAppPage(m, pkg, label, inRow ? row : -1)));
        if (inRow && !tile) b.item("Also add to another row", null, true, () -> sheet.push(m -> moveAppPage(m, pkg, label, -1)));
        if (inRow && (tile || row != layout.favorites())) {
            b.item("Remove from this row", null, false, () -> { layout.removeApp(row, pkg); sheet.close(); });
        }
        if (!tile) {
            b.item("Hide app", null, false, () -> { layout.hideApp(pkg); sheet.close(); });
            appSystemItems(b, pkg);
        }
    }

    /** The app's own Android pages: App settings (storage, permissions, force stop) and Uninstall. */
    private void appSystemItems(Sheet.Builder b, String pkg) {
        b.header("App");
        b.item("App settings", null, true, () -> {
            sheet.close();
            settingsFor(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        });
        int flags = 0;
        try {
            flags = activity.getPackageManager().getApplicationInfo(pkg, 0).flags;
        } catch (Exception ignored) {
        }
        boolean system = (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        // Apps you installed can be uninstalled; apps that came with the TV only lose their updates.
        if (!system || updated) {
            b.item(system ? "Uninstall updates" : "Uninstall", null, true, () -> {
                sheet.close();
                try {
                    activity.startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    settingsFor(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
                }
            });
        }
    }

    private void settingsFor(String action, String pkg) {
        try {
            activity.startActivity(new Intent(action, android.net.Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            settings(android.provider.Settings.ACTION_SETTINGS);
        }
    }

    private boolean startMoveAfterSheet;

    /** Hold OK on an app in All Apps. */
    private void gridAppPage(Sheet.Builder b, String pkg, String label) {
        b.icon(Apps.card(activity, pkg), false).title(label).subtitle("All Apps");
        int fav = layout.favorites();
        if (fav >= 0) {
            String favName = layout.rows().get(fav).title();
            if (layout.rows().get(fav).apps.contains(pkg)) {
                b.item(T.f("Remove from {row}", "row", favName), null, false, () -> { layout.removeApp(fav, pkg); sheet.close(); });
            } else {
                b.item(T.f("Add to {row}", "row", favName), null, false, () -> { layout.moveAppTo(pkg, -1, fav); sheet.close(); });
            }
        }
        b.item("Add to a row", null, true, () -> sheet.push(m -> moveAppPage(m, pkg, label, -1)));
        b.item("Hide app", null, false, () -> { layout.hideApp(pkg); gridChanged = true; sheet.close(); });
        appSystemItems(b, pkg);
    }

    private boolean gridChanged;   // an app was hidden from All Apps: the grid is made again when the page closes

    /** ⚙ on a row: rename, hide, delete. */
    private void rowSettingsPage(Sheet.Builder b, int row) {
        if (row >= layout.rows().size()) { sheet.back(); return; }
        HomeLayout.Row r = layout.rows().get(row);
        b.icon(symbol("settings"), true).title(r.title())
                .subtitle("To move this row: select \u21c5 next to the gear, press OK to lift it, then Up or Down.");
        if (r.isApps()) {
            b.item("Rename", r.name, true, () -> sheet.push(n -> namePage(n, "Rename row", r.name,
                    name -> { layout.rename(row, name); sheet.back(); })));
        }
        if (r.isApps() && !layout.hasAllAppsTile()) {
            b.item("Add the All Apps tile here", null, false, () -> {
                layout.moveAppTo(HomeLayout.ALL_APPS_TILE, -1, row);
                sheet.close();
            });
        }
        b.item("Hide this row", null, false, () -> { layout.setHidden(row, true); sheet.close(); });
        if (r.isApps()) b.item("Delete this row", null, false, () -> { layout.delete(row); sheet.close(); });
        b.item("Add a new app row", null, true, () -> sheet.push(n -> namePage(n, "New row", "",
                name -> { layout.addAppsRow(name, row); sheet.close(); })));
        b.note("Hidden rows can be shown again in Settings \u2192 Home settings \u2192 Rows.");
    }

    private void moveAppPage(Sheet.Builder b, String pkg, String label, int from) {
        if (HomeLayout.ALL_APPS_TILE.equals(pkg)) b.icon(symbol("apps"), true);
        else b.icon(Apps.card(activity, pkg), false);
        b.title(label).subtitle("Move to");
        List<HomeLayout.Row> all = layout.rows();
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).isApps() || i == from) continue;
            int to = i;
            b.item(all.get(i).title(), all.get(i).hidden ? "Hidden" : null, false,
                    () -> { layout.moveAppTo(pkg, from, to); sheet.close(); });
        }
        b.item("A new row", null, true, () -> sheet.push(n -> namePage(n, "New row", "", name -> {
            int to = layout.addAppsRow(name, -1);
            layout.moveAppTo(pkg, from, to);
            sheet.close();
        })));
    }

    /** Every row in order: OK shows or hides it, ← → move it. */
    private void customizePage(Sheet.Builder b) {
        b.icon(symbol("apps"), true).title("Rows")
                .subtitle("Choose which rows you see and in what order.");
        b.heading("Rows");
        List<HomeLayout.Row> all = layout.rows();
        for (int i = 0; i < all.size(); i++) {
            int row = i;
            HomeLayout.Row r = all.get(i);
            String key = "row:" + System.identityHashCode(r);
            b.row(key, r.title(), r.hidden ? "Hidden" : "#" + (shownBefore(row) + 1), r.hidden,
                    () -> { layout.setHidden(row, !r.hidden); sheet.refresh(key); },
                    () -> { layout.moveRow(row, -1); sheet.refresh(key); },
                    () -> { layout.moveRow(row, 1); sheet.refresh(key); });
        }
        b.hint("OK shows or hides a row. \u2190 \u2192 change the order.");
    }

    /** The row's place among the shown rows (for "#1", "#2"…). */
    private int shownBefore(int row) {
        int n = 0;
        for (int i = 0; i < row; i++) if (!layout.rows().get(i).hidden) n++;
        return n;
    }

    private void hiddenAppsPage(Sheet.Builder b) {
        b.icon(symbol("apps"), true).title("Hidden apps").subtitle("Apps you hid from the home screen and All Apps.");
        b.heading("Hidden apps");
        List<String[]> installed = Apps.all(activity);
        for (String pkg : new ArrayList<>(layout.hiddenApps())) {
            String l = label(installed, pkg);
            b.item(l == null ? pkg : l, "Hidden", false, () -> { layout.showApp(pkg); sheet.refresh(); });
        }
        b.hint(layout.hiddenApps().isEmpty() ? "No hidden apps. In All Apps, hold OK on an app to hide it."
                : "OK shows the app again.");
    }

    private void namePage(Sheet.Builder b, String title, String current, Sheet.Text done) {
        b.icon(symbol("apps"), true).title(title).subtitle("Type the name with the remote's keyboard.");
        b.input("Name", current, "Save", done);
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
        else openSheet(this::settingsPage);
    }

    private void settingsMenu() {
        openSheet(this::settingsPage);
    }

    /** The gear: just two places to go. */
    private void settingsPage(Sheet.Builder b) {
        b.icon(symbol("settings"), true).title("Settings").subtitle("Your home screen and your TV");
        b.heading("Settings");
        b.item("Home settings", null, true, () -> sheet.push(this::homeSettingsPage));
        b.item("TV settings", null, true, () -> { sheet.close(); settings(Settings.ACTION_SETTINGS); });
    }

    /** Everything about this home screen, in groups. */
    private void homeSettingsPage(Sheet.Builder b) {
        boolean stremio = Stremio.signedIn(activity), trakt = recs.traktMode();
        int hidden = layout.hiddenApps().size();
        b.icon(symbol("home"), true).title("Home settings").subtitle("Rows, accounts, buttons and how it all looks.");
        b.heading("Home settings");
        b.header("Home screen");
        b.item("Rows", null, true, () -> sheet.push(this::customizePage));
        b.item("Hidden apps", hidden == 0 ? "None" : String.valueOf(hidden), true, () -> sheet.push(this::hiddenAppsPage));
        b.item("Background", backgroundName(), true, () -> sheet.push(this::backgroundPage));
        b.toggle("Animations", animationsOn(), this::setAnimations);
        b.header("Your stuff");
        b.item("Streaming apps", Store.tmdbKey(activity).isEmpty() ? "Off" : "On", true, () -> sheet.push(this::tmdbPage));
        b.item("Stremio", stremio ? "Signed in" : "Sign in", true, () -> sheet.push(this::stremioPage));
        b.item("Trakt", trakt ? "Signed in" : Trakt.configured() ? "Sign in" : "Off", true, () -> sheet.push(this::traktPage));
        b.header("TV");
        String city = Store.get(activity, Store.CITY, "");
        b.item("Weather", city.isEmpty() ? "Off" : city, true, () -> sheet.push(this::cityPage));
        b.item("Live TV app", liveTvName(), true, () -> sheet.push(this::liveTvPage));
        b.item("Remote buttons", null, true, () -> sheet.push(this::remoteButtonsPage));
        b.item("Home button opens", TvControl.debloatIsHome(activity) ? "Debloat TV" : "TV's own", true, () -> sheet.push(this::homeScreenPage));
    }

    /** The Home button: this home screen, or the TV's own (through the TV's own adb; needs USB debugging). */
    private void homeScreenPage(Sheet.Builder b) {
        boolean ours = TvControl.debloatIsHome(activity);
        b.icon(symbol("home"), true).title("Home screen").subtitle("Which home screen the Home button opens.");
        b.heading("Home button opens");
        b.row("home:debloat", "Debloat TV", ours ? "\u2713" : null, false, () -> switchHome(true), null, null);
        b.row("home:original", "The TV's own home screen", ours ? null : "\u2713", false, () -> switchHome(false), null, null);
        if (!TvControl.adbOn) b.note("Needs USB debugging on (Settings \u2192 System \u2192 Developer options). Or on a computer: ./install.sh <TV-IP> --undo");
        else b.hint("The first time, the TV asks \u201cAllow USB debugging?\u201d: tick Always allow and press OK.");
    }

    private void switchHome(boolean debloat) {
        if (debloat == TvControl.debloatIsHome(activity)) return;
        sheet.replaceTop(p -> {
            p.icon(symbol("home"), true).title("Home screen").subtitle("Switching\u2026");
            p.note("If the TV asks \u201cAllow USB debugging?\u201d, tick Always allow and press OK.");
        });
        TvControl.setHome(activity, debloat, error -> {
            if (error == null) {
                sheet.close();
                if (!debloat) {   // straight to the TV's own home screen
                    activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return;
            }
            sheet.replaceTop(p -> {
                p.icon(symbol("home"), true).title("Home screen").subtitle("Couldn't switch");
                p.note(error);
            });
        });
    }

    // MARK: - Streaming apps (TMDB), weather, Live TV

    private void tmdbPage(Sheet.Builder b) {
        boolean on = !Store.tmdbKey(activity).isEmpty();
        b.icon(symbol("sparkles"), true).title("Streaming apps")
                .subtitle("Popular and new on the streaming apps on this TV, in your country.");
        b.heading("Streaming apps");
        b.note(on ? "On. Rows: Popular on your apps, New on your apps."
                : "Needs a free TMDB key: themoviedb.org → Settings → API. Then type it below (or send it with the installer).");
        b.input("TMDB key", Store.tmdbKey(activity), "Save", key -> {
            Store.put(activity, Store.TMDB_KEY, key);
            recs.refreshNow();
            sheet.back();
        });
        if (on) b.item("Turn off", null, false, () -> { Store.put(activity, Store.TMDB_KEY, null); recs.refreshNow(); sheet.refresh(); });
        b.item("Country", Store.region(activity), false, () -> {});
        b.hint("This product uses the TMDB API but is not endorsed or certified by TMDB.");
    }

    private void cityPage(Sheet.Builder b) {
        b.icon(symbol("location"), true).title("Weather").subtitle("The city for the temperature at the top.");
        b.heading("City");
        b.input("Type a city", Store.get(activity, Store.CITY, ""), "Search", name -> sheet.push(r -> cityResults(r, name)));
        if (!Store.get(activity, Store.CITY, "").isEmpty()) {
            b.item("Turn off", null, false, () -> {
                Store.put(activity, Store.CITY, null);
                Store.put(activity, Store.LATITUDE, null);
                Store.put(activity, Store.LONGITUDE, null);
                Weather.reset();
                updateWeather();
                sheet.refresh();
            });
        }
        b.hint("Weather by Open-Meteo.com.");
    }

    private void cityResults(Sheet.Builder b, String name) {
        b.icon(symbol("location"), true).title("Weather").subtitle(T.f("Places called “{n}”", "n", name));
        b.heading("Pick your city");
        b.note("Searching…");
        new Thread(() -> {
            try {
                List<String[]> found = Weather.findPlace(name);
                main.post(() -> sheet.replaceTop(r -> {
                    r.icon(symbol("location"), true).title("Weather").subtitle(T.f("Places called “{n}”", "n", name));
                    r.heading("Pick your city");
                    if (found.isEmpty()) r.note("Nothing found. Try another spelling.");
                    for (String[] place : found) {
                        r.item(place[0], null, false, () -> {
                            Store.put(activity, Store.CITY, place[3]);
                            Store.put(activity, Store.LATITUDE, place[1]);
                            Store.put(activity, Store.LONGITUDE, place[2]);
                            Weather.reset();
                            Weather.refresh(activity);
                            if (!"1".equals(Store.get(activity, "setup_done", ""))) {
                                setupDone(4);
                                return;
                            }
                            sheet.back();
                            sheet.back();
                        });
                    }
                }));
            } catch (Exception e) {
                main.post(() -> sheet.replaceTop(r -> {
                    r.icon(symbol("location"), true).title("Weather").subtitle("Couldn't search");
                    r.note("No connection to the weather service. Try again later.");
                }));
            }
        }, "city").start();
    }

    private String liveTvName() {
        String pkg = Store.get(activity, Store.LIVE_TV, "");
        if (pkg.isEmpty()) return "None";
        String l = label(Apps.all(activity), pkg);
        return l == null ? "Not installed" : l;
    }

    private void liveTvPage(Sheet.Builder b) {
        List<String[]> apps = new ArrayList<>();
        apps.add(new String[] {"", "None"});
        List<String[]> all = Apps.all(activity);
        java.util.Collections.sort(all, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        apps.addAll(all);
        String current = Store.get(activity, Store.LIVE_TV, "");
        b.icon(symbol("live_tv"), true).title("Live TV app").subtitle("What the Live TV button opens (an IPTV app, say).");
        b.heading("Live TV app");
        for (String[] app : apps) {
            b.row("live:" + app[0], app[1], app[0].equals(current) ? "✓" : null, false, () -> {
                Store.put(activity, Store.LIVE_TV, app[0].isEmpty() ? null : app[0]);
                sheet.back();
            }, null, null);
        }
    }

    // MARK: - Remote buttons

    /** Every button you've set (on TCL the app buttons are there from the start), and Add/Remove. */
    private void remoteButtonsPage(Sheet.Builder b) {
        b.icon(symbol("remote"), true).title("Remote buttons").subtitle("Choose what buttons on the remote open.");
        b.heading("Remote buttons");
        List<String[]> apps = new ArrayList<>();
        apps.add(new String[] {"", "Nothing"});
        apps.add(new String[] {Buttons.SETTINGS, "Settings menu"});
        apps.add(new String[] {Buttons.ALL_APPS, "All Apps"});
        apps.add(new String[] {Buttons.HOME, "Home screen"});
        int places = apps.size();
        apps.addAll(Apps.all(activity));
        java.util.Collections.sort(apps.subList(places, apps.size()), (x, y) -> x[1].compareToIgnoreCase(y[1]));
        java.util.Map<Integer, Buttons.Button> all = Buttons.all(activity);
        for (java.util.Map.Entry<Integer, Buttons.Button> e : all.entrySet()) {
            int code = e.getKey();
            String current = e.getValue().target, name = e.getValue().name;
            int at = -1;
            for (int j = 0; j < apps.size(); j++) if (apps.get(j)[0].equals(current)) at = j;
            int now = Math.max(at, 0);
            String rowKey = "button:" + code;
            b.row(rowKey, name, at >= 0 ? apps.get(at)[1] : "Not installed", current.isEmpty(),
                    () -> pickApp(name, apps, current, chosen -> Buttons.set(activity, code, chosen, name), rowKey),
                    () -> { Buttons.set(activity, code, apps.get((now - 1 + apps.size()) % apps.size())[0], name); sheet.refresh(rowKey); },
                    () -> { Buttons.set(activity, code, apps.get((now + 1) % apps.size())[0], name); sheet.refresh(rowKey); });
        }
        b.item("Add a button", null, true, () -> sheet.push(learnPage));
        if (!all.isEmpty()) b.item("Remove a button", null, true, () -> sheet.push(this::removeButtonPage));
        b.hint("OK shows all the apps. ← → change it one step.");
    }

    private interface Chosen { void on(String pkg); }

    /** A list of every choice, the one in use ticked and selected; OK picks it and goes back. */
    private void pickApp(String title, List<String[]> apps, String current, Chosen chosen, String backKey) {
        sheet.push(b -> {
            b.icon(symbol("remote"), true).title(title).subtitle("Choose what this button opens.");
            b.heading(title);
            for (String[] app : apps) {
                String pkg = app[0];
                b.row("pick:" + pkg, app[1], pkg.equals(current) ? "✓" : null, false, () -> {
                    chosen.on(pkg);
                    sheet.back();
                    sheet.refresh(backKey);
                }, null, null);
            }
            b.hint("OK picks, Back cancels.");
        }, "pick:" + current);
    }

    // Learning a button: the key service writes the next button pressed into a file; this page waits for it.
    private final Sheet.Page learnPage = this::learnButtonPage;

    private void learnButtonPage(Sheet.Builder b) {
        b.icon(symbol("remote"), true).title("Add a button").subtitle("Give any button on the remote something to open.");
        b.heading("Press the button now");
        b.note("Press the button you want to use. The arrows, OK, Back, Home, Menu, volume and power can't be used.");
        b.item("Cancel", null, false, () -> sheet.back());
        try {
            new java.io.FileOutputStream(Buttons.learnFile(activity)).close();   // empty: waiting
        } catch (java.io.IOException ignored) {
        }
        main.removeCallbacks(learnPoll);
        main.postDelayed(learnPoll, 300);
    }

    private final Runnable learnPoll = new Runnable() {
        @Override public void run() {
            java.io.File f = Buttons.learnFile(activity);
            if (sheet.top() != learnPage) {   // left the page: stop waiting
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return;
            }
            String code = "";
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[16];
                int n = in.read(buf);
                if (n > 0) code = new String(buf, 0, n).trim();
            } catch (java.io.IOException ignored) {
            }
            if (code.isEmpty()) {
                main.postDelayed(this, 250);
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            int key = Integer.parseInt(code);
            if (!Buttons.all(activity).containsKey(key)) Buttons.set(activity, key, "", Buttons.name(key));
            sheet.back();
            sheet.refresh("button:" + key);
        }
    };

    private void removeButtonPage(Sheet.Builder b) {
        b.icon(symbol("remote"), true).title("Remove a button").subtitle("The button goes back to doing what it did before.");
        b.heading("Remove a button");
        for (java.util.Map.Entry<Integer, Buttons.Button> e : Buttons.all(activity).entrySet()) {
            int code = e.getKey();
            b.item(e.getValue().name, null, false, () -> { Buttons.set(activity, code, null, null); sheet.back(); });
        }
    }

    private static final String[] ANIMATION_SCALES = {"window_animation_scale", "transition_animation_scale", "animator_duration_scale"};

    private boolean animationsOn() {
        return Settings.Global.getFloat(activity.getContentResolver(), "animator_duration_scale", 1f) > 0;
    }

    /** The TV's animation speed (all three system scales): 1 = normal, 0 = off. */
    private void setAnimations(boolean on) {
        try {
            for (String key : ANIMATION_SCALES) Settings.Global.putFloat(activity.getContentResolver(), key, on ? 1f : 0f);
        } catch (SecurityException e) {
            Logs.w("animations: no permission to change them (pm grant tv.debloat.home android.permission.WRITE_SECURE_SETTINGS)");
        }
    }

    private void stremioPage(Sheet.Builder b) {
        boolean in = Stremio.signedIn(activity);
        b.icon(symbol("play"), true).title("Stremio")
                .subtitle(in ? "Continue watching and your Library come from your Stremio account."
                        : "Sign in to see Continue watching and your Library.");
        b.heading("Stremio");
        if (in) {
            b.item("Refresh now", null, false, () -> { refreshStremio(); sheet.close(); });
            b.item("Sign out", null, false, () -> { Stremio.signOut(activity); sheet.refresh(); });
        } else {
            b.item("Sign in", null, true, () -> { sheet.close(); activity.startActivity(new Intent(activity, StremioLoginActivity.class)); });
        }
    }

    private void traktPage(Sheet.Builder b) {
        boolean in = recs.traktMode();
        b.icon(symbol("sparkles"), true).title("Trakt")
                .subtitle(in ? "For you, Trending and Up next come from your Trakt account."
                        : "Sign in to see your Trakt recommendations.");
        b.heading("Trakt");
        if (in) {
            b.item("Refresh now", null, false, () -> { recs.refreshNow(); sheet.close(); });
            b.item("Sign out", null, false, () -> { Trakt.signOut(activity); recs.refreshNow(); sheet.refresh(); });
        } else if (Trakt.configured()) {
            b.item("Sign in", null, true, () -> { sheet.close(); activity.startActivity(new Intent(activity, TraktLoginActivity.class)); });
        } else {
            b.note("Trakt needs your own (free) Trakt app: trakt.tv/oauth/applications \u2192 New application, redirect URI http://localhost. Type its Client ID below.");
        }
        b.input("Trakt Client ID", Store.traktId(activity), "Save", id -> { Store.put(activity, Store.TRAKT_ID, id); sheet.refresh(); });
    }

    private void inputsMenu() {
        openSheet(this::inputsPage);
    }

    private void inputsPage(Sheet.Builder b) {
        b.icon(symbol("input"), true).title("Inputs").subtitle("Switch to a device or to Live TV.");
        b.heading("Inputs");
        for (Apps.Item it : Apps.inputs(activity)) {
            b.item(it.label, it.connected ? null : "Nothing connected", false, () -> { sheet.close(); open(it, null); });
        }
    }

    private void focusRow(int layoutIndex) {
        main.post(() -> {
            int i = rowIndex.indexOf(layoutIndex);
            if (i < 0) return;
            View first = firstCard(cards(rowViews.get(i)));
            if (first != null) first.requestFocus();
        });
    }

    // MARK: - Helpers

    private void open(Apps.Item it, View from) {
        Apps.open(activity, it, from);
    }

    private void settings(String action) {
        try {
            activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void updateWeather() {
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
    private void updateWifi() {
        boolean online = false;
        try {
            ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) {
        }
        wifiButtonIcon.setAlpha(online ? 1f : 0.35f);
    }

    private Bitmap symbol(String name) {
        return Icons.white(activity, name);
    }

    private ImageView image() {
        ImageView v = new ImageView(activity);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private TextView text(int size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(activity);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int px(float units) {
        return Math.round(units * u);
    }
}
