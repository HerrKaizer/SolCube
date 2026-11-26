package dev.suite

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Receives IBinder handoffs from the built-in uid=2000 daemon (`SolCubeDaemon`) and child services
 * (`SolCubeChild`). Guarded by `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`
 * in the manifest plus a constant-time check against [Ipc.secret].
 */
class EngineProvider : ContentProvider() {
    companion object {
        /** Forgets pairing + onboarding and stops the built-in engine so setup starts fresh. */
        fun resetEngine(ctx: android.content.Context) {
            PairingService.stop(ctx)
            SetupPrefs.reset(ctx)
            AdbEngine.resetIdentity(ctx)
            Shell.shutdownBuiltInDaemon()
            Island.hide()
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle().apply { putBoolean("ok", false) }
        val res = Bundle()
        if (method == "reset_engine") {
            resetEngine(ctx)
            res.putBoolean("ok", true)
            return res
        }
        val secret = extras?.getString("secret") ?: ""
        if (!Ipc.same(secret, Ipc.secret(ctx))) {
            res.putBoolean("ok", false)
            return res
        }

        when (method) {
            "attach_daemon" -> {
                val daemon = extras?.getBinder("daemon")
                val children = extras?.getBundle("children")
                val ok = if (daemon != null) {
                    Shell.onDaemonAttached(ctx, daemon, children)
                    true
                } else {
                    false
                }
                res.putBoolean("ok", ok)
            }
            "attach_child" -> {
                val suffix = arg ?: ""
                val binder = extras?.getBinder("binder")
                val ok = if (suffix.isNotEmpty() && binder != null) {
                    Shell.onChildAttached(ctx, suffix, binder)
                    true
                } else {
                    false
                }
                res.putBoolean("ok", ok)
            }
            else -> res.putBoolean("ok", false)
        }
        return res
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
