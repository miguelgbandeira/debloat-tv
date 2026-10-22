package tv.debloat.home;

import android.content.Context;
import android.content.SharedPreferences;

/** The person's settings (set on the TV, or sent once by the installer through SetupReceiver). */
final class Store {
    private Store() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String get(Context c, String key, String fallback) {
        return sp(c).getString(key, fallback);
    }

    static void put(Context c, String key, String value) {
        if (value == null) sp(c).edit().remove(key).apply();
        else sp(c).edit().putString(key, value.trim()).apply();
    }

    // The keys the app reads.
    static final String TMDB_KEY = "tmdb_key", TRAKT_ID = "trakt_client_id", REGION = "region", CITY = "city",
            LATITUDE = "latitude", LONGITUDE = "longitude", LIVE_TV = "live_tv_app", UNITS = "units";

    static String tmdbKey(Context c) { return get(c, TMDB_KEY, ""); }

    static String traktId(Context c) { return get(c, TRAKT_ID, ""); }

    /** Two letters (PT, US, GB…): which titles are on which streaming app. Defaults to the TV's country. */
    static String region(Context c) {
        String r = get(c, REGION, "");
        if (r.isEmpty()) r = java.util.Locale.getDefault().getCountry();
        return r.isEmpty() ? "US" : r.toUpperCase(java.util.Locale.ROOT);
    }

    static boolean fahrenheit(Context c) {
        String u = get(c, UNITS, "");
        if (u.equals("F")) return true;
        if (u.equals("C")) return false;
        return "US".equals(java.util.Locale.getDefault().getCountry());
    }
}
