package dev.suite

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Twice a day asks GitHub for the latest SolCube release. When it's newer than this build,
 * [notifyUpdate] posts a notification ("Remind me later" / "Download now") and [MainActivity]
 * can show its release notes dialog.
 */
object Updates {
    const val RELEASES = "https://github.com/HerrKaizer/SolCube/releases"
    private const val API = "https://api.github.com/repos/HerrKaizer/SolCube/releases/latest"
    private const val EVERY = 12 * 60 * 60 * 1000L   // twice a day
    private const val REMIND_DELAY_MS = 24 * 60 * 60 * 1000L
    private const val P = "updates"
    private const val CHANNEL_UPDATES = "solcube_updates"
    private const val NOTIF_ID = 42
    const val ACTION_REMIND_LATER = "dev.suite.UPDATE_REMIND_LATER"
    const val EXTRA_DOWNLOAD = "download"

    data class Release(val version: String, val title: String, val notes: String, val url: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** 2.1.3 > 2.1 etc. */
    fun newer(a: String, b: String): Boolean {
        val x = a.trim().removePrefix("v").split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The newest known release if it's newer than this build. */
    fun available(ctx: Context): Release? {
        val p = prefs(ctx)
        val v = p.getString("version", null) ?: return null
        if (!newer(v, current(ctx))) return null
        return Release(v, p.getString("title", "") ?: "", p.getString("notes", "") ?: "", p.getString("url", RELEASES) ?: RELEASES)
    }

    /** Asks GitHub if half a day has passed since the last check (or [force]). Network: off the main thread. */
    fun check(ctx: Context, force: Boolean = false): Release? {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("checked", 0) < EVERY) return available(ctx)
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "SolCube-app")
            val code = c.responseCode
            if (code != 200) { Log.w("SolCube", "update check: HTTP $code"); return available(ctx) }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            p.edit()
                .putLong("checked", now)
                .putString("version", j.optString("tag_name").removePrefix("v"))
                .putString("title", j.optString("name"))
                .putString("notes", j.optString("body"))
                .putString("url", j.optString("html_url", RELEASES))
                .apply()
        } catch (e: Exception) {
            Log.w("SolCube", "update check failed: $e")
        }
        return available(ctx)
    }

    /** True once per release version (unless snoozed via "Remind me later"). */
    fun shouldNotify(ctx: Context, r: Release): Boolean {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now < p.getLong("remind_after", 0L)) return false
        if (p.getString("notified", null) == r.version) return false
        p.edit().putString("notified", r.version).remove("remind_after").apply()
        return true
    }

    fun remindLater(ctx: Context) {
        cancelNotification(ctx)
        prefs(ctx).edit()
            .putLong("remind_after", System.currentTimeMillis() + REMIND_DELAY_MS)
            .remove("notified")
            .apply()
    }

    fun cancelNotification(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
    }

    fun notifyUpdate(ctx: Context, r: Release) {
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        I18n.init(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, I18n.t("Updates"), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openPi = PendingIntent.getActivity(ctx, 1, Intent(ctx, UpdateActivity::class.java), flags)
        val downloadPi = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, UpdateActivity::class.java).putExtra(EXTRA_DOWNLOAD, true),
            flags,
        )
        val remindPi = PendingIntent.getBroadcast(
            ctx, 3,
            Intent(ctx, UpdateActionReceiver::class.java).setAction(ACTION_REMIND_LATER),
            flags,
        )
        val n = Notification.Builder(ctx, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notif_update)
            .setContentTitle(I18n.t("New update available"))
            .setContentText("SolCube ${r.version}")
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null as Icon?, I18n.t("Remind me later"), remindPi).build())
            .addAction(Notification.Action.Builder(null as Icon?, I18n.t("Download now"), downloadPi).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** True the first time it's asked for a given version (so the pop-up opens once by itself). */
    fun firstShow(ctx: Context, r: Release): Boolean {
        val p = prefs(ctx)
        if (p.getString("shown", null) == r.version) return false
        p.edit().putString("shown", r.version).apply()
        return true
    }

    /** GitHub markdown → plain text for the dialog. */
    private fun plain(md: String) = md.lines().joinToString("\n") { l ->
        l.replace(Regex("^#+\\s*"), "")
            .replace(Regex("^\\s*[-*]\\s+"), "• ")
            .replace("**", "").replace("`", "")
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
    }.trim()

    fun dialog(act: Activity, r: Release, onDone: () -> Unit = {}) {
        I18n.init(act)
        cancelNotification(act)
        val notes = plain(r.notes).ifEmpty { I18n.t("No release notes.") }
        SolCubeDialog.Builder(act)
            .setTitle(I18n.t("Update available: ${r.version}"))
            .setMessage(I18n.t("You have ${current(act)}.\n\n$notes\n\nInstall the new APK over this one — your settings stay."))
            .setPositiveButton("Open GitHub") { _, _ ->
                runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))) }
            }
            .setNegativeButton("Later", null)
            .setOnDismissListener { onDone() }
            .show()
    }
}

/** Handles "Remind me later" from the update notification. */
class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Updates.ACTION_REMIND_LATER) {
            Updates.remindLater(context.applicationContext)
        }
    }
}

/** Shows the update pop-up or opens the GitHub Releases page from the update notification. */
class UpdateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updates.cancelNotification(this)
        if (intent.getBooleanExtra(Updates.EXTRA_DOWNLOAD, false)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.RELEASES))) }
            finish()
            return
        }
        val r = Updates.available(this) ?: return finish()
        Updates.dialog(this, r) { finish() }
    }
}
