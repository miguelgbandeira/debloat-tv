package tv.debloat.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The titles in the home screen's rows:
 *  - New / Popular series and movies (four rows, from TMDB with your own free key): with Stremio on the TV, what's
 *    new and popular anywhere, opened in Stremio; without it, what's on the streaming apps installed on the TV, in
 *    your country, opened in the app it's on.
 *  - Up next / For you / Trending: from your Trakt account, when signed in. Open in Stremio when it's installed.
 */
final class Picks {
    interface Listener { void onPicks(); }

    static final String POPULAR = "popular", NEW = "new";

    static final class Pick {
        String key, group, kind, title, card, backdrop, meta = "", overview = "", note, link, pkg;
        transient int tmdb;   // while fetching
    }

    /** TMDB's name for a streaming service (lowercase) → its Android TV app. */
    private static final Map<String, String> APP_FOR = new LinkedHashMap<>();
    /** The app → the name shown on the cards. */
    private static final Map<String, String> NAME_OF = new LinkedHashMap<>();

    private static void known(String app, String name, String... tmdbNames) {
        NAME_OF.put(app, name);
        for (String t : tmdbNames) APP_FOR.put(t, app);
    }

    static {
        known("com.netflix.ninja", "Netflix", "netflix", "netflix basic with ads");
        known("com.amazon.amazonvideo.livingroom", "Prime Video", "amazon prime video", "prime video", "amazon prime video with ads");
        known("com.disney.disneyplus", "Disney+", "disney plus");
        known("com.wbd.stream", "Max", "max", "hbo max");
        known("com.apple.atve.androidtv.appletv", "Apple TV", "apple tv plus", "apple tv+");
        known("com.hulu.livingroomplus", "Hulu", "hulu");
        known("com.cbs.ott", "Paramount+", "paramount plus", "paramount+");
        known("com.peacocktv.peacockandroid", "Peacock", "peacock", "peacock premium");
        known("com.skyshowtime.skyshowtime.google", "SkyShowtime", "skyshowtime");
        known("com.crunchyroll.crunchyroid", "Crunchyroll", "crunchyroll");
        known("pt.rtp.play", "RTP Play", "rtp play");
        known("com.filmin.filminandroidtv", "Filmin", "filmin");
        known("com.mubi", "MUBI", "mubi");
    }

    private static Picks instance;

    static synchronized Picks get(Context c) {
        if (instance == null) instance = new Picks(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile long tmdbAt, traktAt;
    private volatile boolean fetching;
    private volatile String stamp = "";

    private Picks(Context c) {
        context = c;
        cache = c.getSharedPreferences("picks", Context.MODE_PRIVATE);
        try {
            picks = parse(new JSONArray(cache.getString("list", "[]")));
            stamp = cache.getString("stamp", "");
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    boolean traktMode() {
        return Trakt.signedIn(context);
    }

    /** The titles of one row, in order. */
    List<Pick> group(String group) {
        List<Pick> out = new ArrayList<>();
        for (Pick p : picks) if (group.equals(p.group)) out.add(p);
        return out;
    }

    /** Fetch again now (after signing in, or a new key). */
    void refreshNow() {
        tmdbAt = 0;
        traktAt = 0;
        refreshIfStale();
    }

    void refreshIfStale() {
        if (fetching) return;
        String tmdb = Store.tmdbKey(context);
        boolean trakt = traktMode();
        String now = tmdb.hashCode() + ":" + trakt + ":" + Store.region(context) + ":" + Apps.installed(context, Stremio.PKG);
        long t = System.currentTimeMillis();
        boolean tmdbStale = !tmdb.isEmpty() && (t - tmdbAt > 6 * 3600_000L || !now.equals(stamp));
        boolean traktStale = trakt && (t - traktAt > 20 * 60_000L || !now.equals(stamp));
        boolean dropped = !now.equals(stamp) && (tmdb.isEmpty() || !trakt);
        if (!tmdbStale && !traktStale && !dropped) return;
        fetching = true;
        new Thread(() -> {
            List<Pick> list = new ArrayList<>(picks);
            try {
                if (tmdb.isEmpty()) list.removeIf(p -> POPULAR.equals(p.group) || NEW.equals(p.group));
                if (!trakt) list.removeIf(p -> !POPULAR.equals(p.group) && !NEW.equals(p.group));
                if (tmdbStale) {
                    List<Pick> fresh = fetchServices(tmdb);
                    list.removeIf(p -> POPULAR.equals(p.group) || NEW.equals(p.group));
                    list.addAll(fresh);
                    tmdbAt = System.currentTimeMillis();
                }
                if (traktStale) {
                    List<Pick> fresh = fetchTrakt();
                    list.removeIf(p -> !POPULAR.equals(p.group) && !NEW.equals(p.group));
                    list.addAll(fresh);
                    traktAt = System.currentTimeMillis();
                }
            } catch (Exception e) {
                Logs.w("picks: " + e.getMessage());
            } finally {
                picks = list;
                stamp = now;
                save();
                fetching = false;
                main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onPicks(); });
            }
        }, "picks").start();
    }

    // MARK: - Trakt

    private List<Pick> fetchTrakt() throws IOException {
        List<Pick> out = new ArrayList<>();
        boolean stremio = Apps.installed(context, Stremio.PKG);
        for (Trakt.Entry e : Trakt.lists(context)) {
            if (e.fanart == null || e.fanart.isEmpty()) continue;
            Pick p = new Pick();
            p.key = "trakt:" + e.group + ":" + e.kind + e.tmdb;
            p.group = e.group;
            p.kind = e.kind;
            p.title = e.title;
            p.card = e.fanart;
            p.backdrop = e.fanart;
            p.overview = e.overview;
            p.note = e.note;
            List<String> meta = new ArrayList<>();
            meta.add(e.kind.equals("movie") ? "Movie" : "Series");
            if (!e.genre.isEmpty()) meta.add(e.genre);
            if (e.year > 0) meta.add(String.valueOf(e.year));
            p.meta = String.join(" · ", meta);
            if (stremio) {
                p.pkg = Stremio.PKG;
                p.link = Trakt.stremioLink(e);
            }
            out.add(p);
        }
        return out;
    }

    // MARK: - TMDB: popular and new on your streaming apps

    private List<Pick> fetchServices(String key) throws Exception {
        if (Apps.installed(context, Stremio.PKG)) return fetchEverything(key);
        String region = Store.region(context);
        // The services TMDB has in your country that have an app on this TV: TMDB id → {name, package}.
        Map<Integer, String[]> mine = new LinkedHashMap<>();   // TMDB provider id → {name, app}
        JSONArray providers = tmdb(key, "/watch/providers/movie?watch_region=" + region).getJSONArray("results");
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String app = APP_FOR.get(o.optString("provider_name").toLowerCase(Locale.ROOT));
            if (app != null && Apps.installed(context, app) && taken.add(app)) mine.put(o.getInt("provider_id"), new String[] {NAME_OF.get(app), app});
        }
        List<Pick> out = new ArrayList<>();
        if (mine.isEmpty()) return out;
        String recent = day(-75), today = day(0);
        // Series and movies apart (each has its own rows), one title from each app in turn.
        List<List<Pick>> popTv = new ArrayList<>(), popMovie = new ArrayList<>(), newTv = new ArrayList<>(), newMovie = new ArrayList<>();
        for (Map.Entry<Integer, String[]> s : mine.entrySet()) {
            String base = "&watch_region=" + region + "&with_watch_providers=" + s.getKey()
                    + "&with_watch_monetization_types=flatrate&include_adult=false";
            for (String kind : new String[] {"tv", "movie"}) {
                List<Pick> p = new ArrayList<>(), n = new ArrayList<>();
                JSONArray a = tmdb(key, "/discover/" + kind + "?sort_by=popularity.desc&vote_count.gte=50" + base).getJSONArray("results");
                addAll(p, a, kind, POPULAR, s.getValue()[0], s.getValue()[1], 10);
                String dates = kind.equals("movie") ? "&primary_release_date.gte=" + recent + "&primary_release_date.lte=" + today
                        : "&air_date.gte=" + recent + "&air_date.lte=" + today;
                JSONArray b = tmdb(key, "/discover/" + kind + "?sort_by=popularity.desc&vote_count.gte=10" + dates + base).getJSONArray("results");
                addAll(n, b, kind, NEW, s.getValue()[0], s.getValue()[1], 10);
                (kind.equals("tv") ? popTv : popMovie).add(p);
                (kind.equals("tv") ? newTv : newMovie).add(n);
            }
        }
        out.addAll(interleave(popTv, 24));
        out.addAll(interleave(popMovie, 24));
        out.addAll(interleave(newTv, 24));
        out.addAll(interleave(newMovie, 24));
        return out;
    }

    /** Not talk shows, news, reality or soaps: on top of TMDB's popular series every day, and not what you'd look for. */
    private static final String SERIES_ONLY = "&without_genres=10763,10764,10766,10767";

    /** The same for a list that can't be filtered when asked for (trending). */
    private static boolean notSeries(JSONObject o) {
        JSONArray g = o.optJSONArray("genre_ids");
        for (int i = 0; g != null && i < g.length(); i++) {
            int id = g.optInt(i);
            if (id == 10763 || id == 10764 || id == 10766 || id == 10767) return true;
        }
        return false;
    }

    /** What's new and popular anywhere, each opened in Stremio. */
    private List<Pick> fetchEverything(String key) throws Exception {
        String recent = day(-75), today = day(0);
        // Popular: trending this week (TMDB's plain popularity keeps old titles on top). New: out in the last weeks.
        String[][] rows = {
                {"tv", POPULAR, "/trending/tv/week?"},
                {"movie", POPULAR, "/trending/movie/week?"},
                {"tv", NEW, "/discover/tv?sort_by=popularity.desc&vote_count.gte=20&air_date.gte=" + recent + "&air_date.lte=" + today + SERIES_ONLY},
                // Out to watch at home (digital or disc), not only in cinemas.
                {"movie", NEW, "/discover/movie?sort_by=popularity.desc&vote_count.gte=20&with_release_type=4|5&release_date.gte="
                        + day(-90) + "&release_date.lte=" + today},
        };
        List<Pick> out = new ArrayList<>();
        for (String[] r : rows) {
            JSONArray results = tmdb(key, r[2] + "&include_adult=false").getJSONArray("results"), keep = new JSONArray();
            for (int i = 0; i < results.length(); i++) {
                JSONObject o = results.optJSONObject(i);
                if (o != null && !notSeries(o)) keep.put(o);
            }
            List<Pick> row = new ArrayList<>();
            addAll(row, keep, r[0], r[1], null, Stremio.PKG, 20);
            for (Pick p : row) {
                String imdb = imdb(key, p.kind, p.tmdb);
                if (imdb.isEmpty()) continue;   // Stremio finds titles by their IMDb id
                p.link = "stremio:///detail/" + (p.kind.equals("movie") ? "movie/" : "series/") + imdb;
                out.add(p);
            }
        }
        return out;
    }

    /** TMDB id → IMDb id, kept: titles stay in the rows for days. */
    private final Map<String, String> imdbIds = new java.util.concurrent.ConcurrentHashMap<>();

    private String imdb(String key, String kind, int id) {
        String k = kind + id, known = imdbIds.get(k);
        if (known != null) return known;
        try {
            String imdb = tmdb(key, "/" + kind + "/" + id + "/external_ids").optString("imdb_id", "");
            if (!imdb.startsWith("tt")) imdb = "";
            imdbIds.put(k, imdb);
            return imdb;
        } catch (Exception e) {
            return "";
        }
    }

    /** One page of TMDB results as picks; `service` is the app's name on the cards (none for Stremio). */
    private static void addAll(List<Pick> out, JSONArray a, String kind, String group, String service, String app, int max) {
        for (int i = 0; i < a.length() && out.size() < max; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String backdrop = o.optString("backdrop_path", "");
            if (backdrop.isEmpty() || "null".equals(backdrop)) continue;
            Pick p = new Pick();
            p.key = "tmdb:" + group + ":" + kind + o.optInt("id");
            p.group = group;
            p.kind = kind;
            p.title = o.optString(kind.equals("movie") ? "title" : "name");
            p.card = "https://image.tmdb.org/t/p/w780" + backdrop;
            p.backdrop = "https://image.tmdb.org/t/p/w1280" + backdrop;
            p.overview = o.optString("overview", "");
            String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
            p.tmdb = o.optInt("id");
            p.meta = (kind.equals("movie") ? "Movie" : "Series") + (date.length() >= 4 ? " · " + date.substring(0, 4) : "")
                    + (service != null ? " · " + service : "");
            p.pkg = app;
            out.add(p);
        }
    }

    /** One from each service in turn, without repeats, up to max. */
    private static List<Pick> interleave(List<List<Pick>> lists, int max) {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; out.size() < max; i++) {
            boolean any = false;
            for (List<Pick> l : lists) {
                if (i >= l.size()) continue;
                any = true;
                Pick p = l.get(i);
                if (seen.add(p.kind + p.title) && out.size() < max) out.add(p);
            }
            if (!any) break;
        }
        return out;
    }

    private static String day(int offset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static JSONObject tmdb(String key, String path) throws Exception {
        boolean bearer = key.length() > 40;   // the long "API Read Access Token", or the short "API Key"
        String url = "https://api.themoviedb.org/3" + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + key);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB didn't accept the key");
            if (code != 200) throw new IOException("TMDB answered " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    // MARK: - Saved between restarts (the rows show at once, then refresh)

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Pick p : picks) {
                a.put(new JSONObject().put("key", p.key).put("group", p.group).put("kind", p.kind).put("title", p.title)
                        .put("card", p.card).put("backdrop", p.backdrop).put("meta", p.meta).put("overview", p.overview)
                        .put("note", p.note == null ? "" : p.note).put("link", p.link == null ? "" : p.link)
                        .put("pkg", p.pkg == null ? "" : p.pkg));
            }
        } catch (Exception ignored) {
        }
        cache.edit().putString("list", a.toString()).putString("stamp", stamp).apply();
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.key = o.optString("key");
            p.group = o.optString("group");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.card = o.optString("card");
            p.backdrop = o.optString("backdrop");
            p.meta = o.optString("meta");
            p.overview = o.optString("overview");
            p.note = o.optString("note").isEmpty() ? null : o.optString("note");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            out.add(p);
        }
        return out;
    }
}
