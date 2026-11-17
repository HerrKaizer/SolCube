package dev.suite

import android.app.Activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Twice a day asks GitHub for the latest Suite release. When it's newer than this build, a
 * pop-up shows its notes and a link to the releases page (once per version; MainActivity
 * also shows an "Update available" link until it's installed).
 */
object Updates {
    const val RELEASES = "https://github.com/HerrKaizer/Suite/releases"
    private const val API = "https://api.github.com/repos/HerrKaizer/Suite/releases/latest"
    private const val EVERY = 12 * 60 * 60 * 1000L   // twice a day
    private const val P = "updates"

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

    /** Asks GitHub if a day has passed since the last check (or [force]). Network: off the main thread. */
    fun check(ctx: Context, force: Boolean = false): Release? {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("checked", 0) < EVERY) return available(ctx)
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Suite-app")
            val code = c.responseCode
            if (code != 200) { Log.w("Suite", "update check: HTTP $code"); return available(ctx) }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            p.edit()
                .putLong("checked", now)
                .putString("version", j.optString("tag_name").removePrefix("v"))
                .putString("title", j.optString("name"))
                .putString("notes", j.optString("body"))
                .putString("url", j.optString("html_url", RELEASES))
                .apply()
        } catch (e: Exception) {
            Log.w("Suite", "update check failed: $e")
        }
        return available(ctx)
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
        val notes = plain(r.notes).ifEmpty { "No release notes." }
        SuiteDialog.Builder(act)
            .setTitle("Update available: ${r.version}")
            .setMessage("You have ${current(act)}.\n\n$notes\n\nInstall the new APK over this one — your settings stay.")
            .setPositiveButton("Open GitHub") { _, _ ->
                runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))) }
            }
            .setNegativeButton("Later", null)
            .setOnDismissListener { onDone() }
            .show()
    }
}

/** Shows the update pop-up over whatever is on screen (started by SuiteService's daily check). */
class UpdateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = Updates.available(this) ?: return finish()
        Updates.dialog(this, r) { finish() }
    }
}
