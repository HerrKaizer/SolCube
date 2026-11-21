package dev.suite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings enabled sections back after a reboot or an update of SolCube itself. The service then
 * starts the shell-side programs as soon as Shizuku is available.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { SolCubeService.sync(context.applicationContext) } catch (_: Exception) {}
    }
}
