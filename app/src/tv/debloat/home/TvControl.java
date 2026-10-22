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
    /** The TV's own home screens (Google TV's, then older Android TV ones): the same list the installer uses. */
    static final String[] OWN_HOMES = {GOOGLE_HOME, "com.google.android.tvlauncher", "com.google.android.leanbacklauncher"};

    /** The TV's own home screen on this TV (installed, whether on or off), or null. */
    static String ownHome(Context c) {
        for (String p : OWN_HOMES) {
            try {
                c.getPackageManager().getApplicationInfo(p, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                return p;
            } catch (Exception ignored) {
            }
        }
        return null;
    }
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
        adb.run("cmd appops set tv.debloat.home AUTO_START allow");
        String list = adb.run("settings get secure enabled_accessibility_services").trim();
        StringBuilder others = new StringBuilder();
        for (String s : list.split(":")) {
            if (s.isEmpty() || s.equals("null") || s.equals(SERVICE)) continue;
            others.append(others.length() == 0 ? "" : ":").append(s);
        }
        // Off, then on: that's what makes Android bind it again.
        if (others.length() == 0) adb.run("settings delete secure enabled_accessibility_services");
        else adb.run("settings put secure enabled_accessibility_services '" + others + "'");
        adb.run("settings put secure enabled_accessibility_services '" + (others.length() == 0 ? SERVICE : others + ":" + SERVICE) + "'");
        adb.run("settings put secure accessibility_enabled 1");
    }

    /** Is the TV's own home screen turned off (so Home opens Debloat TV)? */
    static boolean debloatIsHome(Context c) {
        String own = ownHome(c);
        if (own == null) return true;   // nothing else to be home
        try {
            return !c.getPackageManager().getApplicationInfo(own, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        } catch (Exception e) {
            return true;
        }
    }

    /** Makes Home open Debloat TV (true) or the TV's own home screen (false). */
    static void setHome(Context c, boolean debloat, Done done) {
        new Thread(() -> {
            String error = null;
            try (Adb adb = Adb.connect(c, 20000)) {
                String own = ownHome(c);
                if (own == null) throw new java.io.IOException("This TV has no other home screen to switch to.");
                boolean googleTv = own.equals(GOOGLE_HOME);
                if (debloat) {
                    if (googleTv && adb.run("dumpsys wifi").contains("cname=" + GOOGLE_SETUP)) {
                        throw new java.io.IOException("The TV's first setup saved a Wi-Fi network: forget it and connect again in Settings → Network, then try once more.");
                    }
                    adb.run("cmd role add-role-holder --user 0 android.app.role.HOME tv.debloat.home");
                    adb.run("pm disable-user --user 0 " + own);
                    if (googleTv) adb.run("pm disable-user --user 0 " + GOOGLE_SETUP);
                } else {
                    if (googleTv) adb.run("pm enable " + GOOGLE_SETUP);
                    adb.run("pm enable " + own);
                    adb.run("cmd role add-role-holder --user 0 android.app.role.HOME " + own);
                }
                // Check it took: the TV's own home screen is off for Debloat TV, on for the TV's.
                boolean ok = adb.run("pm list packages -d " + own).contains("package:" + own) == debloat;
                if (!ok) throw new java.io.IOException("The TV didn't make the change. Try again, or use ./install.sh on a computer.");
            } catch (Exception e) {
                error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
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
