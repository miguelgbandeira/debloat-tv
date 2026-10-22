package tv.debloat.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/** The home screen (Android's Home). */
public final class HomeActivity extends Activity {
    /** Extra: a place to open (from a remote button): Buttons.SETTINGS, ALL_APPS or HOME. */
    static final String OPEN = "open";

    private HomeView home;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        home = new HomeView(this);
        setContentView(home);
        String open = getIntent().getStringExtra(OPEN);
        if (open != null) home.post(() -> home.openPlace(open));
    }

    @Override protected void onResume() {
        super.onResume();
        home.resume();
        TvControl.repairAfterBoot(this);   // remote buttons back on after a restart (needs USB debugging)
    }

    @Override protected void onPause() {
        home.pause();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String open = intent.getStringExtra(OPEN);
        if (open != null) home.openPlace(open);
        else home.reset(true);   // Home pressed on the home screen: back to the top
    }

    @Override protected void onDestroy() {
        home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Back on the home screen does nothing (there's nothing behind it). */
    @Override public void onBackPressed() {}
}
