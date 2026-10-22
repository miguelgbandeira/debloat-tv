package tv.debloat.home;

import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Holding OK on an app or a row: Favorites, move, hide, the app's settings and uninstall; a row's own options. */
final class Menus {
    private final HomeView home;

    Menus(HomeView home) {
        this.home = home;
    }

    /** Hold OK (or Menu) on an app in a row: Move, Move to another row, Remove from row. */
    void appMenu(View card, int row) {
        Object app = card.getTag(HomeView.TAG_APP);
        if (!(app instanceof String[]) || row < 0 || row >= home.layout.rows().size()) return;
        String[] a = (String[]) app;
        moveTarget = card;
        home.openSheet(b -> appPage(b, a[0], a[1], row));
    }
    View moveTarget;   // the card "Move" lifts once the home.sheet closes
    void appPage(Sheet.Builder b, String pkg, String label, int row) {
        HomeLayout.Row r = home.layout.rows().get(row);
        boolean inRow = r.isApps();
        boolean tile = HomeLayout.ALL_APPS_TILE.equals(pkg);
        if (tile) b.icon(home.symbol("apps"), true);
        else b.icon(Apps.card(home.activity, pkg), false);
        b.title(label).subtitle(inRow ? T.f("In “{row}”", "row", r.title()) : r.title());
        if (!tile) {
            int fav = home.layout.favorites();
            if (fav >= 0) {
                String favName = home.layout.rows().get(fav).title();
                if (home.layout.rows().get(fav).apps.contains(pkg)) {
                    b.item(T.f("Remove from {row}", "row", favName), null, false, () -> { home.layout.removeApp(fav, pkg); home.sheet.close(); });
                } else {
                    b.item(T.f("Add to {row}", "row", favName), null, false, () -> { home.layout.moveAppTo(pkg, -1, fav); home.sheet.close(); });
                }
            }
        }
        if (inRow) b.item("Move", "◀ ▶", false, () -> { startMoveAfterSheet = true; home.sheet.close(); });
        b.item("Move to another row", null, true, () -> home.sheet.push(m -> moveAppPage(m, pkg, label, inRow ? row : -1)));
        if (inRow && !tile) b.item("Also add to another row", null, true, () -> home.sheet.push(m -> moveAppPage(m, pkg, label, -1)));
        if (inRow && (tile || row != home.layout.favorites())) {
            b.item("Remove from this row", null, false, () -> { home.layout.removeApp(row, pkg); home.sheet.close(); });
        }
        if (!tile) {
            b.item("Hide app", null, false, () -> { home.layout.hideApp(pkg); home.sheet.close(); });
            appSystemItems(b, pkg);
        }
    }
    boolean startMoveAfterSheet;
    /** The app's own Android pages: App home.settings (storage, permissions, force stop) and Uninstall. */
    void appSystemItems(Sheet.Builder b, String pkg) {
        b.header("App");
        b.item("App settings", null, true, () -> {
            home.sheet.close();
            settingsFor(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        });
        int flags = 0;
        try {
            flags = home.activity.getPackageManager().getApplicationInfo(pkg, 0).flags;
        } catch (Exception ignored) {
        }
        boolean system = (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        // Apps you installed can be uninstalled; apps that came with the TV only lose their updates.
        if (!system || updated) {
            b.item(system ? "Uninstall updates" : "Uninstall", null, true, () -> {
                home.sheet.close();
                try {
                    home.activity.startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    settingsFor(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
                }
            });
        }
    }
    void settingsFor(String action, String pkg) {
        try {
            home.activity.startActivity(new Intent(action, android.net.Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            home.settings(android.provider.Settings.ACTION_SETTINGS);
        }
    }
    /** Hold OK on an app in All Apps. */
    void gridAppPage(Sheet.Builder b, String pkg, String label) {
        b.icon(Apps.card(home.activity, pkg), false).title(label).subtitle("All Apps");
        int fav = home.layout.favorites();
        if (fav >= 0) {
            String favName = home.layout.rows().get(fav).title();
            if (home.layout.rows().get(fav).apps.contains(pkg)) {
                b.item(T.f("Remove from {row}", "row", favName), null, false, () -> { home.layout.removeApp(fav, pkg); home.sheet.close(); });
            } else {
                b.item(T.f("Add to {row}", "row", favName), null, false, () -> { home.layout.moveAppTo(pkg, -1, fav); home.sheet.close(); });
            }
        }
        b.item("Add to a row", null, true, () -> home.sheet.push(m -> moveAppPage(m, pkg, label, -1)));
        b.item("Hide app", null, false, () -> { home.layout.hideApp(pkg); gridChanged = true; home.sheet.close(); });
        appSystemItems(b, pkg);
    }
    boolean gridChanged;   // an app was hidden from All Apps: the grid is made again when the page closes
    /** ⚙ on a row: rename, hide, delete. */
    void rowSettingsPage(Sheet.Builder b, int row) {
        if (row >= home.layout.rows().size()) { home.sheet.back(); return; }
        HomeLayout.Row r = home.layout.rows().get(row);
        b.icon(home.symbol("settings"), true).title(r.title())
                .subtitle("To move this row: select \u21c5 next to the gear, press OK to lift it, then Up or Down.");
        if (r.isApps()) {
            b.item("Rename", r.name, true, () -> home.sheet.push(n -> namePage(n, "Rename row", r.name,
                    name -> { home.layout.rename(row, name); home.sheet.back(); })));
        }
        if (r.isApps() && !home.layout.hasAllAppsTile()) {
            b.item("Add the All Apps tile here", null, false, () -> {
                home.layout.moveAppTo(HomeLayout.ALL_APPS_TILE, -1, row);
                home.sheet.close();
            });
        }
        b.item("Hide this row", null, false, () -> { home.layout.setHidden(row, true); home.sheet.close(); });
        if (r.isApps()) b.item("Delete this row", null, false, () -> { home.layout.delete(row); home.sheet.close(); });
        b.item("Add a new app row", null, true, () -> home.sheet.push(n -> namePage(n, "New row", "",
                name -> { home.layout.addAppsRow(name, row); home.sheet.close(); })));
        b.note("Hidden home.rows can be shown again in Settings \u2192 Home settings \u2192 Rows.");
    }
    void moveAppPage(Sheet.Builder b, String pkg, String label, int from) {
        if (HomeLayout.ALL_APPS_TILE.equals(pkg)) b.icon(home.symbol("apps"), true);
        else b.icon(Apps.card(home.activity, pkg), false);
        b.title(label).subtitle("Move to");
        List<HomeLayout.Row> all = home.layout.rows();
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).isApps() || i == from) continue;
            int to = i;
            b.item(all.get(i).title(), all.get(i).hidden ? "Hidden" : null, false,
                    () -> { home.layout.moveAppTo(pkg, from, to); home.sheet.close(); });
        }
        b.item("A new row", null, true, () -> home.sheet.push(n -> namePage(n, "New row", "", name -> {
            int to = home.layout.addAppsRow(name, -1);
            home.layout.moveAppTo(pkg, from, to);
            home.sheet.close();
        })));
    }
    /** Every row in order: OK shows or hides it, ← → move it. */
    void customizePage(Sheet.Builder b) {
        b.icon(home.symbol("apps"), true).title("Rows")
                .subtitle("Choose which home.rows you see and in what order.");
        b.heading("Rows");
        List<HomeLayout.Row> all = home.layout.rows();
        for (int i = 0; i < all.size(); i++) {
            int row = i;
            HomeLayout.Row r = all.get(i);
            String key = "row:" + System.identityHashCode(r);
            b.row(key, r.title(), r.hidden ? "Hidden" : "#" + (shownBefore(row) + 1), r.hidden,
                    () -> { home.layout.setHidden(row, !r.hidden); home.sheet.refresh(key); },
                    () -> { home.layout.moveRow(row, -1); home.sheet.refresh(key); },
                    () -> { home.layout.moveRow(row, 1); home.sheet.refresh(key); });
        }
        b.hint("OK shows or hides a row. \u2190 \u2192 change the order.");
    }
    /** The row's place among the shown rows (for "#1", "#2"…). */
    int shownBefore(int row) {
        int n = 0;
        for (int i = 0; i < row; i++) if (!home.layout.rows().get(i).hidden) n++;
        return n;
    }
    void hiddenAppsPage(Sheet.Builder b) {
        b.icon(home.symbol("apps"), true).title("Hidden apps").subtitle("Apps you hid from the home screen and All Apps.");
        b.heading("Hidden apps");
        List<String[]> installed = Apps.all(home.activity);
        for (String pkg : new ArrayList<>(home.layout.hiddenApps())) {
            String l = HomeView.label(installed, pkg);
            b.item(l == null ? pkg : l, "Hidden", false, () -> { home.layout.showApp(pkg); home.sheet.refresh(); });
        }
        b.hint(home.layout.hiddenApps().isEmpty() ? "No hidden apps. In All Apps, hold OK on an app to hide it."
                : "OK shows the app again.");
    }
    void namePage(Sheet.Builder b, String title, String current, Sheet.Text done) {
        b.icon(home.symbol("apps"), true).title(title).subtitle("Type the name with the remote's keyboard.");
        b.input("Name", current, "Save", done);
    }
}
