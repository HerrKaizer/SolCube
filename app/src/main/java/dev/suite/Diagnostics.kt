package dev.suite

import android.content.Context
import org.json.JSONObject

/**
 * Builds the Diagnostics report (Settings → Diagnostics, and "Copy report"). Plain words instead of
 * internal names: users read it and paste it into bug reports, so it stays in English.
 * Split out of MainActivity, which only puts the texts into its views.
 */
object Diagnostics {
    enum class Health { OK, STOPPED, WARN }

    class Report(
        val health: Health,
        val summary: String,
        val appInfo: String,
        val services: String,
        val live: String,
        val events: String,
    ) {
        val full get() = "$appInfo\n\nServices:\n$services\n\nLive values:\n$live\n\nRecent events:\n$events"
    }

    private class Svc(val name: String, val tag: String, val o: JSONObject?, val wanted: Boolean) {
        val running get() = o?.optBoolean("running") == true || o?.optBoolean("streaming") == true
    }

    fun build(
        ctx: Context,
        s: Components.Status,
        canDraw: Boolean,
        appName: (String) -> String,
        lastGesture: String,
    ): Report {
        val ready = s.shell == Shell.State.READY
        val engineState = when {
            ready -> "Running"
            SolCubeHost.starting -> "Starting…"
            !AdbEngine.isPaired(ctx) -> "Not paired"
            else -> "Stopped"
        }
        val services = listOf(
            Svc("Buttons & Circle to Search", "buttons", s.cts, Components.ctsServiceWanted(ctx)),
            Svc("Routines & gestures", "routines", s.routines, Routines.load(ctx).any { it.optBoolean("on", true) }),
            Svc("Profiles", "profiles", s.profiles, Profiles.load(ctx).any { it.optBoolean("on", true) && Profiles.apps(it).isNotEmpty() }),
            Svc("Frame interpolation", "fi", s.fi, Components.enabled(ctx, Components.KEY_GAME)),
            Svc("Super resolution", "sr", s.sr, Components.enabled(ctx, Components.KEY_SR)),
            Svc("Play together", "mix", s.mix, Components.enabled(ctx, Components.KEY_MIX)),
            Svc("Stream to laptop", "stream", s.stream, Components.enabled(ctx, Components.KEY_STREAM)),
            Svc("Lock screen status bar", "statusbar", s.statusbar, Components.statusBarDaemonWanted(ctx)),
        )
        val runningCount = services.count { it.running }
        val problems = services.count { it.wanted && !it.running && ready }
        val health = when {
            ready && problems == 0 -> Health.OK
            !ready && !SolCubeHost.starting -> Health.STOPPED
            else -> Health.WARN
        }
        val summary = when {
            !ready -> "SolEngine: $engineState"
            problems > 0 -> "SolEngine: Running · $problems not running"
            runningCount == 0 -> "SolEngine: Running · nothing switched on"
            else -> "SolEngine: Running · $runningCount active"
        }

        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val a11yRunning = SolCubeA11y.instance != null
        val verCode = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
        val keepAlive = when {
            a11yRunning && SolCubeService.anyEnabled(ctx) -> "Accessibility service"
            SolCubeService.running -> "Foreground service"
            SolCubeService.anyEnabled(ctx) -> "Starting…"
            else -> "Not needed (nothing on)"
        }
        fun yes(b: Boolean, y: String = "Allowed", n: String = "Not allowed") = if (b) y else n
        val b = android.os.Build.VERSION.RELEASE
        val appInfo = buildString {
            appendLine("SolCube ${Updates.current(ctx)} ($verCode)")
            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android $b (SDK ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.DISPLAY}")
            appendLine("SolEngine: $engineState · Wireless debugging ${if (AdbEngine.isWirelessAdbEnabled(ctx)) "on" else "off"}")
            appendLine("Kept alive by: $keepAlive")
            appendLine("Accessibility: ${yes(a11yRunning, "On", "Off")} · Overlay: ${yes(canDraw)}")
            appendLine("Notifications: ${yes(nm?.areNotificationsEnabled() == true)} · Do Not Disturb access: ${yes(nm?.isNotificationPolicyAccessGranted == true)}")
            appendLine("Battery: ${yes(pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true, "Unrestricted", "Optimized (may be stopped)")}")
            appendLine("Double power press: ${if (DoublePress.ready(ctx)) "SolCube" else "Another app"}")
            val theme = arrayOf("Light", "System", "Dark")[Look.themeMode(ctx)]
            append("Theme: $theme (${if (Look.isDark(ctx)) "dark" else "light"}) · Language: ${I18n.current()}")
        }

        val svcInfo = services.joinToString("\n") { sv ->
            val o = sv.o
            val st = when {
                o?.optBoolean("streaming") == true -> "Streaming"
                sv.running -> "Running"
                sv.wanted && ready -> "⚠ On, but not running"
                sv.wanted -> "On (waiting for SolEngine)"
                else -> "Off"
            }
            val extra = when (sv.tag) {
                "fi" -> o?.optString("game")?.takeIf { it.isNotEmpty() }?.let { " · ${appName(it)}" } ?: ""
                "profiles" -> o?.optString("activeName")?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
                "mix" -> o?.optString("error")?.takeIf { it.isNotEmpty() }?.let { " · error: $it" } ?: ""
                else -> ""
            }
            "• ${sv.name}: $st$extra"
        }

        val eventLines = mutableListOf<String>()
        for (sv in services) {
            val arr = sv.o?.optJSONArray("log") ?: continue
            for (i in maxOf(0, arr.length() - 3) until arr.length()) {
                val msg = arr.optString(i).trim()
                if (msg.isNotEmpty()) eventLines += "[${sv.tag}] $msg"
            }
        }
        // SolCube's own events (engine starts and why they failed, pairing, …): the app's logcat lines
        // are dropped on some vivo builds, so this is the only place they can be seen.
        val appLines = AppLog.recent(ctx, 10).map { "[app] $it" }
        val events = if (eventLines.isEmpty() && appLines.isEmpty()) "(nothing recorded yet)"
            else (eventLines.takeLast(12) + appLines).joinToString("\n")

        // the raw values that used to sit on the feature cards
        val live = buildString {
            val fi = s.fi
            appendLine("Frame interpolation")
            if (fi == null) appendLine("  not running")
            else {
                appendLine("  fi  = ${fi.optString("fi")}")
                appendLine("  gpu = ${fi.optString("gpu")}")
                logText(fi).lines().forEach { appendLine("  $it") }
            }
            val sr = s.sr
            appendLine("Super resolution")
            appendLine(if (sr == null) "  not running" else "  sr = ${sr.optString("sr")}  vsr = ${sr.optString("vsr")}")
            appendLine("Gestures")
            append("  last drawing: $lastGesture")
        }
        return Report(health, summary, appInfo, svcInfo, live, events)
    }

    fun logText(o: JSONObject): String {
        val log = o.optJSONArray("log")
        return if (log == null || log.length() == 0) "(no events yet)"
        else (maxOf(0, log.length() - 8) until log.length()).joinToString("\n") { log.optString(it) }
    }
}
