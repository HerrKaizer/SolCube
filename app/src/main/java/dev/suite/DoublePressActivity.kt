package dev.suite

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.media.AudioManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import org.json.JSONArray

/**
 * Double power press (replaces the "Double Power Key" app). vivo fires
 * vivo.intent.action.NFC_BUS_SWINGSPLASH on a double press and opens the preferred handler;
 * Suite makes itself that handler (DoublePress.makeDefault). This invisible activity opens the
 * chosen app and finishes.
 */
class DoublePressActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        DoublePress.run(this)
        finish()
        overridePendingTransition(0, 0)
    }
}

object DoublePress {
    const val ACTION = "vivo.intent.action.NFC_BUS_SWINGSPLASH"
    private const val KEY_TARGET = "double_target"   // "app:<pkg>"
    private const val KEY_APPS = "double_apps"       // the apps to choose from
    private const val KEY_VIBRATE = "double_vibrate"
    private const val KEY_NFC = "double_nfc"
    private const val TAG = "Suite"
    // what the Double Power Key app was set to when Suite took over
    private const val MIRPAY = ""

    // Android's single-vs-double press window (secure multi_press_timeout, default 300 ms)
    const val WINDOW_MIN = 250
    const val WINDOW_MAX = 700

    fun target(ctx: Context): String =
        Components.prefs(ctx).getString(KEY_TARGET, null)?.removePrefix("app:")?.takeIf { it.contains('.') } ?: MIRPAY

    fun setTarget(ctx: Context, pkg: String) = Components.prefs(ctx).edit().putString(KEY_TARGET, "app:$pkg").apply()

    // sound-mode actions instead of an app
    const val RING_CYCLE = "ring:cycle"     // ring → vibrate → silent → ring
    const val RING_SILENT = "ring:silent"   // silent on / off
    const val RING_VIBRATE = "ring:vibrate" // vibrate on / off

    /** The ring action picked, or null when the double press opens an app. */
    fun ringChoice(ctx: Context): String? =
        Components.prefs(ctx).getString(KEY_TARGET, null)?.takeIf { it.startsWith("ring:") }

    fun setRingChoice(ctx: Context, c: String) = Components.prefs(ctx).edit().putString(KEY_TARGET, c).apply()

    fun apps(ctx: Context): List<String> {
        val raw = Components.prefs(ctx).getString(KEY_APPS, null)
        val list = raw?.let { r -> runCatching { JSONArray(r).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull() }
            ?: emptyList()
        val t = target(ctx)
        return if (t.isEmpty() || t in list) list else list + t
    }

    fun saveApps(ctx: Context, list: List<String>) =
        Components.prefs(ctx).edit().putString(KEY_APPS, JSONArray(list).toString()).apply()

    fun vibrate(ctx: Context) = Components.prefs(ctx).getBoolean(KEY_VIBRATE, true)
    fun setVibrate(ctx: Context, on: Boolean) = Components.prefs(ctx).edit().putBoolean(KEY_VIBRATE, on).apply()
    fun nfc(ctx: Context) = Components.prefs(ctx).getBoolean(KEY_NFC, true)
    fun setNfc(ctx: Context, on: Boolean) = Components.prefs(ctx).edit().putBoolean(KEY_NFC, on).apply()

    fun window(ctx: Context): Int = runCatching {
        Settings.Secure.getInt(ctx.contentResolver, "multi_press_timeout")
    }.getOrDefault(300)

    /** Writes Android's multi-press window as the shell user. Off the main thread. */
    fun setWindow(ctx: Context, ms: Int): Boolean =
        Shell.oneShot(ctx, "put secure multi_press_timeout ${ms.coerceIn(WINDOW_MIN, WINDOW_MAX)}") == "ok"

    private fun intent() = Intent(ACTION).addCategory(Intent.CATEGORY_DEFAULT)

    /** Package of the activity that currently gets the double press (null = Android asks). */
    fun handler(ctx: Context): String? {
        val pm = ctx.packageManager
        val pkg = pm.resolveActivity(intent(), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // no "always" choice resolves to the chooser (vivo's is labelled "Share"): not a real handler
        return pkg?.takeIf { p -> pm.queryIntentActivities(intent(), 0).any { it.activityInfo.packageName == p } }
    }

    /** Makes Suite the "always" handler, as the shell user (needs Shizuku). Off the main thread. */
    fun makeDefault(ctx: Context): Boolean {
        val set = ctx.packageManager.queryIntentActivities(intent(), 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toMutableList()
        val me = ComponentName(ctx, DoublePressActivity::class.java)
        if (me !in set) set += me
        val cmd = "prefer-double " + me.flattenToString() + " " + set.joinToString(",") { it.flattenToString() }
        return Shell.oneShot(ctx, cmd) == "ok"
    }

    fun run(act: Activity) {
        ringChoice(act)?.let { c ->
            Log.i(TAG, "double press → $c")
            if (vibrate(act)) runCatching {
                act.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(60, 255))
            }
            Ringer.toggle(act.applicationContext, c)
            return
        }
        val pkg = target(act)
        Log.i(TAG, "double press → $pkg")
        if (vibrate(act)) runCatching {
            act.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(60, 255))
        }
        val app = act.applicationContext
        // NFC first (it takes a moment to come up); the shell side turns it off again once the app is gone
        if (nfc(act)) Thread { Shell.command(app, Shell.Kind.NFC, "nfc-session $pkg") }.start()
        act.packageManager.getLaunchIntentForPackage(pkg)?.let {
            act.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } ?: Log.w(TAG, "double press: $pkg has no launcher activity")
    }
}

/** Ring / vibrate / silent for the double press. */
object Ringer {
    private const val KEY_PREV = "ring_prev"   // the mode to go back to when silent is turned off

    private fun name(m: Int) = when (m) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    fun toggle(ctx: Context, choice: String) {
        val am = ctx.getSystemService(AudioManager::class.java)
        // the system's actual mode; am.ringerMode is the "external" one and can lag behind when
        // something else (shell, vivo's own toggle) changed the internal mode
        val cur = runCatching { Settings.Global.getInt(ctx.contentResolver, "mode_ringer") }.getOrElse { am.ringerMode }
        val prefs = Components.prefs(ctx)
        val next = when (choice) {
            DoublePress.RING_CYCLE -> when (cur) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            DoublePress.RING_SILENT ->
                if (cur == AudioManager.RINGER_MODE_SILENT) prefs.getInt(KEY_PREV, AudioManager.RINGER_MODE_NORMAL)
                else { prefs.edit().putInt(KEY_PREV, cur).apply(); AudioManager.RINGER_MODE_SILENT }
            else ->
                if (cur == AudioManager.RINGER_MODE_VIBRATE) AudioManager.RINGER_MODE_NORMAL
                else AudioManager.RINGER_MODE_VIBRATE
        }
        Island.show(ctx, when (next) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Ring"
        })
        // directly when Suite has Do Not Disturb access (silent needs it), otherwise as the shell user
        val direct = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted &&
            runCatching { am.ringerMode = next; true }.getOrDefault(false)
        if (!direct) Thread { Shell.oneShot(ctx, "ringer ${name(next)}") }.start()
    }

    /** Gives Suite Do Not Disturb access through Shizuku so ring changes are instant. Off the main thread. */
    fun ensureAccess(ctx: Context) {
        if (ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) return
        Shell.oneShot(ctx, "allow-dnd ${ctx.packageName}")
    }
}
