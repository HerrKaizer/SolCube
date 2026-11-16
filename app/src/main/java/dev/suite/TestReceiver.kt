package dev.suite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Developer hook (only shell/system can send it, see the manifest permission):
 *   am broadcast -n dev.suite/.TestReceiver -a dev.suite.TEST_CTS --ei freeze 150
 * runs the full screenshot -> ripple -> Circle to Search sequence with that freeze, and
 *   am broadcast -n dev.suite/.TestReceiver -a dev.suite.SET_FREEZE --ei freeze 150
 * stores a new default freeze.
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ms = intent.getIntExtra("freeze", Components.DEFAULT_FREEZE)
        val ctx = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    "dev.suite.TEST_CTS" -> Shell.service(Shell.Kind.CTS)?.command("test $ms")
                    // am broadcast -n dev.suite/.TestReceiver -a dev.suite.RT_STATUS  → logcat tag Suite
                    // am broadcast -n dev.suite/.TestReceiver -a dev.suite.RT_RUN --es id work
                    "dev.suite.RT_CLOSE" -> Shell.service(Shell.Kind.ROUTINES)?.command("close_apps")
                    // --es apps pkg1,pkg2
                    "dev.suite.RT_REVIVE" -> Shell.service(Shell.Kind.ROUTINES)?.command("revive " + intent.getStringExtra("apps"))
                    "dev.suite.RT_RUN" -> Shell.service(Shell.Kind.ROUTINES)?.command("run " + intent.getStringExtra("id"))
                    "dev.suite.RT_STATUS" -> android.util.Log.i("Suite", "routines: " + Shell.service(Shell.Kind.ROUTINES)?.status())
                    "dev.suite.SET_FREEZE" -> {
                        Components.prefs(ctx).edit().putInt(Components.KEY_FREEZE, ms.coerceIn(0, 600)).commit()
                        Shell.service(Shell.Kind.CTS)?.configure(Components.ctsConfig(ctx))
                    }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
