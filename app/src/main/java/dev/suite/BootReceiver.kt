package dev.suite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings enabled sections back after a reboot or an update of SolCube itself.
 * After a reboot it also starts SolEngine like Shizuku's "start on boot": Wireless debugging on
 * (WRITE_SECURE_SETTINGS), connect to this phone's adbd with the saved pairing, launch the engine.
 * If Wi-Fi isn't up yet, SolCubeHost retries as soon as it connects. After an update the running
 * engine restarts itself from the new APK, so nothing is needed here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        try { SolCubeService.sync(app) } catch (_: Exception) {}
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !AdbEngine.isPaired(app)) return
        val pending = goAsync()
        Thread {
            try {
                if (AdbEngine.start(app, "boot") == AdbEngine.Start.OK) Components.reconcile(app)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
