package dev.suite

import android.content.Context
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Secrets for SolCube's local connections.
 *  - [secret]: shared by the app and its shell-side listener (handed over through the Shizuku
 *    binder in ctsConfig), so the ripple port 127.0.0.1:47321 only talks to the real other side:
 *    no other app can receive the screenshot or make SolCube draw a picture.
 *  - [helperToken]: set by the laptop helper over adb (shell-only receiver); the stream service
 *    sends it with every request so other apps can't drive the helper.
 * Both are in private prefs ("ipc"), left out of backups.
 */
object Ipc {
    private const val P = "ipc"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun secret(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("secret", null)?.let { return it }
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val s = b.joinToString("") { "%02x".format(it) }
        p.edit().putString("secret", s).commit()
        return s
    }

    fun helperToken(ctx: Context): String = prefs(ctx).getString("helper_token", "") ?: ""
    fun setHelperToken(ctx: Context, t: String) = prefs(ctx).edit().putString("helper_token", t).commit()

    fun nonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun mac(secret: String, msg: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return m.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Constant-time compare. */
    fun same(a: String, b: String): Boolean = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
