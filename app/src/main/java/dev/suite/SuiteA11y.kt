package dev.suite

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Exists only so the Circle to Search ripple can use TYPE_ACCESSIBILITY_OVERLAY, the one window
 * type an app may draw ABOVE the status and navigation bars (a normal app overlay sits under
 * them, so the real status bar showed on top of the screenshot's own one). It does not read
 * window content or react to events. The shell-side listener keeps it enabled.
 */
class SuiteA11y : AccessibilityService() {
    companion object {
        @Volatile var instance: SuiteA11y? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
