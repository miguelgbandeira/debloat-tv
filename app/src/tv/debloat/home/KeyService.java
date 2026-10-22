package tv.debloat.home;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before apps do, so the buttons you set work everywhere. Android waits for it on every key,
 * so it lives in a small process of its own and does almost nothing.
 */
public final class KeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        return Buttons.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
