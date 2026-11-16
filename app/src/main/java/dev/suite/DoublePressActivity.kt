package dev.suite

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
