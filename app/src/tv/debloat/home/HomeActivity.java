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
    // Coming back from an app (onRestart, then the Home intent): stay where you were. Home pressed on the home screen
    // itself (only a brief onPause before the intent): back to the top.
    private boolean returning;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        home = new HomeView(this);
        setContentView(home);
        String open = getIntent().getStringExtra(OPEN);
        if (open != null) home.post(() -> home.openPlace(open));
    }

    @Override protected void onResume() {
        super.onResume();
        returning = false;
        home.resume();
        TvControl.repairAfterBoot(this);   // remote buttons back on after a restart (needs USB debugging)
    }

    @Override protected void onRestart() {
        super.onRestart();
        returning = true;
    }

    @Override protected void onPause() {
        home.pause();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String open = intent.getStringExtra(OPEN);
        if (open != null) home.openPlace(open);
        else if (!returning) home.reset(true);   // Home pressed on the home screen: back to the top (from an app: where you were)
    }

    @Override protected void onDestroy() {
        home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (home.onKey(event)) return true;
        // Back on the home screen itself does nothing: there's nothing behind it.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(event);
    }
}
