package dev.suite

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts SolCube's background overlays and pill (TYPE_ACCESSIBILITY_OVERLAY) so they render above
 * system bars without triggering Android's "displaying over other apps" or "running in the
 * background" system notifications. Does not read window content or react to events.
 */
class SolCubeA11y : AccessibilityService() {
    companion object {
        @Volatile var instance: SolCubeA11y? = null

        fun isEnabled(ctx: Context): Boolean = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
                .split(':').any {
                    it.equals("${ctx.packageName}/${SolCubeA11y::class.java.name}", true) ||
                        it.equals("${ctx.packageName}/.SolCubeA11y", true)
                }
        }.getOrDefault(false)
    }

    override fun onServiceConnected() {
        instance = this
        SolCubeHost.onA11yConnected(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        SolCubeHost.onConfigurationChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        SolCubeHost.onA11yDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        SolCubeHost.onA11yDisconnected(this)
        super.onDestroy()
    }
}
