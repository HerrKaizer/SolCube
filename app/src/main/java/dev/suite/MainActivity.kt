package dev.suite

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import dev.suite.shell.Gestures
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // Palette and widgets borrowed from TapDrop (frosted glass over soft wallpaper glows).

    private lateinit var p: Look.Palette
    private val fontRegular: Typeface by lazy { googleSans(400) }
    private val fontMedium: Typeface by lazy { googleSans(560) }
    private val fontBold: Typeface by lazy { googleSans(680) }

    private fun googleSans(weight: Int): Typeface = try {
        Typeface.Builder(assets, "fonts/GoogleSansFlex.ttf")
            .setFontVariationSettings("'wght' $weight")
            .build() ?: Typeface.DEFAULT
    } catch (_: Exception) {
        Typeface.DEFAULT
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var resumed = false
    private var last: Components.Status? = null
    private val busy = HashSet<String>()

    private class Section(
        val key: String,
        val card: LinearLayout,
        val body: LinearLayout,
        val toggle: TextView,
        val dot: View,
        val status: TextView,
        val detail: TextView,
        /** Looks like the group cards: dot + one-line summary, no Running/Not running. */
        val compact: Boolean = false,
    )

    private class GroupCard(
        val card: LinearLayout,
        val dot: View,
        val summary: TextView,
        val body: LinearLayout,
    )

    private lateinit var shizukuCard: LinearLayout
    private lateinit var shizukuText: TextView
    private lateinit var shizukuButton: TextView
    private lateinit var overlayCard: LinearLayout
    private lateinit var shortcutsGroup: GroupCard
    private lateinit var automationGroup: GroupCard
    private lateinit var gamingGroup: GroupCard
    private lateinit var audioGroup: GroupCard
    private lateinit var volDial: VolumeDial
    private lateinit var ctsTouch: Section
    private lateinit var ctsButton: Section
    private lateinit var ctsNav: Section
    private lateinit var ctsDot: View
    private lateinit var ctsStatus: TextView
    private lateinit var ctsNote: TextView
    private lateinit var game: Section
    private lateinit var srSec: Section
    private lateinit var mixSec: Section
    private lateinit var gestSec: Section
    private lateinit var temp: Section
    private lateinit var fpsSec: Section
    private lateinit var diagDot: View
    private lateinit var diagSummary: TextView
    private lateinit var diagBody: LinearLayout
    private lateinit var diagAppText: TextView
    private lateinit var diagSvcText: TextView
    private lateinit var diagLogText: TextView
    private lateinit var diagLiveText: TextView
    private var lastDiagReport: String = ""
    private var mainScroll: ScrollView? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        io.execute { Components.reconcile(this) }
        poll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Look.isDark(this)) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        RenderTest.rebuild = { runOnUiThread { recreate() } }
        // 3.4.2: the temporary rendering switches are gone from Settings; drop what they (or the
        // test hook) saved, so it's the defaults: sway on open, full-res wallpaper, three-step blur
        Components.prefs(this).let { pr ->
            if (!pr.getBoolean("render_defaults_v1", false)) pr.edit()
                .remove(GlassBackdrop.KEY_MOTION).remove(GlassBackdrop.KEY_HALF).remove(KEY_HEADER_PASSES)
                .putBoolean("render_defaults_v1", true).apply()
        }
        p = palette()
        Components.ctsTouch(this)   // one-time split of the old single Circle to Search switch
        actionBar?.hide()
        styleSystemBars()
        setContentView(build(savedInstanceState?.getInt("scroll_y", 0) ?: 0))
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mainScroll?.let { outState.putInt("scroll_y", it.scrollY) }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        io.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        SolCubeService.sync(this)   // e.g. overlay permission was just granted
        io.execute { Components.reconcile(this) }
        poll()
        checkUpdates()
        cleanupOldBackup()
        keepAlive()
        renderDouble()   // vivo's switch / the handler may have changed in Settings
        // volume buttons / other apps change the media volume: keep the fine volume control in step
        runCatching { registerReceiver(volReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"), RECEIVER_EXPORTED) }
        syncVolume()
    }

    private val volReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == android.media.AudioManager.STREAM_MUSIC) syncVolume()
        }
    }

    private fun syncVolume() {
        if (!::volDial.isInitialized || volDial.isDragging) return
        val v = FineVolume.percent(this)
        if (volDial.value != v) volDial.value = v
    }

    override fun onPause() {
        runCatching { unregisterReceiver(volReceiver) }
        resumed = false
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // ================================================================== status

    private val pollTask = Runnable { poll() }

    private fun poll() {
        main.removeCallbacks(pollTask)
        io.execute {
            val s = Components.status()
            main.post {
                last = s
                render()
                if (resumed) main.postDelayed(pollTask, 2000)
            }
        }
    }

    private fun render() {
        val s = last ?: Components.Status(Shell.state())
        val rawOverlay = Settings.canDrawOverlays(this)
        val a11yOk = SolCubeA11y.instance != null || SolCubeA11y.isEnabled(this)
        val canDraw = rawOverlay || a11yOk
        val shellOk = s.shell == Shell.State.READY

        shizukuCard.visibility = if (s.shell == Shell.State.READY) View.GONE else View.VISIBLE
        when (s.shell) {
            Shell.State.NOT_RUNNING -> {
                shizukuText.text = "Shizuku isn't running — it stops on every reboot. Start it again in the Shizuku app (Wireless debugging). Circle to Search, Frame interpolation and Super resolution need it; SolCube brings them back by itself once it's running."
                shizukuButton.text = "Open Shizuku"
            }
            Shell.State.NO_PERMISSION -> {
                shizukuText.text = "Allow SolCube to use Shizuku so it can start and stop the shell-side parts."
                shizukuButton.text = "Allow"
            }
            Shell.State.READY -> {}
        }
        overlayCard.visibility = if (canDraw || shellOk) View.GONE else View.VISIBLE

        // Circle to Search: shell listener + optional ripple overlay in this app
        val anim = Components.ctsAnimate(this)
        val ripple = RippleHost.running
        val rippleOk = !anim || ripple
        val listener = s.cts?.optBoolean("running") == true
        fun ctsDetail(sec: Section, idle: String) = when {
            !shellOk && enabled(sec) -> "Waiting for Shizuku"
            anim && enabled(sec) && !canDraw && !shellOk -> "Needs “Display over other apps”"
            else -> idle
        }
        showSection(ctsTouch, running = s.cts?.optBoolean("touch") == true && enabled(ctsTouch) && rippleOk,
            detail = ctsDetail(ctsTouch, "Hold two fingers still on the screen"))
        showSection(ctsButton, running = s.cts?.optBoolean("button") == true && enabled(ctsButton) && rippleOk,
            detail = ctsDetail(ctsButton, "Hold the power button (screen on)"))
        showSection(ctsNav, running = s.cts?.optBoolean("nav") == true && enabled(ctsNav) && rippleOk,
            detail = ctsDetail(ctsNav, "Hold one finger still on the navigation bar at the bottom"))
        showSection(holdSec, running = s.cts?.optBoolean("button") == true && enabled(holdSec),
            detail = when {
                !shellOk && enabled(holdSec) -> "Waiting for Shizuku"
                else -> "Hold the power button (screen on) to " + actionText(PowerHold.action) + "."
            })
        val count = listOf(ctsTouch, ctsButton, ctsNav).count { enabled(it) }
        val ctsActive = listOf(
            enabled(ctsTouch) && s.cts?.optBoolean("touch") == true,
            enabled(ctsButton) && s.cts?.optBoolean("button") == true,
            enabled(ctsNav) && s.cts?.optBoolean("nav") == true,
        ).any { it } && rippleOk
        ctsStatus.put(when {
            count == 0 -> "Not running"
            ctsActive -> "$count active"
            else -> "$count enabled"
        })
        ctsStatus.setTextColor(if (ctsActive) p.ok else p.sub)
        (ctsDot.background as GradientDrawable).setColor(if (ctsActive) p.ok else alpha(p.sub, 0.5f))

        val anyCts = count > 0
        ctsNote.put(when {
            !anyCts -> "Pick a trigger below"
            !anim && !listener && shellOk -> "Starting the listener…"
            !anim -> "Triggers Google's Circle to Search instantly"
            listener && !ripple -> "Listener running, ripple overlay starting…"
            !listener && ripple && shellOk -> "Starting the listener…"
            else -> "Ripple, then Google's Circle to Search"
        })

        // Frame interpolation
        val fi = s.fi
        val fiRunning = fi?.optBoolean("running") == true
        showSection(
            game, running = fiRunning,
            detail = when {
                !shellOk && enabled(game) -> "Waiting for Shizuku"
                fi == null -> "Keeps vivo frame interpolation on in listed games"
                else -> buildString {
                    val pkg = fi.optString("game")
                    val bat = fi.optInt("battery") / 10f
                    if (pkg.isEmpty()) append("No listed game running")
                    else {
                        append(appName(pkg)).append(" · ").append(fi.optInt("src")).append("→").append(fi.optInt("dst")).append(" fps")
                    }
                    if (fi.optBoolean("paused")) append(" · Paused: hot")
                    append(String.format(java.util.Locale.US, " · %.1f °C", bat))
                }
            },
        )
        // Super resolution (its raw values are in Diagnostics)
        val sr = s.sr
        showSection(
            srSec, running = sr?.optBoolean("running") == true,
            detail = when {
                !shellOk && enabled(srSec) -> "Waiting for Shizuku"
                else -> "vivo super resolution, kept on while enabled"
            },
        )

        // Play together
        val mix = s.mix
        showSection(
            mixSec, running = mix?.optBoolean("running") == true,
            detail = when {
                !shellOk && enabled(mixSec) -> "Waiting for Shizuku"
                mix == null -> "Apps keep playing when another starts sound. Calls still pause them."
                mix.optString("error").isNotEmpty() -> "Couldn't take over audio focus: ${mix.optString("error")}"
                else -> {
                    val apps = mix.optJSONArray("apps")
                    val names = if (apps == null) emptyList() else (0 until apps.length()).map { appName(apps.optString(it)) }.distinct()
                    if (names.isEmpty()) "No app has asked for audio yet" else "Playing together: " + names.joinToString(", ")
                }
            },
        )

        renderStream(s.stream, shellOk)
        renderRoutines(s.routines, shellOk)
        renderGestures(s.routines, shellOk)
        renderProfiles(s.profiles, shellOk)
        renderDouble()

        showSection(
            fpsSec, running = TempOverlay.running && enabled(fpsSec),
            detail = if (enabled(fpsSec) && !canDraw && !shellOk) "Needs “Display over other apps”"
            else "Frames per second on screen, in its own pill: left of the temperature pill in landscape, above it in portrait.",
        )
        showSection(
            temp, running = TempOverlay.running && enabled(temp),
            detail = if (enabled(temp) && !canDraw && !shellOk) "Needs “Display over other apps”"
            else "CPU · GPU · Battery °C" +
                (if (Style.showLoad(this)) " + CPU · GPU load" else "") + " — drag to move, hold 1 s to turn off",
        )

        fun setGroup(g: GroupCard, active: Boolean, text: String) {
            g.summary.put(text)
            (g.dot.background as GradientDrawable).setColor(if (active) p.ok else alpha(p.sub, 0.5f))
        }

        val scParts = mutableListOf<String>()
        if (count > 0) scParts += "Circle to Search"
        if (DoublePress.ready(this)) scParts += "Double press"
        if (enabled(holdSec)) scParts += "Power hold"
        setGroup(shortcutsGroup, ctsActive || DoublePress.ready(this) || enabled(holdSec),
            if (scParts.isEmpty()) "All off" else scParts.joinToString(" · "))

        val rtAll = Routines.load(this)
        val rtOn = rtAll.count { it.optBoolean("on", true) }
        val prAll = Profiles.load(this)
        val prOn = prAll.count { it.optBoolean("on", true) && Profiles.apps(it).isNotEmpty() }
        val rtActive = rtOn > 0 && s.routines?.optBoolean("running") == true
        val prActive = prOn > 0 && s.profiles?.optBoolean("running") == true
        val actProf = s.profiles?.optString("activeName")?.takeIf { it.isNotEmpty() }
        setGroup(automationGroup, rtActive || prActive, buildString {
            append("$rtOn/${rtAll.size} routines · $prOn/${prAll.size} profiles")
            if (actProf != null) append(" · Active: $actProf")
        })

        val gameParts = mutableListOf<String>()
        if (enabled(fpsSec)) gameParts += "FPS counter"
        if (enabled(temp)) gameParts += "Temp pill"
        if (fiRunning || enabled(game)) gameParts += "Frame interpolation"
        if (sr?.optBoolean("running") == true || enabled(srSec)) gameParts += "Super res"
        if (s.stream?.optBoolean("streaming") == true || Components.enabled(this, Components.KEY_STREAM)) gameParts += "Stream"
        setGroup(gamingGroup,
            TempOverlay.running || fiRunning || sr?.optBoolean("running") == true || s.stream?.optBoolean("streaming") == true || Components.enabled(this, Components.KEY_STREAM),
            if (gameParts.isEmpty()) "All off" else gameParts.joinToString(" · "))

        val mixOn = mix?.optBoolean("running") == true || enabled(mixSec)
        syncVolume()
        setGroup(audioGroup, mixOn,
            (if (mixOn) "Mixing · " else "") + "Volume ${FineVolume.percent(this)}%")

        renderDiagnostics(s, rawOverlay)
    }

    private fun renderDiagnostics(s: Components.Status, canDraw: Boolean) {
        val shState = when (s.shell) {
            Shell.State.READY -> "Ready"
            Shell.State.NO_PERMISSION -> "No permission"
            Shell.State.NOT_RUNNING -> "Not running"
        }
        val daemons = listOf(
            "cts" to s.cts,
            "routines" to s.routines,
            "profiles" to s.profiles,
            "fi" to s.fi,
            "sr" to s.sr,
            "mix" to s.mix,
            "stream" to s.stream,
        )
        val runningCount = daemons.count { (_, o) -> o?.optBoolean("running") == true || o?.optBoolean("streaming") == true }
        val dotColor = when (s.shell) {
            Shell.State.READY -> p.ok
            Shell.State.NO_PERMISSION -> p.warn
            Shell.State.NOT_RUNNING -> alpha(p.sub, 0.5f)
        }
        (diagDot.background as GradientDrawable).setColor(dotColor)
        val sum = "Shizuku: $shState · $runningCount shell active"
        if (diagSummary.text != sum) diagSummary.text = sum

        val nm = getSystemService(android.app.NotificationManager::class.java)
        val pm = getSystemService(android.os.PowerManager::class.java)
        val dndGranted = nm?.isNotificationPolicyAccessGranted == true
        val dozeIgnored = pm?.isIgnoringBatteryOptimizations(packageName) == true
        val a11yRunning = SolCubeA11y.instance != null
        val doubleReady = DoublePress.ready(this)
        val themeName = arrayOf("Light", "System", "Dark")[Look.themeMode(this)]
        val verName = Updates.current(this)
        val verCode = runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)

        val hostMode = when {
            SolCubeA11y.instance != null && SolCubeService.anyEnabled(this@MainActivity) -> "active (a11y)"
            SolCubeService.running -> "active (fgs)"
            SolCubeService.anyEnabled(this@MainActivity) -> "starting"
            else -> "idle"
        }

        val appInfo = buildString {
            appendLine("Version: $verName ($verCode)")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Theme: $themeName (${if (isDark()) "dark" else "light"}) · Keep-alive: $hostMode")
            appendLine("Shizuku: $shState · Overlay: ${if (canDraw) "ok" else "missing"} · A11y: ${if (a11yRunning) "on" else "off"}")
            append("DND: ${if (dndGranted) "ok" else "no"} · Battery whitelist: ${if (dozeIgnored) "ok" else "no"} · Double-press: ${if (doubleReady) "suite" else "other"}")
        }
        val svcInfo = daemons.joinToString("\n") { (name, o) ->
            val st = when {
                o == null -> "stopped"
                o.optBoolean("streaming") -> "streaming"
                o.optBoolean("running") -> "running"
                else -> "idle"
            }
            val extra = when (name) {
                "fi" -> o?.optString("game")?.takeIf { it.isNotEmpty() }?.let { " (${appName(it)})" } ?: ""
                "profiles" -> o?.optString("activeName")?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
                "mix" -> o?.optString("error")?.takeIf { it.isNotEmpty() }?.let { " (err: $it)" } ?: ""
                else -> ""
            }
            val req = when (name) {
                "cts" -> Components.ctsServiceWanted(this)
                "routines" -> Routines.load(this).any { it.optBoolean("on", true) }
                "profiles" -> Profiles.load(this).any { it.optBoolean("on", true) && Profiles.apps(it).isNotEmpty() }
                "fi" -> Components.enabled(this, Components.KEY_GAME)
                "sr" -> Components.enabled(this, Components.KEY_SR)
                "mix" -> Components.enabled(this, Components.KEY_MIX)
                "stream" -> Components.enabled(this, Components.KEY_STREAM)
                else -> false
            }
            "• $name: $st${if (req) " (enabled)" else ""}$extra"
        }
        val eventLines = mutableListOf<String>()
        for ((name, o) in daemons) {
            val arr = o?.optJSONArray("log") ?: continue
            val start = maxOf(0, arr.length() - 3)
            for (i in start until arr.length()) {
                val msg = arr.optString(i).trim()
                if (msg.isNotEmpty()) eventLines += "[$name] $msg"
            }
        }
        val recentLogs = if (eventLines.isEmpty()) "(no shell events recorded)" else eventLines.takeLast(12).joinToString("\n")

        if (diagAppText.text != appInfo) diagAppText.text = appInfo
        if (diagSvcText.text != svcInfo) diagSvcText.text = svcInfo
        if (diagLogText.text != recentLogs) diagLogText.text = recentLogs

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
            append("  last drawing: ${lastGestureText(s.routines)}")
        }
        if (diagLiveText.text != live) diagLiveText.text = live
        lastDiagReport = "$appInfo\n\nServices:\n$svcInfo\n\nLive values:\n$live\n\nRecent events:\n$recentLogs"
    }

    private fun enabled(sec: Section) = Components.enabled(this, sec.key)

    private fun logText(o: org.json.JSONObject): String {
        val log = o.optJSONArray("log")
        return if (log == null || log.length() == 0) "(no events yet)"
        else (maxOf(0, log.length() - 8) until log.length()).joinToString("\n") { log.optString(it) }
    }

    private fun showSection(sec: Section, running: Boolean, detail: String) {
        val on = enabled(sec)
        val working = sec.key in busy
        sec.toggle.put(when {
            working -> "…"
            on -> "Disable"
            else -> "Enable"
        })
        // only rebuild the button when its look changes: a new glass pane compiles its shader, and
        // doing that for every section on every 2 s refresh made the whole page hitch
        if (sec.toggle.tag != on) {
            sec.toggle.tag = on
            sec.toggle.setTextColor(if (on) p.fg else p.onAccent)
            sec.toggle.background = ripple(if (on) glass(20.dp) else accentGradient(20.dp), 20.dp)
        }
        (sec.dot.background as GradientDrawable).setColor(if (running) p.ok else alpha(p.sub, 0.5f))
        if (sec.compact) {
            // first line next to the dot; anything after it (e.g. "Last drawing ignored") below
            sec.status.put(detail.substringBefore('\n'))
            val rest = if ('\n' in detail) detail.substringAfter('\n') else ""
            sec.detail.put(rest)
            sec.detail.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        sec.status.put(if (running) "Running" else "Not running")
        sec.status.setTextColor(if (running) p.ok else p.sub)
        sec.detail.put(detail)
    }

    private fun toggle(sec: Section) {
        if (sec.key in busy) return
        val on = !enabled(sec)
        val needsOverlay = sec.key in setOf(Components.KEY_TEMP, Components.KEY_FPS) ||
            (Components.ctsAnimate(this) && sec.key in setOf(Components.KEY_CTS_TOUCH, Components.KEY_CTS_BUTTON, Components.KEY_CTS_NAV))
        val hasOverlay = Settings.canDrawOverlays(this) || SolCubeA11y.instance != null ||
            SolCubeA11y.isEnabled(this) || Shell.state() == Shell.State.READY
        if (on && needsOverlay && !hasOverlay) {
            openOverlaySettings()
        }
        busy += sec.key
        render()
        io.execute {
            when (sec.key) {
                Components.KEY_CTS_TOUCH, Components.KEY_CTS_BUTTON, Components.KEY_CTS_NAV, Components.KEY_HOLD_ACT -> Components.setCtsTrigger(this, sec.key, on)
                Components.KEY_GAME -> Components.setGame(this, on)
                Components.KEY_SR -> Components.setSr(this, on)
                Components.KEY_MIX -> Components.setMix(this, on)
                Components.KEY_TEMP -> Components.setTemp(this, on)
                Components.KEY_FPS -> Components.setFps(this, on)
                GestureStore.KEY -> GestureStore.setOn(this, on)
            }
            noticeName(sec.key)?.let { Island.show(this, "$it ${if (on) "on" else "off"}") }
            Thread.sleep(400)   // let the service / programs come up before re-checking
            val s = Components.status()
            main.post { busy -= sec.key; last = s; render() }
        }
    }

    /** Short names for the status pill (it fits ~12 characters). */
    private fun noticeName(key: String): String? = when (key) {
        Components.KEY_CTS_TOUCH -> "Touch CTS"
        Components.KEY_CTS_BUTTON -> "Power CTS"
        Components.KEY_CTS_NAV -> "Nav bar CTS"
        Components.KEY_HOLD_ACT -> "Power hold"
        Components.KEY_GAME -> "FI"
        Components.KEY_SR -> "SR"
        Components.KEY_MIX -> "Mixing"
        Components.KEY_TEMP -> "Temp pill"
        Components.KEY_FPS -> "FPS counter"
        GestureStore.KEY -> "Gestures"
        else -> null
    }

    // ================================================================== layout

    private fun build(initialScrollY: Int = 0): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(16.dp, 28.dp, 16.dp, 24.dp)
            // keep focus off the package field so the page opens at the top
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val suiteTitle = label("SolCube", 42f, p.fg).apply {
            typeface = fontBold
            letterSpacing = -0.02f
        }

        updateLink = textButton("") { Updates.available(this)?.let { Updates.dialog(this, it) } }.apply { visibility = View.GONE }
        root.addView(updateLink, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 8.dp; leftMargin = (-6).dp })

        // small glass pill, lined up with the title and set a little apart from it
        root.addView(label("Can't find a feature?", 13f, p.link, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(14.dp, 0, 14.dp, 0)
            background = ripple(glass(15.dp, level = 0), 15.dp)
            setOnClickListener { featureRequest() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, 30.dp).apply { topMargin = 52.dp; leftMargin = 4.dp; bottomMargin = 18.dp })

        // Shizuku notice
        shizukuCard = card()
        shizukuText = label("", 14.5f, p.fg)
        shizukuCard.addView(shizukuText)
        shizukuButton = pillButton("", filled = true) {
            if (Shell.state() == Shell.State.NOT_RUNNING) {
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                    ?: toast("Shizuku isn't installed")
            } else if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(1)
            }
        }
        shizukuCard.addView(shizukuButton, LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { topMargin = 14.dp })
        root.addView(shizukuCard, cardParams())

        // Overlay permission notice
        overlayCard = card()
        overlayCard.addView(label("Allow “Display over other apps” for the ripple and the temperature pill.", 14.5f, p.fg))
        overlayCard.addView(pillButton("Allow", filled = true) { openOverlaySettings() },
            LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { topMargin = 14.dp })
        root.addView(overlayCard, cardParams())

        // 1. Shortcuts big card (collapsed by default)
        shortcutsGroup = groupCard("shortcuts", "Shortcuts", defaultOpen = false)
        val ctsCard = innerCard()
        val ctsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        ctsHeader.addView(label("Circle to Search", 16.5f, p.fg, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val ctsChevron = chevronBadge(false)
        ctsHeader.addView(ctsChevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
        ctsCard.addView(ctsHeader)
        val ctsStatusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ctsDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        ctsStatusRow.addView(ctsDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        ctsStatus = label("", 14f, p.sub, bold = true)
        ctsStatusRow.addView(ctsStatus)
        ctsCard.addView(ctsStatusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        ctsNote = label("", 13f, p.sub)
        ctsCard.addView(ctsNote, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

        val ctsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ctsBody.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp })
        ctsTouch = section(Components.KEY_CTS_TOUCH, "Two-finger hold", into = ctsBody)
        addHoldStepper(ctsBody, Components.KEY_TOUCH_HOLD) { Components.touchHold(this) }
        ctsBody.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp })
        ctsButton = section(Components.KEY_CTS_BUTTON, "Power-button hold", into = ctsBody)
        addHoldStepper(ctsBody, Components.KEY_BUTTON_HOLD) { Components.buttonHold(this) }
        ctsBody.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp })
        ctsNav = section(Components.KEY_CTS_NAV, "Navigation bar hold", into = ctsBody)
        addHoldStepper(ctsBody, Components.KEY_NAV_HOLD) { Components.navHold(this) }
        ctsBody.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp })
        ctsBody.addView(Switch(this).apply {
            text = "Instant trigger (no animation)"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            styleSwitch(this)
            isChecked = !Components.ctsAnimate(this@MainActivity)
            setOnCheckedChangeListener { _, instant ->
                Components.setCtsAnimate(this@MainActivity, !instant)
                render()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        ctsBody.addView(label("Skips the ripple overlay and opens Circle to Search right away.", 12f, p.sub),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 2.dp })
        // vivo hides the "default digital assistant" page; Circle to Search needs Google there
        ctsBody.addView(textButton("Default assistant settings") { openDefaultAssistantSettings() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp; leftMargin = (-12).dp })
        ctsCard.addView(ctsBody)
        makeCollapsible("cts", ctsCard, listOf(ctsHeader, ctsStatusRow, ctsNote), ctsChevron, ctsBody, defaultOpen = false)
        shortcutsGroup.body.addView(ctsCard, innerCardParams())
        shortcutsGroup.body.addView(buildDoubleCard(inner = true), innerCardParams())
        shortcutsGroup.body.addView(buildHoldCard(inner = true))
        root.addView(shortcutsGroup.card, cardParams())

        // 2. Automation big card (expanded by default): Routines + Profiles
        automationGroup = groupCard("automation", "Automation", defaultOpen = true)
        automationGroup.body.addView(buildRoutinesCard(inner = true), innerCardParams())
        automationGroup.body.addView(buildProfilesCard(inner = true))
        root.addView(automationGroup.card, cardParams())

        // 3. Gestures (standalone card): two-finger drawings that trigger routines
        gestSec = section(GestureStore.KEY, "Gestures", compact = true)
        addGestureSettings(gestSec.body)
        root.addView(gestSec.card, cardParams())

        // 4. Gaming big card (collapsed by default)
        gamingGroup = groupCard("gaming", "Gaming", defaultOpen = false)
        fpsSec = section(Components.KEY_FPS, "FPS counter", collapsible = false, inner = true)
        gamingGroup.body.addView(fpsSec.card, innerCardParams())

        temp = section(Components.KEY_TEMP, "Temperature overlay", inner = true)
        addTempSettings(temp.body)
        gamingGroup.body.addView(temp.card, innerCardParams())

        game = section(Components.KEY_GAME, "Frame interpolation", inner = true)
        addGameControls(game.body)
        qsHint(game.body, FiTile::class.java, "Frame interpolation", R.drawable.ic_tile_fi)
        gamingGroup.body.addView(game.card, innerCardParams())

        srSec = section(Components.KEY_SR, "Super resolution", collapsible = false, inner = true)
        qsHint(srSec.body, SrTile::class.java, "Super resolution", R.drawable.ic_tile_sr)
        gamingGroup.body.addView(srSec.card, innerCardParams())

        gamingGroup.body.addView(buildStreamCard(inner = true))
        root.addView(gamingGroup.card, cardParams())

        // 5. Audio big card: Play together (mixing) + Fine volume
        audioGroup = groupCard("audio", "Audio", defaultOpen = false)
        mixSec = section(Components.KEY_MIX, "Play together", collapsible = false, inner = true)
        qsHint(mixSec.body, MixTile::class.java, "Play together", R.drawable.ic_tile_mix)
        audioGroup.body.addView(mixSec.card, innerCardParams())
        audioGroup.body.addView(buildFineVolumeCard())
        root.addView(audioGroup.card, cardParams())

        // 5. Settings big card, separated below with open space
        val settingsCard = card().apply {
            setPadding(16.dp, 18.dp, 16.dp, 18.dp)
        }
        settingsCard.addView(label("Settings", 20f, p.fg, bold = true))

        val settingsHost = FloatingCardHost().apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(16.dp, 12.dp, 16.dp, 4.dp)
        }
        settingsCard.addView(settingsHost, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = (-16).dp
            rightMargin = (-16).dp
        })

        val themeCard = innerCard()
        val themeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        themeHeader.addView(label("Theme", 15.5f, p.fg, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val modeNames = arrayOf("Light", "System", "Dark")
        val themeVal = label(modeNames[Look.themeMode(this)], 13f, p.sub)
        themeHeader.addView(themeVal)
        themeCard.addView(themeHeader)
        themeCard.addView(ThemeSliderView(Look.themeMode(this)) { newMode ->
            themeVal.text = modeNames[newMode]
            val wasDark = isDark()
            Look.setThemeMode(this, newMode)
            if (wasDark != isDark()) {
                recreate()
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 44.dp).apply { topMargin = 12.dp })
        settingsHost.addView(themeCard, innerCardParams())


        val powerCard = innerCard().apply {
            val baseBg = background
            background = ripple(baseBg, 18.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                try {
                    startActivity(Intent().setClassName("com.iqoo.powersaving", "com.iqoo.powersaving.BackgroundHighUsageActivity"))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }
        val powerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        powerRow.addView(label("vivo background power settings", 15f, p.link, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        powerRow.addView(label("›", 18f, p.sub))
        powerCard.addView(powerRow)
        powerCard.addView(label("Allow SolCube to use power in the background, or vivo freezes it.", 12.5f, p.sub).apply {
            setPadding(0, 4.dp, 0, 0)
        })
        settingsHost.addView(powerCard, innerCardParams())

        val diagCard = innerCard()
        val diagHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        diagHeader.addView(label("Diagnostics", 15.5f, p.fg, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val diagChevron = chevronBadge(false)
        diagHeader.addView(diagChevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
        diagCard.addView(diagHeader)

        val diagStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        diagDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        diagStatusRow.addView(diagDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        diagSummary = label("", 13f, p.sub)
        diagStatusRow.addView(diagSummary, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        diagCard.addView(diagStatusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

        diagBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagBody.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 12.dp; bottomMargin = 10.dp })
        diagBody.addView(label("App & permissions", 13f, p.fg, bold = true))
        diagAppText = mono(11.5f).apply { setLineSpacing(2.dp.toFloat(), 1f) }
        diagBody.addView(diagAppText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        diagBody.addView(label("Shell services", 13f, p.fg, bold = true),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        diagSvcText = mono(11.5f).apply { setLineSpacing(2.dp.toFloat(), 1f) }
        diagBody.addView(diagSvcText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        diagBody.addView(label("Live values", 13f, p.fg, bold = true),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        diagLiveText = mono(11f).apply { setLineSpacing(2.dp.toFloat(), 1f) }
        diagBody.addView(diagLiveText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        diagBody.addView(label("Recent events", 13f, p.fg, bold = true),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        diagLogText = mono(11f).apply { setLineSpacing(2.dp.toFloat(), 1f) }
        diagBody.addView(diagLogText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        diagBody.addView(pillButton("Copy report", filled = false) {
            val report = lastDiagReport
            if (report.isNotEmpty()) {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("SolCube diagnostics", report))
                toast("Diagnostics copied")
            }
        }.apply {
            textSize = 13.5f
            setPadding(12.dp, 0, 12.dp, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 38.dp).apply { topMargin = 12.dp })
        diagCard.addView(diagBody)
        makeCollapsible("settings_diag", diagCard, listOf(diagHeader, diagStatusRow), diagChevron, diagBody, defaultOpen = false)
        settingsHost.addView(diagCard, innerCardParams())

        val settingsActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val updateBtn = pillButton("Check for updates", filled = false) { checkUpdates(manual = true) }.apply {
            textSize = 13.5f
            setPadding(8.dp, 0, 8.dp, 0)
        }
        lateinit var restartBtn: TextView
        restartBtn = pillButton("Restart SolCube", filled = false) { restartSolCube(restartBtn) }.apply {
            textSize = 13.5f
            setPadding(8.dp, 0, 8.dp, 0)
        }
        settingsActions.addView(updateBtn, LinearLayout.LayoutParams(0, 42.dp, 1f))
        settingsActions.addView(restartBtn, LinearLayout.LayoutParams(0, 42.dp, 1f).apply { leftMargin = 10.dp })
        settingsCard.addView(settingsActions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })

        root.addView(settingsCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = 56.dp
            bottomMargin = 12.dp
        })
        root.addView(label("SolCube ${Updates.current(this)}", 12f, p.sub).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })

        return pinnedBackdropLayout(
            titleView = suiteTitle,
            body = root,
            titleLeftPx = 22.dp,
            titleTopPx = 28.dp,
            titleBottomGapPx = 22.dp,
            bodySidePadPx = 16.dp,
            bodyBottomPadPx = 24.dp,
            blurBelowTitlePx = 12.dp,
            fadeBelowTitlePx = 0,
            edgeToEdge = true,
            liftPx = 18.dp,
            liftStartPx = 44.dp,
            liftSpanPx = 110.dp,
            initialScrollY = initialScrollY,
            onScrollReady = { mainScroll = it },
        )
    }

    /** "Hold  350 ms   −  +" row for a trigger's hold time (50 ms steps). */
    private fun addHoldStepper(card: LinearLayout, key: String, current: () -> Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val value = label("", 13.5f, p.fg)
        fun show() { value.text = "Hold ${current()} ms" }
        show()
        row.addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(chip("−", false) { Components.setHold(this, key, current() - 50); show() },
            LinearLayout.LayoutParams(44.dp, 32.dp))
        row.addView(chip("+", false) { Components.setHold(this, key, current() + 50); show() },
            LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
        card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
    }

    private fun restartSolCube(btn: TextView? = null) {
        btn?.isEnabled = false
        btn?.text = "Restarting…"
        toast("Restarting SolCube (back in ~2 s)…")
        io.execute {
            if (Shell.state() == Shell.State.READY) {
                val res = Shell.command(this, Shell.Kind.UTIL, "restart-suite")
                if (res == "ok") return@execute
            }
            stopService(Intent(this, SolCubeService::class.java))
            Shell.reset()
            Thread.sleep(250)
            SolCubeService.sync(this)
            main.post {
                btn?.isEnabled = true
                btn?.text = "Restart SolCube"
                poll()
                toast("Restarted (Shizuku wasn't running)")
            }
        }
    }

    private fun uiPrefs() = getSharedPreferences("ui", MODE_PRIVATE)

    private inner class ChevronBadgeView(open: Boolean) : View(this) {
        private var arrowAngle = if (open) 180f else 0f
        private var rotAnim: android.animation.ValueAnimator? = null
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.dp.toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = alpha(p.fg, 0.8f)
        }
        private val path = android.graphics.Path()

        init {
            val dark = isDark()
            background = ripple(glass(15.dp, 1, if (dark) Color.argb(26, 255, 255, 255) else Color.argb(70, 255, 255, 255)), 15.dp)
            elevation = if (dark) 4.dp.toFloat() else 5.dp.toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                    o.alpha = 1f
                }
            }
            outlineAmbientShadowColor = if (dark) Color.argb(120, 0, 0, 0) else Color.argb(85, 30, 24, 18)
            outlineSpotShadowColor = if (dark) Color.argb(230, 0, 0, 0) else Color.argb(185, 30, 24, 18)
        }

        fun setOpen(open: Boolean, animate: Boolean, duration: Long = 480L) {
            rotAnim?.cancel()
            val target = if (open) 180f else 0f
            if (!animate || arrowAngle == target) {
                arrowAngle = target
                invalidate()
                return
            }
            rotAnim = android.animation.ValueAnimator.ofFloat(arrowAngle, target).apply {
                this.duration = duration
                interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener {
                    arrowAngle = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val halfW = 5.5f * resources.displayMetrics.density
            val halfH = 2.75f * resources.displayMetrics.density
            path.reset()
            path.moveTo(cx - halfW, cy - halfH)
            path.lineTo(cx, cy + halfH)
            path.lineTo(cx + halfW, cy - halfH)
            canvas.save()
            canvas.rotate(arrowAngle, cx, cy)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }

    private fun chevronBadge(open: Boolean) = ChevronBadgeView(open)

    private fun makeCollapsible(
        key: String,
        card: LinearLayout,
        headerClickTargets: List<View>,
        chevron: ChevronBadgeView,
        body: LinearLayout,
        defaultOpen: Boolean = false,
    ) {
        var open = uiPrefs().getBoolean("open_$key", defaultOpen)
        // after the animation the body must NOT clip: the glow on gold chips/buttons draws outside
        // their bounds (Glow.unclip turns clipping off later than this, so saving the value here
        // brought clipping back on every expand and cut the halos off square)
        val restClipChildren = false
        val restClipToPadding = false
        val initLp = body.layoutParams as? LinearLayout.LayoutParams
        val restTopMargin = initLp?.topMargin ?: 0
        val restBottomMargin = initLp?.bottomMargin ?: 0
        body.visibility = if (open) View.VISIBLE else View.GONE
        chevron.setOpen(open, animate = false)
        var anim: android.animation.ValueAnimator? = null
        val interp = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

        val toggleOpen = View.OnClickListener {
            open = !open
            uiPrefs().edit().putBoolean("open_$key", open).apply()
            anim?.cancel()
            val dur = if (open) 480L else 420L
            chevron.setOpen(open, animate = true, duration = dur)
            val lp = (body.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { body.layoutParams = it }
            val availW = (card.width - card.paddingLeft - card.paddingRight - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
            if (availW <= 0) {
                body.visibility = if (open) View.VISIBLE else View.GONE
                lp.height = WRAP_CONTENT
                lp.topMargin = restTopMargin
                lp.bottomMargin = restBottomMargin
                body.alpha = 1f
                body.clipChildren = restClipChildren
                body.clipToPadding = restClipToPadding
                return@OnClickListener
            }

            fun restoreAndFinish(visible: Boolean) {
                body.visibility = if (visible) View.VISIBLE else View.GONE
                lp.height = WRAP_CONTENT
                lp.topMargin = restTopMargin
                lp.bottomMargin = restBottomMargin
                body.alpha = 1f
                body.clipChildren = restClipChildren
                body.clipToPadding = restClipToPadding
                body.requestLayout()
                anim = null
            }

            if (open) {
                val startH = if (body.visibility == View.GONE) 0 else body.height
                lp.height = WRAP_CONTENT
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val targetH = body.measuredHeight
                if (targetH <= 0) {
                    restoreAndFinish(visible = true)
                    return@OnClickListener
                }
                body.clipChildren = true
                body.clipToPadding = false
                val startFrac = (startH.toFloat() / targetH).coerceIn(0f, 1f)
                lp.height = startH
                lp.topMargin = (restTopMargin * startFrac).toInt()
                lp.bottomMargin = (restBottomMargin * startFrac).toInt()
                body.alpha = startFrac
                body.visibility = View.VISIBLE
                body.requestLayout()
                var cancelled = false
                anim = android.animation.ValueAnimator.ofInt(startH, targetH).apply {
                    duration = dur
                    interpolator = interp
                    addUpdateListener { a ->
                        val h = a.animatedValue as Int
                        val frac = (h.toFloat() / targetH).coerceIn(0f, 1f)
                        lp.height = h
                        lp.topMargin = (restTopMargin * frac).toInt()
                        lp.bottomMargin = (restBottomMargin * frac).toInt()
                        body.alpha = frac
                        body.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (!cancelled) restoreAndFinish(visible = true)
                        }
                    })
                    start()
                }
            } else {
                val startH = if (body.visibility == View.GONE) 0 else body.height
                if (startH <= 0) {
                    restoreAndFinish(visible = false)
                    return@OnClickListener
                }
                body.clipChildren = true
                body.clipToPadding = false
                var cancelled = false
                anim = android.animation.ValueAnimator.ofInt(startH, 0).apply {
                    duration = dur
                    interpolator = interp
                    addUpdateListener { a ->
                        val h = a.animatedValue as Int
                        val frac = (h.toFloat() / startH).coerceIn(0f, 1f)
                        lp.height = h
                        lp.topMargin = (restTopMargin * frac).toInt()
                        lp.bottomMargin = (restBottomMargin * frac).toInt()
                        body.alpha = frac
                        body.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (!cancelled) restoreAndFinish(visible = false)
                        }
                    })
                    start()
                }
            }
        }
        chevron.setOnClickListener(toggleOpen)
        headerClickTargets.forEach { it.setOnClickListener(toggleOpen) }
    }

    private inner class FloatingCardHost : LinearLayout(this) {
        private val d = resources.displayMetrics.density
        private val cardRadius = 18f * d
        private val dy1 = 3f * d
        private val dy2 = 1.5f * d
        private val cardRect = android.graphics.RectF()
        private val shadowRect = android.graphics.RectF()
        private val cardPath = android.graphics.Path()
        private val dark = isDark()
        private val ambientPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = if (dark) Color.argb(68, 0, 0, 0) else Color.argb(20, 28, 22, 16)
            maskFilter = android.graphics.BlurMaskFilter(18f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private val spotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = if (dark) Color.argb(24, 0, 0, 0) else Color.argb(7, 28, 22, 16)
            maskFilter = android.graphics.BlurMaskFilter(9f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility != View.VISIBLE || c.width <= 0 || c.height <= 0) continue
                val l = c.left + c.translationX
                val t = c.top + c.translationY
                val r = l + c.width
                val b = t + c.height
                cardRect.set(l, t, r, b)
                cardPath.reset()
                cardPath.addRoundRect(cardRect, cardRadius, cardRadius, android.graphics.Path.Direction.CW)
                canvas.save()
                canvas.clipOutPath(cardPath)
                shadowRect.set(l, t + dy1, r, b + dy1)
                canvas.drawRoundRect(shadowRect, cardRadius, cardRadius, ambientPaint)
                shadowRect.set(l, t + dy2, r, b + dy2)
                canvas.drawRoundRect(shadowRect, cardRadius, cardRadius, spotPaint)
                canvas.restore()
            }
            super.dispatchDraw(canvas)
        }
    }

    private fun groupCard(key: String, title: String, defaultOpen: Boolean = false): GroupCard {
        val card = card()
        card.setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(label(title, 20f, p.fg, bold = true))
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        statusRow.addView(dot, LinearLayout.LayoutParams(7.dp, 7.dp).apply { rightMargin = 7.dp })
        val summary = label("", 12.5f, p.sub).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusRow.addView(summary, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        titleCol.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        header.addView(titleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chevron = chevronBadge(defaultOpen)
        header.addView(chevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 10.dp })
        card.addView(header)
        val body = FloatingCardHost().apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(16.dp, 10.dp, 16.dp, 16.dp)
        }
        card.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = (-16).dp
            rightMargin = (-16).dp
            topMargin = 2.dp
            bottomMargin = (-14).dp
        })
        makeCollapsible("group_$key", card, listOf(header), chevron, body, defaultOpen = defaultOpen)
        return GroupCard(card, dot, summary, body)
    }

    /** A switchable block: its own card, an inner card, or (with [into]) a sub-row inside a card. */
    private fun section(key: String, title: String, into: LinearLayout? = null, collapsible: Boolean = into == null, inner: Boolean = false, compact: Boolean = false): Section {
        val card = into ?: if (inner) innerCard() else card()
        if (compact) card.setPadding(16.dp, 16.dp, 16.dp, 16.dp)   // same as the group cards
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // compact: title and status line stacked on the left like the group cards, buttons to the right
        val titleCol = if (compact) LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } else null
        val titleView = label(title, if (into != null) 15.5f else if (inner) 16.5f else if (compact) 20f else 18f, p.fg, bold = true)
        if (titleCol != null) {
            titleCol.addView(titleView)
            header.addView(titleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        } else header.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chevron = if (collapsible) chevronBadge(false).also {
            header.addView(it, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
        } else null
        lateinit var sec: Section
        val toggle = label("", if (inner) 14f else 14.5f, p.onAccent, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = if (inner) 84.dp else 96.dp
            setPadding(if (inner) 14.dp else 18.dp, 0, if (inner) 14.dp else 18.dp, 0)
            setOnClickListener { toggle(sec) }
        }
        header.addView(toggle, LinearLayout.LayoutParams(WRAP_CONTENT, if (inner) 36.dp else 40.dp).apply { leftMargin = 8.dp })
        card.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (into != null) topMargin = 14.dp
        })

        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        if (compact) statusRow.addView(dot, LinearLayout.LayoutParams(7.dp, 7.dp).apply { rightMargin = 7.dp })
        else statusRow.addView(dot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        val status = if (compact) label("", 12.5f, p.sub).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        } else label("", 14.5f, p.sub, bold = true)
        if (compact) statusRow.addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) else statusRow.addView(status)
        if (titleCol != null) titleCol.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        else card.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })

        val detail = label("", 13f, p.sub).apply { setLineSpacing(2.dp.toFloat(), 1f) }
        // compact cards keep the face as short as the group cards: extra lines go inside, under the chevron
        if (!compact) card.addView(detail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

        val body = if (collapsible && chevron != null) {
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { b ->
                card.addView(b)
                makeCollapsible(key, card, listOf(header, statusRow, detail), chevron, b, defaultOpen = false)
            }
        } else card
        if (compact) body.addView(detail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })

        sec = Section(key, card, body, toggle, dot, status, detail, compact)
        return sec
    }

    // ------------------------------------------------------------------ FI + SR controls

    private lateinit var gameList: LinearLayout
    private lateinit var thresholds: LinearLayout

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun mono(size: Float) = label("", size, p.sub).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun addGameControls(card: LinearLayout) {
        // (the raw fi / gpu values and the log are in Diagnostics)
        card.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 12.dp; bottomMargin = 6.dp })
        card.addView(label("Games", 14.5f, p.fg, bold = true).apply { setPadding(0, 8.dp, 0, 2.dp) })
        card.addView(label("Tap the ratio to change it. Only whole multiples work.", 12f, p.sub))
        gameList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(gameList)

        // add a game by package name
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = android.widget.EditText(this).apply {
            hint = "package name, e.g. com.game.app"
            setSingleLine()
            textSize = 14f
            setTextColor(p.fg)
            setHintTextColor(p.sub)
            typeface = fontRegular
            background = glass(14.dp)
            setPadding(14.dp, 0, 14.dp, 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        addRow.addView(field, LinearLayout.LayoutParams(0, 44.dp, 1f))
        addRow.addView(pillButton("Add", filled = true) {
            val pkg = field.text.toString().trim()
            if (!pkg.matches(Regex("[A-Za-z][\\w]*(\\.[A-Za-z_][\\w]*)+"))) { toast("Enter a package name"); return@pillButton }
            val list = Components.games(this)
            if (list.any { it.pkg == pkg }) { toast("Already in the list"); return@pillButton }
            Components.saveGames(this, list + Components.Game(pkg, 60, 120))
            field.setText("")
            renderGames()
        }, LinearLayout.LayoutParams(80.dp, 44.dp).apply { leftMargin = 8.dp })
        card.addView(addRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })

        card.addView(label("Heat pause (battery)", 14.5f, p.fg, bold = true).apply { setPadding(0, 16.dp, 0, 2.dp) })
        thresholds = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(thresholds)

        renderGames()
    }

    private fun chip(t: String, selected: Boolean, onClick: () -> Unit) =
        label(t, 13f, if (selected) p.onAccent else p.fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(12.dp, 0, 12.dp, 0)
            background = ripple(if (selected) accentGradient(16.dp) else glass(16.dp), 16.dp)
            setOnClickListener { onClick() }
        }

    /** Fine volume: media volume in 1 % steps (vivo's stream has 150 steps; the buttons jump 10). */
    private fun buildFineVolumeCard(): LinearLayout {
        val c = innerCard()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Fine volume", 16.5f, p.fg, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val styles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(styles)
        c.addView(head)
        c.addView(label("Media volume in 1 % steps. The volume buttons still jump 10 % at a time.", 12.5f, p.sub),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        volDial = VolumeDial(this, p, isDark(), fontMedium, FineVolume.knob(this)) { v -> FineVolume.set(this, v) }
        volDial.value = FineVolume.percent(this)
        c.addView(volDial, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        fun drawStyles() {
            styles.removeAllViews()
            val knob = FineVolume.knob(this)
            styles.addView(chip("Knob", knob) { FineVolume.setKnob(this, true); volDial.knob = true; volDial.requestLayout(); volDial.invalidate(); drawStyles() },
                LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
            styles.addView(chip("Slider", !knob) { FineVolume.setKnob(this, false); volDial.knob = false; volDial.requestLayout(); volDial.invalidate(); drawStyles() },
                LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
        }
        drawStyles()
        return c
    }

    private fun renderGames() {
        gameList.removeAllViews()
        val list = Components.games(this)
        list.forEachIndexed { i, g ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(appName(g.pkg), 14f, p.fg).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            names.addView(label(g.pkg, 10.5f, p.sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            row.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            fun update(ng: Components.Game?) {
                val nl = list.toMutableList()
                if (ng == null) nl.removeAt(i) else nl[i] = ng
                Components.saveGames(this, nl)
                renderGames()
            }
            row.addView(chip("${g.src}→${g.dst}", false) {
                update(if (g.src == 45) g.copy(src = 60, dst = 120) else g.copy(src = 45, dst = 90))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 8.dp })
            row.addView(label("✕", 15f, p.sub).apply {
                gravity = Gravity.CENTER
                background = ripple(null, 16.dp)
                setOnClickListener { update(null) }
            }, LinearLayout.LayoutParams(32.dp, 32.dp).apply { leftMargin = 4.dp })
            gameList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        }

        thresholds.removeAllViews()
        fun stepper(title: String, value: Int, set: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(String.format(java.util.Locale.US, "%s %.1f °C", title, value / 10f), 13.5f, p.fg),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(chip("−", false) { set(value - 5) }, LinearLayout.LayoutParams(44.dp, 32.dp))
            row.addView(chip("+", false) { set(value + 5) }, LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
            thresholds.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        }
        val hot = Components.hot(this); val cool = Components.cool(this)
        stepper("Pause at", hot) { v -> if (v in (cool + 5)..550) { Components.saveGames(this, list, hot = v); renderGames() } }
        stepper("Resume at", cool) { v -> if (v in 300..(hot - 5)) { Components.saveGames(this, list, cool = v); renderGames() } }
    }

    private fun addTempSettings(card: LinearLayout) {
        val prefs = Style.prefs(this)
        val divider = View(this).apply { setBackgroundColor(p.stroke) }
        card.addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp; bottomMargin = 4.dp })

        fun slider(title: (Int) -> String, min: Int, max: Int, value: Int, save: (Int) -> Unit) {
            val l = label(title(value), 13.5f, p.fg).apply { setPadding(0, 10.dp, 0, 0) }
            card.addView(l)
            card.addView(SeekBar(this).apply {
                this.max = max - min
                progress = value - min
                progressTintList = ColorStateList.valueOf(p.accent)
                thumbTintList = ColorStateList.valueOf(p.accent)
                progressBackgroundTintList = ColorStateList.valueOf(p.sub)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                        if (fromUser) { l.text = title(v + min); save(v + min) }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = (-8).dp; rightMargin = (-8).dp })
        }
        slider({ "Background opacity: $it%" }, 0, 100, prefs.getInt(Style.KEY_BG_OPACITY, Style.DEFAULT_BG_OPACITY)) {
            prefs.edit().putInt(Style.KEY_BG_OPACITY, it).apply()
        }
        slider({ "Size: $it%" }, Style.MIN_SIZE, Style.MAX_SIZE, prefs.getInt(Style.KEY_SIZE, Style.DEFAULT_SIZE)) {
            prefs.edit().putInt(Style.KEY_SIZE, it).apply()
        }
        card.addView(Switch(this).apply {
            text = "Gray numbers"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            styleSwitch(this)
            isChecked = prefs.getBoolean(Style.KEY_GRAY, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Style.KEY_GRAY, on).apply() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        card.addView(Switch(this).apply {
            text = "Show CPU & GPU load"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            styleSwitch(this)
            isChecked = Style.showLoad(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(Style.KEY_SHOW_LOAD, on).apply()
                if (on && Components.enabled(this@MainActivity, Components.KEY_TEMP)) {
                    io.execute { Shell.start(this@MainActivity, Shell.Kind.TEMP) }
                }
                poll()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        card.addView(Switch(this).apply {
            text = "Load as bars (upright in portrait)"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            styleSwitch(this)
            isChecked = Style.loadBars(this@MainActivity)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Style.KEY_LOAD_BARS, on).apply() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        card.addView(Switch(this).apply {
            text = "Blur background"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            styleSwitch(this)
            isChecked = Style.blur(this@MainActivity)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Style.KEY_BLUR, on).apply() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
    }

    // ------------------------------------------------------------------ stream to laptop

    private lateinit var streamDot: View
    private lateinit var streamStatus: TextView
    private lateinit var streamDetail: TextView
    private lateinit var wideBtn: TextView
    private lateinit var streamBtn: TextView
    private var streamBusy = false

    private fun buildStreamCard(inner: Boolean = false): LinearLayout {
        val card = if (inner) innerCard() else card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(betaTitle("Stream to laptop", if (inner) 16.5f else 18f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chevron = chevronBadge(false)
        header.addView(chevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
        card.addView(header)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        streamDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        statusRow.addView(streamDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        streamStatus = label("", 14.5f, p.sub, bold = true)
        statusRow.addView(streamStatus)
        card.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        streamDetail = label("", 13f, p.sub).apply { setLineSpacing(2.dp.toFloat(), 1f) }
        card.addView(streamDetail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wideBtn = pillButton("", filled = true) { streamAction(wide = true) }
        streamBtn = pillButton("", filled = true) { streamAction(wide = false) }
        row.addView(wideBtn, LinearLayout.LayoutParams(0, 44.dp, 1f))
        row.addView(streamBtn, LinearLayout.LayoutParams(0, 44.dp, 1f).apply { leftMargin = 8.dp })
        card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label("The phone stays the touch controller, dimmed. Exit from the round button on the phone, or Ctrl+Alt+X on the laptop. If the cable is pulled, the screen goes back to normal by itself.", 12f, p.sub),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })

        // launch buttons shown on the bubble
        body.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp; bottomMargin = 6.dp })
        body.addView(label("Apps on the bubble", 14.5f, p.fg, bold = true).apply { setPadding(0, 8.dp, 0, 2.dp) })
        body.addView(label("Launch buttons in the bubble's panel, plus Back and Home.", 12f, p.sub))
        streamAppList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(streamAppList)
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = android.widget.EditText(this).apply {
            hint = "package name, e.g. com.game.app"
            setSingleLine()
            textSize = 14f
            setTextColor(p.fg)
            setHintTextColor(p.sub)
            typeface = fontRegular
            background = glass(14.dp)
            setPadding(14.dp, 0, 14.dp, 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        addRow.addView(field, LinearLayout.LayoutParams(0, 44.dp, 1f))
        addRow.addView(pillButton("Add", filled = true) {
            val pkg = field.text.toString().trim()
            if (!pkg.matches(Regex("[A-Za-z][\\w]*(\\.[A-Za-z_][\\w]*)+"))) { toast("Enter a package name"); return@pillButton }
            if (packageManager.getLaunchIntentForPackage(pkg) == null) { toast("No app with that package is installed"); return@pillButton }
            val list = Components.streamApps(this)
            if (pkg in list) { toast("Already in the list"); return@pillButton }
            Components.saveStreamApps(this, list + pkg)
            field.setText("")
            renderStreamApps()
        }, LinearLayout.LayoutParams(80.dp, 44.dp).apply { leftMargin = 8.dp })
        body.addView(addRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        card.addView(body)
        makeCollapsible("stream", card, listOf(header, statusRow, streamDetail), chevron, body, defaultOpen = false)
        renderStreamApps()
        return card
    }

    private lateinit var streamAppList: LinearLayout

    private fun renderStreamApps() {
        streamAppList.removeAllViews()
        val list = Components.streamApps(this)
        list.forEach { pkg ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(appName(pkg), 14f, p.fg).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            names.addView(label(pkg, 10.5f, p.sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            row.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(label("✕", 15f, p.sub).apply {
                gravity = Gravity.CENTER
                background = ripple(null, 16.dp)
                setOnClickListener { Components.saveStreamApps(this@MainActivity, list - pkg); renderStreamApps() }
            }, LinearLayout.LayoutParams(32.dp, 32.dp).apply { leftMargin = 4.dp })
            streamAppList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        }
    }

    private fun renderStream(st: org.json.JSONObject?, shellOk: Boolean) {
        val mode = Components.enabled(this, Components.KEY_STREAM)
        val streaming = st?.optBoolean("streaming") == true
        val helper = st?.optBoolean("helper") == true
        streamStatus.put(when {
            streaming -> "Streaming"
            mode -> "Laptop shape on"
            else -> "Off"
        })
        val live = mode || streaming
        streamStatus.setTextColor(if (live) p.ok else p.sub)
        (streamDot.background as GradientDrawable).setColor(if (live) p.ok else alpha(p.sub, 0.5f))
        streamDetail.put(when {
            !shellOk -> "Needs Shizuku"
            mode && !helper -> "Laptop not reachable — screen resets in 15 s"
            mode -> "Phone screen matches the laptop. Start the stream when the game is open."
            else -> "Plug in the cable; the laptop helper must be running."
        })
        wideBtn.put(if (streamBusy) "…" else if (mode) "Normal screen" else "16:10 screen")
        streamBtn.put(if (streaming) "Stop stream" else "Start stream")
        listOf(wideBtn to !mode, streamBtn to !streaming).forEach { (b, filled) ->
            if (b.tag == filled) return@forEach
            b.tag = filled
            b.setTextColor(if (filled) p.onAccent else p.fg)
            b.background = ripple(if (filled) accentGradient(24.dp) else glass(24.dp), 24.dp)
        }
    }

    private fun streamAction(wide: Boolean) {
        if (streamBusy) return
        val mode = Components.enabled(this, Components.KEY_STREAM)
        val streaming = last?.stream?.optBoolean("streaming") == true
        val hasOverlay = Settings.canDrawOverlays(this) || SolCubeA11y.instance != null ||
            SolCubeA11y.isEnabled(this) || Shell.state() == Shell.State.READY
        if (!hasOverlay) { openOverlaySettings(); return }
        streamBusy = true
        render()
        io.execute {
            val r = when {
                wide -> Components.setStreamMode(this, !mode)
                streaming -> Components.streamCmd(this, "stream off")
                else -> {
                    // "Start stream" also switches the phone to the laptop's shape
                    val w = if (!mode) Components.setStreamMode(this, true) else "ok"
                    if (w != "ok") w else Components.streamCmd(this, "stream on")
                }
            }
            val s = Components.status()
            if (r == "ok") Island.show(this, when {
                wide -> if (!mode) "Laptop shape on" else "Laptop shape off"
                streaming -> "Stream off"
                else -> "Stream on"
            })
            main.post {
                streamBusy = false; last = s; render()
                when (r) {
                    "no-helper" -> toast("Laptop helper not found — is the cable in and the helper running?")
                    "no-shell" -> toast("Shizuku isn't ready")
                }
            }
        }
    }

    // ------------------------------------------------------------------ default assistant + double press

    /** The hidden "Assist & voice input" page (same fallbacks as the Double Power Key app). */
    private fun openDefaultAssistantSettings() {
        for (a in listOf("android.settings.VOICE_INPUT_SETTINGS", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "android.settings.MANAGE_ASSISTANT")) {
            try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.google.android.googlequicksearchbox")))
        }
    }

    private lateinit var dpDot: View
    private lateinit var dpStatus: TextView
    private lateinit var dpDetail: TextView
    private lateinit var dpMake: TextView
    private lateinit var dpChoices: LinearLayout
    private var dpBusy = false

    private fun buildDoubleCard(inner: Boolean = false): LinearLayout {
        val card = if (inner) innerCard() else card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(label("Double power press", if (inner) 16.5f else 18f, p.fg, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chevron = chevronBadge(false)
        header.addView(chevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
        card.addView(header)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dpDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        statusRow.addView(dpDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        dpStatus = label("", 14.5f, p.sub, bold = true)
        statusRow.addView(dpStatus)
        card.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        dpDetail = label("", 13f, p.sub)
        card.addView(dpDetail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        dpMake = pillButton("Make SolCube the handler", filled = true) {
            if (dpBusy) return@pillButton
            dpBusy = true
            renderDouble()
            io.execute {
                val ok = DoublePress.makeDefault(this)
                main.post {
                    dpBusy = false
                    renderDouble()
                    if (!ok) toast("Couldn't set it (is Shizuku running?)")
                }
            }
        }
        card.addView(dpMake, LinearLayout.LayoutParams(MATCH_PARENT, 44.dp).apply { topMargin = 12.dp })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label("Does", 12.5f, p.sub).apply { setPadding(0, 14.dp, 0, 0) })
        dpChoices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dpChoices)

        // Android's single-vs-double press window
        val windowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val windowLabel = label("", 13.5f, p.fg)
        var pending = DoublePress.window(this)
        fun showWindow() { windowLabel.text = "Time between presses: $pending ms" }
        showWindow()
        val save = Runnable { io.execute { val ok = DoublePress.setWindow(this, pending); if (!ok) main.post { toast("Couldn't save it (is Shizuku running?)") } } }
        fun step(d: Int) {
            pending = (pending + d).coerceIn(DoublePress.WINDOW_MIN, DoublePress.WINDOW_MAX)
            showWindow()
            main.removeCallbacks(save); main.postDelayed(save, 800)   // save once the taps stop
        }
        windowRow.addView(windowLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        windowRow.addView(chip("−", false) { step(-50) }, LinearLayout.LayoutParams(44.dp, 32.dp))
        windowRow.addView(chip("+", false) { step(50) }, LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
        body.addView(windowRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        body.addView(label("Android's own setting (default 300 ms). Takes effect after a restart. A single press locks the screen that much later.", 12f, p.sub))

        body.addView(Switch(this).apply {
            text = "Short vibration on double press"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            styleSwitch(this)
            isChecked = DoublePress.vibrate(this@MainActivity)
            setOnCheckedChangeListener { _, c -> DoublePress.setVibrate(this@MainActivity, c) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        card.addView(body)
        makeCollapsible("double", card, listOf(header, statusRow, dpDetail), chevron, body, defaultOpen = false)
        renderDouble()
        return card
    }

    private fun renderDouble() {
        val h = DoublePress.handler(this)
        val mine = h == packageName
        val wallet = DoublePress.walletSwitchOn(this)
        val ready = mine && wallet
        dpStatus.put(when {
            ready -> "SolCube handles it"
            mine -> "vivo's double press is off"
            else -> "Not handled by SolCube"
        })
        dpStatus.setTextColor(if (ready) p.ok else p.sub)
        (dpDot.background as GradientDrawable).setColor(if (ready) p.ok else alpha(p.sub, 0.5f))
        dpDetail.put(when {
            ready -> "Double-press the power button to " + actionText(DoublePress.action) + "."
            mine -> "Its switch in vivo Wallet (“Double press the Power button to quickly swipe cards”) is off. Set up turns it on."
            h == null -> "Android asks which app to use each time."
            else -> "Right now it goes to ${appName(h)}."
        })
        dpMake.visibility = if (ready) View.GONE else View.VISIBLE
        dpMake.text = if (dpBusy) "…" else "Set up double press"
        renderChoices(dpChoices, DoublePress.action, cts = true) { renderDouble() }
    }

    /** "open Mir Pay" / "turn silent mode on or off" … for the status line. */
    private fun actionText(pa: PressAction): String = when (pa.action(this)) {
        PressAction.CTS -> "open Circle to Search"
        PressAction.RING_CYCLE -> "go Ring → Vibrate → Silent"
        PressAction.RING_SILENT -> "turn silent mode on or off"
        PressAction.RING_VIBRATE -> "turn vibrate mode on or off"
        PressAction.CUSTOM -> pa.customAction(this)?.let { customActionPhrase(it) } ?: "run the action picked below"
        else -> pa.target(this).takeIf { it.isNotEmpty() }?.let { "open ${appName(it)}" } ?: "open the app picked below"
    }

    private fun customActionPhrase(c: org.json.JSONObject): String {
        val type = c.optString("type")
        val allRoutines = if (type == "run_routine") Routines.load(this) else emptyList()
        val rtName: (String) -> String = { rid -> allRoutines.firstOrNull { it.optString("id") == rid }?.optString("name") ?: "?" }
        val desc = Actions.describe(c, ::appName, rtName)
        return when {
            type in setOf("gemini_cts", "gemini_live", "circle_to_search") -> "open $desc"
            type == "notice" -> "show $desc"
            type == "shell_cmd" -> c.optString("cmd").trim().takeIf { it.isNotEmpty() }?.let { "run $it" } ?: "run shell command"
            c.optString("value") == "toggle" -> "toggle ${Actions.ALL[type] ?: type}"
            type == "media_control" -> "control media (${Actions.valueText(type, c.optString("value", "play_pause"))})"
            type in setOf("open_app", "open_url", "run_routine", "go_home", "lock", "wake_screen",
                "screenshot", "vibrate", "close_app", "close_apps", "launch_bg", "revive", "wait") ->
                desc.replaceFirstChar { it.lowercase() }
            else -> "set $desc"
        }
    }

    private fun amberChip(t: String, selected: Boolean, onClick: () -> Unit): TextView {
        val dark = isDark()
        val bg: Drawable = when {
            selected && dark -> accentGradient(16.dp)
            selected -> LiquidGlass(
                p, 16.dp.toFloat(), dark = false, resources.displayMetrics.density, level = 2,
                tint = Color.argb(245, 0xE6, 0x9A, 0x28),
                shine = 0.45f,
                halo = Color.argb(90, 0xF4, 0xB2, 0x4C),
                stroke = Color.argb(210, 0xB8, 0x76, 0x0A),
            )
            else -> LiquidGlass(
                p, 16.dp.toFloat(), dark, resources.displayMetrics.density, level = 2,
                tint = Color.argb(if (dark) 58 else 52, 0xF4, 0xB2, 0x4C),
                stroke = if (dark) Color.argb(175, 0xFF, 0xCE, 0x4A) else Color.argb(170, 0xB8, 0x76, 0x0A),
            )
        }
        val fg = if (selected) Color.rgb(0x1E, 0x10, 0x02) else p.link
        return label(t, 13f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(12.dp, 0, 12.dp, 0)
            background = ripple(bg, 16.dp)
            setOnClickListener { onClick() }
        }
    }

    private fun pickCustomPressAction(pa: PressAction, rerender: () -> Unit) {
        val flatKeys = Actions.GROUPS.flatMap { (_, items) -> items.map { it.first } }
        val groups = Actions.GROUPS.map { (hdr, items) -> hdr to items.map { it.second } }
        val allRoutines = Routines.load(this)
        SolCubeDialog.Builder(this).setTitle("Pick action")
            .setGroupedItems(groups) { _, w ->
                val k = flatKeys[w]
                val picked = Actions.defaultAction(k, allowRestore = false, allowToggle = true)
                pa.setCustomAction(this, picked)
                rerender()
                if (k in setOf("revive", "launch_bg", "close_app", "open_app", "notice", "open_url", "shell_cmd", "run_routine")) {
                    editActionDetail(k, picked, allRoutines, null) {
                        pa.setCustomAction(this, picked)
                        rerender()
                    }
                } else {
                    val vals = Actions.values(k, allowRestore = false, allowToggle = true)
                    if (vals.isNotEmpty()) {
                        SolCubeDialog.Builder(this).setTitle(Actions.ALL[k] ?: k)
                            .setItems(vals.map { Actions.valueText(k, it) }.toTypedArray()) { _, vw ->
                                picked.put("value", vals[vw])
                                pa.setCustomAction(this, picked)
                                rerender()
                            }.show()
                    }
                }
            }.show()
    }

    /** The choices of a power-button action: apps (tap to use, hold to remove), sound modes, [cts], or any action. */
    private fun renderChoices(box: LinearLayout, pa: PressAction, cts: Boolean, rerender: () -> Unit) {
        val act = pa.action(this)
        val custom = if (act == PressAction.CUSTOM) pa.customAction(this) else null
        val allRoutines = if (custom?.optString("type") == "run_routine") Routines.load(this) else emptyList()
        val rtKey = if (custom?.optString("type") == "run_routine") {
            allRoutines.firstOrNull { it.optString("id") == custom.optString("routineId") }?.optString("name").orEmpty()
        } else ""
        val t = pa.target(this)
        val apps = pa.apps(this)
        // called on every 2 s refresh: rebuilding the chips (new glass shaders) each time made the page hitch
        val key = "$act|${custom?.toString().orEmpty()}|$rtKey|$t|${apps.joinToString(",")}|$cts|${isDark()}"
        if (box.tag == key) return
        box.tag = key
        box.removeAllViews()
        box.addView(hScroll(*apps.map { pkg ->
            chip(appName(pkg), pkg == t && act == null) { pa.setTarget(this, pkg); rerender() }.apply {
                setOnLongClickListener {
                    SolCubeDialog.Builder(this@MainActivity).setMessage("Remove ${appName(pkg)} from the list?")
                        .setPositiveButton("Remove") { _, _ ->
                            val rest = apps - pkg
                            pa.saveApps(this@MainActivity, rest)
                            if (pkg == t) {
                                if (rest.isNotEmpty()) pa.setTarget(this@MainActivity, rest.first())
                                else pa.setAction(this@MainActivity, PressAction.RING_SILENT)
                            }
                            rerender()
                        }.setNegativeButton("Cancel", null).show()
                    true
                }
            }
        }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        box.addView(textButton("Add app") { pickActionApp(pa, rerender) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 2.dp; leftMargin = (-12).dp })
        if (apps.isNotEmpty()) box.addView(label("Tap an app to use it; hold it to remove it.", 12f, p.sub))

        // …or something other than opening an app
        box.addView(label("Or", 12.5f, p.sub).apply { setPadding(0, 12.dp, 0, 0) })
        val others = mutableListOf<Pair<String, String>>()
        if (cts) others += PressAction.CTS to "Circle to Search"
        others += PressAction.RING_CYCLE to "Ring › Vibrate › Silent"
        others += PressAction.RING_SILENT to "Silent on/off"
        others += PressAction.RING_VIBRATE to "Vibrate on/off"
        val chips = others.map { (c, name) ->
            chip(name, act == c) {
                pa.setAction(this, c)
                rerender()
                if (c.startsWith("ring:")) io.execute { Ringer.ensureAccess(this) }   // instant changes (silent needs DND access)
            }
        } + amberChip("Other", act == PressAction.CUSTOM) {
            pickCustomPressAction(pa, rerender)
        }
        box.addView(hScroll(*chips.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

        if (custom != null) {
            val rtList = if (custom.optString("type") == "run_routine") allRoutines else Routines.load(this)
            val rtName: (String) -> String = { rid -> rtList.firstOrNull { it.optString("id") == rid }?.optString("name") ?: "Pick routine" }
            val customRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            customRow.addView(label(Actions.describe(custom, ::appName, rtName), 13.5f, p.fg, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addActionOptionChips(customRow, custom, isRoutine = false, allowToggle = true, allRoutines = rtList, selfRoutineId = null) {
                pa.setCustomAction(this, custom)
                rerender()
            }
            box.addView(customRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        }
    }

    private fun pickSingleApp(title: String, exclude: Collection<String>, onPick: (String) -> Unit) {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(launch, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName && it !in exclude }.map { it to appName(it) }.sortedBy { it.second.lowercase() }
        SolCubeDialog.Builder(this).setTitle(title)
            .setItems(list.map { it.second }.toTypedArray()) { _, w -> onPick(list[w].first) }
            .show()
    }

    private fun pickActionApp(pa: PressAction, rerender: () -> Unit) {
        val have = pa.apps(this)
        pickSingleApp("Add an app", have) { pkg ->
            pa.saveApps(this, have + pkg)
            pa.setTarget(this, pkg)
            rerender()
        }
    }

    // ------------------------------------------------------------------ power-button hold (not CTS)

    private lateinit var holdSec: Section
    private lateinit var holdChoices: LinearLayout

    private fun buildHoldCard(inner: Boolean = false): LinearLayout {
        holdSec = section(Components.KEY_HOLD_ACT, "Power button hold", inner = inner)
        val body = holdSec.body
        body.addView(label("Does", 12.5f, p.sub).apply { setPadding(0, 14.dp, 0, 0) })
        holdChoices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(holdChoices)
        addHoldStepper(body, Components.KEY_BUTTON_HOLD) { Components.buttonHold(this) }
        body.addView(label("Same hold as Circle to Search's power-button hold: turning this on turns that off, and the other way round.", 12f, p.sub),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        renderHold()
        return holdSec.card
    }

    private fun renderHold() {
        renderChoices(holdChoices, PowerHold.action, cts = false) { renderHold(); render() }
    }

    // ------------------------------------------------------------------ routines

    private lateinit var rtDot: View
    private lateinit var rtStatus: TextView
    private lateinit var rtDetail: TextView
    private lateinit var rtList: LinearLayout
    private var rtLastRun: org.json.JSONObject? = null

    private fun buildRoutinesCard(inner: Boolean = false): LinearLayout {
        val card = if (inner) innerCard() else card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(betaTitle("Routines", if (inner) 16.5f else 18f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chevron = chevronBadge(true)
        header.addView(chevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
        card.addView(header)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rtDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        statusRow.addView(rtDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        rtStatus = label("", 14.5f, p.sub, bold = true)
        statusRow.addView(rtStatus)
        card.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        rtDetail = label("", 13f, p.sub)
        card.addView(rtDetail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rtList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rtList)
        body.addView(textButton("New routine") { editRoutine(null) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = 8.dp; leftMargin = (-12).dp
        })
        card.addView(body)
        makeCollapsible("routines", card, listOf(header, statusRow, rtDetail), chevron, body, defaultOpen = true)
        renderRoutineList()
        return card
    }

    private fun shortLastRun(id: String): String? {
        val t = rtLastRun?.optLong(id, 0L) ?: 0L
        if (t <= 0) return null
        val d = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val time = d.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return when (d.toLocalDate()) {
            today -> time
            today.minusDays(1) -> "yest $time"
            else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", java.util.Locale.US))
        }
    }

    private fun playButton(onClick: () -> Unit): View {
        val d = resources.displayMetrics.density
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = alpha(p.fg, 0.88f)
            pathEffect = android.graphics.CornerPathEffect(1.8f * d)
        }
        val path = android.graphics.Path()
        return object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                val cx = width / 2f + 1.0f * d
                val cy = height / 2f
                val rx = 4.6f * d
                val ry = 5.4f * d
                path.reset()
                path.moveTo(cx - rx, cy - ry)
                path.lineTo(cx + rx, cy)
                path.lineTo(cx - rx, cy + ry)
                path.close()
                canvas.drawPath(path, paint)
            }
        }.apply {
            background = ripple(glass(16.dp), 16.dp)
            setOnClickListener { onClick() }
        }
    }

    private fun renderRoutineList() {
        rtList.removeAllViews()
        val all = Routines.load(this)
        fun addRow(r: org.json.JSONObject, into: LinearLayout) {
            into.addView(View(this).apply { setBackgroundColor(p.stroke) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 6.dp })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8.dp, 0, 8.dp)
                background = ripple(null, 14.dp)
                setOnClickListener { editRoutine(r) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val on = r.optBoolean("on", true)
            val id = r.optString("id")
            val name = r.optString("name")
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(name, 15f, if (on) p.fg else p.sub, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            shortLastRun(id)?.let {
                top.addView(label(it, 11.5f, p.ok).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp })
            }
            val rtName: (String) -> String = { id -> all.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?" }
            col.addView(top)
            col.addView(label("${Routines.summary(r, all, ::appName) { GestureStore.name(this, it) }}  →  ${Routines.actionsText(r, ::appName, rtName)}", 12f, p.sub).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 2.dp, 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(playButton {
                io.execute {
                    val ok = routinesCmd("run $id") == "ok"
                    main.post { toast(if (ok) "Running “$name”" else "Routines engine isn't running"); poll() }
                }
            }, LinearLayout.LayoutParams(34.dp, 32.dp).apply { leftMargin = 8.dp })
            row.addView(chip(if (on) "On" else "Off", on) {
                r.put("on", !on)
                Island.show(this, "${r.optString("name")} ${if (!on) "on" else "off"}")
                io.execute {
                    Routines.save(this, all)
                    main.post {
                        renderRoutineList()
                        renderRoutines(last?.routines, last?.shell == Shell.State.READY)
                        poll()
                    }
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            into.addView(row)
        }
        val isGesture = { r: org.json.JSONObject -> r.optJSONObject("trigger")?.optString("type") == "gesture" }
        all.filterNot(isGesture).forEach { addRow(it, rtList) }
        val gr = all.filter(isGesture)
        if (gr.isEmpty()) return
        // gesture routines in their own box (collapsed by default) so the list stays short
        rtList.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 6.dp })
        val box = innerCard()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val onN = gr.count { it.optBoolean("on", true) }
        header.addView(label("Gesture routines", 15f, p.fg, bold = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        header.addView(label("$onN/${gr.size} on", 12.5f, p.sub).apply { setPadding(10.dp, 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chevron = chevronBadge(false)
        header.addView(chevron, LinearLayout.LayoutParams(30.dp, 30.dp))
        box.addView(header)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gr.forEach { addRow(it, inner) }
        box.addView(inner)
        makeCollapsible("routines_gestures", box, listOf(header), chevron, inner, defaultOpen = false)
        rtList.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
    }

    private fun renderRoutines(st: org.json.JSONObject?, shellOk: Boolean) {
        val all = Routines.load(this)
        val total = all.size
        val onCount = all.count { it.optBoolean("on", true) }
        val any = onCount > 0
        val running = st?.optBoolean("running") == true
        val active = running && any
        rtStatus.put(when {
            !active -> "Not running"
            onCount == total -> "All running"
            else -> "$onCount/$total running"
        })
        rtStatus.setTextColor(if (active) p.ok else p.sub)
        (rtDot.background as GradientDrawable).setColor(if (active) p.ok else alpha(p.sub, 0.5f))
        rtDetail.put(when {
            !any -> "All routines are off"
            !shellOk -> "Waiting for Shizuku"
            st == null -> "Starting…"
            st.optString("error").isNotEmpty() -> "Can't listen for events, checking every 30 s instead"
            else -> "Tap a routine to edit it"
        })
        val lr = st?.optJSONObject("lastRun")
        if (lr != null && lr.toString() != rtLastRun?.toString()) { rtLastRun = lr; renderRoutineList() }
    }

    /** Sends a command to the routines engine, starting it if needed. Off the main thread. */
    private fun routinesCmd(cmd: String): String? {
        var s = Shell.service(Shell.Kind.ROUTINES)
        if (s == null) {
            Shell.start(this, Shell.Kind.ROUTINES)
            val until = System.currentTimeMillis() + 6000
            while (s == null && System.currentTimeMillis() < until) { Thread.sleep(150); s = Shell.service(Shell.Kind.ROUTINES) }
        }
        return s?.let { runCatching { it.command(cmd) }.getOrNull() }
    }

    /** A row of chips that wraps onto more lines (a scrolling row hid the chips past the edge). */
    private fun hScroll(vararg views: View): View = FlowRow(this, 6.dp, 8.dp).apply {
        views.forEach { addView(it, android.view.ViewGroup.LayoutParams(WRAP_CONTENT, 34.dp)) }
    }

    /** Multi-choice list of user-installed (and optional launcher) apps. */
    private fun pickMultiApps(title: String, apps: org.json.JSONArray, userOnly: Boolean = true, done: () -> Unit) {
        val chosen = (0 until apps.length()).map { apps.getString(it) }.toMutableSet()
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = packageManager.queryIntentActivities(launch, 0)
            .map { it.activityInfo.applicationInfo }
            .filter {
                !userOnly ||
                    it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 ||
                    it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            }
            .map { it.packageName }
            .filter { it != packageName }
            .toMutableSet()
        pkgs += chosen   // keep picks that have no launcher icon
        val list = pkgs.map { it to appName(it) }.sortedWith(compareBy({ it.first !in chosen }, { it.second.lowercase() }))
        val checked = BooleanArray(list.size) { list[it].first in chosen }
        SolCubeDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(list.map { it.second }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Done") { _, _ ->
                while (apps.length() > 0) apps.remove(0)
                list.forEachIndexed { i, (pkg, _) -> if (checked[i]) apps.put(pkg) }
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickReviveApps(apps: org.json.JSONArray, done: () -> Unit) =
        pickMultiApps("Revive these apps", apps, userOnly = true, done = done)

    // ------------------------------------------------------------------ gestures (two-finger drawing)

    private lateinit var gestList: LinearLayout
    private lateinit var gestIgnoreText: TextView
    private lateinit var gestSensRow: LinearLayout
    private var gestUsedKey = ""

    private fun addGestureSettings(body: LinearLayout) {
        body.addView(label("Draw a big shape anywhere on the screen, with one or two fingers.",
            12.5f, p.sub).apply { setPadding(0, 12.dp, 0, 0); setLineSpacing(2.dp.toFloat(), 1f) })
        body.addView(label("Tap a gesture below to assign action(s).", 13.5f, p.fg, bold = true)
            .apply { setPadding(0, 8.dp, 0, 0) })
        body.addView(label("The app under your finger(s) still feels the touch, so a gesture can also scroll or move what's on screen a little.",
            12f, p.sub).apply { setPadding(0, 6.dp, 0, 0) })
        body.addView(pillButton("Try a gesture", filled = false) { tryGestures() },
            LinearLayout.LayoutParams(MATCH_PARENT, 44.dp).apply { topMargin = 12.dp })

        gestList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(gestList)

        body.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp })
        body.addView(label("Sensitivity", 13f, p.fg, bold = true).apply { setPadding(0, 12.dp, 0, 0) })
        gestSensRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(gestSensRow)
        renderGestureSens()

        body.addView(Switch(this).apply {
            text = "Short vibration when a gesture is recognised"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            styleSwitch(this)
            isChecked = GestureStore.vibrate(this@MainActivity)
            setOnCheckedChangeListener { _, c -> io.execute { GestureStore.setVibrate(this@MainActivity, c) } }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })

        body.addView(label("Off in these apps", 13f, p.fg, bold = true).apply { setPadding(0, 12.dp, 0, 0) })
        gestIgnoreText = label("", 12.5f, p.sub)
        body.addView(gestIgnoreText)
        body.addView(textButton("Pick apps") {
            val a = GestureStore.ignore(this)
            pickMultiApps("Gestures off in", a, userOnly = false) {
                io.execute { GestureStore.setIgnore(this, a); main.post { renderGestureIgnore() } }
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = (-12).dp })
        renderGestureIgnore()
        renderGestureList()
    }

    private fun renderGestureIgnore() {
        val a = GestureStore.ignore(this)
        gestIgnoreText.text = if (a.length() == 0) "None — gestures work everywhere (not on the lock screen)"
        else (0 until a.length()).joinToString(", ") { appName(a.getString(it)) }
    }

    private fun renderGestureSens() {
        gestSensRow.removeAllViews()
        val s = GestureStore.sens(this)
        gestSensRow.addView(hScroll(*listOf("Strict", "Normal", "Loose").mapIndexed { i, t ->
            chip(t, s == i) { io.execute { GestureStore.setSens(this, i); main.post { renderGestureSens() } } }
        }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        gestSensRow.addView(label(when (s) {
            0 -> "Only clear, careful drawings count. Fewest mistakes."
            2 -> "Sloppier drawings count too, but more chance of a wrong or accidental match."
            else -> "A balance of both."
        }, 12f, p.sub).apply { setPadding(0, 4.dp, 0, 0) })
    }

    /** "1:circle", "2:x" … for gestures with a routine that's on. */
    private fun usedGestures(): Set<String> = Routines.load(this).filter { it.optBoolean("on", true) }
        .mapNotNull { r -> r.optJSONObject("trigger")?.takeIf { t -> t.optString("type") == "gesture" && t.optString("gesture").isNotEmpty() }
            ?.let { t -> "${t.optInt("fingers", 2)}:${t.optString("gesture")}" } }
        .toSet()

    /** Routines (any, on or off) that use this gesture with the other number of fingers, except [selfId]. */
    private fun takenElsewhere(id: String, fingers: Int, selfId: String? = null): List<org.json.JSONObject> =
        GestureStore.routinesFor(this, id, 3 - fingers).filter { it.optString("id") != selfId }

    /** Why (id, fingers) can't be picked now, or null. */
    private fun gestureBlock(id: String, fingers: Int, selfId: String? = null): String? {
        if (fingers == 1) GestureStore.oneFingerWhy(this, id)?.let { return it }
        val other = takenElsewhere(id, fingers, selfId)
        if (other.isNotEmpty()) return "${GestureStore.name(this, id)} is already used with ${GestureStore.fingersText(3 - fingers)} " +
            "(${other.joinToString(", ") { "“${it.optString("name")}”" }}). A gesture belongs to one card at a time, so it can't be drawn both ways."
        return null
    }

    private fun renderGestureList() {
        gestList.removeAllViews()
        val used = usedGestures()
        gestUsedKey = used.sorted().joinToString(",") + "|" + Routines.load(this).joinToString(",") {
            it.optJSONObject("trigger")?.let { t -> t.optString("gesture") + t.optInt("fingers", 2) } ?: ""
        }
        for (fingers in listOf(1, 2)) {
            val box = innerCard()
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            header.addView(
                label(if (fingers == 1) "One finger" else "Two fingers", 16f, p.fg, bold = true),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
            val chevron = chevronBadge(false)
            header.addView(chevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
            box.addView(header)

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            body.addView(label(if (fingers == 1)
                "Draw anywhere with one finger. Only closed shapes, which ordinary scrolling can't draw. The rest are grayed out: tap one to see why."
            else "Two fingers down at once, a little apart. Swipes, shapes and digits.",
                12f, p.sub).apply { setPadding(0, 6.dp, 0, 0) })
            for ((hdr, items) in GestureStore.groups(this, fingers)) {
                body.addView(label(hdr, 13f, p.fg, bold = true).apply { setPadding(0, 12.dp, 0, 0) })
                body.addView(hScroll(*items.map { (id, name) ->
                    val block = gestureBlock(id, fingers)
                    val mine = GestureStore.sampleCount(this, id)
                    val text = if (mine > 0 && Gestures.builtinName(id) != null) "$name  ·  yours" else name
                    chip(text, block == null && "$fingers:$id" in used) {
                        if (block != null) gestureBlocked(id, fingers, block) else gestureOptions(id, fingers)
                    }.apply { if (block != null) alpha = 0.35f }
                }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            }
            box.addView(body)
            makeCollapsible("gestures_f$fingers", box, listOf(header), chevron, body, defaultOpen = false)
            gestList.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        }
        gestList.addView(label("Highlighted = has action(s). Tap a gesture to change them or teach SolCube your way of drawing it.",
            12f, p.sub).apply { setPadding(0, 8.dp, 0, 0) })
        gestList.addView(textButton("Record a custom gesture") { recordGesture(null) { renderGestureList() } },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 4.dp; leftMargin = (-12).dp })
    }

    /** A grayed-out gesture was tapped: say why (and offer the routine that holds it). */
    private fun gestureBlocked(id: String, fingers: Int, why: String) {
        val other = if (fingers == 1 && GestureStore.oneFingerWhy(this, id) != null) emptyList() else takenElsewhere(id, fingers)
        val b = SolCubeDialog.Builder(this)
            .setTitle("${GestureStore.name(this, id)} · ${GestureStore.fingersText(fingers)}")
            .setMessage(why)
        if (other.isNotEmpty()) b.setItems(other.map { "Edit “${it.optString("name")}”" }.toTypedArray()) { _, i -> editRoutine(other[i]) }
        b.setNegativeButton("OK", null).show()
    }

    private fun renderGestures(st: org.json.JSONObject?, shellOk: Boolean) {
        val used = usedGestures()
        val key = used.sorted().joinToString(",") + "|" + Routines.load(this).joinToString(",") {
            it.optJSONObject("trigger")?.let { t -> t.optString("gesture") + t.optInt("fingers", 2) } ?: ""
        }
        if (key != gestUsedKey) renderGestureList()
        val on = enabled(gestSec)
        val running = on && st?.optBoolean("gestures") == true
        // one short line (the card face has room for ~25 characters); the last drawing is in Diagnostics
        showSection(gestSec, running = running, detail = when {
            !on -> "Off"
            !shellOk -> "Waiting for Shizuku"
            used.isEmpty() -> "No gesture has an action"
            st == null -> "Starting…"
            !running -> "Starting the listener…"
            else -> "Listening for ${used.size} gesture${if (used.size == 1) "" else "s"}"
        })
    }

    /** The last drawing the gesture listener saw, for Diagnostics. */
    private fun lastGestureText(st: org.json.JSONObject?): String {
        val lastG = st?.optString("lastGesture").orEmpty()
        return when {
            lastG.isEmpty() || lastG == "—" -> "(none yet)"
            lastG.startsWith("! ") -> "ignored: " + lastG.removePrefix("! ")
            lastG.startsWith("? ") -> lastG.removePrefix("? ").split(" ").let { pt ->
                "not recognised (closest ${GestureStore.name(this, pt[0])} ${pt.getOrElse(1) { "" }}) at ${pt.lastOrNull() ?: ""}"
            }
            else -> lastG.split(" ").let { pt -> "${GestureStore.name(this, pt[0])} ${pt.getOrElse(1) { "" }} at ${pt.lastOrNull() ?: ""}" }
        }
    }

    private fun gesturePad(hint: String): GesturePad = GesturePad(this, p.accent, alpha(p.fg, 0.22f), p.sub).apply {
        this.hint = hint
    }

    /** Draw on a pad and see what SolCube makes of it, with one or two fingers. */
    private fun tryGestures() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label("Draw like you would on the screen, with one or two fingers.", 12.5f, p.sub))
        val pad = gesturePad("Draw a gesture")
        box.addView(pad, LinearLayout.LayoutParams(MATCH_PARENT, 300.dp).apply { topMargin = 10.dp })
        val result = label(" ", 15f, p.fg, bold = true).apply { gravity = Gravity.CENTER }
        box.addView(result, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        val sub = label(" ", 12.5f, p.sub).apply { gravity = Gravity.CENTER }
        box.addView(sub, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val used = usedGestures()
        pad.onDrawing = { d, f ->
            val fingers = if (f >= 2) 2 else 1
            val how = GestureStore.fingersText(fingers)
            val r = GestureStore.recognize(this, d, 0.35f * pad.width, fingers)
            val id = r.id
            if (id != null) {
                result.text = "${GestureStore.name(this, id)}  ·  $how  ·  ${Math.round(r.score * 100)}%"
                val block = gestureBlock(id, fingers)
                result.setTextColor(if (block == null) p.ok else p.sub)
                val by = GestureStore.routinesFor(this, id, fingers).map { it.optString("name") }
                sub.text = when {
                    block != null -> "Not available with $how"
                    by.isNotEmpty() -> "Runs: " + by.joinToString(", ") + (if ("$fingers:$id" !in used) " (turned off)" else "")
                    else -> "Nothing assigned to it with $how yet"
                }
            } else {
                result.setTextColor(p.sub)
                when {
                    fingers == 1 && !Gestures.closed(d) -> {
                        result.text = "Not a closed shape"
                        sub.text = "With one finger only closed shapes count (circle, square, triangle, 8), so scrolling never sets one off"
                    }
                    Gestures.straight(d) -> {
                        result.text = "A straight line"
                        sub.text = "Only up / down / left / right swipes count (two fingers); other lines are taken as scrolling"
                    }
                    else -> {
                        result.text = "Not recognised  ·  $how"
                        sub.text = r.closest?.let { c -> "Closest: ${GestureStore.name(this, c)} (${Math.round(r.score * 100)}%)" +
                            (r.runnerUp?.let { " · then ${GestureStore.name(this, it)} (${Math.round(r.runnerScore * 100)}%)" } ?: "") } ?: " "
                    }
                }
            }
        }
        SolCubeDialog.Builder(this).setTitle("Try a gesture").setView(box).setNegativeButton("Close", null).show()
    }

    private fun gestureOptions(id: String, fingers: Int) {
        val name = GestureStore.name(this, id)
        val mine = GestureStore.routinesFor(this, id, fingers)
        val by = GestureStore.usedBy(this, id)
        val custom = Gestures.builtinName(id) == null
        val samples = GestureStore.sampleCount(this, id)
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        opts += (if (mine.isEmpty()) "Assign action(s)" else "Assign more action(s) (new routine)") to {
            editRoutine(org.json.JSONObject()
                .put("id", Routines.newId()).put("name", name).put("on", true)
                .put("trigger", org.json.JSONObject().put("type", "gesture").put("gesture", id).put("fingers", fingers))
                .put("actions", org.json.JSONArray()), isNew = true)
        }
        mine.forEach { r -> opts += "Edit “${r.optString("name")}”" to { editRoutine(r) } }
        if (!Gestures.isSwipe(id)) {
            opts += (if (custom) "Add more drawings of it" else "Teach my way of drawing it") to { recordGesture(id) { renderGestureList() } }
            if (!custom && samples > 0) opts += "Forget my drawings ($samples)" to {
                io.execute { GestureStore.clearSamples(this, id); main.post { renderGestureList() } }
            }
        }
        if (custom) opts += "Delete" to {
            SolCubeDialog.Builder(this).setTitle("Delete “$name”?")
                .setMessage(if (by.isEmpty()) "It isn't used by any routine." else "Used by: ${by.joinToString(", ")}. Those routines won't run until you pick another gesture.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> io.execute { GestureStore.deleteCustom(this, id); main.post { renderGestureList() } } }
                .show()
        }
        SolCubeDialog.Builder(this).setTitle("$name · ${GestureStore.fingersText(fingers)}")
            .setMessage(if (mine.isEmpty()) "Nothing assigned yet." else "Runs: " + mine.joinToString(", ") { it.optString("name") })
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .setNegativeButton("Close", null).show()
    }

    /**
     * Record a new custom gesture (forId = null) or add the user's own drawings of an existing one.
     * Asks for SAMPLES_PER_CUSTOM drawings. done gets the gesture id.
     */
    private fun recordGesture(forId: String?, done: (String) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val need = GestureStore.SAMPLES_PER_CUSTOM
        var nameField: android.widget.EditText? = null
        if (forId == null) {
            box.addView(label("Experimental: custom gestures haven't been tested much. They may not be recognised reliably, and some two-finger actions in apps (scrolling, moving a map) may set one off by mistake. Shapes with a few clear turns work best.",
                12.5f, p.sub).apply { setLineSpacing(2.dp.toFloat(), 1f) })
            nameField = android.widget.EditText(this).apply {
                hint = "Name, e.g. Heart"
                setSingleLine()
                textSize = 15f
                setTextColor(p.fg)
                setHintTextColor(p.sub)
                typeface = fontRegular
                background = glass(14.dp)
                setPadding(14.dp, 0, 14.dp, 0)
            }
            box.addView(nameField, LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { topMargin = 12.dp })
        } else {
            box.addView(label("Draw ${GestureStore.name(this, forId)} your way, $need times. SolCube keeps these next to its own version.",
                12.5f, p.sub))
        }
        val progress = label("", 13.5f, p.fg, bold = true)
        box.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        val pad = gesturePad("Draw it here")
        box.addView(pad, LinearLayout.LayoutParams(MATCH_PARENT, 280.dp).apply { topMargin = 8.dp })
        val note = label(" ", 12.5f, p.sub)
        box.addView(note, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        val drawings = mutableListOf<List<FloatArray>>()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lateinit var dlg: android.app.Dialog
        val save = pillButton("Save", filled = true) {
            if (drawings.size < need) return@pillButton
            val name = nameField?.text?.toString()?.trim().orEmpty()
            if (forId == null && name.isEmpty()) { toast("Give it a name"); return@pillButton }
            if (forId == null && GestureStore.groups(this).flatMap { it.second }.any { it.second.equals(name, ignoreCase = true) }) {
                toast("There's already a gesture called $name"); return@pillButton
            }
            val list = drawings.toList()
            dlg.dismiss()
            io.execute {
                val id = if (forId == null) GestureStore.addCustom(this, name, list) else { GestureStore.addSamples(this, forId, list); forId }
                main.post { toast("Saved"); done(id) }
            }
        }
        fun refresh() {
            progress.text = if (drawings.size < need) "Drawing ${drawings.size + 1} of $need" else "Done — $need drawings"
            save.alpha = if (drawings.size >= need) 1f else 0.4f
        }
        row.addView(textButton("Start over") { drawings.clear(); pad.clear(); note.text = " "; refresh() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = (-12).dp })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(save, LinearLayout.LayoutParams(120.dp, 44.dp))
        box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        refresh()

        pad.onDrawing = drawing@{ d, _ ->
            if (drawings.size >= need) return@drawing
            if (Gestures.extent(d) < 0.25f * pad.width) { note.text = "A bit bigger, please — draw that one again."; pad.clear(); return@drawing }
            if (Gestures.straight(d)) { note.text = "Straight lines are taken as scrolling. Draw a shape with a turn in it."; pad.clear(); return@drawing }
            drawings += d
            refresh()
            if (drawings.size < need) {
                note.text = "Got it. Again."
                pad.postDelayed({ pad.clear() }, 350)
                return@drawing
            }
            val (other, score) = GestureStore.closestOther(this, drawings) ?: (null to 0f)
            note.text = when {
                forId != null && other != null && other != forId && score >= 0.5f ->
                    "Note: these look more like ${GestureStore.name(this, other)} (${Math.round(score * 100)}%). They might get mixed up."
                forId == null && other != null && score >= 0.55f ->
                    "Heads-up: this is a lot like ${GestureStore.name(this, other)} (${Math.round(score * 100)}% alike), so the two may get mixed up. Start over to draw something more different, or save anyway."
                else -> "Looks distinct from the other gestures. Save it."
            }
        }
        dlg = SolCubeDialog.Builder(this).setTitle(if (forId == null) "Custom gesture" else "Teach ${GestureStore.name(this, forId)}")
            .setView(box).setNegativeButton("Cancel", null).show()
    }

    /** Gesture picker for a routine's trigger: one or two fingers, only what's available (plus recording a new one). */
    private fun pickGesture(selfId: String?, fingers: Int, done: (String) -> Unit) {
        val picks = mutableListOf<String>()
        val shown = mutableListOf<Pair<String, List<String>>>()
        for ((hdr, items) in GestureStore.groups(this, fingers)) {
            val ok = items.filter { gestureBlock(it.first, fingers, selfId) == null }
            if (ok.isEmpty()) continue
            shown += hdr to ok.map { it.second }
            ok.forEach { picks += it.first }
        }
        shown += "New" to listOf("Record a custom gesture…")
        SolCubeDialog.Builder(this).setTitle(if (fingers == 1) "One-finger gesture" else "Two-finger gesture")
            .setGroupedItems(shown) { _, w ->
                if (w < picks.size) done(picks[w])
                else recordGesture(null) { id ->
                    renderGestureList()
                    if (gestureBlock(id, fingers, selfId) == null) done(id)
                    else toast("Saved, but it can't be used with one finger: it isn't a closed shape")
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ profiles (per-app rules)

    private lateinit var profDot: View
    private lateinit var profStatus: TextView
    private lateinit var profDetail: TextView
    private lateinit var profList: LinearLayout

    private fun buildProfilesCard(inner: Boolean = false): LinearLayout {
        val card = if (inner) innerCard() else card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(betaTitle("Profiles", if (inner) 16.5f else 18f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chevron = chevronBadge(true)
        header.addView(chevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
        card.addView(header)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        profDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        statusRow.addView(profDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        profStatus = label("", 14.5f, p.sub, bold = true)
        statusRow.addView(profStatus)
        card.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        profDetail = label("", 13f, p.sub)
        card.addView(profDetail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(profList)
        body.addView(textButton("New profile") { editProfile(null) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = 8.dp; leftMargin = (-12).dp
        })
        card.addView(body)
        makeCollapsible("profiles", card, listOf(header, statusRow, profDetail), chevron, body, defaultOpen = true)
        renderProfileList()
        return card
    }

    private fun renderProfileList() {
        val all = Profiles.load(this)
        val allRoutines = Routines.load(this)
        val rtName: (String) -> String = { id -> allRoutines.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?" }
        val activeId = last?.profiles?.optString("activeId") ?: ""
        // runs on every 2 s refresh: only rebuild (new glass chips) when something shown changed
        val key = all.joinToString("|") + "#" + activeId + "#" + allRoutines.joinToString("|") { it.optString("id") + it.optString("name") }
        if (profList.tag == key) return
        profList.tag = key
        profList.removeAllViews()
        all.forEach { pr ->
            profList.addView(View(this).apply { setBackgroundColor(p.stroke) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 6.dp })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8.dp, 0, 8.dp)
                background = ripple(null, 14.dp)
                setOnClickListener { editProfile(pr) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val on = pr.optBoolean("on", true)
            val id = pr.optString("id")
            val name = pr.optString("name")
            val apps = Profiles.apps(pr)
            val appText = if (apps.isEmpty()) "(no app)" else apps.joinToString(", ") { appName(it) }
            val actText = Profiles.actionsText(pr, ::appName, rtName)
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(name, 15f, if (on) p.fg else p.sub, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            if (id == activeId && activeId.isNotEmpty()) {
                top.addView(label("active", 11.5f, p.ok, bold = true).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp })
            }
            col.addView(top)
            col.addView(label(if (actText.isEmpty()) appText else "$appText  →  $actText", 12f, p.sub).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 2.dp, 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(chip(if (on) "On" else "Off", on) {
                pr.put("on", !on)
                Island.show(this, "$name ${if (!on) "on" else "off"}")
                io.execute {
                    Profiles.save(this, all)
                    main.post {
                        renderProfileList()
                        renderProfiles(last?.profiles, last?.shell == Shell.State.READY)
                        poll()
                    }
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 10.dp })
            profList.addView(row)
        }
    }

    private fun renderProfiles(st: org.json.JSONObject?, shellOk: Boolean) {
        val all = Profiles.load(this)
        val total = all.size
        val onCount = all.count { it.optBoolean("on", true) && Profiles.apps(it).isNotEmpty() }
        val any = onCount > 0
        val running = st?.optBoolean("running") == true
        val active = running && any
        val activeName = st?.optString("activeName")?.takeIf { it.isNotEmpty() }
        profStatus.put(when {
            !active -> "Not running"
            onCount == total -> "All running"
            else -> "$onCount/$total running"
        })
        profStatus.setTextColor(if (active) p.ok else p.sub)
        (profDot.background as GradientDrawable).setColor(if (active) p.ok else alpha(p.sub, 0.5f))
        profDetail.put(when {
            total == 0 -> "Change settings or launch an app while another is open"
            !any -> "All profiles are off"
            !shellOk -> "Waiting for Shizuku"
            st == null -> "Starting…"
            activeName != null -> "Active: $activeName"
            else -> "Tap a profile to edit it"
        })
        renderProfileList()
    }

    private fun promptText(title: String, initial: String, hint: String, onDone: (String) -> Unit) {
        val field = android.widget.EditText(this).apply {
            setText(initial)
            this.hint = hint
            setSingleLine()
            textSize = 15f
            setTextColor(p.fg)
            setHintTextColor(p.sub)
            typeface = fontRegular
            background = glass(14.dp)
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            setSelection(text.length)
        }
        SolCubeDialog.Builder(this).setTitle(title)
            .setView(field)
            .setPositiveButton("Done") { _, _ -> onDone(field.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickSavedNetworks(target: org.json.JSONArray, onDone: () -> Unit) {
        io.execute {
            val raw = routinesCmd("networks")
            val nets = runCatching { org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }
                .getOrDefault(emptyList()).sortedBy { it.lowercase() }
            main.post {
                if (nets.isEmpty()) {
                    promptText("Add Wi-Fi network", "", "Network name (SSID)") { s ->
                        if (s.isNotEmpty()) { target.put(s); onDone() }
                    }
                    return@post
                }
                SolCubeDialog.Builder(this).setTitle("Saved networks")
                    .setItems(nets.toTypedArray()) { _, w -> target.put(nets[w]); onDone() }
                    .show()
            }
        }
    }

    private fun pickBtDevice(onPick: (String) -> Unit) {
        io.execute {
            val raw = routinesCmd("bt_devices")
            val devs = runCatching { org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }
                .getOrDefault(emptyList())
            main.post {
                val items = devs + "Type device name…"
                SolCubeDialog.Builder(this).setTitle("Bluetooth device")
                    .setItems(items.toTypedArray()) { _, w ->
                        if (w == devs.size) {
                            promptText("Bluetooth device name", "", "Exact device name") { name ->
                                if (name.isNotEmpty()) onPick(name)
                            }
                        } else {
                            onPick(devs[w])
                        }
                    }.show()
            }
        }
    }

    private fun editActionDetail(
        k: String,
        act: org.json.JSONObject,
        allRoutines: List<org.json.JSONObject>,
        selfRoutineId: String?,
        rebuild: () -> Unit,
    ) {
        val selectableRoutines = allRoutines.filter { it.optString("id") != selfRoutineId }
        when (k) {
            "revive" -> pickReviveApps(act.optJSONArray("apps") ?: org.json.JSONArray().also { act.put("apps", it) }) { rebuild() }
            "launch_bg" -> pickMultiApps("Launch in background", act.optJSONArray("apps") ?: org.json.JSONArray().also { act.put("apps", it) }, userOnly = false) { rebuild() }
            "close_app" -> pickMultiApps("Close these apps", act.optJSONArray("apps") ?: org.json.JSONArray().also { act.put("apps", it) }, userOnly = false) { rebuild() }
            "close_apps" -> pickMultiApps("Keep these apps open", act.optJSONArray("except") ?: org.json.JSONArray().also { act.put("except", it) }, userOnly = false) { rebuild() }
            "open_app" -> pickSingleApp("Open app", emptyList()) { pkg -> act.put("pkg", pkg); rebuild() }
            "notice" -> promptText("Status pill text", act.optString("text"), "Message to show") { t -> act.put("text", t); rebuild() }
            "open_url" -> promptText("Open link / URL", act.optString("url"), "https://example.com") { u -> act.put("url", u); rebuild() }
            "shell_cmd" -> promptText("Shell command", act.optString("cmd"), "e.g. input keyevent 3") { c -> act.put("cmd", c); rebuild() }
            "run_routine" -> {
                if (selectableRoutines.isEmpty()) { toast("No other routines"); return }
                SolCubeDialog.Builder(this).setTitle("Run routine")
                    .setItems(selectableRoutines.map { it.optString("name") }.toTypedArray()) { _, w ->
                        act.put("routineId", selectableRoutines[w].optString("id")); rebuild()
                    }.show()
            }
        }
    }

    private fun addActionOptionChips(
        row: LinearLayout,
        a: org.json.JSONObject,
        isRoutine: Boolean,
        allowToggle: Boolean,
        allRoutines: List<org.json.JSONObject>,
        selfRoutineId: String?,
        onUpdate: () -> Unit,
    ) {
        val at = a.optString("type")
        val rtName: (String) -> String = { rid -> allRoutines.firstOrNull { it.optString("id") == rid }?.optString("name") ?: "Pick routine" }
        val vals = Actions.values(at, allowRestore = isRoutine, allowToggle = allowToggle)
        when (at) {
            "revive", "launch_bg", "close_app" -> {
                val apps = a.optJSONArray("apps") ?: org.json.JSONArray().also { a.put("apps", it) }
                row.addView(chip("${apps.length()} apps", false) { editActionDetail(at, a, allRoutines, selfRoutineId, onUpdate) },
                    LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            }
            "close_apps" -> {
                val except = a.optJSONArray("except") ?: org.json.JSONArray().also { a.put("except", it) }
                row.addView(chip(if (except.length() == 0) "Except…" else "Keep ${except.length()}", except.length() > 0) {
                    editActionDetail(at, a, allRoutines, selfRoutineId, onUpdate)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            }
            "open_app" -> {
                val pkg = a.optString("pkg")
                row.addView(chip(if (pkg.isEmpty()) "Pick app" else appName(pkg).take(14), pkg.isNotEmpty()) {
                    editActionDetail(at, a, allRoutines, selfRoutineId, onUpdate)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            }
            "notice" -> {
                val t = a.optString("text")
                row.addView(chip(if (t.isEmpty()) "Set text" else t.take(14), t.isNotEmpty()) {
                    editActionDetail(at, a, allRoutines, selfRoutineId, onUpdate)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            }
            "open_url" -> {
                val u = a.optString("url")
                val shortU = u.removePrefix("https://").removePrefix("http://").take(14)
                row.addView(chip(if (u.isEmpty()) "Set URL" else shortU, u.isNotEmpty()) {
                    editActionDetail(at, a, allRoutines, selfRoutineId, onUpdate)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            }
            "shell_cmd" -> {
                val c = a.optString("cmd")
                row.addView(chip(if (c.isEmpty()) "Set cmd" else c.take(14), c.isNotEmpty()) {
                    editActionDetail(at, a, allRoutines, selfRoutineId, onUpdate)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            }
            "run_routine" -> {
                val rid = a.optString("routineId")
                row.addView(chip(if (rid.isEmpty()) "Pick routine" else rtName(rid).take(14), rid.isNotEmpty()) {
                    editActionDetail(at, a, allRoutines, selfRoutineId, onUpdate)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            }
        }
        if (vals.isNotEmpty()) {
            val v = a.optString("value", vals.first())
            row.addView(chip(Actions.valueText(at, v), false) {
                if (vals.size <= 6) {
                    a.put("value", vals[(vals.indexOf(v) + 1) % vals.size]); onUpdate()
                } else {
                    SolCubeDialog.Builder(this).setTitle(Actions.ALL[at] ?: at)
                        .setItems(vals.map { Actions.valueText(at, it) }.toTypedArray()) { _, w ->
                            a.put("value", vals[w]); onUpdate()
                        }.show()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
        }
    }

    private fun renderActionsEditor(
        doCard: LinearLayout,
        acts: org.json.JSONArray,
        isRoutine: Boolean,
        allRoutines: List<org.json.JSONObject>,
        selfRoutineId: String?,
        rebuild: () -> Unit,
    ) {
        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            val at = a.optString("type")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label("${i + 1}.  " + (Actions.ALL[at] ?: at), 14.5f, p.fg),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addActionOptionChips(row, a, isRoutine = isRoutine, allowToggle = false, allRoutines = allRoutines, selfRoutineId = selfRoutineId, onUpdate = rebuild)
            if (i > 0) row.addView(label("↑", 15f, p.sub).apply {
                gravity = Gravity.CENTER
                background = ripple(null, 16.dp)
                setOnClickListener {
                    val prev = acts.getJSONObject(i - 1)
                    acts.put(i - 1, a); acts.put(i, prev); rebuild()
                }
            }, LinearLayout.LayoutParams(32.dp, 32.dp).apply { leftMargin = 2.dp })
            row.addView(label("✕", 15f, p.sub).apply {
                gravity = Gravity.CENTER; background = ripple(null, 16.dp); setOnClickListener { acts.remove(i); rebuild() }
            }, LinearLayout.LayoutParams(28.dp, 32.dp).apply { leftMargin = 4.dp })
            doCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        }
        if (acts.length() == 0) doCard.addView(label("No actions yet", 13f, p.sub).apply { setPadding(0, 8.dp, 0, 0) })
        doCard.addView(textButton("Add action") {
            val flatKeys = Actions.GROUPS.flatMap { (_, items) -> items.map { it.first } }
            val groups = Actions.GROUPS.map { (hdr, items) -> hdr to items.map { it.second } }
            SolCubeDialog.Builder(this).setTitle("Add action")
                .setGroupedItems(groups) { _, w ->
                    val k = flatKeys[w]
                    val act = Actions.defaultAction(k, allowRestore = isRoutine)
                    acts.put(act)
                    rebuild()
                    if (k in setOf("open_app", "launch_bg", "close_app", "notice", "open_url", "shell_cmd", "run_routine")) {
                        editActionDetail(k, act, allRoutines, selfRoutineId, rebuild)
                    }
                }.show()
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp; leftMargin = (-12).dp })
    }

    private fun editProfile(orig: org.json.JSONObject?) {
        val all = Profiles.load(this)
        val allRoutines = Routines.load(this)
        val pr = org.json.JSONObject((orig ?: org.json.JSONObject()
            .put("id", Profiles.newId())
            .put("name", "New profile")
            .put("on", true)
            .put("apps", org.json.JSONArray())
            .put("restoreOnExit", true)
            .put("actions", org.json.JSONArray())).toString())
        val id = pr.getString("id")
        val dlg = android.app.Dialog(this,
            if (isDark()) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleView = label(if (orig == null) "New profile" else "Edit profile", 26f, p.fg, bold = true)
        val nameCard = card()
        nameCard.addView(label("Name", 14.5f, p.fg, bold = true))
        val nameField = android.widget.EditText(this).apply {
            setText(pr.optString("name"))
            setSingleLine()
            textSize = 15f
            setTextColor(p.fg)
            typeface = fontRegular
            background = glass(14.dp)
            setPadding(14.dp, 0, 14.dp, 0)
        }
        nameCard.addView(nameField, LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { topMargin = 8.dp })
        body.addView(nameCard, cardParams())
        val dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dynamic)

        fun apps() = pr.optJSONArray("apps") ?: org.json.JSONArray().also { pr.put("apps", it) }
        fun actions() = pr.optJSONArray("actions") ?: org.json.JSONArray().also { pr.put("actions", it) }

        lateinit var rebuild: () -> Unit
        rebuild = {
            dynamic.removeAllViews()

            // ---- When opening
            val whenCard = card()
            whenCard.addView(label("When opening", 14.5f, p.fg, bold = true))
            val appArr = apps()
            val curApps = (0 until appArr.length()).map { appArr.getString(it) }
            for (i in 0 until appArr.length()) {
                val pkg = appArr.getString(i)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                names.addView(label(appName(pkg), 14.5f, p.fg).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                names.addView(label(pkg, 10.5f, p.sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                row.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(label("✕", 15f, p.sub).apply {
                    gravity = Gravity.CENTER
                    background = ripple(null, 16.dp)
                    setOnClickListener { appArr.remove(i); rebuild() }
                }, LinearLayout.LayoutParams(32.dp, 32.dp))
                whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }
            if (appArr.length() == 0) whenCard.addView(label("No app picked yet", 13f, p.sub).apply { setPadding(0, 8.dp, 0, 0) })
            whenCard.addView(textButton("Add app") {
                pickSingleApp("Add app", curApps) { pkg ->
                    appArr.put(pkg)
                    val curName = nameField.text.toString().trim()
                    if (curName.isEmpty() || curName == "New profile") nameField.setText(appName(pkg))
                    rebuild()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp; leftMargin = (-12).dp })
            dynamic.addView(whenCard, cardParams())

            // ---- Do
            val doCard = card()
            doCard.addView(label("Do", 14.5f, p.fg, bold = true))
            renderActionsEditor(doCard, actions(), isRoutine = false, allRoutines = allRoutines, selfRoutineId = null, rebuild = rebuild)
            doCard.addView(Switch(this).apply {
                text = "Only if not already done"
                textSize = 13.5f
                setTextColor(p.fg)
                typeface = fontRegular
                styleSwitch(this)
                isChecked = pr.optBoolean("onlyIfNeeded", false)
                setOnCheckedChangeListener { _, c -> pr.put("onlyIfNeeded", c) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            doCard.addView(label("Skips actions that are already in place (app already running, setting already set, music already playing). If nothing is left to do, the profile stays silent: no pill.",
                12f, p.sub).apply { setPadding(0, 4.dp, 0, 0) })
            dynamic.addView(doCard, cardParams())

            // ---- When leaving
            val leaveCard = card()
            leaveCard.addView(label("When leaving", 14.5f, p.fg, bold = true))
            leaveCard.addView(Switch(this).apply {
                text = "Back to before when leaving"
                textSize = 13.5f
                setTextColor(p.fg)
                typeface = fontRegular
                styleSwitch(this)
                isChecked = pr.optBoolean("restoreOnExit", true)
                setOnCheckedChangeListener { _, c -> pr.put("restoreOnExit", c) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            leaveCard.addView(label("NFC and settings changed by this profile return to what they were before (NFC also turns off after 3 min locked; skipped if already on). One-shot and background apps stay as-is.",
                12f, p.sub).apply { setPadding(0, 4.dp, 0, 0) })
            dynamic.addView(leaveCard, cardParams())

            // ---- Status pill
            val pillCard = card()
            pillCard.addView(label("Status pill", 14.5f, p.fg, bold = true))
            pillCard.addView(Switch(this).apply {
                text = "Show actions instead of name"
                textSize = 13.5f
                setTextColor(p.fg)
                typeface = fontRegular
                styleSwitch(this)
                isChecked = Profiles.pillActions(pr)
                setOnCheckedChangeListener { _, c -> pr.put("pillActions", c) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            pillCard.addView(label("Off: shows the profile name (e.g. “Wallet” / “Wallet off”). On: shows what changed (e.g. “NFC on” / “NFC off”).",
                12f, p.sub).apply { setPadding(0, 4.dp, 0, 0) })
            dynamic.addView(pillCard, cardParams())
        }
        rebuild()

        fun commit(): List<org.json.JSONObject> {
            pr.put("name", nameField.text.toString().trim().ifEmpty { "Profile" })
            val list = all.toMutableList()
            val i = list.indexOfFirst { it.optString("id") == id }
            if (i >= 0) list[i] = pr else list += pr
            return list
        }

        body.addView(pillButton("Save", filled = true) {
            val list = commit()
            io.execute { Profiles.save(this, list); main.post { renderProfileList(); poll() } }
            dlg.dismiss()
        }, LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { topMargin = 20.dp })
        if (orig != null) body.addView(textButton("Delete profile") {
            SolCubeDialog.Builder(this).setMessage("Delete “${pr.optString("name")}”?")
                .setPositiveButton("Delete") { _, _ ->
                    val list = all.filter { it.optString("id") != id }
                    io.execute { Profiles.save(this, list); main.post { renderProfileList(); poll() } }
                    dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
        }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        body.addView(textButton("Cancel") { dlg.dismiss() }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        dlg.setContentView(pinnedBackdropLayout(
            titleView = titleView,
            body = body,
            titleLeftPx = 22.dp,
            titleTopPx = 20.dp,
            titleBottomGapPx = 16.dp,
            bodySidePadPx = 16.dp,
            bodyBottomPadPx = 28.dp,
            blurBelowTitlePx = 12.dp,
            fadeBelowTitlePx = 4.dp,
            edgeToEdge = true,
        ))
        styleDialogWindow(dlg.window)
        dlg.show()
    }

    private fun pickTime(current: String, set: (String) -> Unit) {
        val parts = current.split(":")
        val h = (parts.getOrNull(0)?.toIntOrNull() ?: 8).coerceIn(0, 23)
        val m = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        val fmt = android.widget.NumberPicker.Formatter { String.format(java.util.Locale.US, "%02d", it) }
        val textPx = 24f * resources.displayMetrics.density
        fun wheel(max: Int, value: Int) = android.widget.NumberPicker(this).apply {
            minValue = 0
            maxValue = max
            this.value = value
            wrapSelectorWheel = true
            setFormatter(fmt)
            textColor = p.fg
            textSize = textPx
            selectionDividerHeight = 0
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val hp = wheel(23, h)
        val mp = wheel(59, m)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            background = glass(20.dp, level = 1)
            addView(hp, LinearLayout.LayoutParams(92.dp, WRAP_CONTENT))
            addView(label(":", 26f, p.fg, bold = true).apply { setPadding(12.dp, 0, 12.dp, 0) })
            addView(mp, LinearLayout.LayoutParams(92.dp, WRAP_CONTENT))
        }
        SolCubeDialog.Builder(this)
            .setTitle("Pick time")
            .setView(wrap)
            .setPositiveButton("Set") { _, _ ->
                set(String.format(java.util.Locale.US, "%02d:%02d", hp.value, mp.value))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editRoutine(orig: org.json.JSONObject?, isNew: Boolean = orig == null) {
        val all = Routines.load(this)
        val r = org.json.JSONObject((orig ?: org.json.JSONObject()
            .put("id", Routines.newId()).put("name", "New routine").put("on", true)
            .put("trigger", org.json.JSONObject().put("type", "dnd_on"))
            .put("actions", org.json.JSONArray())).toString())
        val id = r.getString("id")
        val dlg = android.app.Dialog(this,
            if (isDark()) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleView = label(if (isNew) "New routine" else "Edit routine", 26f, p.fg, bold = true)
        val nameCard = card()
        nameCard.addView(label("Name", 14.5f, p.fg, bold = true))
        val nameField = android.widget.EditText(this).apply {
            setText(r.optString("name"))
            setSingleLine()
            textSize = 15f
            setTextColor(p.fg)
            typeface = fontRegular
            background = glass(14.dp)
            setPadding(14.dp, 0, 14.dp, 0)
        }
        nameCard.addView(nameField, LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { topMargin = 8.dp })
        body.addView(nameCard, cardParams())
        val dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dynamic)

        fun trig() = r.optJSONObject("trigger") ?: org.json.JSONObject().also { r.put("trigger", it) }
        fun conds() = r.optJSONArray("conditions") ?: org.json.JSONArray().also { r.put("conditions", it) }
        fun actions() = r.optJSONArray("actions") ?: org.json.JSONArray().also { r.put("actions", it) }

        lateinit var rebuild: () -> Unit
        rebuild = {
            dynamic.removeAllViews()
            val t = trig()
            val type = t.optString("type")

            // ---- When
            val whenCard = card()
            whenCard.addView(label("When", 14.5f, p.fg, bold = true))
            val trigName = if (type == "gesture") Routines.TRIGGERS[if (t.optInt("fingers", 2) == 1) "gesture1" else "gesture"]
                else Routines.TRIGGERS[type]
            whenCard.addView(hScroll(chip("${trigName ?: "Pick trigger"}  ▾", true) {
                val flatKeys = Routines.TRIGGER_GROUPS.flatMap { (_, items) -> items.map { it.first } }
                val groups = Routines.TRIGGER_GROUPS.map { (hdr, items) -> hdr to items.map { it.second } }
                SolCubeDialog.Builder(this).setTitle("When")
                    .setGroupedItems(groups) { _, w ->
                        val k = flatKeys[w]
                        if (k == "gesture" || k == "gesture1") {
                            val f = if (k == "gesture1") 1 else 2
                            t.put("type", "gesture").put("fingers", f)
                            // the picked gesture may not exist that way (one-finger rules, or taken by the other card)
                            val g = t.optString("gesture")
                            if (g.isNotEmpty() && gestureBlock(g, f, id) != null) t.remove("gesture")
                        } else {
                            t.put("type", k)
                        }
                        rebuild()
                    }
                    .show()
            }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })

            if (type == "wifi_connect") {
                whenCard.addView(Switch(this).apply {
                    text = "Any Wi-Fi network"
                    textSize = 13.5f
                    setTextColor(p.fg)
                    typeface = fontRegular
                    styleSwitch(this)
                    isChecked = t.optBoolean("any", false)
                    setOnCheckedChangeListener { _, c -> t.put("any", c); rebuild() }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }

            if ((type == "wifi_connect" && !t.optBoolean("any", false)) || type == "wifi_disconnect" || type == "wifi_gone") {
                val ssids = t.optJSONArray("ssids") ?: org.json.JSONArray().also { t.put("ssids", it) }
                whenCard.addView(label(when (type) {
                    "wifi_connect" -> "Any of these networks"
                    "wifi_disconnect" -> "Leave empty for any Wi-Fi network"
                    else -> "None of these networks nearby"
                }, 12.5f, p.sub).apply { setPadding(0, 10.dp, 0, 0) })
                for (i in 0 until ssids.length()) {
                    val name = ssids.getString(i)
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(label(name, 14.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(label("✕", 15f, p.sub).apply {
                        gravity = Gravity.CENTER
                        background = ripple(null, 16.dp)
                        setOnClickListener { ssids.remove(i); rebuild() }
                    }, LinearLayout.LayoutParams(32.dp, 32.dp))
                    whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
                }
                whenCard.addView(textButton("Add network") { pickSavedNetworks(ssids) { rebuild() } },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = (-12).dp })
                if (type == "wifi_gone") {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val sec = Routines.wifiGoneSeconds(t)
                    fun saveSec(v: Int) {
                        if (v < 60) { t.put("seconds", v); t.remove("minutes") }
                        else { t.put("minutes", v / 60); t.remove("seconds") }
                        rebuild()
                    }
                    row.addView(label("For ${Routines.wifiGoneDurationText(sec)}", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(chip("−", false) {
                        val next = when {
                            sec > 300 -> sec - 300
                            sec > 60 -> sec - 60
                            else -> 30
                        }
                        saveSec(next)
                    }, LinearLayout.LayoutParams(44.dp, 32.dp))
                    row.addView(chip("+", false) {
                        val next = when {
                            sec < 60 -> 60
                            sec < 300 -> sec + 60
                            else -> minOf(7200, sec + 300)
                        }
                        saveSec(next)
                    }, LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
                    whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
                }
            }

            if (type == "wifi_connect") {
                whenCard.addView(View(this).apply { setBackgroundColor(p.stroke) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 10.dp; bottomMargin = 4.dp })
                val g = r.optJSONObject("wifiGuard")
                val gOn = g != null && g.optBoolean("on", true)
                whenCard.addView(Switch(this).apply {
                    text = "Keep Wi-Fi on before it runs"
                    textSize = 14f
                    setTextColor(p.fg)
                    typeface = fontMedium
                    styleSwitch(this)
                    isChecked = gOn
                    setOnCheckedChangeListener { _, c ->
                        val ng = r.optJSONObject("wifiGuard") ?: org.json.JSONObject()
                            .put("from", r.optString("from").ifEmpty { "08:30" }).put("to", r.optString("to").ifEmpty { "09:20" }).put("minutes", 3)
                        r.put("wifiGuard", ng.put("on", c))
                        rebuild()
                    }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
                if (gOn) {
                    whenCard.addView(label("If Wi-Fi is off in this window, turn it on and buzz. Stops once the routine has run that day. Uses the days below.",
                        12f, p.sub).apply { setPadding(0, 2.dp, 0, 0) })
                    val gf = g!!.optString("from", "08:30"); val gt = g.optString("to", "09:20")
                    whenCard.addView(hScroll(
                        chip(gf, true) { pickTime(gf) { g.put("from", it); rebuild() } },
                        label("to", 13f, p.sub).apply { gravity = Gravity.CENTER },
                        chip(gt, true) { pickTime(gt) { g.put("to", it); rebuild() } },
                    ), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val mins = g.optInt("minutes", 3)
                    row.addView(label("Check every $mins min", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(chip("−", false) { g.put("minutes", maxOf(1, mins - 1)); rebuild() }, LinearLayout.LayoutParams(44.dp, 32.dp))
                    row.addView(chip("+", false) { g.put("minutes", minOf(60, mins + 1)); rebuild() },
                        LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
                    whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
                }
            }

            if (type == "wifi_off") {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val mins = t.optInt("minutes", 3)
                row.addView(label("Check every $mins min", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(chip("−", false) { t.put("minutes", maxOf(1, mins - 1)); rebuild() }, LinearLayout.LayoutParams(44.dp, 32.dp))
                row.addView(chip("+", false) { t.put("minutes", minOf(60, mins + 1)); rebuild() },
                    LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
                whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }

            if (type == "gesture") {
                val gid = t.optString("gesture")
                val gf = t.optInt("fingers", 2)
                whenCard.addView(hScroll(chip(if (gid.isEmpty()) "Pick a gesture  ▾"
                    else "${GestureStore.name(this, gid)}  ▾", gid.isNotEmpty()) {
                    pickGesture(id, gf) { g -> t.put("gesture", g); rebuild() }
                }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
                whenCard.addView(label(when {
                    !GestureStore.on(this) -> "Gestures are off. Turn them on in the Gestures card."
                    gf == 1 -> "One finger, draw it big, anywhere. Not on the lock screen."
                    else -> "Two fingers a little apart, draw it big, anywhere. Not on the lock screen."
                }, 12f, p.sub)
                    .apply { setPadding(0, 6.dp, 0, 0) })
            }

            if (type in setOf("two_finger_hold", "power_hold", "nav_hold")) {
                when (type) {
                    "two_finger_hold" -> addHoldStepper(whenCard, Components.KEY_TOUCH_HOLD) { Components.touchHold(this) }
                    "power_hold" -> addHoldStepper(whenCard, Components.KEY_BUTTON_HOLD) { Components.buttonHold(this) }
                    "nav_hold" -> addHoldStepper(whenCard, Components.KEY_NAV_HOLD) { Components.navHold(this) }
                }
                whenCard.addView(label("Shares this hold duration with Shortcuts. When this routine's schedule and conditions match, it runs instead of the Shortcuts card.",
                    12f, p.sub).apply { setPadding(0, 6.dp, 0, 0) })
            }

            if (type == "double_press") {
                if (!DoublePress.ready(this)) {
                    whenCard.addView(label("Double power press isn't handled by SolCube yet.",
                        12f, p.sub).apply { setPadding(0, 8.dp, 0, 0) })
                    whenCard.addView(textButton("Set up double press") {
                        io.execute {
                            val ok = DoublePress.makeDefault(this)
                            main.post {
                                if (!ok) toast("Couldn't set it (is Shizuku running?)")
                                rebuild()
                            }
                        }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = (-12).dp })
                } else {
                    whenCard.addView(label("Runs instead of the Double power press card when this routine's schedule and conditions match.",
                        12f, p.sub).apply { setPadding(0, 8.dp, 0, 0) })
                }
            }

            if (type == "time") {
                val at = t.optString("at", "")
                whenCard.addView(hScroll(chip(if (at.isEmpty()) "Pick a time" else at, at.isNotEmpty()) {
                    pickTime(at.ifEmpty { "08:00" }) { t.put("at", it); rebuild() }
                }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }

            if (type == "bt_connect" || type == "bt_disconnect") {
                val devs = t.optJSONArray("devices") ?: org.json.JSONArray().also { t.put("devices", it) }
                whenCard.addView(label("Leave empty for any Bluetooth device", 12.5f, p.sub).apply { setPadding(0, 10.dp, 0, 0) })
                for (i in 0 until devs.length()) {
                    val name = devs.getString(i)
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(label(name, 14.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(label("✕", 15f, p.sub).apply {
                        gravity = Gravity.CENTER; background = ripple(null, 16.dp); setOnClickListener { devs.remove(i); rebuild() }
                    }, LinearLayout.LayoutParams(32.dp, 32.dp))
                    whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
                }
                whenCard.addView(textButton("Add Bluetooth device") { pickBtDevice { devs.put(it); rebuild() } },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = (-12).dp })
            }

            if (type == "charging_on" || type == "charging_off") {
                val mode = t.optString("mode", "any").ifEmpty { "any" }
                whenCard.addView(label("Charger type", 12.5f, p.sub).apply { setPadding(0, 10.dp, 0, 0) })
                whenCard.addView(hScroll(*listOf("any" to "Any", "ac" to "AC", "usb" to "USB", "wireless" to "Wireless").map { (m, lbl) ->
                    chip(lbl, mode == m) { t.put("mode", m); rebuild() }
                }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            }

            if (type == "battery_below" || type == "battery_above") {
                val defaultPct = if (type == "battery_below") 20 else 80
                val pct = t.optInt("percent", defaultPct)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label("Level: $pct%", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(chip("−", false) { t.put("percent", maxOf(5, pct - 5)); rebuild() }, LinearLayout.LayoutParams(44.dp, 32.dp))
                row.addView(chip("+", false) { t.put("percent", minOf(100, pct + 5)); rebuild() },
                    LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
                whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }

            if (type == "temp_above") {
                val tempC = t.optInt("temp", 42)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label("At or above $tempC °C", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(chip("−", false) { t.put("temp", maxOf(35, tempC - 1)); rebuild() }, LinearLayout.LayoutParams(44.dp, 32.dp))
                row.addView(chip("+", false) { t.put("temp", minOf(52, tempC + 1)); rebuild() },
                    LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
                whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }

            if (type == "temp_below") {
                val tempC = t.optInt("temp", 38)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label("At or below $tempC °C", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(chip("−", false) { t.put("temp", maxOf(25, tempC - 1)); rebuild() }, LinearLayout.LayoutParams(44.dp, 32.dp))
                row.addView(chip("+", false) { t.put("temp", minOf(48, tempC + 1)); rebuild() },
                    LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
                whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }

            if (type == "screen_off_for") {
                val mins = t.optInt("minutes", 10)
                val steps = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240)
                val i = steps.indexOfFirst { it >= mins }.let { if (it < 0) steps.size - 1 else it }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label("Off for $mins min", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(chip("−", false) { t.put("minutes", steps[maxOf(0, i - 1)]); rebuild() }, LinearLayout.LayoutParams(44.dp, 32.dp))
                row.addView(chip("+", false) { t.put("minutes", steps[minOf(steps.size - 1, i + 1)]); rebuild() },
                    LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
                whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }

            if (type == "game_open" || type == "game_close") {
                whenCard.addView(label("Any game vivo Game Cube runs (the ones it lists in Game space)", 12.5f, p.sub)
                    .apply { setPadding(0, 10.dp, 0, 0) })
            }

            if (type == "app_open" || type == "app_close") {
                val apps = t.optJSONArray("apps") ?: org.json.JSONArray().also { t.put("apps", it) }
                whenCard.addView(label(if (type == "app_open") "When any of these opens" else "When leaving any of these",
                    12.5f, p.sub).apply { setPadding(0, 10.dp, 0, 0) })
                for (i in 0 until apps.length()) {
                    val pkg = apps.getString(i)
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(label(appName(pkg), 14.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(label("✕", 15f, p.sub).apply {
                        gravity = Gravity.CENTER; background = ripple(null, 16.dp); setOnClickListener { apps.remove(i); rebuild() }
                    }, LinearLayout.LayoutParams(32.dp, 32.dp))
                    whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
                }
                whenCard.addView(textButton("Pick apps") { pickMultiApps("Apps", apps, userOnly = false) { rebuild() } },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = (-12).dp })
            }
            dynamic.addView(whenCard, cardParams())

            // ---- Only if
            val ifCard = card()
            ifCard.addView(label("Only if", 14.5f, p.fg, bold = true))
            val days = Routines.days(r).toMutableSet()
            ifCard.addView(hScroll(*listOf("M", "T", "W", "T", "F", "S", "S").mapIndexed { i, d ->
                chip(d, (i + 1) in days) {
                    if (!days.remove(i + 1)) days.add(i + 1)
                    r.put("days", org.json.JSONArray(days.sorted()))
                    rebuild()
                }.apply { minWidth = 40.dp }
            }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            ifCard.addView(label(if (days.isEmpty() || days.size == 7) "Every day" else Routines.dayText(days.toList()), 12f, p.sub)
                .apply { setPadding(0, 4.dp, 0, 0) })
            val from = r.optString("from"); val to = r.optString("to")
            val any = from.isEmpty() || to.isEmpty()
            ifCard.addView(label("Between", 12.5f, p.sub).apply { setPadding(0, 12.dp, 0, 0) })
            ifCard.addView(hScroll(
                chip("Any time", any) { r.remove("from"); r.remove("to"); rebuild() },
                chip(if (any) "From…" else from, !any) { pickTime(from.ifEmpty { "08:00" }) { r.put("from", it); if (r.optString("to").isEmpty()) r.put("to", "18:00"); rebuild() } },
                chip(if (any) "To…" else to, !any) { pickTime(to.ifEmpty { "18:00" }) { r.put("to", it); if (r.optString("from").isEmpty()) r.put("from", "08:00"); rebuild() } },
            ), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

            // Extra state conditions (Apple Shortcuts / Samsung Routines style)
            val cs = conds()
            ifCard.addView(View(this).apply { setBackgroundColor(p.stroke) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp; bottomMargin = 4.dp })
            ifCard.addView(label("Extra conditions", 13f, p.fg, bold = true).apply { setPadding(0, 8.dp, 0, 0) })
            if (cs.length() >= 2) {
                val anyMode = r.optString("conditionsMode") == "any"
                ifCard.addView(hScroll(chip(if (anyMode) "Any can match (OR)" else "All must match (AND)", anyMode) {
                    r.put("conditionsMode", if (anyMode) "all" else "any"); rebuild()
                }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            }
            for (i in 0 until cs.length()) {
                val c = cs.getJSONObject(i)
                val ct = c.optString("type")
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label(Routines.CONDITIONS[ct] ?: ct, 13.5f, p.fg),
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                when (ct) {
                    "battery" -> {
                        val op = c.optString("op", "below")
                        val pct = c.optInt("value", 25)
                        row.addView(chip(if (op == "above") "≥" else "≤", false) {
                            c.put("op", if (op == "above") "below" else "above"); rebuild()
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                        row.addView(chip("$pct%", false) {
                            val opts = listOf(10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 95)
                            SolCubeDialog.Builder(this).setTitle("Battery level")
                                .setItems(opts.map { "$it%" }.toTypedArray()) { _, w -> c.put("value", opts[w]); rebuild() }.show()
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 4.dp })
                    }
                    "temp" -> {
                        val op = c.optString("op", "above")
                        val deg = c.optInt("value", 40)
                        row.addView(chip(if (op == "below") "≤" else "≥", false) {
                            c.put("op", if (op == "below") "above" else "below"); rebuild()
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                        row.addView(chip("$deg °C", false) {
                            val opts = (30..50 step 2).toList()
                            SolCubeDialog.Builder(this).setTitle("Battery temperature")
                                .setItems(opts.map { "$it °C" }.toTypedArray()) { _, w -> c.put("value", opts[w]); rebuild() }.show()
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 4.dp })
                    }
                    "wifi_network" -> {
                        val op = c.optString("op", "connected")
                        val ssids = c.optJSONArray("ssids") ?: org.json.JSONArray().also { c.put("ssids", it) }
                        row.addView(chip(if (op == "not_connected") "Not on" else "On", false) {
                            c.put("op", if (op == "not_connected") "connected" else "not_connected"); rebuild()
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                        row.addView(chip(if (ssids.length() == 0) "Any" else "${ssids.length()} net", false) {
                            pickSavedNetworks(ssids) { rebuild() }
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 4.dp })
                    }
                    "bt_device" -> {
                        val op = c.optString("op", "connected")
                        val devs = c.optJSONArray("devices") ?: org.json.JSONArray().also { c.put("devices", it) }
                        row.addView(chip(if (op == "not_connected") "Not" else "Connected", false) {
                            c.put("op", if (op == "not_connected") "connected" else "not_connected"); rebuild()
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                        row.addView(chip(if (devs.length() == 0) "Any" else "${devs.length()} dev", false) {
                            pickBtDevice { devs.put(it); rebuild() }
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 4.dp })
                    }
                    "app" -> {
                        val op = c.optString("op", "in")
                        val apps = c.optJSONArray("apps") ?: org.json.JSONArray().also { c.put("apps", it) }
                        row.addView(chip(if (op == "not_in") "Not in" else "In", false) {
                            c.put("op", if (op == "not_in") "in" else "not_in"); rebuild()
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                        row.addView(chip("${apps.length()} apps", false) {
                            pickMultiApps("Current app", apps, userOnly = false) { rebuild() }
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 4.dp })
                    }
                    else -> {
                        val vals = Routines.conditionValues(ct)
                        if (vals.isNotEmpty()) {
                            val v = c.optString("value", vals.first())
                            row.addView(chip(Routines.conditionValueText(ct, v), false) {
                                c.put("value", vals[(vals.indexOf(v) + 1) % vals.size]); rebuild()
                            }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                        }
                    }
                }
                row.addView(label("✕", 15f, p.sub).apply {
                    gravity = Gravity.CENTER; background = ripple(null, 16.dp); setOnClickListener { cs.remove(i); rebuild() }
                }, LinearLayout.LayoutParams(28.dp, 32.dp).apply { leftMargin = 4.dp })
                ifCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            }
            ifCard.addView(textButton("Add condition") {
                val flatKeys = Routines.CONDITION_GROUPS.flatMap { (_, items) -> items.map { it.first } }
                val groups = Routines.CONDITION_GROUPS.map { (hdr, items) -> hdr to items.map { it.second } }
                SolCubeDialog.Builder(this).setTitle("Add condition")
                    .setGroupedItems(groups) { _, w ->
                        val k = flatKeys[w]
                        val cond = Routines.defaultCondition(k)
                        cs.put(cond)
                        rebuild()
                        when (k) {
                            "wifi_network" -> pickSavedNetworks(cond.getJSONArray("ssids")) { rebuild() }
                            "bt_device" -> pickBtDevice { cond.getJSONArray("devices").put(it); rebuild() }
                            "app" -> pickMultiApps("Current app", cond.getJSONArray("apps"), userOnly = false) { rebuild() }
                        }
                    }.show()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 4.dp; leftMargin = (-12).dp })

            ifCard.addView(View(this).apply { setBackgroundColor(p.stroke) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 8.dp; bottomMargin = 4.dp })

            val others = all.filter { it.optString("id") != id }
            val after = r.optString("after")
            val afterName = others.firstOrNull { it.optString("id") == after }?.optString("name")
            ifCard.addView(label("Only after another routine ran (within 24 h)", 12.5f, p.sub).apply { setPadding(0, 12.dp, 0, 0) })
            ifCard.addView(hScroll(chip(afterName ?: "No", afterName != null) {
                val names = listOf("No") + others.map { it.optString("name") }
                SolCubeDialog.Builder(this).setTitle("Only after")
                    .setItems(names.toTypedArray()) { _, w ->
                        if (w == 0) r.remove("after") else r.put("after", others[w - 1].optString("id"))
                        rebuild()
                    }.show()
            }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            val unless = r.optString("unless")
            val unlessName = others.firstOrNull { it.optString("id") == unless }?.optString("name")
            ifCard.addView(label("Stop for the day once this routine ran today", 12.5f, p.sub).apply { setPadding(0, 12.dp, 0, 0) })
            ifCard.addView(hScroll(chip(unlessName ?: "No", unlessName != null) {
                val names = listOf("No") + others.map { it.optString("name") }
                SolCubeDialog.Builder(this).setTitle("Stop once this ran")
                    .setItems(names.toTypedArray()) { _, w ->
                        if (w == 0) r.remove("unless") else r.put("unless", others[w - 1].optString("id"))
                        rebuild()
                    }.show()
            }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            // between two routines: after start ran, until end runs (any number of times in between)
            val span = r.optJSONObject("between") ?: org.json.JSONObject()
            fun spanPick(key: String, title: String) {
                val names = listOf("No") + others.map { it.optString("name") }
                SolCubeDialog.Builder(this).setTitle(title)
                    .setItems(names.toTypedArray()) { _, w ->
                        if (w == 0) span.remove(key) else span.put(key, others[w - 1].optString("id"))
                        if (span.length() == 0) r.remove("between") else r.put("between", span)
                        rebuild()
                    }.show()
            }
            val startName = others.firstOrNull { it.optString("id") == span.optString("start") }?.optString("name")
            val endName = others.firstOrNull { it.optString("id") == span.optString("end") }?.optString("name")
            ifCard.addView(label("Only between two routines (after the first ran, until the second runs)", 12.5f, p.sub).apply { setPadding(0, 12.dp, 0, 0) })
            ifCard.addView(hScroll(
                chip(startName?.let { "After $it" } ?: "After…", startName != null) { spanPick("start", "After") },
                chip(endName?.let { "Before $it" } ?: "Before…", endName != null) { spanPick("end", "Before") },
            ), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            ifCard.addView(Switch(this).apply {
                text = "At most once a day"
                textSize = 13.5f
                setTextColor(p.fg)
                typeface = fontRegular
                styleSwitch(this)
                isChecked = r.optBoolean("once")
                setOnCheckedChangeListener { _, c -> r.put("once", c) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
            dynamic.addView(ifCard, cardParams())

            // ---- Do
            val doCard = card()
            doCard.addView(label("Do, in this order", 14.5f, p.fg, bold = true))
            val acts = actions()
            renderActionsEditor(doCard, acts, isRoutine = true, allRoutines = all, selfRoutineId = id, rebuild = rebuild)
            if (acts.length() > 0) doCard.addView(label("“Back to before” puts back what that setting was before a routine last changed it.", 11.5f, p.sub)
                .apply { setPadding(0, 4.dp, 0, 0) })
            dynamic.addView(doCard, cardParams())
        }
        rebuild()

        fun commit(): List<org.json.JSONObject> {
            r.put("name", nameField.text.toString().trim().ifEmpty { "Routine" })
            val list = all.toMutableList()
            val i = list.indexOfFirst { it.optString("id") == id }
            if (i >= 0) list[i] = r else list += r
            return list
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pillButton("Save", filled = true) {
            val list = commit()
            io.execute { Routines.save(this, list); main.post { renderRoutineList(); poll() } }
            dlg.dismiss()
        }, LinearLayout.LayoutParams(0, 46.dp, 1f))
        buttons.addView(pillButton("Run now", filled = false) {
            val list = commit()
            io.execute {
                Routines.save(this, list)
                val ok = routinesCmd("run $id") == "ok"
                main.post { toast(if (ok) "Running “${r.optString("name")}”" else "Routines engine isn't running (Shizuku?)"); renderRoutineList() }
            }
        }, LinearLayout.LayoutParams(0, 46.dp, 1f).apply { leftMargin = 8.dp })
        body.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 20.dp })
        if (!isNew) body.addView(textButton("Delete routine") {
            SolCubeDialog.Builder(this).setMessage("Delete “${r.optString("name")}”?")
                .setPositiveButton("Delete") { _, _ ->
                    val list = all.filter { it.optString("id") != id }.onEach {
                        if (it.optString("after") == id) it.remove("after")
                        if (it.optString("unless") == id) it.remove("unless")
                        it.optJSONObject("between")?.let { b ->
                            if (b.optString("start") == id) b.remove("start")
                            if (b.optString("end") == id) b.remove("end")
                            if (b.length() == 0) it.remove("between")
                        }
                        it.optJSONArray("actions")?.let { actsArr ->
                            for (ai in 0 until actsArr.length()) {
                                val actObj = actsArr.optJSONObject(ai)
                                if (actObj != null && actObj.optString("type") == "run_routine" && actObj.optString("routineId") == id) {
                                    actObj.put("routineId", "")
                                }
                            }
                        }
                    }
                    io.execute { Routines.save(this, list); main.post { renderRoutineList(); poll() } }
                    dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
        }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        body.addView(textButton("Cancel") { dlg.dismiss() }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        dlg.setContentView(pinnedBackdropLayout(
            titleView = titleView,
            body = body,
            titleLeftPx = 22.dp,
            titleTopPx = 20.dp,
            titleBottomGapPx = 16.dp,
            bodySidePadPx = 16.dp,
            bodyBottomPadPx = 28.dp,
            blurBelowTitlePx = 12.dp,
            fadeBelowTitlePx = 4.dp,
            edgeToEdge = true,
        ))
        styleDialogWindow(dlg.window)
        dlg.show()
    }

    // ------------------------------------------------------------------ updates, feedback

    private lateinit var updateLink: TextView

    private var openedNow = false        // set in onStart: the app was just opened / brought back
    private var updateDialogUp = false

    override fun onStart() {
        super.onStart()
        openedNow = true
    }

    /** [manual] = the "Check for updates" button: always asks GitHub and says when there's nothing new. */
    private fun checkUpdates(manual: Boolean = false) {
        io.execute {
            val r = Updates.check(this, force = manual)
            main.post {
                if (isFinishing) return@post
                updateLink.visibility = if (r != null) View.VISIBLE else View.GONE
                if (r != null) {
                    updateLink.text = "Update available: ${r.version}"
                    Updates.firstShow(this, r)   // the background check won't pop it up again
                    // every time the app is opened while it's out of date (and from the button)
                    if ((openedNow || manual) && !updateDialogUp) {
                        updateDialogUp = true
                        Updates.dialog(this, r) { updateDialogUp = false }
                    }
                } else if (manual) {
                    SolCubeDialog.Builder(this).setTitle("You're up to date")
                        .setMessage("SolCube ${Updates.current(this)} is the latest version.")
                        .setPositiveButton("OK", null).show()
                }
                openedNow = false
            }
        }
    }

    /** Once: delete the settings file 2.1.1–2.1.2 left in public Downloads (needs Shizuku; retried on each open). */
    private fun cleanupOldBackup() {
        val p = Components.prefs(this)
        if (p.getBoolean("old_backup_gone", false) || Shell.state() != Shell.State.READY) return
        io.execute { if (Shell.oneShot(this, "cleanup-old-backup") == "ok") p.edit().putBoolean("old_backup_gone", true).apply() }
    }

    /** On every open with Shizuku: Android's background exemptions (battery whitelist, run in
     *  background, active standby bucket), so the system doesn't put SolCube to sleep. */
    private fun keepAlive() {
        if (Shell.state() != Shell.State.READY) return
        io.execute { Shell.oneShot(this, "keep-alive") }
    }

    private fun featureRequest() {
        SolCubeDialog.Builder(this)
            .setTitle("Can't find something?")
            .setMessage("Missing a feature, or something doesn't work on your phone? Send the creator a DM on Reddit with your idea.")
            .setPositiveButton("Message on Reddit") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/user/HerrKaizer__/"))) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    // ================================================================== small widgets (from TapDrop)

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        background = LiquidGlass(p, 24.dp.toFloat(), isDark(), resources.displayMetrics.density, level = 0)
        setPadding(18.dp, 18.dp, 18.dp, 18.dp)
    }

    private fun innerCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        background = LiquidGlass(p, 18.dp.toFloat(), isDark(), resources.displayMetrics.density, level = 1)
        setPadding(14.dp, 14.dp, 14.dp, 14.dp)
    }

    /** Liquid glass pane (see LiquidGlass): [level] 0 on the backdrop, 1 in a card, 2 in a small card. */
    private fun glass(radius: Int, level: Int = 2, tint: Int? = null): Drawable =
        LiquidGlass(p, radius.toFloat(), isDark(), resources.displayMetrics.density, level = level, tint = tint)

    /** Filled buttons: ink (day) / champagne (night) tinted glass. */
    private fun accentGradient(radius: Int): Drawable {
        return LiquidGlass(p, radius.toFloat(), isDark(), resources.displayMetrics.density, level = 2,
            tint = p.fill, shine = p.fillShine, shadow = p.halo == 0, halo = p.halo, stroke = p.fillStroke)
    }

    private fun blendColors(a: Int, b: Int) = Color.rgb((Color.red(a) + Color.red(b)) / 2,
        (Color.green(a) + Color.green(b)) / 2, (Color.blue(a) + Color.blue(b)) / 2)

    private fun cardParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp }

    private fun innerCardParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontMedium else fontRegular
    }

    /** Card title with a small "BETA" tag after it. */
    private fun betaTitle(t: String, size: Float = 18f) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(t, size, p.fg, bold = true))
        addView(label("BETA", 10.5f, p.accent, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(8.dp, 2.dp, 8.dp, 2.dp)
            background = GradientDrawable().apply {
                cornerRadius = 10.dp.toFloat()
                setColor(alpha(p.accent, 0.14f))
                setStroke(1.dp, alpha(p.accent, 0.45f))
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 10.dp })
    }

    /** "Also a Quick Settings tile" + a button for the system's add-tile prompt. */
    private fun qsHint(into: LinearLayout, cls: Class<*>, name: String, icon: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label("Also a Quick Settings tile", 12.5f, p.sub), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(textButton("Add tile") {
            FeatureTile.requestAdd(this, cls, name, icon) { msg -> if (msg.isNotEmpty()) toast(msg) }
        }.apply { textSize = 13f; setPadding(10.dp, 6.dp, 10.dp, 6.dp) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        into.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp; rightMargin = (-10).dp })
    }

    private fun textButton(t: String, onClick: () -> Unit) = label(t, 14.5f, p.link, bold = true).apply {
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = ripple(null, 18.dp)
        setOnClickListener { onClick() }
    }

    /**
     * SolCube's switch look: a pill track (frosted when off, champagne when on, like the filled
     * buttons) with a round white knob. Replaces the stock Material thumb and track.
     */
    private fun styleSwitch(sw: Switch) {
        val trackW = 50.dp; val trackH = 30.dp; val knob = 24.dp; val inset = (trackH - knob) / 2
        fun track(on: Boolean): Drawable = if (on && p.halo != 0)
            GlowPill(p.fill or 0xFF000000.toInt(), p.fill or 0xFF000000.toInt(), p.fillStroke, p.halo, resources.displayMetrics.density, trackW, trackH)
        else GradientDrawable().apply {
            cornerRadius = trackH / 2f
            setSize(trackW, trackH)
            if (on) setColor(p.fill)
            else {
                setColor(if (isDark()) Color.argb(46, 255, 255, 255) else Color.argb(38, 0, 0, 0))
                setStroke(1.dp, if (isDark()) Color.argb(56, 255, 255, 255) else Color.argb(36, 0, 0, 0))
            }
        }
        val trackStates = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(true))
            addState(intArrayOf(), track(false))
        }
        // the knob: a white circle drawn inside a track-height square, so it sits in the track with an
        // even margin. (An InsetDrawable reports its inset as padding, and Switch then stretches the
        // knob over the track ends.)
        val thumb = object : Drawable() {
            private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(28, 0, 0, 0)
            }
            override fun draw(c: android.graphics.Canvas) {
                val b = bounds
                val r = minOf(b.width(), b.height()) / 2f - inset
                c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, fill)
                c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, edge)
            }
            override fun getIntrinsicWidth() = trackH
            override fun getIntrinsicHeight() = trackH
            override fun setAlpha(a: Int) { fill.alpha = a }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        sw.thumbTintList = null
        sw.trackTintList = null
        sw.thumbDrawable = thumb
        sw.trackDrawable = trackStates
        sw.showText = false
        sw.switchMinWidth = trackW
        sw.switchPadding = 14.dp
        sw.minHeight = 44.dp
    }

    private fun pillButton(t: String, filled: Boolean, onClick: () -> Unit) =
        label(t, 15f, if (filled) p.onAccent else p.fg, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            background = ripple(if (filled) accentGradient(24.dp) else glass(24.dp), 24.dp)
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun ripple(content: Drawable?, radius: Int): Drawable {
        val r = RippleDrawable(ColorStateList.valueOf(alpha(p.fg, 0.12f)), null, rounded(Color.WHITE, radius))
        return if (content == null) r else LayerDrawable(arrayOf(content, r))
    }

    private fun alpha(color: Int, a: Float) =
        Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** Sets the text only when it changed (a new text re-lays out the whole page). */
    private fun TextView.put(s: CharSequence) { if (text?.toString() != s.toString()) text = s }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    /**
     * Multi-band progressive vertical blur + top fade applied strictly to the scrolling content
     * wrapper so the underlying [GlassBackdrop] stays completely sharp.
     */
    /** Settings → Header blur: 3 (three steps, default) or 1 (single); 0 = off (test broadcast only). */
    private fun headerPasses() = Components.prefs(this).getInt(KEY_HEADER_PASSES, 3)

    private fun buildHeaderBlurEffect(yTop: Float, yEnd: Float, fadeStart: Float): RenderEffect {
        val d = resources.displayMetrics.density
        val dark = isDark()
        val vr = if (dark) 0x12 / 255f else 0.957f
        val vg = if (dark) 0x0D / 255f else 0.933f
        val vb = if (dark) 0x07 / 255f else 0.902f
        val passes = headerPasses()
        fun bandMask(band: Float): RenderEffect {
            val sh = RuntimeShader(HEADER_BLUR_MASK_SRC).apply {
                setFloatUniform("yTop", yTop)
                setFloatUniform("yEnd", yEnd)
                setFloatUniform("fadeStart", fadeStart)
                setFloatUniform("band", band)
                setFloatUniform("passes", if (passes == 1) 1f else 3f)
                setFloatUniform("veil", vr, vg, vb)
            }
            return RenderEffect.createRuntimeShaderEffect(sh, "src")
        }
        val b0 = bandMask(0f)
        if (passes == 1) {
            // one blur instead of three (each pass blurs the whole scrolling layer, every frame it
            // changes): about a third of the GPU work while scrolling, nearly the same look
            val b = RenderEffect.createChainEffect(bandMask(1f), RenderEffect.createBlurEffect(13f * d, 13f * d, Shader.TileMode.CLAMP))
            return RenderEffect.createBlendModeEffect(b0, b, BlendMode.PLUS)
        }
        val b1 = RenderEffect.createChainEffect(bandMask(1f), RenderEffect.createBlurEffect(5.5f * d, 5.5f * d, Shader.TileMode.CLAMP))
        val b2 = RenderEffect.createChainEffect(bandMask(2f), RenderEffect.createBlurEffect(11.5f * d, 11.5f * d, Shader.TileMode.CLAMP))
        val b3 = RenderEffect.createChainEffect(bandMask(3f), RenderEffect.createBlurEffect(18f * d, 18f * d, Shader.TileMode.CLAMP))
        val low = RenderEffect.createBlendModeEffect(b0, b1, BlendMode.PLUS)
        val high = RenderEffect.createBlendModeEffect(b2, b3, BlendMode.PLUS)
        return RenderEffect.createBlendModeEffect(low, high, BlendMode.PLUS)
    }

    /**
     * Draggable and tappable 3-stop sliding selector for Light / System / Dark theme mode.
     */
    private inner class ThemeSliderView(
        initialMode: Int,
        private val onModeSelected: (Int) -> Unit,
    ) : View(this) {
        private val labels = arrayOf("Light", "System", "Dark")
        private val d = resources.displayMetrics.density
        private var mode = initialMode.coerceIn(0, 2)
        private var pos = mode.toFloat()
        private var anim: android.animation.ValueAnimator? = null

        private val thumbRect = android.graphics.RectF()
        private val thumbFill = p.fill
        private val thumbPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = thumbFill
            if (p.halo != 0) setShadowLayer(8f * d, 0f, 0f, p.halo)
            else setShadowLayer(4f * d, 0f, 1.5f * d, alpha(Color.BLACK, if (isDark()) 0.45f else 0.18f))
        }
        private val rimPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f * d
            color = if (p.fillStroke != 0) p.fillStroke else alpha(Color.WHITE, if (isDark()) 0.25f else 0.45f)
            if (p.fillStroke != 0) strokeWidth = 1.2f * d
        }
        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 13.5f * d
            typeface = fontMedium
        }

        private var downX = 0f
        private var dragging = false
        private var lastTickStop = mode
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        init {
            background = glass(22.dp, level = 2)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val pad = 4f * d
            val segW = ((width - 2f * pad) / 3f).coerceAtLeast(1f)
            val thumbH = (height - 2f * pad).coerceAtLeast(1f)
            val r = thumbH * 0.5f
            val left = pad + pos * segW
            thumbRect.set(left, pad, left + segW, pad + thumbH)
            if (p.halo != 0) {
                Glow.request(this)
            }
            canvas.drawRoundRect(thumbRect, r, r, thumbPaint)
            val rimInset = rimPaint.strokeWidth * 0.5f
            thumbRect.inset(rimInset, rimInset)
            canvas.drawRoundRect(thumbRect, r - rimInset, r - rimInset, rimPaint)

            val fm = textPaint.fontMetrics
            val baseline = height * 0.5f - (fm.ascent + fm.descent) * 0.5f
            for (i in labels.indices) {
                val w = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
                textPaint.color = mixColor(p.sub, p.onAccent, w)
                val cx = pad + (i + 0.5f) * segW
                canvas.drawText(labels[i], cx, baseline, textPaint)
            }
        }

        private fun mixColor(a: Int, b: Int, t: Float): Int {
            val inv = 1f - t
            return Color.argb(
                (Color.alpha(a) * inv + Color.alpha(b) * t).toInt(),
                (Color.red(a) * inv + Color.red(b) * t).toInt(),
                (Color.green(a) * inv + Color.green(b) * t).toInt(),
                (Color.blue(a) * inv + Color.blue(b) * t).toInt(),
            )
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val pad = 4f * d
            val segW = ((width - 2f * pad) / 3f).coerceAtLeast(1f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    anim?.cancel()
                    downX = ev.x
                    dragging = false
                    lastTickStop = kotlin.math.round(pos).toInt().coerceIn(0, 2)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && kotlin.math.abs(ev.x - downX) > touchSlop) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        pos = ((ev.x - pad - segW * 0.5f) / segW).coerceIn(0f, 2f)
                        invalidate()
                        val stop = kotlin.math.round(pos).toInt().coerceIn(0, 2)
                        if (stop != lastTickStop) {
                            lastTickStop = stop
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val target = when {
                        ev.actionMasked == MotionEvent.ACTION_CANCEL -> mode
                        dragging -> kotlin.math.round(pos).toInt().coerceIn(0, 2)
                        else -> ((ev.x - pad) / segW).toInt().coerceIn(0, 2)
                    }
                    val changed = target != mode
                    if (changed && !dragging) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    mode = target
                    anim?.cancel()
                    var cancelled = false
                    anim = android.animation.ValueAnimator.ofFloat(pos, target.toFloat()).apply {
                        duration = 180L
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener {
                            pos = it.animatedValue as Float
                            invalidate()
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                if (!cancelled && changed) onModeSelected(target)
                            }
                        })
                        start()
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    /**
     * Pinned title above a scrolling body over [GlassBackdrop].
     * Child order is fixed as [GlassBackdrop, scrollWrap, titleView] so [LiquidGlass] finds the backdrop.
     */
    private fun pinnedBackdropLayout(
        titleView: TextView,
        body: LinearLayout,
        titleLeftPx: Int,
        titleTopPx: Int,
        titleBottomGapPx: Int,
        bodySidePadPx: Int,
        bodyBottomPadPx: Int,
        blurBelowTitlePx: Int,
        fadeBelowTitlePx: Int,
        edgeToEdge: Boolean,
        liftPx: Int = 0,
        liftStartPx: Int = 0,
        liftSpanPx: Int = 1,
        initialScrollY: Int = 0,
        onScrollReady: ((ScrollView) -> Unit)? = null,
    ): FrameLayout {
        titleView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        var titleH = titleView.measuredHeight
        var topInset = 0
        var bottomInset = 0
        var lastKey = ""
        var headerBlur: RenderEffect? = null
        var appliedBlur: RenderEffect? = null
        var targetLiftY = 0f
        var currentLiftY = 0f
        var liftAnimRunning = false
        var liftLastNs = 0L

        val liftFrame = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (!liftAnimRunning || !titleView.isAttachedToWindow) {
                    liftAnimRunning = false
                    return
                }
                if (liftLastNs == 0L) liftLastNs = now
                val dt = ((now - liftLastNs) / 1e9f).coerceIn(0.001f, 0.05f)
                liftLastNs = now
                val diff = targetLiftY - currentLiftY
                if (kotlin.math.abs(diff) < 0.15f) {
                    currentLiftY = targetLiftY
                    titleView.translationY = currentLiftY
                    liftAnimRunning = false
                } else {
                    val alpha = 1f - kotlin.math.exp(-dt * 5.0f)
                    currentLiftY += diff * alpha
                    titleView.translationY = currentLiftY
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        titleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                liftAnimRunning = false
                android.view.Choreographer.getInstance().removeFrameCallback(liftFrame)
            }
        })

        val bd = GlassBackdrop(this@MainActivity)
        val scroll = BounceScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body)
        }
        onScrollReady?.invoke(scroll)
        if (initialScrollY > 0) {
            scroll.post {
                scroll.scrollTo(0, initialScrollY)
                if (liftPx > 0) {
                    val lu = ((scroll.scrollY - liftStartPx).toFloat() / liftSpanPx.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val ease = lu * lu * lu * (lu * (lu * 6f - 15f) + 10f)
                    targetLiftY = -liftPx * ease
                    currentLiftY = targetLiftY
                    titleView.translationY = currentLiftY
                }
            }
        }
        val scrollWrap = HeaderBlurFrame(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val titleLp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = titleLeftPx
            topMargin = titleTopPx
        }

        fun syncLayout() {
            body.setPadding(
                bodySidePadPx,
                topInset + titleTopPx + titleH + titleBottomGapPx,
                bodySidePadPx,
                bodyBottomPadPx + bottomInset,
            )
            val wantTop = topInset + titleTopPx
            if (titleLp.topMargin != wantTop) {
                titleLp.topMargin = wantTop
                titleView.layoutParams = titleLp
            }
            val key = "$topInset:$bottomInset:$titleH"
            if (key != lastKey) {
                lastKey = key
                val liftedBottom = (topInset + titleTopPx - liftPx + titleH).toFloat()
                val yTop = if (edgeToEdge) (topInset + 4.dp).toFloat() else 0f
                val yEnd = liftedBottom + blurBelowTitlePx
                val fadeStart = liftedBottom + fadeBelowTitlePx
                headerBlur = buildHeaderBlurEffect(yTop, yEnd, fadeStart)
                scrollWrap.stripBottom = kotlin.math.ceil(yEnd).toInt()
                if (scroll.scrollY > 0 && headerPasses() > 0) {
                    appliedBlur = headerBlur
                    scrollWrap.effect = headerBlur
                }
            }
        }

        syncLayout()
        titleView.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && h != titleH) {
                titleH = h
                syncLayout()
            }
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            bd.setScroll(y, scroll)
            val want = if (y > 0 && headerPasses() > 0) headerBlur else null
            if (appliedBlur !== want) {
                appliedBlur = want
                scrollWrap.effect = want
            }
            if (liftPx > 0) {
                val lu = ((y - liftStartPx).toFloat() / liftSpanPx.coerceAtLeast(1)).coerceIn(0f, 1f)
                val ease = lu * lu * lu * (lu * (lu * 6f - 15f) + 10f)
                targetLiftY = -liftPx * ease
                if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    currentLiftY = targetLiftY
                    titleView.translationY = currentLiftY
                } else if (!liftAnimRunning && kotlin.math.abs(targetLiftY - currentLiftY) >= 0.15f) {
                    liftAnimRunning = true
                    liftLastNs = 0L
                    android.view.Choreographer.getInstance().postFrameCallback(liftFrame)
                }
            }
        }

        return FrameLayout(this).apply {
            addView(bd, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scrollWrap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(titleView, titleLp)
            if (edgeToEdge) {
                setOnApplyWindowInsetsListener { _, insets ->
                    val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                    val t = sys.top
                    val b = maxOf(sys.bottom, ime.bottom)
                    if (t != topInset || b != bottomInset) {
                        topInset = t
                        bottomInset = b
                        syncLayout()
                    }
                    insets
                }
            }
        }
    }

    // ================================================================== theme

    private fun isDark() = Look.isDark(this)

    private fun white(a: Float) = Color.argb((a * 255).toInt(), 255, 255, 255)

    private fun palette() = Look.palette(this)

    @Suppress("DEPRECATION")
    private fun styleDialogWindow(w: android.view.Window?) {
        w?.apply {
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            isNavigationBarContrastEnforced = false
            setDecorFitsSystemWindows(false)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            decorView.post { insetsController?.setSystemBarsAppearance(if (isDark()) 0 else f, f) }
        }
    }

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.decorView.setBackgroundColor(p.base)
        window.setDecorFitsSystemWindows(false)
        val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (isDark()) 0 else f, f)
    }

    private companion object {
        const val KEY_HEADER_PASSES = "header_passes"
        private const val HEADER_BLUR_MASK_SRC = """
uniform shader src;
uniform float yTop;
uniform float yEnd;
uniform float fadeStart;
uniform float band;
uniform float passes;
uniform float3 veil;

half4 main(float2 fc) {
    float u = clamp((yEnd - fc.y) / max(yEnd - yTop, 1.0), 0.0, 1.0);
    float t = u * (0.85 + 0.15 * u);
    float s = t * 3.0;
    float w = 0.0;
    if (passes < 1.5) {
        // one blur pass: sharp (band 0) fading into the single blurred copy (band 1)
        w = band < 0.5 ? 1.0 - t : t;
    } else if (band < 0.5) {
        w = clamp(1.0 - s, 0.0, 1.0);
    } else if (band < 1.5) {
        w = max(0.0, 1.0 - abs(s - 1.0));
    } else if (band < 2.5) {
        w = max(0.0, 1.0 - abs(s - 2.0));
    } else {
        w = clamp(s - 2.0, 0.0, 1.0);
    }
    if (w <= 0.0) return half4(0.0);
    half4 c = src.eval(fc);
    float fu = clamp((fadeStart - fc.y) / max(fadeStart, 1.0), 0.0, 1.0);
    float soft = 0.25 * fu * fu * (3.0 - 2.0 * fu);
    half3 rgb = mix(c.rgb, half3(veil) * c.a, half(soft));
    return half4(rgb, c.a) * half(w);
}
"""
    }
}
