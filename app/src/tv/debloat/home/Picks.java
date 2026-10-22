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
 *  - Popular / New on your apps: what's popular and recent on the streaming apps installed on the TV, in your
 *    country (TMDB, with your own free key). Opens the app it's on.
 *  - Up next / For you / Trending: from your Trakt account, when signed in. Open in Stremio when it's installed.
 */
final class Picks {
    interface Listener { void onPicks(); }

    static final String POPULAR = "popular", NEW = "new";

    static final class Pick {
        String key, group, kind, title, card, backdrop, meta = "", overview = "", note, link, pkg;
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
        String now = tmdb.hashCode() + ":" + trakt + ":" + Store.region(context);
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
        List<List<Pick>> popular = new ArrayList<>(), fresh = new ArrayList<>();
        for (Map.Entry<Integer, String[]> s : mine.entrySet()) {
            String base = "&watch_region=" + region + "&with_watch_providers=" + s.getKey()
                    + "&with_watch_monetization_types=flatrate&include_adult=false";
            List<Pick> pop = new ArrayList<>(), neu = new ArrayList<>();
            for (String kind : new String[] {"tv", "movie"}) {
                JSONArray a = tmdb(key, "/discover/" + kind + "?sort_by=popularity.desc&vote_count.gte=50" + base).getJSONArray("results");
                addAll(pop, a, kind, POPULAR, s.getValue(), 10);
                String dates = kind.equals("movie") ? "&primary_release_date.gte=" + recent + "&primary_release_date.lte=" + today
                        : "&air_date.gte=" + recent + "&air_date.lte=" + today;
                JSONArray b = tmdb(key, "/discover/" + kind + "?sort_by=popularity.desc&vote_count.gte=10" + dates + base).getJSONArray("results");
                addAll(neu, b, kind, NEW, s.getValue(), 10);
            }
            popular.add(pop);
            fresh.add(neu);
        }
        out.addAll(interleave(popular, 24));
        out.addAll(interleave(fresh, 24));
        return out;
    }

    private static void addAll(List<Pick> out, JSONArray a, String kind, String group, String[] service, int max) {
        for (int i = 0; i < a.length() && out.size() < max * 2; i++) {
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
            p.meta = (kind.equals("movie") ? "Movie" : "Series") + (date.length() >= 4 ? " · " + date.substring(0, 4) : "")
                    + " · " + service[0];
            p.pkg = service[1];
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
