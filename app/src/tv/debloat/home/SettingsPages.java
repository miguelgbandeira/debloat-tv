package tv.debloat.home;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Settings menu (the gear) and the pages under Home settings: rows, hidden apps, background, animations,
 * accounts (TMDB, Stremio, Trakt), weather, Live TV app, remote buttons and the Home switch.
 */
final class SettingsPages {
    private final HomeView home;

    SettingsPages(HomeView home) {
        this.home = home;
    }

    /** The gear: just two places to go. */
    void settingsPage(Sheet.Builder b) {
        b.icon(home.symbol("settings"), true).title("Settings").subtitle("Your home screen and your TV");
        b.heading("Settings");
        b.item("Home settings", null, true, () -> home.sheet.push(this::homeSettingsPage));
        b.item("TV settings", null, true, () -> { home.sheet.close(); home.settings(Settings.ACTION_SETTINGS); });
    }
    /** Everything about this home screen, in groups. */
    void homeSettingsPage(Sheet.Builder b) {
        boolean stremio = Stremio.signedIn(home.activity), trakt = home.recs.traktMode();
        int hidden = home.layout.hiddenApps().size();
        b.icon(home.symbol("home"), true).title("Home settings").subtitle("Rows, accounts, buttons and how it all looks.");
        b.heading("Home settings");
        b.header("Home screen");
        b.item("Rows", null, true, () -> home.sheet.push(home.menus::customizePage));
        b.item("Hidden apps", hidden == 0 ? "None" : String.valueOf(hidden), true, () -> home.sheet.push(home.menus::hiddenAppsPage));
        b.item("Background", backgroundName(), true, () -> home.sheet.push(this::backgroundPage));
        b.toggle("Animations", animationsOn(), this::setAnimations);
        b.header("Your stuff");
        b.item("Streaming apps", Store.tmdbKey(home.activity).isEmpty() ? "Off" : "On", true, () -> home.sheet.push(this::tmdbPage));
        b.item("Stremio", stremio ? "Signed in" : "Sign in", true, () -> home.sheet.push(this::stremioPage));
        b.item("Trakt", trakt ? "Signed in" : Trakt.configured() ? "Sign in" : "Off", true, () -> home.sheet.push(this::traktPage));
        b.header("TV");
        String city = Store.get(home.activity, Store.CITY, "");
        b.item("Weather", city.isEmpty() ? "Off" : city, true, () -> home.sheet.push(this::cityPage));
        b.item("Live TV app", liveTvName(), true, () -> home.sheet.push(this::liveTvPage));
        b.item("Remote buttons", null, true, () -> home.sheet.push(this::remoteButtonsPage));
        b.item("Home button opens", TvControl.debloatIsHome(home.activity) ? "Debloat TV" : "TV's own", true, () -> home.sheet.push(this::homeScreenPage));
    }
    /** The Home button: this home screen, or the TV's own (through the TV's own adb; needs USB debugging). */
    void homeScreenPage(Sheet.Builder b) {
        boolean ours = TvControl.debloatIsHome(home.activity);
        b.icon(home.symbol("home"), true).title("Home screen").subtitle("Which home screen the Home button opens.");
        b.heading("Home button opens");
        b.row("home:debloat", "Debloat TV", ours ? "\u2713" : null, false, () -> switchHome(true), null, null);
        b.row("home:original", "The TV's own home screen", ours ? null : "\u2713", false, () -> switchHome(false), null, null);
        if (!TvControl.adbOn) b.note("Needs USB debugging on (Settings \u2192 System \u2192 Developer options). Or on a computer: ./install.sh <TV-IP> --undo");
        else b.hint("The first time, the TV asks \u201cAllow USB debugging?\u201d: tick Always allow and press OK.");
    }
    void switchHome(boolean debloat) {
        if (debloat == TvControl.debloatIsHome(home.activity)) return;
        home.sheet.replaceTop(p -> {
            p.icon(home.symbol("home"), true).title("Home screen").subtitle("Switching\u2026");
            p.note("If the TV asks \u201cAllow USB debugging?\u201d, tick Always allow and press OK.");
        });
        TvControl.setHome(home.activity, debloat, error -> {
            if (error == null) {
                home.sheet.close();
                if (!debloat) {   // straight to the TV's own home screen
                    home.activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return;
            }
            home.sheet.replaceTop(p -> {
                p.icon(home.symbol("home"), true).title("Home screen").subtitle("Couldn't switch");
                p.note(error);
            });
        });
    }
    void tmdbPage(Sheet.Builder b) {
        boolean on = !Store.tmdbKey(home.activity).isEmpty();
        b.icon(home.symbol("sparkles"), true).title("Streaming apps")
                .subtitle("Popular and new on the streaming apps on this TV, in your country.");
        b.heading("Streaming apps");
        b.note(on ? "On. Rows: Popular on your apps, New on your apps."
                : "Needs a free TMDB key: themoviedb.org → Settings → API. Then type it below (or send it with the installer).");
        b.input("TMDB key", Store.tmdbKey(home.activity), "Save", key -> {
            Store.put(home.activity, Store.TMDB_KEY, key);
            home.recs.refreshNow();
            home.sheet.back();
        });
        if (on) b.item("Turn off", null, false, () -> { Store.put(home.activity, Store.TMDB_KEY, null); home.recs.refreshNow(); home.sheet.refresh(); });
        b.item("Country", Store.region(home.activity), false, () -> {});
        b.hint("This product uses the TMDB API but is not endorsed or certified by TMDB.");
    }
    void cityPage(Sheet.Builder b) {
        b.icon(home.symbol("location"), true).title("Weather").subtitle("The city for the temperature at the top.");
        b.heading("City");
        b.input("Type a city", Store.get(home.activity, Store.CITY, ""), "Search", name -> home.sheet.push(r -> cityResults(r, name)));
        if (!Store.get(home.activity, Store.CITY, "").isEmpty()) {
            b.item("Turn off", null, false, () -> {
                Store.put(home.activity, Store.CITY, null);
                Store.put(home.activity, Store.LATITUDE, null);
                Store.put(home.activity, Store.LONGITUDE, null);
                Weather.reset();
                home.updateWeather();
                home.sheet.refresh();
            });
        }
        b.hint("Weather by Open-Meteo.com.");
    }
    void cityResults(Sheet.Builder b, String name) {
        b.icon(home.symbol("location"), true).title("Weather").subtitle(T.f("Places called “{n}”", "n", name));
        b.heading("Pick your city");
        b.note("Searching…");
        new Thread(() -> {
            try {
                List<String[]> found = Weather.findPlace(name);
                home.main.post(() -> home.sheet.replaceTop(r -> {
                    r.icon(home.symbol("location"), true).title("Weather").subtitle(T.f("Places called “{n}”", "n", name));
                    r.heading("Pick your city");
                    if (found.isEmpty()) r.note("Nothing found. Try another spelling.");
                    for (String[] place : found) {
                        r.item(place[0], null, false, () -> {
                            Store.put(home.activity, Store.CITY, place[3]);
                            Store.put(home.activity, Store.LATITUDE, place[1]);
                            Store.put(home.activity, Store.LONGITUDE, place[2]);
                            Weather.reset();
                            Weather.refresh(home.activity);
                            if (!"1".equals(Store.get(home.activity, "setup_done", ""))) {
                                home.firstRun.setupDone(4);
                                return;
                            }
                            home.sheet.back();
                            home.sheet.back();
                        });
                    }
                }));
            } catch (Exception e) {
                home.main.post(() -> home.sheet.replaceTop(r -> {
                    r.icon(home.symbol("location"), true).title("Weather").subtitle("Couldn't search");
                    r.note("No connection to the home.weather service. Try again later.");
                }));
            }
        }, "city").start();
    }
    String liveTvName() {
        String pkg = Store.get(home.activity, Store.LIVE_TV, "");
        if (pkg.isEmpty()) return "None";
        String l = HomeView.label(Apps.all(home.activity), pkg);
        return l == null ? "Not installed" : l;
    }
    void liveTvPage(Sheet.Builder b) {
        List<String[]> apps = new ArrayList<>();
        apps.add(new String[] {"", "None"});
        List<String[]> all = Apps.all(home.activity);
        java.util.Collections.sort(all, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        apps.addAll(all);
        String current = Store.get(home.activity, Store.LIVE_TV, "");
        b.icon(home.symbol("live_tv"), true).title("Live TV app").subtitle("What the Live TV button opens (an IPTV app, say).");
        b.heading("Live TV app");
        for (String[] app : apps) {
            b.row("live:" + app[0], app[1], app[0].equals(current) ? "✓" : null, false, () -> {
                Store.put(home.activity, Store.LIVE_TV, app[0].isEmpty() ? null : app[0]);
                home.sheet.back();
            }, null, null);
        }
    }
    /** Every button you've set (on TCL the app buttons are there from the start), and Add/Remove. */
    void remoteButtonsPage(Sheet.Builder b) {
        b.icon(home.symbol("remote"), true).title("Remote buttons").subtitle("Choose what buttons on the remote open.");
        b.heading("Remote buttons");
        List<String[]> apps = new ArrayList<>();
        apps.add(new String[] {"", "Nothing"});
        apps.add(new String[] {Buttons.SETTINGS, "Settings menu"});
        apps.add(new String[] {Buttons.ALL_APPS, "All Apps"});
        apps.add(new String[] {Buttons.HOME, "Home screen"});
        int places = apps.size();
        apps.addAll(Apps.all(home.activity));
        java.util.Collections.sort(apps.subList(places, apps.size()), (x, y) -> x[1].compareToIgnoreCase(y[1]));
        java.util.Map<Integer, Buttons.Button> all = Buttons.all(home.activity);
        for (java.util.Map.Entry<Integer, Buttons.Button> e : all.entrySet()) {
            int code = e.getKey();
            String current = e.getValue().target, name = e.getValue().name;
            int at = -1;
            for (int j = 0; j < apps.size(); j++) if (apps.get(j)[0].equals(current)) at = j;
            int now = Math.max(at, 0);
            String rowKey = "button:" + code;
            b.row(rowKey, name, at >= 0 ? apps.get(at)[1] : "Not installed", current.isEmpty(),
                    () -> pickApp(name, apps, current, chosen -> Buttons.set(home.activity, code, chosen, name), rowKey),
                    () -> { Buttons.set(home.activity, code, apps.get((now - 1 + apps.size()) % apps.size())[0], name); home.sheet.refresh(rowKey); },
                    () -> { Buttons.set(home.activity, code, apps.get((now + 1) % apps.size())[0], name); home.sheet.refresh(rowKey); });
        }
        b.item("Add a button", null, true, () -> home.sheet.push(learnPage));
        if (!all.isEmpty()) b.item("Remove a button", null, true, () -> home.sheet.push(this::removeButtonPage));
        b.hint("OK shows all the apps. ← → change it one step.");
    }
    interface Chosen { void on(String pkg); }
    /** A list of every choice, the one in use ticked and selected; OK picks it and goes back. */
    void pickApp(String title, List<String[]> apps, String current, Chosen chosen, String backKey) {
        home.sheet.push(b -> {
            b.icon(home.symbol("remote"), true).title(title).subtitle("Choose what this button opens.");
            b.heading(title);
            for (String[] app : apps) {
                String pkg = app[0];
                b.row("pick:" + pkg, app[1], pkg.equals(current) ? "✓" : null, false, () -> {
                    chosen.on(pkg);
                    home.sheet.back();
                    home.sheet.refresh(backKey);
                }, null, null);
            }
            b.hint("OK picks, Back cancels.");
        }, "pick:" + current);
    }
    // Learning a button: the key service writes the next button pressed into a file; this page waits for it.
    final Sheet.Page learnPage = this::learnButtonPage;
    void learnButtonPage(Sheet.Builder b) {
        b.icon(home.symbol("remote"), true).title("Add a button").subtitle("Give any button on the remote something to open.");
        b.heading("Press the button now");
        b.note("Press the button you want to use. The arrows, OK, Back, Home, Menu, volume and power can't be used.");
        b.item("Cancel", null, false, () -> home.sheet.back());
        try {
            new java.io.FileOutputStream(Buttons.learnFile(home.activity)).close();   // empty: waiting
        } catch (java.io.IOException ignored) {
        }
        home.main.removeCallbacks(learnPoll);
        home.main.postDelayed(learnPoll, 300);
    }
    final Runnable learnPoll = new Runnable() {
        @Override public void run() {
            java.io.File f = Buttons.learnFile(home.activity);
            if (home.sheet.top() != learnPage) {   // left the page: stop waiting
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
                home.main.postDelayed(this, 250);
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            int key = Integer.parseInt(code);
            if (!Buttons.all(home.activity).containsKey(key)) Buttons.set(home.activity, key, "", Buttons.name(key));
            home.sheet.back();
            home.sheet.refresh("button:" + key);
        }
    };
    void removeButtonPage(Sheet.Builder b) {
        b.icon(home.symbol("remote"), true).title("Remove a button").subtitle("The button goes back to doing what it did before.");
        b.heading("Remove a button");
        for (java.util.Map.Entry<Integer, Buttons.Button> e : Buttons.all(home.activity).entrySet()) {
            int code = e.getKey();
            b.item(e.getValue().name, null, false, () -> { Buttons.set(home.activity, code, null, null); home.sheet.back(); });
        }
    }
    static final String[] ANIMATION_SCALES = {"window_animation_scale", "transition_animation_scale", "animator_duration_scale"};
    boolean animationsOn() {
        return Settings.Global.getFloat(home.activity.getContentResolver(), "animator_duration_scale", 1f) > 0;
    }
    /** The TV's animation speed (all three system scales): 1 = normal, 0 = off. */
    void setAnimations(boolean on) {
        try {
            for (String key : ANIMATION_SCALES) Settings.Global.putFloat(home.activity.getContentResolver(), key, on ? 1f : 0f);
        } catch (SecurityException e) {
            Logs.w("animations: no permission to change them (pm grant tv.debloat.home android.permission.WRITE_SECURE_SETTINGS)");
        }
    }
    void stremioPage(Sheet.Builder b) {
        boolean in = Stremio.signedIn(home.activity);
        b.icon(home.symbol("play"), true).title("Stremio")
                .subtitle(in ? "Continue watching and your Library come from your Stremio account."
                        : "Sign in to see Continue watching and your Library.");
        b.heading("Stremio");
        if (in) {
            b.item("Refresh now", null, false, () -> { home.refreshStremio(); home.sheet.close(); });
            b.item("Sign out", null, false, () -> { Stremio.signOut(home.activity); home.sheet.refresh(); });
        } else {
            b.item("Sign in", null, true, () -> { home.sheet.close(); home.activity.startActivity(new Intent(home.activity, StremioLoginActivity.class)); });
        }
    }
    void traktPage(Sheet.Builder b) {
        boolean in = home.recs.traktMode();
        b.icon(home.symbol("sparkles"), true).title("Trakt")
                .subtitle(in ? "For you, Trending and Up next come from your Trakt account."
                        : "Sign in to see your Trakt recommendations.");
        b.heading("Trakt");
        if (in) {
            b.item("Refresh now", null, false, () -> { home.recs.refreshNow(); home.sheet.close(); });
            b.item("Sign out", null, false, () -> { Trakt.signOut(home.activity); home.recs.refreshNow(); home.sheet.refresh(); });
        } else if (Trakt.configured()) {
            b.item("Sign in", null, true, () -> { home.sheet.close(); home.activity.startActivity(new Intent(home.activity, TraktLoginActivity.class)); });
        } else {
            b.note("Trakt needs your own (free) Trakt app: trakt.tv/oauth/applications \u2192 New application, redirect URI http://localhost. Type its Client ID below.");
        }
        b.input("Trakt Client ID", Store.traktId(home.activity), "Save", id -> { Store.put(home.activity, Store.TRAKT_ID, id); home.sheet.refresh(); });
    }
    void inputsPage(Sheet.Builder b) {
        b.icon(home.symbol("input"), true).title("Inputs").subtitle("Switch to a device or to Live TV.");
        b.heading("Inputs");
        for (Apps.Item it : Apps.inputs(home.activity)) {
            b.item(it.label, it.connected ? null : "Nothing connected", false, () -> { home.sheet.close(); home.open(it, null); });
        }
    }
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
    android.content.SharedPreferences look() {
        return home.activity.getSharedPreferences("look", android.content.Context.MODE_PRIVATE);
    }
    String background() {
        String key = look().getString("background", "aurora");
        for (String[] b : BACKGROUNDS) if (b[0].equals(key)) return key;
        return "aurora";   // e.g. "posters", from before posters became the on-hover layer
    }
    String backgroundName() {
        for (String[] b : BACKGROUNDS) if (b[0].equals(background())) return b[1];
        return "Aurora";
    }
    /** Paints the chosen fixed background (or brings the posters back). */
    void applyBackground() {
        String key = background();
        home.shownBackdrop = "";
        for (String[] b : BACKGROUNDS) {
            if (!b[0].equals(key) || b.length < 3) continue;
            int[] colors = new int[b.length - 2];
            for (int i = 2; i < b.length; i++) colors[i - 2] = Color.parseColor(b[i]);
            if (colors.length == 1) {
                home.backdrop.setImageDrawable(new android.graphics.drawable.ColorDrawable(colors[0]));
            } else {
                home.backdrop.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors));
            }
            return;
        }
    }
    void backgroundPage(Sheet.Builder b) {
        b.icon(home.symbol("sparkles"), true).title("Background").subtitle("What you see when no title is selected.");
        b.heading("Background");
        String now = background();
        for (String[] bg : BACKGROUNDS) {
            String key = bg[0];
            b.row("bg:" + key, bg[1], key.equals(now) ? "\u2713" : null, false, () -> {
                look().edit().putString("background", key).apply();
                applyBackground();
                home.sheet.refresh("bg:" + key);
            }, null, null);
        }
        b.hint("A title's poster shows while you're on it, then fades back to this.");
    }
}
