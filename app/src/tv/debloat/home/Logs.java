package tv.debloat.home;

import android.util.Log;

/** One tag for everything the app logs ("adb logcat -s DebloatTV"). */
final class Logs {
    private Logs() {}

    static void w(String message) {
        Log.i("DebloatTV", message);
    }
}
