package tv.debloat.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Settings sent by the installer, so nothing has to be typed with the remote:
 *   adb shell am broadcast -a tv.debloat.home.SETUP -n tv.debloat.home/.SetupReceiver \
 *     --es tmdb_key KEY --es trakt_client_id ID --es city Lisbon --es latitude 38.7 --es longitude -9.1 \
 *     --es region PT --es live_tv_app se.hedekonsult.sparkle --es use_stremio 1 --es setup_done 1 --es layout '{json}'
 * With setup_done=1 the TV skips its first-run questions (they were answered on the computer).
 * Only the shell can send it (the receiver needs WRITE_SECURE_SETTINGS, which normal apps don't have).
 */
public final class SetupReceiver extends BroadcastReceiver {
    private static final String[] KEYS = {Store.TMDB_KEY, Store.TRAKT_ID, Store.CITY, Store.LATITUDE, Store.LONGITUDE,
            Store.REGION, Store.LIVE_TV, Store.UNITS, "use_stremio", "use_streaming", "use_trakt", "setup_done"};

    @Override public void onReceive(Context c, Intent intent) {
        StringBuilder done = new StringBuilder();
        for (String key : KEYS) {
            String v = intent.getStringExtra(key);
            if (v == null) continue;
            Store.put(c, key, v.isEmpty() ? null : v);
            done.append(key).append(' ');
        }
        String layout = intent.getStringExtra("layout");
        if (layout != null) {
            try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), "layout-import.json"))) {
                out.write(layout.getBytes(StandardCharsets.UTF_8));
                done.append("layout ");
            } catch (Exception e) {
                Logs.w("setup layout: " + e.getMessage());
            }
        }
        Weather.reset();
        Logs.w("setup: " + done);
        setResultData("ok: " + done.toString().trim());
    }
}
