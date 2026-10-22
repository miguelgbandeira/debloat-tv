package tv.debloat.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The TV's apps and inputs: listing them, their cards, and opening them. */
final class Apps {
    private Apps() {}

    static final int APP = 0, LIVE_TV = 1, INPUT = 2, TV_SETTINGS = 3;

    /** Something that opens: an app (maybe on a page of it), Live TV, an input or the TV's settings. */
    static final class Item {
        int kind;
        String key, label, pkg, uri, inputId, icon;
        boolean connected = true;
    }

    static Item app(String pkg, String label) {
        return app(pkg, label, null);
    }

    static Item app(String pkg, String label, String uri) {
        Item it = new Item();
        it.kind = APP;
        it.key = pkg;
        it.pkg = pkg;
        it.label = label;
        it.uri = uri;
        return it;
    }

    static Item action(int kind, String key, String label, String icon) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.icon = icon;
        return it;
    }

    /** Every app with a TV (or, for apps you installed, a phone) launcher entry: {package, name}. */
    static List<String[]> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(c.getPackageName());
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (seen.add(ri.activityInfo.packageName)) out.add(new String[] {ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm))});
        }
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!system && seen.add(ri.activityInfo.packageName)) out.add(new String[] {ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm))});
        }
        return out;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    static boolean installed(Context c, String pkg) {
        return launchIntent(c.getPackageManager(), pkg) != null;
    }

    // MARK: - Cards (16:9: the app's TV banner, or its icon on a dark card)

    private static final int CARD_W = 480, CARD_H = 270;
    private static final Map<String, Bitmap> CARDS = new HashMap<>();

    /** The card if it was already made (no work on the caller's thread), or null. */
    static synchronized Bitmap cachedCard(String pkg) {
        return CARDS.get(pkg);
    }

    /** The app's card (made once, then kept). Slow the first time: call it off the main thread. */
    static synchronized Bitmap card(Context c, String pkg) {
        Bitmap ready = CARDS.get(pkg);
        if (ready != null) return ready;
        PackageManager pm = c.getPackageManager();
        Bitmap b = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Drawable banner = null;
        try {
            banner = pm.getApplicationBanner(pkg);
            if (banner == null) {
                Intent tv = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (tv != null) banner = pm.getActivityBanner(tv);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (banner != null) {
            banner.setBounds(0, 0, CARD_W, CARD_H);
            banner.draw(canvas);
        } else {
            Paint bg = new Paint();
            bg.setShader(new LinearGradient(0, 0, 0, CARD_H, 0xFF2C2D34, 0xFF1C1D22, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, CARD_W, CARD_H, bg);
            try {
                Drawable icon = pm.getApplicationIcon(pkg);
                int s = 132, x = (CARD_W - s) / 2, y = 44;
                icon.setBounds(x, y, x + s, y + s);
                icon.draw(canvas);
                Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
                label.setColor(0xE6FFFFFF);
                label.setTextSize(28);
                label.setTypeface(Fonts.medium);
                label.setTextAlign(Paint.Align.CENTER);
                String name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                Rect r = new Rect();
                label.getTextBounds(name, 0, name.length(), r);
                canvas.drawText(name, CARD_W / 2f, 230, label);
            } catch (PackageManager.NameNotFoundException ignored) {
                canvas.drawColor(Color.DKGRAY);
            }
        }
        b.setHasMipMap(true);
        CARDS.put(pkg, b);
        return b;
    }

    // MARK: - Inputs and Live TV

    /** The HDMI and other external inputs (hardware passthrough inputs), the ones with nothing connected marked. */
    static List<Item> inputs(Context c) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.isHidden(c)) continue;
            Item it = new Item();
            it.kind = INPUT;
            it.inputId = info.getId();
            it.key = "input:" + info.getId();
            CharSequence custom = info.loadCustomLabel(c);
            it.label = String.valueOf(custom != null ? custom : info.loadLabel(c));
            it.icon = "input";
            try {
                it.connected = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            } catch (RuntimeException e) {
                it.connected = true;
            }
            out.add(it);
        }
        return out;
    }

    /** Live TV: the app chosen in settings (an IPTV app, say), or none. */
    static Intent liveTv(Context c) {
        String pkg = Store.get(c, Store.LIVE_TV, "");
        return pkg.isEmpty() ? null : launchIntent(c.getPackageManager(), pkg);
    }

    // MARK: - Opening

    /** Opens the item, growing out of the view it was picked from. False if nothing could be opened. */
    static boolean open(Activity a, Item it, View from) {
        Intent intent = null, plain = null;
        switch (it.kind) {
            case APP:
                plain = launchIntent(a.getPackageManager(), it.pkg);
                intent = plain;
                if (plain != null && it.uri != null) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(it.uri)).setPackage(it.pkg);
                }
                break;
            case LIVE_TV:
                intent = liveTv(a);
                break;
            case INPUT:
                intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
                break;
            case TV_SETTINGS:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            default:
                break;
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (from != null && from.getWidth() > 0) {
                a.startActivity(intent, ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle());
            } else {
                a.startActivity(intent);
            }
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Logs.w("couldn't open " + it.label + ": " + e.getMessage());
            if (plain == null || plain == intent) return false;
            try {
                a.startActivity(plain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException e2) {
                return false;
            }
        }
    }
}
