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

    private static void load(Context c) {
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

    private static void send(Context c, boolean on, String pkg) {
        String action = on ? "com.wireguard.android.action.SET_TUNNEL_UP" : "com.wireguard.android.action.SET_TUNNEL_DOWN";
        try {
            c.sendBroadcast(new Intent(action).setPackage(WIREGUARD).putExtra("tunnel", tunnel));
            Logs.w("vpn " + tunnel + (on ? " on" : " off") + " (" + pkg + " in front)");
        } catch (RuntimeException e) {
            Logs.w("vpn: " + e.getMessage());
        }
    }
}
