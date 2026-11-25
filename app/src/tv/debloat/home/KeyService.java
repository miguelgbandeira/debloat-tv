package tv.debloat.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Sees the remote's keys before apps do, so the buttons you set work everywhere. Android waits for it on every key,
 * so it lives in a small process of its own and does almost nothing. It also notices which app is in front (for Vpn).
 */
public final class KeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        return Buttons.onKey(this, event);
    }

    private String front;
    private final Map<String, Boolean> activities = new HashMap<>();

    /**
     * A window changed: when it's another app's screen (an activity, not a keyboard, dialog or volume bar), the VPN
     * rules may switch the tunnel. Only the package and class names are read.
     */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null || pkg.toString().equals(front) || !isActivity(pkg.toString(), cls.toString())) return;
        front = pkg.toString();
        Vpn.front(this, front);
    }

    private boolean isActivity(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean known = activities.get(key);
        if (known == null) {
            try {
                getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
                known = true;
            } catch (PackageManager.NameNotFoundException e) {
                known = false;
            }
            activities.put(key, known);
        }
        return known;
    }

    @Override public void onInterrupt() {}
}
