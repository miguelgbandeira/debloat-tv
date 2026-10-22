package tv.debloat.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The home screen's rows, in order, edited on the TV like Projectivy's categories: every row can move up and down and
 * be hidden; app rows also have a name and their apps, and can be added, renamed and deleted. Hidden apps show in no
 * row (not even All apps) until shown again.
 */
final class HomeLayout {
    static final String CONTINUE = "continue", LIBRARY = "library", UP_NEXT = "upnext", FOR_YOU = "foryou",
            TRENDING = "trending", APPS = "apps", ALL_APPS = "allapps", INPUTS = "inputs", POPULAR = "popular", NEW = "new";

    static final class Row {
        String type, name = "";
        boolean hidden;
        final List<String> apps = new ArrayList<>();

        boolean isApps() { return APPS.equals(type); }

        /** What the row is called on screen. */
        String title() {
            switch (type) {
                case CONTINUE: return "Continue watching";
                case LIBRARY: return "Library";
                case POPULAR: return "Popular on your apps";
                case NEW: return "New on your apps";
                case UP_NEXT: return "Up next on Trakt";
                case FOR_YOU: return "For you";
                case TRENDING: return "Trending";
                case ALL_APPS: return "All apps";
                case INPUTS: return "Inputs & settings";
                default: return name;
            }
        }
    }

    private static final String PREFS = "layout";

    private final SharedPreferences prefs;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> hiddenApps = new LinkedHashSet<>();

    /** Well-known streaming apps: the first ones found go into Favorites on a new TV. */
    private static final String[] STREAMING = {"com.netflix.ninja", "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.wbd.stream", "com.hbo.hbonow",
            "com.apple.atve.androidtv.appletv", "com.hulu.livingroomplus", "com.cbs.ott", "com.peacocktv.peacockandroid",
            "com.stremio.one", "org.jellyfin.androidtv", "com.plexapp.android", "com.spotify.tv.android"};

    private final Context context;

    HomeLayout(Context c) {
        context = c.getApplicationContext();
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String saved = prefs.getString("rows", null);
            if (saved != null) {
                JSONArray a = new JSONArray(saved);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Row r = new Row();
                    r.type = o.optString("type");
                    r.name = o.optString("name");
                    r.hidden = o.optBoolean("hidden");
                    JSONArray apps = o.optJSONArray("apps");
                    for (int j = 0; apps != null && j < apps.length(); j++) r.apps.add(apps.getString(j));
                    rows.add(r);
                }
                JSONArray hidden = new JSONArray(prefs.getString("hiddenApps", "[]"));
                for (int i = 0; i < hidden.length(); i++) hiddenApps.add(hidden.getString(i));
            }
        } catch (Exception e) {
            rows.clear();
        }
        if (rows.isEmpty()) defaults();
        // All apps is a grid now (top right, and the tile at the end of Favorites), no longer a row.
        boolean dropped = false;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (ALL_APPS.equals(rows.get(i).type)) {
                rows.remove(i);
                dropped = true;
            }
        }
        if (dropped) save();
        // The All Apps tile is an ordinary entry of an app row, at the end of the last one (Tools) to start with.
        if (!prefs.getBoolean("allAppsTileTools", false)) {
            prefs.edit().putBoolean("allAppsTileTools", true).apply();
            int last = lastAppRow();
            if (last >= 0) {
                for (Row r : rows) r.apps.remove(ALL_APPS_TILE);   // it started in Favorites
                rows.get(last).apps.add(ALL_APPS_TILE);
                save();
            }
        }
    }

    /** The last app row (Tools unless renamed or moved), or -1. */
    int lastAppRow() {
        for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).isApps()) return i;
        return -1;
    }

    /** The All Apps tile's entry in an app row (moved and removed like an app). */
    static final String ALL_APPS_TILE = "@allapps";

    boolean hasAllAppsTile() {
        for (Row r : rows) if (r.isApps() && r.apps.contains(ALL_APPS_TILE)) return true;
        return false;
    }

    /** The first app row (Favorites, unless renamed or moved): where "Add to Favorites" goes. -1 if there is none. */
    int favorites() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).isApps()) return i;
        return -1;
    }

    /** First run: Stremio first, your favourite apps, Trakt's picks, the other app rows, then the rest. */

    /**
     * A new TV: titles rows first (each shows only when it has titles: Stremio, Trakt or your streaming apps), your
     * streaming apps as Favorites, every other app in Apps with the All Apps tile, then the inputs. Or the layout the
     * installer sent (layout-import.json), used once.
     */
    private void defaults() {
        if (importLayout()) return;
        add(CONTINUE, "", false);
        Row fav = add(APPS, "Favorites", false);
        java.util.Set<String> installed = new java.util.HashSet<>();
        for (String[] app : Apps.launchable(context)) installed.add(app[0]);
        for (String pkg : STREAMING) if (installed.contains(pkg) && fav.apps.size() < 6) fav.apps.add(pkg);
        add(POPULAR, "", false);
        add(NEW, "", false);
        add(FOR_YOU, "", false);
        add(TRENDING, "", false);
        Row other = add(APPS, "Apps", false);
        java.util.List<String[]> rest = Apps.launchable(context);
        java.util.Collections.sort(rest, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        for (String[] app : rest) if (!fav.apps.contains(app[0])) other.apps.add(app[0]);
        other.apps.add(ALL_APPS_TILE);
        add(LIBRARY, "", false);
        add(UP_NEXT, "", true);
        add(INPUTS, "", false);
        save();
    }

    /** The layout sent by the installer: {"rows":[{type,name,hidden,apps[]}], "hiddenApps":[...]}. */
    private boolean importLayout() {
        java.io.File f = new java.io.File(context.getFilesDir(), "layout-import.json");
        if (!f.exists()) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int n = in.read(data);
            JSONObject o = new JSONObject(new String(data, 0, Math.max(0, n), "UTF-8"));
            JSONArray a = o.getJSONArray("rows");
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                Row row = add(r.getString("type"), r.optString("name"), r.optBoolean("hidden"));
                JSONArray apps = r.optJSONArray("apps");
                for (int j = 0; apps != null && j < apps.length(); j++) row.apps.add(apps.getString(j));
            }
            JSONArray hidden = o.optJSONArray("hiddenApps");
            for (int i = 0; hidden != null && i < hidden.length(); i++) hiddenApps.add(hidden.getString(i));
            save();
            return !rows.isEmpty();
        } catch (Exception e) {
            Logs.w("layout import: " + e.getMessage());
            rows.clear();
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private Row add(String type, String name, boolean hidden) {
        Row r = new Row();
        r.type = type;
        r.name = name;
        r.hidden = hidden;
        rows.add(r);
        return r;
    }

    private void apps(String name, String... pkgs) {
        Row r = add(APPS, name, false);
        for (String p : pkgs) r.apps.add(p);
    }

    /** Goes up on every change: the home screen rebuilds its rows only when it moved. */
    int version;

    private void save() {
        version++;
        JSONArray a = new JSONArray();
        for (Row r : rows) {
            JSONArray apps = new JSONArray();
            for (String p : r.apps) apps.put(p);
            try {
                a.put(new JSONObject().put("type", r.type).put("name", r.name).put("hidden", r.hidden).put("apps", apps));
            } catch (Exception ignored) {
            }
        }
        JSONArray hidden = new JSONArray();
        for (String p : hiddenApps) hidden.put(p);
        prefs.edit().putString("rows", a.toString()).putString("hiddenApps", hidden.toString()).apply();
    }

    List<Row> rows() { return rows; }

    Set<String> hiddenApps() { return hiddenApps; }

    // MARK: - Rows

    void moveRow(int i, int delta) {
        int j = i + delta;
        if (j < 0 || j >= rows.size()) return;
        rows.add(j, rows.remove(i));
        save();
    }

    /**
     * After moving rows on screen: `shown` lists the layout positions of the rows on screen, in their new order. They
     * take the places the shown rows had; hidden rows stay where they were. Returns each shown row's new position.
     */
    List<Integer> reorderShown(List<Integer> shown) {
        List<Integer> slots = new ArrayList<>(shown);
        java.util.Collections.sort(slots);
        List<Row> before = new ArrayList<>(rows);
        for (int k = 0; k < shown.size(); k++) rows.set(slots.get(k), before.get(shown.get(k)));
        save();
        return slots;
    }

    void setHidden(int i, boolean hidden) {
        rows.get(i).hidden = hidden;
        save();
    }

    void rename(int i, String name) {
        rows.get(i).name = name;
        save();
    }

    /** Deletes an app row (its apps go back to All apps). */
    void delete(int i) {
        if (rows.get(i).isApps()) rows.remove(i);
        save();
    }

    /** A new, empty app row right after row i (at the end with i = -1). Returns its position. */
    int addAppsRow(String name, int after) {
        Row r = new Row();
        r.type = APPS;
        r.name = name;
        int at = after < 0 ? rows.size() : after + 1;
        rows.add(at, r);
        save();
        return at;
    }

    // MARK: - Apps

    void moveApp(int row, String pkg, int delta) {
        List<String> apps = rows.get(row).apps;
        int i = apps.indexOf(pkg), j = i + delta;
        if (i < 0 || j < 0 || j >= apps.size()) return;
        apps.add(j, apps.remove(i));
        save();
    }

    /** Moves the app to the end of another row (from = -1: from All apps, so it stays nowhere else). */
    void moveAppTo(String pkg, int from, int to) {
        if (from >= 0) rows.get(from).apps.remove(pkg);
        if (!rows.get(to).apps.contains(pkg)) rows.get(to).apps.add(pkg);
        save();
    }

    void removeApp(int row, String pkg) {
        rows.get(row).apps.remove(pkg);
        save();
    }

    void hideApp(String pkg) {
        hiddenApps.add(pkg);
        save();
    }

    void showApp(String pkg) {
        hiddenApps.remove(pkg);
        save();
    }

    /** Whether one of the app rows has the app (then All apps leaves it out). */
    boolean inAppRow(String pkg) {
        for (Row r : rows) if (r.isApps() && r.apps.contains(pkg)) return true;
        return false;
    }
}
