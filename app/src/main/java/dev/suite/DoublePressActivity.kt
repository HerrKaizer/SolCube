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
 * Invisible activity that runs a power-button action and finishes:
 *  - double press: vivo fires vivo.intent.action.NFC_BUS_SWINGSPLASH and opens the preferred
 *    handler; SolCube makes itself that handler (DoublePress.makeDefault).
 *  - (the power-button hold has its own HoldActivity below, which only shell/system may start)
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

/** Power-button hold action. Only shell/system may start it (manifest permission). */
class HoldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        PowerHold.action.perform(this, vibrate = false, nfc = false)
        finish()
        overridePendingTransition(0, 0)
    }
}

/**
 * What a power-button gesture does: open an app (picked from a list), switch the sound mode, or
 * Circle to Search. Stored as "<slot>_target" = "app:<pkg>" | "ring:…" | "cts", plus "<slot>_apps".
 */
class PressAction(slot: String, private val allowCts: Boolean) {
    private val kTarget = "${slot}_target"
    private val kApps = "${slot}_apps"

    companion object {
        const val RING_CYCLE = "ring:cycle"     // ring → vibrate → silent → ring
        const val RING_SILENT = "ring:silent"   // silent on / off
        const val RING_VIBRATE = "ring:vibrate" // vibrate on / off
        const val CTS = "cts"
        private const val TAG = "SolCube"
    }

    private fun raw(ctx: Context) = Components.prefs(ctx).getString(kTarget, null)

    /** The app to open ("" if none picked yet). */
    fun target(ctx: Context): String = raw(ctx)?.removePrefix("app:")?.takeIf { it.contains('.') } ?: ""

    fun setTarget(ctx: Context, pkg: String) = Components.prefs(ctx).edit().putString(kTarget, "app:$pkg").apply()

    /** A non-app action (ring:… or cts), or null when it opens an app. */
    fun action(ctx: Context): String? =
        raw(ctx)?.takeIf { it.startsWith("ring:") || (allowCts && it == CTS) }

    fun setAction(ctx: Context, c: String) = Components.prefs(ctx).edit().putString(kTarget, c).apply()

    fun apps(ctx: Context): List<String> {
        val list = Components.prefs(ctx).getString(kApps, null)
            ?.let { r -> runCatching { JSONArray(r).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull() }
            ?: emptyList()
        val t = target(ctx)
        return if (t.isEmpty() || t in list) list else list + t
    }

    fun saveApps(ctx: Context, list: List<String>) =
        Components.prefs(ctx).edit().putString(kApps, JSONArray(list).toString()).apply()

    fun perform(act: Activity, vibrate: Boolean, nfc: Boolean) {
        val app = act.applicationContext
        val a = action(act)
        Log.i(TAG, "power action → ${a ?: target(act)}")
        if (vibrate && a != CTS) runCatching {
            val attrs = android.os.VibrationAttributes.Builder()
                .setUsage(android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                .build()
            act.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(60, 255), attrs)
        }
        when {
            a == CTS -> Thread {
                // ripple + Circle to Search from the power button when the listener runs, else straight to CTS
                val r = if (Components.enabled(app, Components.KEY_CTS)) Shell.command(app, Shell.Kind.CTS, "power-cts") else null
                if (r != "ok") Shell.oneShot(app, "cts")
            }.start()
            a != null -> Ringer.toggle(app, a)
            else -> {
                val pkg = target(act)
                if (pkg.isEmpty()) return
                // NFC first (it takes a moment to come up); the shell side turns it off again once the app is gone
                if (nfc) Thread { Shell.command(app, Shell.Kind.NFC, "nfc-session $pkg") }.start()
                runCatching { act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(pkg, 0)).toString() }
                    .getOrNull()?.let { Island.show(app, it) }
                act.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    act.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                } ?: Log.w(TAG, "power action: $pkg has no launcher activity")
            }
        }
    }
}

/** Power-button hold doing something other than Circle to Search (its own card). */
object PowerHold {
    val action = PressAction("hold", allowCts = false)
}

object DoublePress {
    const val ACTION = "vivo.intent.action.NFC_BUS_SWINGSPLASH"
    private const val KEY_VIBRATE = "double_vibrate"
    private const val KEY_NFC = "double_nfc"
    /** vivo Wallet's "Double press the Power button to quickly swipe cards" (system table). */
    const val WALLET_SWITCH = "com_vivo_wallet_nfc_support_double_click"

    val action = PressAction("double", allowCts = true)

    // Android's single-vs-double press window (secure multi_press_timeout, default 300 ms)
    const val WINDOW_MIN = 250
    const val WINDOW_MAX = 700

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

    /** Is vivo's own double-press switch on? Without it vivo never sends the double press. */
    fun walletSwitchOn(ctx: Context): Boolean = runCatching {
        Settings.System.getInt(ctx.contentResolver, WALLET_SWITCH) == 1
    }.getOrDefault(true)   // can't read it: don't nag

    private fun intent() = Intent(ACTION).addCategory(Intent.CATEGORY_DEFAULT)

    /** Package of the activity that currently gets the double press (null = Android asks). */
    fun handler(ctx: Context): String? {
        val pm = ctx.packageManager
        val pkg = pm.resolveActivity(intent(), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // no "always" choice resolves to the chooser (vivo's is labelled "Share"): not a real handler
        return pkg?.takeIf { p -> pm.queryIntentActivities(intent(), 0).any { it.activityInfo.packageName == p } }
    }

    /** Ready = vivo's switch on and SolCube the handler. */
    fun ready(ctx: Context) = handler(ctx) == ctx.packageName && walletSwitchOn(ctx)

    /**
     * Turns vivo's double-press switch on and makes SolCube the "always" handler, as the shell user
     * (needs Shizuku). Off the main thread.
     */
    fun makeDefault(ctx: Context): Boolean {
        if (!walletSwitchOn(ctx) && Shell.oneShot(ctx, "put system $WALLET_SWITCH 1") != "ok") return false
        val set = ctx.packageManager.queryIntentActivities(intent(), 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toMutableList()
        val me = ComponentName(ctx, DoublePressActivity::class.java)
        if (me !in set) set += me
        val cmd = "prefer-double " + me.flattenToString() + " " + set.joinToString(",") { it.flattenToString() }
        return Shell.oneShot(ctx, cmd) == "ok"
    }

    fun run(act: Activity) = action.perform(act, vibrate(act), nfc = false)
}

/** Ring / vibrate / silent for the power-button actions. */
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
            PressAction.RING_CYCLE -> when (cur) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            PressAction.RING_SILENT ->
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
        // directly when SolCube has Do Not Disturb access (silent needs it), otherwise as the shell user
        val direct = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted &&
            runCatching { am.ringerMode = next; true }.getOrDefault(false)
        if (!direct) Thread { Shell.oneShot(ctx, "ringer ${name(next)}") }.start()
    }

    /** Gives SolCube Do Not Disturb access through Shizuku so ring changes are instant. Off the main thread. */
    fun ensureAccess(ctx: Context) {
        if (ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) return
        Shell.oneShot(ctx, "allow-dnd ${ctx.packageName}")
    }
}
