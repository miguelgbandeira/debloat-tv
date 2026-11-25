package tv.debloat.home;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before apps do, so the buttons you set work everywhere. Android waits for it on every key,
 * so it lives in a small process of its own and does almost nothing. It also notices which app is in front (for Vpn).
 */
public final class KeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        return Buttons.onKey(this, event);
    }

    private CharSequence front;

    /** The app in front changed: the VPN rules (Vpn) may switch the tunnel. Only the package name is read. */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || pkg == null || pkg.equals(front)) return;
        front = pkg;
        Vpn.forApp(this, pkg);
    }

    @Override public void onInterrupt() {}
}
