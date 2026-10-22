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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Your Stremio account: Continue watching and your Library, the same lists the Stremio app shows. Sign in once on the
 * TV with a code (link.stremio.com, Stremio's own TV sign-in); the account key stays in the app's private preferences.
 * Titles open on their Stremio page (series: on the episode you were watching).
 */
final class Stremio {
    private Stremio() {}

    static final String PKG = "com.stremio.one";

    private static final String API = "https://api.strem.io/api/";
    private static final String LINK = "https://link.stremio.com/api/v2/";
    private static final String CINEMETA = "https://v3-cinemeta.strem.io/meta/";

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("stremio", Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return !prefs(c).getString("authKey", "").isEmpty();
    }

    static void signOut(Context c) {
        prefs(c).edit().clear().apply();
        synchronized (Stremio.class) {
            library = new ArrayList<>();
            fetchedAt = 0;
        }
    }

    // MARK: - Sign-in with a code (like Stremio's own TV app)

    /** {code, link, qrcode}. */
    static JSONObject createLink() throws IOException {
        try {
            JSONObject o = new JSONObject(get(LINK + "create?type=Create"));
            JSONObject r = o.optJSONObject("result");
            if (r == null || !r.optBoolean("success")) throw new IOException("Stremio didn't give a code");
            return r;
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    /** One check of a code: true when signed in (key saved), false while waiting. */
    static boolean readLink(Context c, String code) throws IOException {
        try {
            JSONObject o = new JSONObject(get(LINK + "read?type=Read&code=" + code));
            JSONObject r = o.optJSONObject("result");
            String key = r == null ? "" : r.optString("authKey", "");
            if (key.isEmpty()) return false;
            prefs(c).edit().putString("authKey", key).apply();
            return true;
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    // MARK: - Library

    /** A library title, ready for a card and the backdrop. */
    static final class Item {
        String id, type, name, background, logo, poster, videoId, note = "";
        double progress;     // 0..1 of the episode or movie (0 = not started)
        long lastWatched, modified;
        boolean continueWatching, removedOrTemp;
    }

    private static List<Item> library = new ArrayList<>();
    private static long fetchedAt;

    /** Continue watching, newest first (as in Stremio: started, not finished, removed ones only when temporary). */
    static synchronized List<Item> continueWatching() {
        List<Item> out = new ArrayList<>();
        for (Item it : library) if (it.continueWatching) out.add(it);
        Collections.sort(out, (a, b) -> Long.compare(Math.max(b.lastWatched, b.modified), Math.max(a.lastWatched, a.modified)));
        return out;
    }

    /** Your library (not removed), most recently changed first. */
    static synchronized List<Item> recentLibrary(int max) {
        List<Item> out = new ArrayList<>();
        for (Item it : library) if (!it.removedOrTemp) out.add(it);
        Collections.sort(out, (a, b) -> Long.compare(Math.max(b.lastWatched, b.modified), Math.max(a.lastWatched, a.modified)));
        return out.size() > max ? out.subList(0, max) : out;
    }

    /** Fetches the library if it's older than a minute (called when the home screen comes back). True if it changed. */
    static boolean refresh(Context c) throws IOException {
        String key = prefs(c).getString("authKey", "");
        if (key.isEmpty()) return false;
        synchronized (Stremio.class) {
            if (System.currentTimeMillis() - fetchedAt < 60_000L) return false;
        }
        String body;
        try {
            body = post(API + "datastoreGet", new JSONObject().put("authKey", key).put("collection", "libraryItem")
                    .put("ids", new JSONArray()).put("all", true));
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        List<Item> items = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(body);
            JSONObject error = o.optJSONObject("error");
            if (error != null) {
                if (error.optInt("code") == 1) signOut(c);   // the session ended: sign in again
                throw new IOException("Stremio: " + error.optString("message"));
            }
            JSONArray a = o.optJSONArray("result");
            for (int i = 0; a != null && i < a.length(); i++) {
                Item it = parse(a.getJSONObject(i));
                if (it != null) items.add(it);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Stremio sent an unexpected answer");
        }
        synchronized (Stremio.class) {
            boolean changed = !signature(items).equals(signature(library));
            library = items;
            fetchedAt = System.currentTimeMillis();
            return changed;
        }
    }

    private static String signature(List<Item> items) {
        StringBuilder s = new StringBuilder();
        for (Item it : items) s.append(it.id).append(it.videoId).append(Math.round(it.progress * 100)).append(it.continueWatching).append(',');
        return s.toString();
    }

    private static Item parse(JSONObject o) {
        String type = o.optString("type");
        if (!type.equals("movie") && !type.equals("series")) return null;   // channels, "other"
        Item it = new Item();
        it.id = o.optString("_id");
        it.type = type;
        it.name = o.optString("name");
        it.background = o.optString("background", "");
        it.logo = o.optString("logo", "");
        it.poster = o.optString("poster", "");
        it.modified = time(o.optString("_mtime"));
        boolean removed = o.optBoolean("removed"), temp = o.optBoolean("temp");
        it.removedOrTemp = removed || temp;
        JSONObject s = o.optJSONObject("state");
        if (s != null) {
            it.videoId = s.optString("video_id", "");
            it.lastWatched = time(s.optString("lastWatched"));
            long offset = s.optLong("timeOffset"), duration = s.optLong("duration");
            it.progress = duration > 0 ? Math.min(1, offset / (double) duration) : 0;
            // Stremio's rule: started (time offset), and either kept in the library or a temporary (watched) one.
            it.continueWatching = offset > 0 && (!removed || temp) && it.progress < 0.95;
            int season = s.optInt("season"), episode = s.optInt("episode");
            if (type.equals("series") && season > 0 && episode > 0) {
                it.note = T.f("S{s} · E{e}", "s", season, "e", episode);
            }
            if (it.continueWatching && duration > 0) {
                long left = Math.max(1, (duration - offset) / 60000);
                String rest = T.f("{m} min left", "m", left);
                it.note = it.note.isEmpty() ? rest : it.note + " · " + rest;
            }
        }
        if (it.background.isEmpty() && it.id.startsWith("tt")) it.background = "https://images.metahub.space/background/medium/" + it.id + "/img";
        if (it.logo.isEmpty() && it.id.startsWith("tt")) it.logo = "https://images.metahub.space/logo/medium/" + it.id + "/img";
        return it;
    }

    /** "2026-10-20T21:14:03.123Z" → epoch ms (0 if missing). */
    private static long time(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Opens the title on its Stremio page; a series on the episode in progress. */
    static String link(Item it) {
        String link = "stremio:///detail/" + it.type + "/" + it.id;
        if (it.type.equals("series") && it.videoId != null && !it.videoId.isEmpty() && !it.videoId.equals(it.id)) link += "/" + it.videoId;
        return link;
    }

    // MARK: - Details for the backdrop (Cinemeta: Stremio's own catalog)

    /** {description, genres/runtime line}, fetched once per title and kept for the session. */
    private static final Map<String, String[]> DETAILS = new HashMap<>();

    static String[] details(Item it) {
        synchronized (DETAILS) {
            return DETAILS.get(it.type + it.id);
        }
    }

    static String[] fetchDetails(Item it) {
        String[] known = details(it);
        if (known != null) return known;
        String[] out = {"", ""};
        try {
            JSONObject meta = new JSONObject(get(CINEMETA + it.type + "/" + it.id + ".json")).optJSONObject("meta");
            if (meta != null) {
                out[0] = meta.optString("description", "");
                List<String> parts = new ArrayList<>();
                parts.add(it.type.equals("series") ? "Series" : "Movie");
                JSONArray genres = meta.optJSONArray("genres");
                if (genres != null && genres.length() > 0) parts.add(genres.optString(0));
                String year = meta.optString("releaseInfo", "");
                if (!year.isEmpty()) parts.add(year);
                String runtime = meta.optString("runtime", "");
                if (!runtime.isEmpty() && it.type.equals("movie")) parts.add(runtime);
                out[1] = String.join(" · ", parts);
            }
        } catch (Exception e) {
            Logs.w("cinemeta " + it.id + ": " + e.getMessage());
        }
        synchronized (DETAILS) {
            DETAILS.put(it.type + it.id, out);
        }
        return out;
    }

    // MARK: - HTTP

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String post(String url, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Stremio returned HTTP " + code);
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
