package tv.debloat.home;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * The few system changes Debloat TV makes from the TV itself, through its own adb connection (Adb):
 *  - remote buttons: TVs like TCL stop the key service at every restart; it's turned back on;
 *  - the Home button: this home screen, or the TV's own (its home app and setup app turned back on).
 * Without USB debugging on, none of this runs and the settings page says so.
 */
final class TvControl {
    private TvControl() {}

    static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx", GOOGLE_SETUP = "com.google.android.tungsten.setupwraith";
    private static final String SERVICE = "tv.debloat.home/tv.debloat.home.KeyService";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    /** Was the TV's adb reachable at the last check (USB debugging on)? */
    static volatile boolean adbOn = true;

    interface Done { void on(String error); }   // null = it worked

    /** Is the remote-button service running (enabled and bound)? */
    static boolean buttonsRunning(Context c) {
        AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> on = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo i : on) if (SERVICE.equals(i.getId())) return true;
        return false;
    }

    /** After a restart: if the remote buttons stopped, turn them back on (quietly; nothing happens without adb). */
    static void repairAfterBoot(Context c) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            adbOn = Adb.available();   // also tells the settings page whether USB debugging is on
            if (buttonsRunning(c) || !adbOn) {
                busy = false;
                return;
            }
            try (Adb adb = Adb.connect(c, 20000)) {
                startButtons(adb);
                Logs.w("remote buttons turned back on");
            } catch (Exception e) {
                Logs.w("repair: " + e.getMessage());
            } finally {
                busy = false;
            }
        }, "repair").start();
    }

    /** Allows the app to start at boot (TCL), and re-binds the key service (other accessibility services stay). */
    private static void startButtons(Adb adb) throws java.io.IOException {
        adb.shell("cmd appops set tv.debloat.home AUTO_START allow");
        String list = adb.shell("settings get secure enabled_accessibility_services").trim();
        StringBuilder others = new StringBuilder();
        for (String s : list.split(":")) {
            if (s.isEmpty() || s.equals("null") || s.equals(SERVICE)) continue;
            others.append(others.length() == 0 ? "" : ":").append(s);
        }
        // Off, then on: that's what makes Android bind it again.
        if (others.length() == 0) adb.shell("settings delete secure enabled_accessibility_services");
        else adb.shell("settings put secure enabled_accessibility_services '" + others + "'");
        adb.shell("settings put secure enabled_accessibility_services '" + (others.length() == 0 ? SERVICE : others + ":" + SERVICE) + "'");
        adb.shell("settings put secure accessibility_enabled 1");
    }

    /** Is the TV's own home screen turned off (so Home opens Debloat TV)? */
    static boolean debloatIsHome(Context c) {
        try {
            return !c.getPackageManager().getApplicationInfo(GOOGLE_HOME, 0).enabled;
        } catch (Exception e) {
            return true;   // no Google TV home on this TV
        }
    }

    /** Makes Home open Debloat TV (true) or the TV's own home screen (false). */
    static void setHome(Context c, boolean debloat, Done done) {
        new Thread(() -> {
            String error = null;
            try (Adb adb = Adb.connect(c, 20000)) {
                if (debloat) {
                    if (adb.shell("dumpsys wifi").contains("cname=" + GOOGLE_SETUP)) {
                        throw new java.io.IOException("The TV's first setup saved a Wi-Fi network: forget it and connect again in Settings → Network, then try once more.");
                    }
                    adb.shell("cmd role add-role-holder --user 0 android.app.role.HOME tv.debloat.home");
                    adb.shell("pm disable-user --user 0 " + GOOGLE_HOME);
                    adb.shell("pm disable-user --user 0 " + GOOGLE_SETUP);
                } else {
                    adb.shell("pm enable " + GOOGLE_SETUP);
                    adb.shell("pm enable " + GOOGLE_HOME);
                    adb.shell("cmd role add-role-holder --user 0 android.app.role.HOME " + GOOGLE_HOME);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String result = error;
            MAIN.post(() -> done.on(result));
        }, "home").start();
    }

    /** Asks for "Allow USB debugging?" now (from setup), so later repairs work without the prompt. */
    static void authorize(Context c, Done done) {
        new Thread(() -> {
            String error = null;
            try (Adb adb = Adb.connect(c, 60000)) {
                startButtons(adb);
            } catch (Exception e) {
                error = adbOn ? "The TV didn't allow the connection." : "USB debugging is off.";
            }
            String result = error;
            MAIN.post(() -> done.on(result));
        }, "authorize").start();
    }
}
