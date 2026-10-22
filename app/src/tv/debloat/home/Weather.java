package tv.debloat.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The current weather where you are (Open-Meteo: free, no key), refreshed every 20 minutes. */
final class Weather {
    interface Listener { void onWeather(); }

    static volatile double temp = Double.NaN;
    static volatile int code = -1;
    static volatile boolean day = true;

    private static long fetchedAt;
    private static volatile boolean fetching;
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }

    static void removeListener(Listener l) { LISTENERS.remove(l); }

    static boolean known() {
        return !Double.isNaN(temp);
    }

    /** "19°" in the person's units. */
    static String degrees(Context c) {
        if (!known()) return "";
        return Math.round(Store.fahrenheit(c) ? temp * 9 / 5 + 32 : temp) + "°";
    }

    /** Forget the last reading (the city changed). */
    static void reset() {
        fetchedAt = 0;
        temp = Double.NaN;
    }

    static void refresh(Context c) {
        String lat = Store.get(c, Store.LATITUDE, ""), lon = Store.get(c, Store.LONGITUDE, "");
        if (lat.isEmpty() || lon.isEmpty() || fetching || System.currentTimeMillis() - fetchedAt < 20 * 60_000L) return;
        fetching = true;
        new Thread(() -> {
            try {
                JSONObject now = new JSONObject(get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current=temperature_2m,weather_code,is_day",
                        lat, lon))).getJSONObject("current");
                temp = now.getDouble("temperature_2m");
                code = now.getInt("weather_code");
                day = now.optInt("is_day", 1) == 1;
                fetchedAt = System.currentTimeMillis();
                MAIN.post(() -> { for (Listener l : new ArrayList<>(LISTENERS)) l.onWeather(); });
            } catch (Exception e) {
                Logs.w("weather: " + e.getMessage());
            } finally {
                fetching = false;
            }
        }, "weather").start();
    }

    /** Places matching a name (Open-Meteo geocoding): {label, latitude, longitude}. */
    static List<String[]> findPlace(String name) throws Exception {
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&name="
                + java.net.URLEncoder.encode(name, "UTF-8")));
        List<String[]> out = new ArrayList<>();
        org.json.JSONArray results = o.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String label = r.optString("name") + (r.optString("admin1").isEmpty() ? "" : ", " + r.optString("admin1"))
                    + (r.optString("country").isEmpty() ? "" : ", " + r.optString("country"));
            out.add(new String[] {label, String.valueOf(r.getDouble("latitude")), String.valueOf(r.getDouble("longitude")),
                    r.optString("name"), r.optString("country_code")});
        }
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
