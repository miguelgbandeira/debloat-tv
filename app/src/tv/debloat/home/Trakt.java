package tv.debloat.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Trakt (trakt.tv): your own picks instead of TMDB's trending. Sign in once with a code on the TV (device login; the
 * app is a PKCE app, so there is no client secret), then the home screen shows Up next, Recommended for you and
 * Trending, each opening its title in Stremio.
 *
 * The Client ID comes from config.properties ("trakt_client_id"). Tokens live in the app's private preferences and
 * renew themselves (refresh token), so the sign-in is only needed once.
 */
final class Trakt {
    private Trakt() {}

    static final String GROUP_UP_NEXT = "upnext", GROUP_FOR_YOU = "foryou", GROUP_TRENDING = "trending";

    /** A title from one of the lists: TMDB id for the artwork, IMDb id for Stremio, the next episode for Up next. */
    static final class Entry {
        String group, kind, title, imdb, note, fanart, overview = "", genre = "";
        int tmdb, season, episode, year;
    }

    private static final String API = "https://api.trakt.tv";
    private static final String PREFS = "trakt";

    static boolean configured() {
        return !Store.traktId(App.ctx).trim().isEmpty();
    }

    /** Asks Trakt for the description, genres and pictures too (the rows need no other service). */
    private static final String MORE = "extended=full,images";

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return configured() && !prefs(c).getString("refresh", "").isEmpty();
    }

    static void signOut(Context c) {
        prefs(c).edit().clear().apply();
    }

    // MARK: - Device sign-in ("go to trakt.tv/activate and enter ABCD1234")

    /** Starts a sign-in: {device_code, user_code, verification_url, expires_in, interval}. */
    static JSONObject deviceCode() throws IOException {
        try {
            return new JSONObject(post("/oauth/device/code", new JSONObject().put("client_id", clientId()), null));
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    /** One check of a sign-in: true when done (tokens saved), false while waiting. Throws when it can't finish. */
    static boolean poll(Context c, String deviceCode) throws IOException {
        try {
            String body = post("/oauth/device/token",
                    new JSONObject().put("code", deviceCode).put("client_id", clientId()), null);
            save(c, new JSONObject(body));
            return true;
        } catch (HttpError e) {
            switch (e.code) {
                case 400: case 429: return false;          // not approved yet / asked too fast
                case 404: throw new IOException("Trakt didn't recognise the code");
                case 409: throw new IOException("That code was already used");
                case 410: throw new IOException("The code expired");
                case 418: throw new IOException("The sign-in was declined on Trakt");
                default: throw e;
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    private static void save(Context c, JSONObject t) {
        long expiresAt = (t.optLong("created_at", System.currentTimeMillis() / 1000) + t.optLong("expires_in", 0)) * 1000L;
        prefs(c).edit().putString("access", t.optString("access_token")).putString("refresh", t.optString("refresh_token"))
                .putLong("expiresAt", expiresAt).apply();
    }

    /** A valid access token, renewed a day before it expires (tokens last 7 days). */
    private static synchronized String token(Context c) throws IOException {
        SharedPreferences p = prefs(c);
        String refresh = p.getString("refresh", "");
        if (refresh.isEmpty()) throw new IOException("Not signed in to Trakt");
        if (System.currentTimeMillis() < p.getLong("expiresAt", 0) - 24 * 3600_000L) return p.getString("access", "");
        try {
            String body = post("/oauth/token", new JSONObject().put("refresh_token", refresh).put("client_id", clientId())
                    .put("redirect_uri", "http://localhost").put("grant_type", "refresh_token"), null);
            save(c, new JSONObject(body));
        } catch (HttpError e) {
            if (e.code == 400 || e.code == 401) signOut(c);   // the refresh token is no longer valid: sign in again
            throw new IOException("Trakt sign-in expired: sign in again");
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        return p.getString("access", "");
    }

    // MARK: - Lists

    /** Up next first (in Trakt's order), then Recommended (shows and movies alternating), then Trending. */
    static List<Entry> lists(Context c) throws IOException {
        String auth = token(c);
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            JSONArray upNext = new JSONArray(get("/sync/progress/up_next?limit=12&" + MORE, auth));
            for (int i = 0; i < upNext.length(); i++) {
                JSONObject o = upNext.getJSONObject(i);
                Entry e = entry(GROUP_UP_NEXT, "tv", o.optJSONObject("show"));
                JSONObject progress = o.optJSONObject("progress");
                JSONObject next = progress == null ? null : progress.optJSONObject("next_episode");
                if (e == null || next == null) continue;
                e.season = next.optInt("season");
                e.episode = next.optInt("number");
                e.note = T.f("Next: S{s} · E{e}", "s", e.season, "e", e.episode);
                if (seen.add(e.kind + e.tmdb)) out.add(e);
            }
            JSONArray shows = new JSONArray(get("/recommendations/shows?limit=12&ignore_collected=true&ignore_watchlisted=false&" + MORE, auth));
            JSONArray movies = new JSONArray(get("/recommendations/movies?limit=12&ignore_collected=true&ignore_watchlisted=false&" + MORE, auth));
            for (int i = 0; i < Math.max(shows.length(), movies.length()); i++) {
                if (i < shows.length()) add(out, seen, entry(GROUP_FOR_YOU, "tv", shows.getJSONObject(i)));
                if (i < movies.length()) add(out, seen, entry(GROUP_FOR_YOU, "movie", movies.getJSONObject(i)));
            }
            JSONArray hotShows = new JSONArray(get("/shows/trending?limit=10&" + MORE, auth));
            JSONArray hotMovies = new JSONArray(get("/movies/trending?limit=10&" + MORE, auth));
            for (int i = 0; i < Math.max(hotShows.length(), hotMovies.length()); i++) {
                if (i < hotMovies.length()) add(out, seen, entry(GROUP_TRENDING, "movie", hotMovies.getJSONObject(i).optJSONObject("movie")));
                if (i < hotShows.length()) add(out, seen, entry(GROUP_TRENDING, "tv", hotShows.getJSONObject(i).optJSONObject("show")));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt sent an unexpected answer");
        }
        return out;
    }

    private static void add(List<Entry> out, Set<String> seen, Entry e) {
        if (e != null && seen.add(e.kind + e.tmdb)) out.add(e);
    }

    /** A movie or show object from Trakt; null without the TMDB and IMDb ids (no artwork or no Stremio page). */
    private static Entry entry(String group, String kind, JSONObject o) {
        if (o == null) return null;
        JSONObject ids = o.optJSONObject("ids");
        if (ids == null) return null;
        Entry e = new Entry();
        e.group = group;
        e.kind = kind;
        e.title = o.optString("title");
        e.tmdb = ids.optInt("tmdb");
        e.imdb = ids.optString("imdb", "");
        if (e.tmdb <= 0 || e.imdb.isEmpty() || "null".equals(e.imdb)) return null;
        e.overview = o.optString("overview", "");
        if ("null".equals(e.overview)) e.overview = "";
        e.year = o.optInt("year");
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            String g = genres.optString(0);
            e.genre = g.isEmpty() ? "" : Character.toUpperCase(g.charAt(0)) + g.substring(1).replace('-', ' ');
        }
        JSONObject images = o.optJSONObject("images");
        JSONArray fanart = images == null ? null : images.optJSONArray("fanart");
        if (fanart == null || fanart.length() == 0) fanart = images == null ? null : images.optJSONArray("thumb");
        if (fanart != null && fanart.length() > 0) {
            // Trakt gives "media.trakt.tv/images/…/medium/x.jpg.webp"; the full size is sharper for the backdrop.
            String path = fanart.optString(0);
            e.fanart = (path.startsWith("http") ? path : "https://" + path).replace("/medium/", "/full/");
        }
        return e;
    }

    /** Opens the title on its Stremio page (for Up next, on the next episode). */
    static String stremioLink(Entry e) {
        if (e.kind.equals("movie")) return "stremio:///detail/movie/" + e.imdb;
        String link = "stremio:///detail/series/" + e.imdb;
        if (e.season > 0 && e.episode > 0) link += "/" + e.imdb + ":" + e.season + ":" + e.episode;
        return link;
    }

    // MARK: - HTTP

    static final class HttpError extends IOException {
        final int code;
        HttpError(int code) {
            super("Trakt returned HTTP " + code);
            this.code = code;
        }
    }

    private static String clientId() {
        return Store.traktId(App.ctx).trim();
    }

    private static HttpURLConnection open(String path, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", clientId());
        c.setRequestProperty("User-Agent", "tv-home/1.0");
        if (auth != null) c.setRequestProperty("Authorization", "Bearer " + auth);
        return c;
    }

    private static String get(String path, String auth) throws IOException {
        HttpURLConnection c = open(path, auth);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String post(String path, JSONObject body, String auth) throws IOException {
        HttpURLConnection c = open(path, auth);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
