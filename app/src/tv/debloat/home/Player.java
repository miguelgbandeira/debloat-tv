package tv.debloat.home;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The app titles open in: Stremio or Nuvio (Nuvio+ IPTV too), chosen in Home settings when both are on the TV.
 * Titles carry Stremio links (stremio:///detail/movie/tt…); for Nuvio they become its own (nuvio://movie/tt…), which
 * open the title's page. Nuvio has no link for one episode, so an episode opens its series.
 * Only where titles open: Continue watching and Library still come from your Stremio account.
 */
final class Player {
    private Player() {}

    static final String KEY = "player";
    /** Nuvio+ IPTV, then Nuvio's own builds. */
    private static final String[] NUVIO = {"com.nuvio.livetv", "com.nuvio.tv", "com.nuvio.app"};

    private static String nuvio(Context c) {
        for (String pkg : NUVIO) if (Apps.installed(c, pkg)) return pkg;
        return null;
    }

    /** The apps on this TV that can open titles: {package, name}. */
    static List<String[]> choices(Context c) {
        List<String[]> out = new ArrayList<>();
        List<String[]> all = Apps.all(c);
        if (Apps.installed(c, Stremio.PKG)) out.add(new String[] {Stremio.PKG, "Stremio"});
        String n = nuvio(c);
        if (n != null) {
            String label = HomeView.label(all, n);
            out.add(new String[] {n, label != null ? label : "Nuvio"});
        }
        return out;
    }

    /** The chosen app if it's still here, otherwise whichever is; null with neither. */
    static String chosen(Context c) {
        String saved = Store.get(c, KEY, "");   // a package, or "stremio" / "nuvio" from the installer
        if (saved.equals("stremio")) saved = Stremio.PKG;
        if (saved.equals("nuvio")) saved = String.valueOf(nuvio(c));
        if (!saved.isEmpty() && Apps.installed(c, saved)) return saved;
        if (Apps.installed(c, Stremio.PKG)) return Stremio.PKG;
        return nuvio(c);
    }

    static boolean any(Context c) {
        return chosen(c) != null;
    }

    static String name(Context c) {
        String pkg = chosen(c);
        for (String[] p : choices(c)) if (p[0].equals(pkg)) return p[1];
        return "Stremio";
    }

    /** What a title card opens: a Stremio link goes to the chosen app, in its own form. */
    static Apps.Item item(Context c, String pkg, String label, String link) {
        if (!Stremio.PKG.equals(pkg) || link == null || !link.startsWith("stremio:///detail/")) return Apps.item(pkg, label, link);
        String app = chosen(c);
        if (app == null || app.equals(Stremio.PKG)) return Apps.item(Stremio.PKG, label, link);
        String[] parts = link.substring("stremio:///detail/".length()).split("/");   // type, id[, episode]
        if (parts.length < 2) return Apps.item(app, label, null);
        return Apps.item(app, label, "nuvio://" + parts[0] + "/" + parts[1]);
    }
}
