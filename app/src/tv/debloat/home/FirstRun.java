package tv.debloat.home;

import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The first time Debloat TV opens: where you watch (only what's installed is offered), then only the questions that
 * apply (TMDB key, Stremio and Trakt sign-ins, weather). Answers the installer already sent are skipped.
 */
final class FirstRun {
    private final HomeView home;

    FirstRun(HomeView home) {
        this.home = home;
    }

    int setupStep;   // 0 where, 1 TMDB, 2 Stremio, 3 Trakt, 4 home.weather, 5 done
    /** Stremio is used but not signed in, and its sign-in hasn't been offered on this TV yet. */
    boolean signInPending() {
        return useStremio() && !Stremio.signedIn(home.activity) && !"1".equals(Store.get(home.activity, "stremio_asked", ""));
    }
    /** The next setup page that applies (steps already answered, or sent by the installer, are skipped). */
    void setupNext() {
        if (home.sheet.isOpen()) return;
        boolean answered = "1".equals(Store.get(home.activity, "setup_done", ""));   // answered on the computer
        if (answered && !signInPending()) return;
        if (answered && setupStep < 2) setupStep = 2;   // only the sign-in is left
        boolean stremioApp = Apps.installed(home.activity, Stremio.PKG);
        boolean streaming = !foundStreamingApps().isEmpty();
        while (true) {
            switch (setupStep) {
                case 0:
                    if (stremioApp || streaming) { home.openSheet(this::setupWherePage); return; }
                    break;
                case 1:
                    if (useStreaming() && Store.tmdbKey(home.activity).isEmpty()) { home.openSheet(this::setupTmdbPage); return; }
                    break;
                case 2:
                    if (useStremio() && !Stremio.signedIn(home.activity) && !"1".equals(Store.get(home.activity, "stremio_asked", ""))) {
                        home.openSheet(this::setupStremioPage);
                        return;
                    }
                    break;
                case 3:
                    if (answered) { setupStep = 5; continue; }
                    if (!home.recs.traktMode() && !"0".equals(Store.get(home.activity, "use_trakt", ""))) { home.openSheet(this::setupTraktPage); return; }
                    break;
                case 4:
                    if (Store.get(home.activity, Store.CITY, "").isEmpty()) { home.openSheet(this::setupWeatherPage); return; }
                    break;
                default:
                    Store.put(home.activity, "setup_done", "1");
                    home.recs.refreshNow();
                    home.build();
                    return;
            }
            setupStep++;
        }
    }
    boolean setupHandled;   // the page closed by a choice (not by Back)
    void setupDone(int step) {
        setupStep = step + 1;
        setupHandled = true;
        home.sheet.close();   // its close handler shows the next page
    }
    boolean useStremio() {
        return Apps.installed(home.activity, Stremio.PKG) && !"0".equals(Store.get(home.activity, "use_stremio", ""));
    }
    boolean useStreaming() {
        return !foundStreamingApps().isEmpty() && !"0".equals(Store.get(home.activity, "use_streaming", ""));
    }
    /** The streaming apps on this TV that TMDB knows, by name. */
    List<String> foundStreamingApps() {
        String[][] known = {{"com.netflix.ninja", "Netflix"}, {"com.amazon.amazonvideo.livingroom", "Prime Video"},
                {"com.disney.disneyplus", "Disney+"}, {"com.wbd.stream", "Max"}, {"com.hbo.hbonow", "Max"},
                {"com.apple.atve.androidtv.appletv", "Apple TV"}, {"com.hulu.livingroomplus", "Hulu"},
                {"com.cbs.ott", "Paramount+"}, {"com.peacocktv.peacockandroid", "Peacock"},
                {"com.skyshowtime.skyshowtime.google", "SkyShowtime"}, {"com.crunchyroll.crunchyroid", "Crunchyroll"},
                {"pt.rtp.play", "RTP Play"}};
        List<String> out = new ArrayList<>();
        for (String[] k : known) if (Apps.installed(home.activity, k[0]) && !out.contains(k[1])) out.add(k[1]);
        return out;
    }
    void setupWherePage(Sheet.Builder b) {
        b.icon(home.symbol("tv"), true).title("Welcome to Debloat TV").subtitle("A few questions, then your home screen is ready.");
        b.heading("Where do you watch movies and shows?");
        if (Apps.installed(home.activity, Stremio.PKG)) {
            b.row("where:stremio", "Stremio", useStremio() ? "\u2713" : null, !useStremio(), () -> {
                Store.put(home.activity, "use_stremio", useStremio() ? "0" : "1");
                home.sheet.refresh("where:stremio");
            }, null, null);
        }
        List<String> found = foundStreamingApps();
        if (!found.isEmpty()) {
            String names = found.size() <= 3 ? String.join(", ", found) : String.join(", ", found.subList(0, 3)) + "\u2026";
            b.row("where:streaming", "Streaming apps", useStreaming() ? names + "  \u2713" : names, !useStreaming(), () -> {
                Store.put(home.activity, "use_streaming", useStreaming() ? "0" : "1");
                home.sheet.refresh("where:streaming");
            }, null, null);
        }
        b.item("Continue", null, true, () -> setupDone(0));
        b.hint("OK ticks or unticks. You can change all of this later in Settings \u2192 Home settings.");
    }
    void setupTmdbPage(Sheet.Builder b) {
        b.icon(home.symbol("sparkles"), true).title("Streaming apps").subtitle("Popular and new titles on your apps, in your country.");
        b.heading("Add a free TMDB key");
        b.note("Scan this with your phone, sign up at The Movie Database (free), and copy your API key:");
        b.image("https://api.qrserver.com/v1/create-qr-code/?size=360x360&margin=0&data="
                + android.net.Uri.encode("https://www.themoviedb.org/settings/api"), 220);
        b.input("TMDB API key", "", "Save", key -> { Store.put(home.activity, Store.TMDB_KEY, key); setupDone(1); });
        b.item("Skip", null, false, () -> { Store.put(home.activity, "use_streaming", "0"); setupDone(1); });
        b.hint("themoviedb.org/settings/api \u00b7 This product uses the TMDB API but is not endorsed or certified by TMDB.");
    }
    void setupStremioPage(Sheet.Builder b) {
        b.icon(home.symbol("play"), true).title("Stremio").subtitle("Continue watching and your Library, from your Stremio account.");
        b.heading("Sign in to Stremio");
        b.item("Sign in with your phone", null, true, () -> {
            setupHandled = true;
            Store.put(home.activity, "stremio_asked", "1");
            home.sheet.close();
            home.activity.startActivity(new Intent(home.activity, StremioLoginActivity.class));   // setup goes on when it's done
        });
        b.item("Later", null, false, () -> { Store.put(home.activity, "stremio_asked", "1"); setupDone(2); });
    }
    void setupTraktPage(Sheet.Builder b) {
        b.icon(home.symbol("sparkles"), true).title("Trakt (optional)").subtitle("Recommendations from your Trakt history.");
        b.heading("Use Trakt?");
        if (Trakt.configured()) {
            b.item("Sign in with your phone", null, true, () -> {
                setupHandled = true;
                home.sheet.close();
                home.activity.startActivity(new Intent(home.activity, TraktLoginActivity.class));
            });
        } else {
            b.note("Trakt needs your own free Trakt app: trakt.tv/oauth/applications \u2192 New application, redirect URI http://localhost. Type its Client ID:");
            b.input("Trakt Client ID", "", "Save", id -> { Store.put(home.activity, Store.TRAKT_ID, id); home.sheet.refresh(); });
        }
        b.item("Not now", null, false, () -> { Store.put(home.activity, "use_trakt", "0"); setupDone(3); });
    }
    void setupWeatherPage(Sheet.Builder b) {
        b.icon(home.symbol("location"), true).title("Weather").subtitle("The temperature at the top of the screen.");
        b.heading("Your city");
        b.input("Type a city", "", "Search", name -> home.sheet.push(r -> home.pages.cityResults(r, name)));
        b.item("Skip", null, false, () -> { Store.put(home.activity, Store.CITY, ""); setupDone(4); });
    }
}
