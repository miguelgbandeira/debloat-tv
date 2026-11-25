package tv.debloat.home;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * A WireGuard tunnel that's on while certain apps are in front (e.g. IPTV apps) and off once you leave them. It only
 * turns off a tunnel it turned on itself, so a VPN you switch on by hand in WireGuard stays as you left it.
 * Uses the official WireGuard app's remote control (its Settings → Advanced → "Allow remote control apps", and the
 * CONTROL_TUNNELS permission the installer grants). Called from the key service, which sees the front app change.
 */
final class Vpn {
    private Vpn() {}

    static final String TUNNEL = "vpn_tunnel", APPS = "vpn_apps";
    private static final String WIREGUARD = "com.wireguard.android";
    /** Leaving for a moment (Home and straight back) doesn't drop the tunnel. */
    private static final long OFF_DELAY_MS = 4000;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "vpn.txt");
    }

    /** While this file exists, the tunnel is on because of us (kept on disk in case the service restarts). */
    private static File ours(Context c) {
        return new File(c.getFilesDir(), "vpn-ours");
    }

    /** From the installer: the tunnel's name in WireGuard and the apps (comma-separated packages) that use it. */
    static void save(Context c, String tunnel, String apps) {
        String text = clean(tunnel) + "\n" + clean(apps) + "\n";
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logs.w("vpn save: " + e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String tunnel = "";
    private static Set<String> apps = new HashSet<>();
    private static long stamp = Long.MIN_VALUE;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingOff;

    private static synchronized void load(Context c) {
        File f = file(c);
        long s = f.lastModified() * 31 + f.length();
        if (s == stamp) return;
        stamp = s;
        tunnel = "";
        apps = new HashSet<>();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int n = in.read(data);
            String[] lines = new String(data, 0, Math.max(0, n), StandardCharsets.UTF_8).split("\n", -1);
            tunnel = lines.length > 0 ? lines[0] : "";
            if (lines.length > 1 && !lines[1].isEmpty()) apps = new HashSet<>(Arrays.asList(lines[1].split(",")));
        } catch (Exception e) {
            Logs.w("vpn: " + e.getMessage());
        }
    }

    /** A different app came to the front (main thread). */
    static void front(Context c, String pkg) {
        load(c);
        if (tunnel.isEmpty()) return;
        if (apps.contains(pkg)) {
            if (pendingOff != null) MAIN.removeCallbacks(pendingOff);
            pendingOff = null;
            send(c, true, pkg);   // every time: it may have been switched off by hand meanwhile
            try {
                ours(c).createNewFile();
            } catch (Exception e) {
                Logs.w("vpn: " + e.getMessage());
            }
        } else if (ours(c).exists() && pendingOff == null) {
            final Context app = c.getApplicationContext();
            pendingOff = () -> {
                pendingOff = null;
                send(app, false, pkg);
                ours(app).delete();
            };
            MAIN.postDelayed(pendingOff, OFF_DELAY_MS);
        }
    }

    /** Is a tunnel set up, the WireGuard app here, and its rights not yet given back since this restart? */
    static boolean needsWireGuardRepair(Context c) {
        load(c);
        if (tunnel.isEmpty()) return false;
        try {
            c.getPackageManager().getPackageInfo(WIREGUARD, 0);
        } catch (Exception e) {
            return false;
        }
        return !String.valueOf(bootCount(c)).equals(readRepaired(c));
    }

    /**
     * TCL TVs take back, at every restart, WireGuard's consent to run a VPN and its right to start in the background:
     * without them a tunnel request does nothing. Given back once per restart (the installer did it the first time).
     */
    static void repairWireGuard(Context c, Adb adb) throws java.io.IOException {
        adb.run("cmd appops set " + WIREGUARD + " ACTIVATE_VPN allow");
        adb.run("cmd appops set " + WIREGUARD + " AUTO_START allow");
        adb.run("cmd appops set " + WIREGUARD + " RUN_ANY_IN_BACKGROUND allow");
        adb.run("cmd deviceidle whitelist +" + WIREGUARD);
        try (FileOutputStream out = new FileOutputStream(repairedFile(c))) {
            out.write(String.valueOf(bootCount(c)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logs.w("vpn repair: " + e.getMessage());
        }
        Logs.w("vpn: WireGuard allowed again after the restart");
    }

    private static int bootCount(Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
    }

    private static File repairedFile(Context c) {
        return new File(c.getFilesDir(), "vpn-repaired-boot");
    }

    private static String readRepaired(Context c) {
        File f = repairedFile(c);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[32];
            int n = in.read(data);
            return new String(data, 0, Math.max(0, n), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void send(Context c, boolean on, String pkg) {
        String action = on ? "com.wireguard.android.action.SET_TUNNEL_UP" : "com.wireguard.android.action.SET_TUNNEL_DOWN";
        boolean was = active(c);
        try {
            c.sendBroadcast(new Intent(action).setPackage(WIREGUARD).putExtra("tunnel", tunnel));
            Logs.w("vpn " + tunnel + (on ? " on" : " off") + " (" + pkg + " in front)");
        } catch (RuntimeException e) {
            Logs.w("vpn: " + e.getMessage());
        }
        if (on != was) announce(c.getApplicationContext(), on, ++generation);
    }

    // MARK: - "VPN on: Singapore" / "VPN off", once the change has really happened

    private static final long CHECK_EVERY_MS = 500, GIVE_UP_MS = 12000;
    private static int generation;   // a newer request stops the checks of an older one

    /** Is any VPN carrying the TV's traffic? */
    private static boolean active(Context c) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private static void announce(Context c, boolean on, int gen) {
        final long start = android.os.SystemClock.uptimeMillis();
        Runnable check = new Runnable() {
            @Override public void run() {
                if (gen != generation) return;
                if (active(c) == on) {
                    notice(c, on ? "VPN on: " + place() : "VPN off", on);
                } else if (android.os.SystemClock.uptimeMillis() - start > GIVE_UP_MS) {
                    notice(c, on ? "VPN didn't connect (" + place() + ")" : "VPN still on", !on);
                } else {
                    MAIN.postDelayed(this, CHECK_EVERY_MS);
                }
            }
        };
        MAIN.postDelayed(check, CHECK_EVERY_MS);
    }

    /** The tunnel's name as a place: "singapore" → "Singapore". */
    private static String place() {
        return tunnel.isEmpty() ? tunnel : Character.toUpperCase(tunnel.charAt(0)) + tunnel.substring(1);
    }

    /** Top right over the open app (Notice); a plain toast if the key service isn't there to draw it. */
    private static void notice(Context c, String text, boolean vpnOn) {
        Logs.w(text);
        if (!Notice.show(text, vpnOn)) android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_SHORT).show();
    }
}
