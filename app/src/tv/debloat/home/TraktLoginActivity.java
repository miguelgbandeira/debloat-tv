package tv.debloat.home;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Trakt sign-in on the TV: shows the address and the code, checks every few seconds and closes by itself once you
 * approve on your phone. Opened from the home screen (or from Home settings → Trakt).
 */
public final class TraktLoginActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView code, status;
    private volatile boolean active;
    private Thread worker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.rgb(16, 16, 20));
        box.addView(text("Sign in to Trakt", 40, Color.WHITE, true));
        box.addView(text("On your phone or computer, go to", 24, Color.rgb(170, 170, 180), false));
        box.addView(text("trakt.tv/activate", 34, Color.rgb(237, 28, 36), true));
        box.addView(text("and enter this code:", 24, Color.rgb(170, 170, 180), false));
        code = text("…", 72, Color.WHITE, true);
        code.setLetterSpacing(0.15f);
        box.addView(code);
        status = text("", 22, Color.rgb(140, 140, 150), false);
        box.addView(status);
        setContentView(box);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 12, 0, 12);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override protected void onResume() {
        super.onResume();
        start();
    }

    private void start() {
        if (!Trakt.configured()) {
            status.setText("No Trakt Client ID: add trakt_client_id to config.properties and build again.");
            return;
        }
        active = true;
        worker = new Thread(this::signIn, "trakt-login");
        worker.start();
    }

    @Override protected void onPause() {
        super.onPause();
        stop();
    }

    private void stop() {
        active = false;
        if (worker != null) worker.interrupt();
    }

    private void signIn() {
        try {
            JSONObject d = Trakt.deviceCode();
            String device = d.getString("device_code");
            String user = d.getString("user_code");
            long until = System.currentTimeMillis() + d.optLong("expires_in", 600) * 1000L;
            long every = Math.max(5, d.optLong("interval", 5)) * 1000L;
            main.post(() -> {
                code.setText(user);
                status.setText("Waiting for you to approve on Trakt…");
            });
            while (active && System.currentTimeMillis() < until) {
                Thread.sleep(every);
                if (Trakt.poll(this, device)) {
                    Picks.get(this).refreshNow();
                    main.post(() -> {
                        status.setText("Signed in. Loading your lists…");
                        main.postDelayed(this::finish, 1500);
                    });
                    return;
                }
            }
            if (active) main.post(() -> status.setText("The code expired. Press OK for a new one."));
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            Logs.w("trakt sign-in: " + why);
            if (active) main.post(() -> status.setText(why + " " + "Press OK to try again."));
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && (worker == null || !worker.isAlive())) {
            stop();
            start();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
