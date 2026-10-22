package tv.debloat.home;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Stremio sign-in on the TV, the way Stremio's own TV app does it: scan the QR code (or open the address) on your
 * phone, sign in there, and this screen closes by itself.
 */
public final class StremioLoginActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView address, status;
    private ImageView qr;
    private volatile boolean active;
    private Thread worker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.rgb(16, 16, 22));
        box.addView(text("Sign in to Stremio", 40, Color.WHITE, true));
        box.addView(text("Scan the code with your phone, or go to", 24, Color.rgb(170, 170, 180), false));
        address = text("…", 34, Color.rgb(139, 92, 246), true);
        box.addView(address);
        qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(16, 16, 16, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(320, 320);
        lp.topMargin = 24;
        lp.bottomMargin = 24;
        box.addView(qr, lp);
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
        t.setPadding(0, 10, 0, 10);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override protected void onResume() {
        super.onResume();
        start();
    }

    @Override protected void onPause() {
        super.onPause();
        stop();
    }

    private void start() {
        active = true;
        worker = new Thread(this::signIn, "stremio-login");
        worker.start();
    }

    private void stop() {
        active = false;
        if (worker != null) worker.interrupt();
    }

    private void signIn() {
        try {
            JSONObject link = Stremio.createLink();
            String code = link.getString("code");
            String url = link.getString("link");
            String qrUrl = link.optString("qrcode", "");
            main.post(() -> {
                address.setText(url.replace("https://", ""));
                status.setText("Waiting for you to sign in on your phone…");
                if (!qrUrl.isEmpty()) Thumbs.get(this).into(qr, qrUrl, 320);
            });
            long until = System.currentTimeMillis() + 5 * 60_000L;
            while (active && System.currentTimeMillis() < until) {
                Thread.sleep(3000);
                if (Stremio.readLink(this, code)) {
                    main.post(() -> {
                        status.setText("Signed in. Loading your library…");
                        main.postDelayed(this::finish, 1200);
                    });
                    return;
                }
            }
            if (active) main.post(() -> status.setText("The code expired. Press OK for a new one."));
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            Logs.w("stremio sign-in: " + why);
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
