package dev.suite

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts SolCube's background overlays and pill (TYPE_ACCESSIBILITY_OVERLAY) so they render above
 * system bars without triggering Android's "displaying over other apps" or "running in the
 * background" system notifications. Does not read window content or react to events.
 */
class SolCubeA11y : AccessibilityService() {
    private val cancelLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRestore: Runnable? = null

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

    /**
     * Cancels any ongoing touchscreen gesture by momentarily pulsing motionEventSources
     * so AccessibilityInputFilter / InputDispatcher resets active touch streams cleanly.
     */
    fun cancelOngoingTouch(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            synchronized(cancelLock) {
                val info = serviceInfo ?: return false
                clearPendingRestoreLocked()
                val next = if (info.motionEventSources == 0) InputDevice.SOURCE_ROTARY_ENCODER else 0
                info.motionEventSources = next
                setServiceInfo(info)
                if (next != 0) {
                    val restore = Runnable {
                        synchronized(cancelLock) {
                            pendingRestore = null
                            runCatching {
                                val cur = serviceInfo ?: return@runCatching
                                if (cur.motionEventSources != 0) {
                                    cur.motionEventSources = 0
                                    setServiceInfo(cur)
                                }
                            }
                        }
                    }
                    pendingRestore = restore
                    mainHandler.postDelayed(restore, 40L)
                }
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun clearPendingRestoreLocked() {
        pendingRestore?.let {
            mainHandler.removeCallbacks(it)
            pendingRestore = null
        }
    }

    private fun handleDisconnect() {
        synchronized(cancelLock) { clearPendingRestoreLocked() }
        if (instance === this) instance = null
        SolCubeHost.onA11yDisconnected(this)
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
        handleDisconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handleDisconnect()
        super.onDestroy()
    }
}
