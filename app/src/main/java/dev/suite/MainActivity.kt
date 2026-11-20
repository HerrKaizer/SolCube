package dev.suite

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
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
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
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
    private lateinit var ctsTouch: Section
    private lateinit var ctsButton: Section
    private lateinit var ctsNav: Section
    private lateinit var ctsDot: View
    private lateinit var ctsStatus: TextView
    private lateinit var ctsNote: TextView
    private lateinit var game: Section
    private lateinit var srSec: Section
    private lateinit var mixSec: Section
    private lateinit var temp: Section

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        io.execute { Components.reconcile(this) }
        poll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        Components.ctsTouch(this)   // one-time split of the old single Circle to Search switch
        actionBar?.hide()
        styleSystemBars()
        setContentView(build())
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        io.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        SuiteService.sync(this)   // e.g. overlay permission was just granted
        io.execute { Components.reconcile(this) }
        poll()
        checkUpdates()
        cleanupOldBackup()
        renderDouble()   // vivo's switch / the handler may have changed in Settings
    }

    override fun onPause() {
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
        val canDraw = Settings.canDrawOverlays(this)

        shizukuCard.visibility = if (s.shell == Shell.State.READY) View.GONE else View.VISIBLE
        when (s.shell) {
            Shell.State.NOT_RUNNING -> {
                shizukuText.text = "Shizuku isn't running — it stops on every reboot. Start it again in the Shizuku app (Wireless debugging). Circle to Search, Frame interpolation and Super resolution need it; Suite brings them back by itself once it's running."
                shizukuButton.text = "Open Shizuku"
            }
            Shell.State.NO_PERMISSION -> {
                shizukuText.text = "Allow Suite to use Shizuku so it can start and stop the shell-side parts."
                shizukuButton.text = "Allow"
            }
            Shell.State.READY -> {}
        }
        overlayCard.visibility = if (canDraw) View.GONE else View.VISIBLE
        val shellOk = s.shell == Shell.State.READY

        // Circle to Search: shell listener + ripple overlay in this app
        val ripple = RippleHost.running
        val listener = s.cts?.optBoolean("running") == true
        fun ctsDetail(sec: Section, idle: String) = when {
            !shellOk && enabled(sec) -> "Waiting for Shizuku"
            enabled(sec) && !canDraw -> "Needs “Display over other apps”"
            else -> idle
        }
        showSection(ctsTouch, running = s.cts?.optBoolean("touch") == true && ripple,
            detail = ctsDetail(ctsTouch, "Hold two fingers still on the screen"))
        showSection(ctsButton, running = s.cts?.optBoolean("button") == true && enabled(ctsButton) && ripple,
            detail = ctsDetail(ctsButton, "Hold the power button (screen on)"))
        showSection(ctsNav, running = s.cts?.optBoolean("nav") == true && ripple,
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
        ).any { it } && ripple
        ctsStatus.text = when {
            count == 0 -> "Not running"
            ctsActive -> "$count active"
            else -> "$count enabled"
        }
        ctsStatus.setTextColor(if (ctsActive) p.ok else p.sub)
        (ctsDot.background as GradientDrawable).setColor(if (ctsActive) p.ok else alpha(p.sub, 0.5f))

        val anyCts = count > 0
        ctsNote.text = when {
            !anyCts -> "Pick a trigger below"
            listener && !ripple -> "Listener running, ripple overlay starting…"
            !listener && ripple && shellOk -> "Starting the listener…"
            else -> "Ripple, then Google's Circle to Search"
        }

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
        fiLive.visibility = if (fi != null) View.VISIBLE else View.GONE
        fiLog.visibility = fiLive.visibility
        if (fi != null) {
            fiLive.text = "fi  = ${fi.optString("fi")}\ngpu = ${fi.optString("gpu")}"
            fiLog.text = logText(fi)
        }

        // Super resolution
        val sr = s.sr
        showSection(
            srSec, running = sr?.optBoolean("running") == true,
            detail = when {
                !shellOk && enabled(srSec) -> "Waiting for Shizuku"
                sr == null -> "vivo super resolution, kept on while enabled"
                else -> "sr = ${sr.optString("sr")}  vsr = ${sr.optString("vsr")}"
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
        renderProfiles(s.profiles, shellOk)
        renderDouble()

        showSection(
            temp, running = TempOverlay.running,
            detail = if (enabled(temp) && !canDraw) "Needs “Display over other apps”"
            else if (Style.showLoad(this)) "CPU · GPU · Battery °C + CPU · GPU load — drag to move"
            else "CPU · GPU · Battery °C — drag the pill to move it",
        )

        fun setGroup(g: GroupCard, active: Boolean, text: String) {
            g.summary.text = text
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
        if (TempOverlay.running || enabled(temp)) gameParts += "Temp pill"
        if (fiRunning || enabled(game)) gameParts += "Frame interp"
        if (sr?.optBoolean("running") == true || enabled(srSec)) gameParts += "Super res"
        if (s.stream?.optBoolean("streaming") == true || Components.enabled(this, Components.KEY_STREAM)) gameParts += "Stream"
        setGroup(gamingGroup,
            TempOverlay.running || fiRunning || sr?.optBoolean("running") == true || s.stream?.optBoolean("streaming") == true || Components.enabled(this, Components.KEY_STREAM),
            if (gameParts.isEmpty()) "All off" else gameParts.joinToString(" · "))
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
        sec.toggle.text = when {
            working -> "…"
            on -> "Disable"
            else -> "Enable"
        }
        sec.toggle.setTextColor(if (on) p.fg else p.onAccent)
        sec.toggle.background = ripple(if (on) glass(20.dp) else accentGradient(20.dp), 20.dp)
        sec.status.text = if (running) "Running" else "Not running"
        sec.status.setTextColor(if (running) p.ok else p.sub)
        (sec.dot.background as GradientDrawable).setColor(if (running) p.ok else alpha(p.sub, 0.5f))
        sec.detail.text = detail
    }

    private fun toggle(sec: Section) {
        if (sec.key in busy) return
        val on = !enabled(sec)
        val needsOverlay = sec.key in setOf(Components.KEY_CTS_TOUCH, Components.KEY_CTS_BUTTON, Components.KEY_CTS_NAV, Components.KEY_TEMP)
        if (on && needsOverlay && !Settings.canDrawOverlays(this)) {
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
            }
            Thread.sleep(400)   // let the service / programs come up before re-checking
            val s = Components.status()
            main.post { busy -= sec.key; last = s; render() }
        }
    }

    // ================================================================== layout

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(16.dp, 28.dp, 16.dp, 24.dp)
            // keep focus off the package field so the page opens at the top
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        root.addView(label("Suite", 32f, p.fg).apply {
            typeface = fontBold
            letterSpacing = -0.01f
            setPadding(6.dp, 0, 0, 22.dp)
        })

        updateLink = textButton("") { Updates.available(this)?.let { Updates.dialog(this, it) } }.apply { visibility = View.GONE }
        root.addView(updateLink, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 8.dp; leftMargin = (-6).dp })

        root.addView(textButton("Can't find a feature?") { featureRequest() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 8.dp })

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

        // 3. Gaming big card (collapsed by default)
        gamingGroup = groupCard("gaming", "Gaming", defaultOpen = false)
        temp = section(Components.KEY_TEMP, "Temperature overlay", inner = true)
        addTempSettings(temp.body)
        gamingGroup.body.addView(temp.card, innerCardParams())

        game = section(Components.KEY_GAME, "Frame interpolation", inner = true)
        addGameControls(game.body)
        gamingGroup.body.addView(game.card, innerCardParams())

        srSec = section(Components.KEY_SR, "Super resolution", collapsible = false, inner = true)
        gamingGroup.body.addView(srSec.card, innerCardParams())

        gamingGroup.body.addView(buildStreamCard(inner = true))
        root.addView(gamingGroup.card, cardParams())

        // 4. Play together (standalone card)
        mixSec = section(Components.KEY_MIX, "Play together", collapsible = false)
        root.addView(mixSec.card, cardParams())

        root.addView(textButton("vivo background power settings") {
            try {
                startActivity(Intent().setClassName("com.iqoo.powersaving", "com.iqoo.powersaving.BackgroundHighUsageActivity"))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(label("Allow Suite to use power in the background, or vivo freezes it.", 12.5f, p.sub).apply {
            gravity = Gravity.CENTER
        })

        lateinit var restartBtn: TextView
        restartBtn = pillButton("Restart Suite", filled = false) { restartSuite(restartBtn) }.apply {
            textSize = 13.5f
            setPadding(20.dp, 0, 20.dp, 0)
        }
        root.addView(restartBtn, LinearLayout.LayoutParams(WRAP_CONTENT, 36.dp).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 16.dp
        })

        root.addView(textButton("Check for updates") { checkUpdates(manual = true) }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16.dp })
        root.addView(label("Suite ${Updates.current(this)}", 12f, p.sub).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 2.dp })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(root)
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        return FrameLayout(this).apply {
            addView(GlassBackdrop(this@MainActivity).also { bd -> scroll.setOnScrollChangeListener { _, _, y, _, _ -> bd.setScroll(y) } }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
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

    private fun restartSuite(btn: TextView? = null) {
        btn?.isEnabled = false
        btn?.text = "Restarting…"
        toast("Restarting Suite (back in ~2 s)…")
        io.execute {
            if (Shell.state() == Shell.State.READY) {
                val res = Shell.command(this, Shell.Kind.UTIL, "restart-suite")
                if (res == "ok") return@execute
            }
            stopService(Intent(this, SuiteService::class.java))
            Shell.reset()
            Thread.sleep(250)
            SuiteService.sync(this)
            main.post {
                btn?.isEnabled = true
                btn?.text = "Restart Suite"
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
        val restClipChildren = body.clipChildren
        val restClipToPadding = body.clipToPadding
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
        private val dy2 = 1f * d
        private val cardRect = android.graphics.RectF()
        private val shadowRect = android.graphics.RectF()
        private val cardPath = android.graphics.Path()
        private val dark = isDark()
        private val ambientPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = if (dark) Color.argb(140, 0, 0, 0) else Color.argb(38, 28, 22, 16)
            maskFilter = android.graphics.BlurMaskFilter(7f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private val spotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = if (dark) Color.argb(110, 0, 0, 0) else Color.argb(26, 28, 22, 16)
            maskFilter = android.graphics.BlurMaskFilter(2.5f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
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
            setPadding(14.dp, 8.dp, 14.dp, 14.dp)
        }
        card.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = (-14).dp
            rightMargin = (-14).dp
            topMargin = 4.dp
            bottomMargin = (-12).dp
        })
        makeCollapsible("group_$key", card, listOf(header), chevron, body, defaultOpen = defaultOpen)
        return GroupCard(card, dot, summary, body)
    }

    /** A switchable block: its own card, an inner card, or (with [into]) a sub-row inside a card. */
    private fun section(key: String, title: String, into: LinearLayout? = null, collapsible: Boolean = into == null, inner: Boolean = false): Section {
        val card = into ?: if (inner) innerCard() else card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(label(title, if (into != null) 15.5f else if (inner) 16.5f else 18f, p.fg, bold = true),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
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
        statusRow.addView(dot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        val status = label("", 14.5f, p.sub, bold = true)
        statusRow.addView(status)
        card.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })

        val detail = label("", 13f, p.sub).apply { setLineSpacing(2.dp.toFloat(), 1f) }
        card.addView(detail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })

        val body = if (collapsible && chevron != null) {
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { b ->
                card.addView(b)
                makeCollapsible(key, card, listOf(header, statusRow, detail), chevron, b, defaultOpen = false)
            }
        } else card

        sec = Section(key, card, body, toggle, dot, status, detail)
        return sec
    }

    // ------------------------------------------------------------------ FI + SR controls

    private lateinit var fiLive: TextView
    private lateinit var fiLog: TextView
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
        fiLive = mono(11.5f)
        card.addView(fiLive, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })

        card.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 8.dp; bottomMargin = 6.dp })
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

        card.addView(label("Log", 14.5f, p.fg, bold = true).apply { setPadding(0, 16.dp, 0, 4.dp) })
        fiLog = mono(10.5f)
        card.addView(fiLog)
        renderGames()
    }

    private fun chip(t: String, selected: Boolean, onClick: () -> Unit) =
        label(t, 13f, if (selected) p.onAccent else p.fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(12.dp, 0, 12.dp, 0)
            background = ripple(if (selected) accentGradient(16.dp) else glass(16.dp), 16.dp)
            setOnClickListener { onClick() }
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
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
            isChecked = prefs.getBoolean(Style.KEY_GRAY, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Style.KEY_GRAY, on).apply() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        card.addView(Switch(this).apply {
            text = "Show CPU & GPU load"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
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
            text = "Load as horizontal bars"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
            isChecked = Style.loadBars(this@MainActivity)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Style.KEY_LOAD_BARS, on).apply() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        card.addView(Switch(this).apply {
            text = "Blur background"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
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
        streamStatus.text = when {
            streaming -> "Streaming"
            mode -> "Laptop shape on"
            else -> "Off"
        }
        val live = mode || streaming
        streamStatus.setTextColor(if (live) p.ok else p.sub)
        (streamDot.background as GradientDrawable).setColor(if (live) p.ok else alpha(p.sub, 0.5f))
        streamDetail.text = when {
            !shellOk -> "Needs Shizuku"
            mode && !helper -> "Laptop not reachable — screen resets in 15 s"
            mode -> "Phone screen matches the laptop. Start the stream when the game is open."
            else -> "Plug in the cable; the laptop helper must be running."
        }
        wideBtn.text = if (streamBusy) "…" else if (mode) "Normal screen" else "16:10 screen"
        streamBtn.text = if (streaming) "Stop stream" else "Start stream"
        listOf(wideBtn to !mode, streamBtn to !streaming).forEach { (b, filled) ->
            b.setTextColor(if (filled) p.onAccent else p.fg)
            b.background = ripple(if (filled) accentGradient(24.dp) else glass(24.dp), 24.dp)
        }
    }

    private fun streamAction(wide: Boolean) {
        if (streamBusy) return
        val mode = Components.enabled(this, Components.KEY_STREAM)
        val streaming = last?.stream?.optBoolean("streaming") == true
        if (!Settings.canDrawOverlays(this)) { openOverlaySettings(); return }
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
        dpMake = pillButton("Make Suite the handler", filled = true) {
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
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
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
        dpStatus.text = when {
            ready -> "Suite handles it"
            mine -> "vivo's double press is off"
            else -> "Not handled by Suite"
        }
        dpStatus.setTextColor(if (ready) p.ok else p.sub)
        (dpDot.background as GradientDrawable).setColor(if (ready) p.ok else alpha(p.sub, 0.5f))
        dpDetail.text = when {
            ready -> "Double-press the power button to " + actionText(DoublePress.action) + "."
            mine -> "Its switch in vivo Wallet (“Double press the Power button to quickly swipe cards”) is off. Set up turns it on."
            h == null -> "Android asks which app to use each time."
            else -> "Right now it goes to ${appName(h)}."
        }
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
        else -> pa.target(this).takeIf { it.isNotEmpty() }?.let { "open ${appName(it)}" } ?: "open the app picked below"
    }

    /** The choices of a power-button action: apps (tap to use, hold to remove), sound modes, [cts]. */
    private fun renderChoices(box: LinearLayout, pa: PressAction, cts: Boolean, rerender: () -> Unit) {
        box.removeAllViews()
        val act = pa.action(this)
        val t = pa.target(this)
        val apps = pa.apps(this)
        box.addView(hScroll(*apps.map { pkg ->
            chip(appName(pkg), pkg == t && act == null) { pa.setTarget(this, pkg); rerender() }.apply {
                setOnLongClickListener {
                    SuiteDialog.Builder(this@MainActivity).setMessage("Remove ${appName(pkg)} from the list?")
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
        box.addView(hScroll(*others.map { (c, name) ->
            chip(name, act == c) {
                pa.setAction(this, c)
                rerender()
                if (c.startsWith("ring:")) io.execute { Ringer.ensureAccess(this) }   // instant changes (silent needs DND access)
            }
        }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
    }

    private fun pickSingleApp(title: String, exclude: Collection<String>, onPick: (String) -> Unit) {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(launch, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName && it !in exclude }.map { it to appName(it) }.sortedBy { it.second.lowercase() }
        SuiteDialog.Builder(this).setTitle(title)
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
        all.forEach { r ->
            rtList.addView(View(this).apply { setBackgroundColor(p.stroke) },
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
            col.addView(label("${Routines.summary(r, all, ::appName)}  →  ${Routines.actionsText(r, ::appName, rtName)}", 12f, p.sub).apply {
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
                io.execute {
                    Routines.save(this, all)
                    main.post {
                        renderRoutineList()
                        renderRoutines(last?.routines, last?.shell == Shell.State.READY)
                        poll()
                    }
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { leftMargin = 6.dp })
            rtList.addView(row)
        }
    }

    private fun renderRoutines(st: org.json.JSONObject?, shellOk: Boolean) {
        val all = Routines.load(this)
        val total = all.size
        val onCount = all.count { it.optBoolean("on", true) }
        val any = onCount > 0
        val running = st?.optBoolean("running") == true
        val active = running && any
        rtStatus.text = when {
            !active -> "Not running"
            onCount == total -> "All running"
            else -> "$onCount/$total running"
        }
        rtStatus.setTextColor(if (active) p.ok else p.sub)
        (rtDot.background as GradientDrawable).setColor(if (active) p.ok else alpha(p.sub, 0.5f))
        rtDetail.text = when {
            !any -> "All routines are off"
            !shellOk -> "Waiting for Shizuku"
            st == null -> "Starting…"
            st.optString("error").isNotEmpty() -> "Can't listen for events, checking every 30 s instead"
            else -> "Tap a routine to edit it"
        }
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
        SuiteDialog.Builder(this).setTitle(title)
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
        profList.removeAllViews()
        val all = Profiles.load(this)
        val allRoutines = Routines.load(this)
        val rtName: (String) -> String = { id -> allRoutines.firstOrNull { it.optString("id") == id }?.optString("name") ?: "?" }
        val activeId = last?.profiles?.optString("activeId") ?: ""
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
        profStatus.text = when {
            !active -> "Not running"
            onCount == total -> "All running"
            else -> "$onCount/$total running"
        }
        profStatus.setTextColor(if (active) p.ok else p.sub)
        (profDot.background as GradientDrawable).setColor(if (active) p.ok else alpha(p.sub, 0.5f))
        profDetail.text = when {
            total == 0 -> "Change settings or launch an app while another is open"
            !any -> "All profiles are off"
            !shellOk -> "Waiting for Shizuku"
            st == null -> "Starting…"
            activeName != null -> "Active: $activeName"
            else -> "Tap a profile to edit it"
        }
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
        SuiteDialog.Builder(this).setTitle(title)
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
                SuiteDialog.Builder(this).setTitle("Saved networks")
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
                SuiteDialog.Builder(this).setTitle("Bluetooth device")
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

    private fun renderActionsEditor(
        doCard: LinearLayout,
        acts: org.json.JSONArray,
        isRoutine: Boolean,
        allRoutines: List<org.json.JSONObject>,
        selfRoutineId: String?,
        rebuild: () -> Unit,
    ) {
        val rtName: (String) -> String = { rid -> allRoutines.firstOrNull { it.optString("id") == rid }?.optString("name") ?: "Pick routine" }
        val selectableRoutines = allRoutines.filter { it.optString("id") != selfRoutineId }
        fun editActionDetail(k: String, act: org.json.JSONObject) {
            when (k) {
                "revive" -> pickReviveApps(act.optJSONArray("apps") ?: org.json.JSONArray().also { act.put("apps", it) }) { rebuild() }
                "launch_bg" -> pickMultiApps("Launch in background", act.optJSONArray("apps") ?: org.json.JSONArray().also { act.put("apps", it) }, userOnly = false) { rebuild() }
                "close_app" -> pickMultiApps("Close these apps", act.optJSONArray("apps") ?: org.json.JSONArray().also { act.put("apps", it) }, userOnly = false) { rebuild() }
                "open_app" -> pickSingleApp("Open app", emptyList()) { pkg -> act.put("pkg", pkg); rebuild() }
                "notice" -> promptText("Status pill text", act.optString("text"), "Message to show") { t -> act.put("text", t); rebuild() }
                "open_url" -> promptText("Open link / URL", act.optString("url"), "https://example.com") { u -> act.put("url", u); rebuild() }
                "shell_cmd" -> promptText("Shell command", act.optString("cmd"), "e.g. input keyevent 3") { c -> act.put("cmd", c); rebuild() }
                "run_routine" -> {
                    if (selectableRoutines.isEmpty()) { toast("No other routines"); return }
                    SuiteDialog.Builder(this).setTitle("Run routine")
                        .setItems(selectableRoutines.map { it.optString("name") }.toTypedArray()) { _, w ->
                            act.put("routineId", selectableRoutines[w].optString("id")); rebuild()
                        }.show()
                }
            }
        }

        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            val at = a.optString("type")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label("${i + 1}.  " + (Actions.ALL[at] ?: at), 14.5f, p.fg),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val vals = Actions.values(at, allowRestore = isRoutine)
            when (at) {
                "revive", "launch_bg", "close_app" -> {
                    val apps = a.optJSONArray("apps") ?: org.json.JSONArray().also { a.put("apps", it) }
                    row.addView(chip("${apps.length()} apps", false) { editActionDetail(at, a) },
                        LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                }
                "close_apps" -> {
                    val except = a.optJSONArray("except") ?: org.json.JSONArray().also { a.put("except", it) }
                    row.addView(chip(if (except.length() == 0) "Except…" else "Keep ${except.length()}", except.length() > 0) {
                        pickMultiApps("Keep these apps open", except, userOnly = false) { rebuild() }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                }
                "open_app" -> {
                    val pkg = a.optString("pkg")
                    row.addView(chip(if (pkg.isEmpty()) "Pick app" else appName(pkg).take(14), pkg.isNotEmpty()) {
                        editActionDetail(at, a)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                }
                "notice" -> {
                    val t = a.optString("text")
                    row.addView(chip(if (t.isEmpty()) "Set text" else t.take(14), t.isNotEmpty()) {
                        editActionDetail(at, a)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                }
                "open_url" -> {
                    val u = a.optString("url")
                    val shortU = u.removePrefix("https://").removePrefix("http://").take(14)
                    row.addView(chip(if (u.isEmpty()) "Set URL" else shortU, u.isNotEmpty()) {
                        editActionDetail(at, a)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                }
                "shell_cmd" -> {
                    val c = a.optString("cmd")
                    row.addView(chip(if (c.isEmpty()) "Set cmd" else c.take(14), c.isNotEmpty()) {
                        editActionDetail(at, a)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                }
                "run_routine" -> {
                    val rid = a.optString("routineId")
                    row.addView(chip(if (rid.isEmpty()) "Pick routine" else rtName(rid).take(14), rid.isNotEmpty()) {
                        editActionDetail(at, a)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                }
            }
            if (vals.isNotEmpty()) {
                val v = a.optString("value", vals.first())
                row.addView(chip(Actions.valueText(at, v), false) {
                    if (vals.size <= 6) {
                        a.put("value", vals[(vals.indexOf(v) + 1) % vals.size]); rebuild()
                    } else {
                        SuiteDialog.Builder(this).setTitle(Actions.ALL[at] ?: at)
                            .setItems(vals.map { Actions.valueText(at, it) }.toTypedArray()) { _, w ->
                                a.put("value", vals[w]); rebuild()
                            }.show()
                    }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
            }
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
            SuiteDialog.Builder(this).setTitle("Add action")
                .setGroupedItems(groups) { _, w ->
                    val k = flatKeys[w]
                    val act = Actions.defaultAction(k, allowRestore = isRoutine)
                    acts.put(act)
                    rebuild()
                    if (k in setOf("open_app", "launch_bg", "close_app", "notice", "open_url", "shell_cmd", "run_routine")) {
                        editActionDetail(k, act)
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

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 20.dp, 16.dp, 28.dp) }
        body.addView(label(if (orig == null) "New profile" else "Edit profile", 26f, p.fg, bold = true).apply {
            setPadding(6.dp, 0, 0, 16.dp)
        })
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
            dynamic.addView(doCard, cardParams())

            // ---- When leaving
            val leaveCard = card()
            leaveCard.addView(label("When leaving", 14.5f, p.fg, bold = true))
            leaveCard.addView(Switch(this).apply {
                text = "Back to before when leaving"
                textSize = 13.5f
                setTextColor(p.fg)
                typeface = fontRegular
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
                isChecked = pr.optBoolean("restoreOnExit", true)
                setOnCheckedChangeListener { _, c -> pr.put("restoreOnExit", c) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            leaveCard.addView(label("NFC and settings changed by this profile return to what they were before (NFC also turns off after 3 min locked; skipped if already on). One-shot and background apps stay as-is.",
                12f, p.sub).apply { setPadding(0, 4.dp, 0, 0) })
            dynamic.addView(leaveCard, cardParams())
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
            SuiteDialog.Builder(this).setMessage("Delete “${pr.optString("name")}”?")
                .setPositiveButton("Delete") { _, _ ->
                    val list = all.filter { it.optString("id") != id }
                    io.execute { Profiles.save(this, list); main.post { renderProfileList(); poll() } }
                    dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
        }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        body.addView(textButton("Cancel") { dlg.dismiss() }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
        dlg.setContentView(FrameLayout(this).apply {
            addView(GlassBackdrop(this@MainActivity).also { bd -> scroll.setOnScrollChangeListener { _, _, y, _, _ -> bd.setScroll(y) } }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        dlg.window?.apply {
            @Suppress("DEPRECATION") statusBarColor = p.base
            @Suppress("DEPRECATION") navigationBarColor = p.base
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
    }

    private fun pickTime(current: String, set: (String) -> Unit) {
        val parts = current.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        android.app.TimePickerDialog(this, { _, hh, mm -> set(String.format(java.util.Locale.US, "%02d:%02d", hh, mm)) }, h, m, true).show()
    }

    private fun editRoutine(orig: org.json.JSONObject?) {
        val all = Routines.load(this)
        val r = org.json.JSONObject((orig ?: org.json.JSONObject()
            .put("id", Routines.newId()).put("name", "New routine").put("on", true)
            .put("trigger", org.json.JSONObject().put("type", "dnd_on"))
            .put("actions", org.json.JSONArray())).toString())
        val id = r.getString("id")
        val dlg = android.app.Dialog(this,
            if (isDark()) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 20.dp, 16.dp, 28.dp) }
        body.addView(label(if (orig == null) "New routine" else "Edit routine", 26f, p.fg, bold = true).apply {
            setPadding(6.dp, 0, 0, 16.dp)
        })
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
            whenCard.addView(hScroll(chip("${Routines.TRIGGERS[type] ?: "Pick trigger"}  ▾", true) {
                val flatKeys = Routines.TRIGGER_GROUPS.flatMap { (_, items) -> items.map { it.first } }
                val groups = Routines.TRIGGER_GROUPS.map { (hdr, items) -> hdr to items.map { it.second } }
                SuiteDialog.Builder(this).setTitle("When")
                    .setGroupedItems(groups) { _, w -> t.put("type", flatKeys[w]); rebuild() }
                    .show()
            }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })

            if (type == "wifi_connect") {
                whenCard.addView(Switch(this).apply {
                    text = "Any Wi-Fi network"
                    textSize = 13.5f
                    setTextColor(p.fg)
                    typeface = fontRegular
                    thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
                    trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
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
                    val mins = t.optInt("minutes", 10)
                    row.addView(label("For $mins min", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(chip("−", false) { t.put("minutes", maxOf(1, mins - if (mins > 5) 5 else 1)); rebuild() },
                        LinearLayout.LayoutParams(44.dp, 32.dp))
                    row.addView(chip("+", false) { t.put("minutes", minOf(120, mins + if (mins >= 5) 5 else 1)); rebuild() },
                        LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
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
                    thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
                    trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
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
                            SuiteDialog.Builder(this).setTitle("Battery level")
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
                            SuiteDialog.Builder(this).setTitle("Battery temperature")
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
                SuiteDialog.Builder(this).setTitle("Add condition")
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
                SuiteDialog.Builder(this).setTitle("Only after")
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
                SuiteDialog.Builder(this).setTitle("Stop once this ran")
                    .setItems(names.toTypedArray()) { _, w ->
                        if (w == 0) r.remove("unless") else r.put("unless", others[w - 1].optString("id"))
                        rebuild()
                    }.show()
            }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            // between two routines: after start ran, until end runs (any number of times in between)
            val span = r.optJSONObject("between") ?: org.json.JSONObject()
            fun spanPick(key: String, title: String) {
                val names = listOf("No") + others.map { it.optString("name") }
                SuiteDialog.Builder(this).setTitle(title)
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
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
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
        if (orig != null) body.addView(textButton("Delete routine") {
            SuiteDialog.Builder(this).setMessage("Delete “${r.optString("name")}”?")
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

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
        dlg.setContentView(FrameLayout(this).apply {
            addView(GlassBackdrop(this@MainActivity).also { bd -> scroll.setOnScrollChangeListener { _, _, y, _, _ -> bd.setScroll(y) } }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        dlg.window?.apply {
            @Suppress("DEPRECATION") statusBarColor = p.base
            @Suppress("DEPRECATION") navigationBarColor = p.base
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
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
                    SuiteDialog.Builder(this).setTitle("You're up to date")
                        .setMessage("Suite ${Updates.current(this)} is the latest version.")
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

    private fun featureRequest() {
        SuiteDialog.Builder(this)
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
        val c = blendColors(p.accent, p.accent2)
        return LiquidGlass(p, radius.toFloat(), isDark(), resources.displayMetrics.density, level = 2,
            tint = Color.argb(242, Color.red(c), Color.green(c), Color.blue(c)), shine = if (isDark()) 0.78f else 0.37f, shadow = true)
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

    private fun textButton(t: String, onClick: () -> Unit) = label(t, 14.5f, p.link, bold = true).apply {
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = ripple(null, 18.dp)
        setOnClickListener { onClick() }
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

    private fun ripple(content: Drawable?, radius: Int) =
        RippleDrawable(ColorStateList.valueOf(alpha(p.fg, 0.12f)), content, rounded(Color.WHITE, radius))

    private fun alpha(color: Int, a: Float) =
        Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ================================================================== theme

    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun white(a: Float) = Color.argb((a * 255).toInt(), 255, 255, 255)

    private fun palette() = Look.palette(this)

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.decorView.setBackgroundColor(p.base)
        window.setDecorFitsSystemWindows(false)
        if (!isDark()) {
            val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(f, f)
        }
    }
}
