package tv.debloat.home;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Turns a WireGuard tunnel on when certain apps come to the front, and off for others: e.g. Tivimate through a VPN,
 * Stremio without. Uses the official WireGuard app's remote control (its "Allow remote control apps" setting, and the
 * CONTROL_TUNNELS permission the installer grants). Apps in neither list leave the tunnel as it is.
 * Kept in a small file because the key service, which sees the front app change, runs in its own process.
 */
final class Vpn {
    private Vpn() {}

    static final String TUNNEL = "vpn_tunnel", ON_APPS = "vpn_on_apps", OFF_APPS = "vpn_off_apps";
    private static final String WIREGUARD = "com.wireguard.android";

    private static File file(Context c) {
        return new File(c.getFilesDir(), "vpn.txt");
    }

    /** From the installer: tunnel name, then the apps (comma-separated packages) that turn it on, then off. */
    static void save(Context c, String tunnel, String on, String off) {
        String text = clean(tunnel) + "\n" + clean(on) + "\n" + clean(off) + "\n";
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
    private static Set<String> on = new HashSet<>(), off = new HashSet<>();
    private static long stamp = Long.MIN_VALUE;

    private static synchronized void load(Context c) {
        File f = file(c);
        long s = f.lastModified() * 31 + f.length();
        if (s == stamp) return;
        stamp = s;
        tunnel = "";
        on = new HashSet<>();
        off = new HashSet<>();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int n = in.read(data);
            String[] lines = new String(data, 0, Math.max(0, n), StandardCharsets.UTF_8).split("\n", -1);
            tunnel = lines.length > 0 ? lines[0] : "";
            if (lines.length > 1 && !lines[1].isEmpty()) on = new HashSet<>(Arrays.asList(lines[1].split(",")));
            if (lines.length > 2 && !lines[2].isEmpty()) off = new HashSet<>(Arrays.asList(lines[2].split(",")));
        } catch (Exception e) {
            Logs.w("vpn: " + e.getMessage());
        }
    }

    /** An app is opening or came to the front: switch the tunnel if that app has a rule. */
    static synchronized void forApp(Context c, CharSequence pkg) {
        if (pkg == null) return;
        load(c);
        if (tunnel.isEmpty()) return;
        String p = pkg.toString();
        Boolean want = on.contains(p) ? Boolean.TRUE : off.contains(p) ? Boolean.FALSE : null;
        if (want == null) return;
        String action = want ? "com.wireguard.android.action.SET_TUNNEL_UP" : "com.wireguard.android.action.SET_TUNNEL_DOWN";
        try {
            c.sendBroadcast(new Intent(action).setPackage(WIREGUARD).putExtra("tunnel", tunnel));
            Logs.w("vpn " + tunnel + (want ? " on" : " off") + " for " + p);
        } catch (RuntimeException e) {
            Logs.w("vpn: " + e.getMessage());
        }
    }
}
