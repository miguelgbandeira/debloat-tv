package tv.debloat.home;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the remote's buttons open: an app, a place in Debloat TV ("@settings", "@apps", "@home"), or nothing (the
 * button keeps doing what it did). Kept in a small file because the key service runs in its own process.
 */
final class Buttons {
    private Buttons() {}

    static final String SETTINGS = "@settings", ALL_APPS = "@apps", HOME = "@home";

    // TCL remotes' app buttons (their key codes, read from the TV's input log).
    static final int TCL_NETFLIX = 4062, TCL_YOUTUBE = 190, TCL_YOUTUBE_ALT = 4065, TCL_PRIME = 4099, TCL_CHANNEL = 4020;

    /** A button: what it opens and its name. */
    static final class Button {
        String target, name;

        Button(String target, String name) {
            this.target = target;
            this.name = name;
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "buttons.json");
    }

    /** While this file exists, the next button pressed is written into it instead of being used ("Add a button"). */
    static File learnFile(Context c) {
        return new File(c.getFilesDir(), "learn-button");
    }

    private static Map<Integer, Button> cache;
    private static long cacheStamp = Long.MIN_VALUE;

    /** Every configured button, in the order they were added. */
    static synchronized Map<Integer, Button> all(Context c) {
        File f = file(c);
        long stamp = f.lastModified() * 31 + f.length();
        if (cache == null || stamp != cacheStamp) {
            cacheStamp = stamp;
            cache = new LinkedHashMap<>();
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] data = new byte[(int) f.length()];
                    int n = in.read(data);
                    JSONArray a = new JSONArray(new String(data, 0, Math.max(0, n), StandardCharsets.UTF_8));
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        cache.put(o.getInt("code"), new Button(o.optString("target"), o.optString("name")));
                    }
                } catch (Exception e) {
                    Logs.w("buttons: " + e.getMessage());
                }
            }
        }
        return new LinkedHashMap<>(cache);
    }

    static synchronized void set(Context c, int code, String target, String name) {
        Map<Integer, Button> all = all(c);
        if (target == null) all.remove(code);
        else all.put(code, new Button(target, name));
        JSONArray a = new JSONArray();
        try {
            for (Map.Entry<Integer, Button> e : all.entrySet()) {
                a.put(new JSONObject().put("code", e.getKey()).put("target", e.getValue().target).put("name", e.getValue().name));
            }
            File tmp = new File(c.getFilesDir(), "buttons.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file(c))) Logs.w("buttons: couldn't save");
        } catch (Exception e) {
            Logs.w("buttons: " + e.getMessage());
        }
    }

    /** First run: the remote's gear opens the Settings menu, and on TCL the app buttons open their apps. */
    static void seedDefaults(Context c) {
        if (file(c).exists()) return;
        set(c, KeyEvent.KEYCODE_NOTIFICATION, SETTINGS, "Settings (gear) button");
        if ("TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND)) {
            set(c, TCL_NETFLIX, "com.netflix.ninja", "Netflix button");
            set(c, TCL_YOUTUBE, "com.google.android.youtube.tv", "YouTube button");
            set(c, TCL_PRIME, "com.amazon.amazonvideo.livingroom", "Prime Video button");
        }
    }

    /** Buttons that can't be given an app: moving around, OK, Back, Home, Menu, volume and power. */
    static boolean learnable(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_TV_POWER: case KeyEvent.KEYCODE_UNKNOWN:
                return false;
            default:
                return true;
        }
    }

    /** A readable name for a button that was just learned. */
    static String name(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_PROG_RED: return "Red button";
            case KeyEvent.KEYCODE_PROG_GREEN: return "Green button";
            case KeyEvent.KEYCODE_PROG_YELLOW: return "Yellow button";
            case KeyEvent.KEYCODE_PROG_BLUE: return "Blue button";
            case KeyEvent.KEYCODE_SETTINGS: case KeyEvent.KEYCODE_NOTIFICATION: return "Settings (gear) button";
            case KeyEvent.KEYCODE_INFO: return "Info button";
            case KeyEvent.KEYCODE_GUIDE: return "Guide button";
            case KeyEvent.KEYCODE_TV: return "TV button";
            case KeyEvent.KEYCODE_TV_INPUT: return "Input button";
            case KeyEvent.KEYCODE_CHANNEL_UP: return "Channel up";
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return "Channel down";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "Play/Pause button";
            case KeyEvent.KEYCODE_SEARCH: return "Search button";
            case TCL_NETFLIX: return "Netflix button";
            case TCL_YOUTUBE: case TCL_YOUTUBE_ALT: return "YouTube button";
            case TCL_PRIME: return "Prime Video button";
            case TCL_CHANNEL: return "TCL Channel button";
            case 4056: return "123 button";
            case 4184: return "TCL TV button";
            default:
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return "Number " + (code - KeyEvent.KEYCODE_0);
                String raw = KeyEvent.keyCodeToString(code);
                if (raw.startsWith("KEYCODE_")) {
                    String w = raw.substring(8).replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
                    return Character.toUpperCase(w.charAt(0)) + w.substring(1) + " button";
                }
                return "Button " + code;
        }
    }

    /** Opens a target: an app, or one of Debloat TV's places. */
    static boolean launch(Context c, String target) {
        if (target.startsWith("@")) {
            c.startActivity(new Intent(c, HomeActivity.class).putExtra(HomeActivity.OPEN, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        Intent i = Apps.launchIntent(c.getPackageManager(), target);
        if (i == null) return false;
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    /** From the key service: true when the key was ours (it then goes no further). */
    static boolean onKey(Context c, KeyEvent e) {
        int code = e.getKeyCode() == TCL_YOUTUBE_ALT ? TCL_YOUTUBE : e.getKeyCode();
        File learn = learnFile(c);
        if (learn.exists() && learnable(code)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                try (FileOutputStream out = new FileOutputStream(learn)) {
                    out.write(String.valueOf(code).getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
            return true;
        }
        Button b = all(c).get(code);
        if (b == null || b.target.isEmpty()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) launch(c, b.target);
        return true;
    }
}
