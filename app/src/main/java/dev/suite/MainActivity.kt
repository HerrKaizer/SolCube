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
        val toggle: TextView,
        val dot: View,
        val status: TextView,
        val detail: TextView,
    )

    private lateinit var shizukuCard: LinearLayout
    private lateinit var shizukuText: TextView
    private lateinit var shizukuButton: TextView
    private lateinit var overlayCard: LinearLayout
    private lateinit var ctsTouch: Section
    private lateinit var ctsButton: Section
    private lateinit var ctsNote: TextView
    private lateinit var game: Section
    private lateinit var srSec: Section
    private lateinit var srLog: TextView
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
        poll()
        checkUpdates()
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
        showSection(ctsButton, running = s.cts?.optBoolean("button") == true && ripple,
            detail = ctsDetail(ctsButton, "Hold the power button (screen on)"))
        val anyCts = enabled(ctsTouch) || enabled(ctsButton)
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
                fi == null -> "Keeps vivo frame interpolation on in the games below"
                else -> buildString {
                    val pkg = fi.optString("game")
                    val bat = fi.optInt("battery") / 10f
                    if (pkg.isEmpty()) append("No listed game running")
                    else {
                        append(appName(pkg)).append(" · PID ").append(fi.optInt("pid"))
                        append(" · ").append(fi.optInt("src")).append(" → ").append(fi.optInt("dst")).append(" fps")
                    }
                    if (fi.optBoolean("paused")) append("\nPaused: battery too hot")
                    append(String.format(java.util.Locale.US, "\nBattery %.1f °C", bat))
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
        srLog.visibility = if (sr != null) View.VISIBLE else View.GONE
        if (sr != null) srLog.text = logText(sr)

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
        renderDouble()

        showSection(
            temp, running = TempOverlay.running,
            detail = if (enabled(temp) && !canDraw) "Needs “Display over other apps”" else "CPU · GPU · Battery °C — drag the pill to move it",
        )
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
        sec.toggle.background = ripple(if (on) glass(20.dp, p.chip) else accentGradient(20.dp), 20.dp)
        sec.status.text = if (running) "Running" else "Not running"
        sec.status.setTextColor(if (running) p.ok else p.sub)
        (sec.dot.background as GradientDrawable).setColor(if (running) p.ok else alpha(p.sub, 0.5f))
        sec.detail.text = detail
    }

    private fun toggle(sec: Section) {
        if (sec.key in busy) return
        val on = !enabled(sec)
        val needsOverlay = sec.key in setOf(Components.KEY_CTS_TOUCH, Components.KEY_CTS_BUTTON, Components.KEY_TEMP)
        if (on && needsOverlay && !Settings.canDrawOverlays(this)) {
            openOverlaySettings()
        }
        busy += sec.key
        render()
        io.execute {
            when (sec.key) {
                Components.KEY_CTS_TOUCH, Components.KEY_CTS_BUTTON -> Components.setCtsTrigger(this, sec.key, on)
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

        // Circle to Search: one card, two separately switchable triggers
        val ctsCard = card()
        ctsCard.addView(label("Circle to Search", 18f, p.fg, bold = true))
        ctsNote = label("", 13f, p.sub)
        ctsCard.addView(ctsNote, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        ctsTouch = section(Components.KEY_CTS_TOUCH, "Two-finger hold", into = ctsCard)
        addHoldStepper(ctsCard, Components.KEY_TOUCH_HOLD) { Components.touchHold(this) }
        ctsCard.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp })
        ctsButton = section(Components.KEY_CTS_BUTTON, "Power-button hold", into = ctsCard)
        addHoldStepper(ctsCard, Components.KEY_BUTTON_HOLD) { Components.buttonHold(this) }
        // vivo hides the "default digital assistant" page; Circle to Search needs Google there
        ctsCard.addView(textButton("Default assistant settings") { openDefaultAssistantSettings() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp; leftMargin = (-12).dp })
        root.addView(ctsCard, cardParams())

        root.addView(buildDoubleCard(), cardParams())

        mixSec = section(Components.KEY_MIX, "Play together")
        root.addView(mixSec.card, cardParams())

        root.addView(buildRoutinesCard(), cardParams())

        game = section(Components.KEY_GAME, "Frame interpolation")
        addGameControls(game.card)
        root.addView(game.card, cardParams())

        srSec = section(Components.KEY_SR, "Super resolution")
        srLog = mono(10.5f)
        srSec.card.addView(srLog, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        root.addView(srSec.card, cardParams())

        temp = section(Components.KEY_TEMP, "Temperature overlay")
        addTempSettings(temp.card)
        root.addView(temp.card, cardParams())

        root.addView(buildStreamCard(), cardParams())

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

        root.addView(label("Suite ${Updates.current(this)}", 12f, p.sub).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 20.dp })

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
            addView(GlassBackdrop(this@MainActivity, p.base, p.glows), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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

    /** A switchable block: its own card, or (with [into]) a sub-row inside an existing card. */
    private fun section(key: String, title: String, into: LinearLayout? = null): Section {
        val card = into ?: card()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, if (into != null) 16f else 18f, p.fg, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        lateinit var sec: Section
        val toggle = label("", 14.5f, p.onAccent, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = 96.dp
            setPadding(18.dp, 0, 18.dp, 0)
            setOnClickListener { toggle(sec) }
        }
        header.addView(toggle, LinearLayout.LayoutParams(WRAP_CONTENT, 40.dp).apply { leftMargin = 12.dp })
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
        sec = Section(key, card, toggle, dot, status, detail)
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
            background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(p.chip); setStroke(1.dp, p.stroke) }
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
            background = ripple(if (selected) accentGradient(16.dp) else glass(16.dp, p.chip), 16.dp)
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
    }

    // ------------------------------------------------------------------ stream to laptop

    private lateinit var streamDot: View
    private lateinit var streamStatus: TextView
    private lateinit var streamDetail: TextView
    private lateinit var wideBtn: TextView
    private lateinit var streamBtn: TextView
    private var streamBusy = false

    private fun buildStreamCard(): LinearLayout {
        val card = card()
        card.addView(betaTitle("Stream to laptop"))
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
        card.addView(label("The phone stays the touch controller, dimmed. Exit from the round button on the phone, or Ctrl+Alt+X on the laptop. If the cable is pulled, the screen goes back to normal by itself.", 12f, p.sub),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })

        // launch buttons shown on the bubble
        card.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 14.dp; bottomMargin = 6.dp })
        card.addView(label("Apps on the bubble", 14.5f, p.fg, bold = true).apply { setPadding(0, 8.dp, 0, 2.dp) })
        card.addView(label("Launch buttons in the bubble's panel, plus Back and Home.", 12f, p.sub))
        streamAppList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(streamAppList)
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = android.widget.EditText(this).apply {
            hint = "package name, e.g. com.game.app"
            setSingleLine()
            textSize = 14f
            setTextColor(p.fg)
            setHintTextColor(p.sub)
            typeface = fontRegular
            background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(p.chip); setStroke(1.dp, p.stroke) }
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
        card.addView(addRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
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
            b.background = ripple(if (filled) accentGradient(24.dp) else glass(24.dp, p.chip), 24.dp)
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

    private fun buildDoubleCard(): LinearLayout {
        val card = card()
        card.addView(label("Double power press", 18f, p.fg, bold = true))
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
        card.addView(label("Opens", 12.5f, p.sub).apply { setPadding(0, 14.dp, 0, 0) })
        dpChoices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(dpChoices)
        card.addView(Switch(this).apply {
            text = "Turn NFC on while it's open"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
            isChecked = DoublePress.nfc(this@MainActivity)
            setOnCheckedChangeListener { _, c -> DoublePress.setNfc(this@MainActivity, c) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        card.addView(label("NFC goes off as soon as the app leaves the screen (even if it stays in recents), or after 3 minutes locked. Not if it was already on.", 12f, p.sub))

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
        card.addView(windowRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        card.addView(label("Android's own setting (default 300 ms). Takes effect after a restart. A single press locks the screen that much later.", 12f, p.sub))

        card.addView(Switch(this).apply {
            text = "Short vibration on double press"
            textSize = 13.5f
            setTextColor(p.fg)
            typeface = fontRegular
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(p.accent, 0.5f), alpha(p.sub, 0.4f)))
            isChecked = DoublePress.vibrate(this@MainActivity)
            setOnCheckedChangeListener { _, c -> DoublePress.setVibrate(this@MainActivity, c) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        renderDouble()
        return card
    }

    private fun renderDouble() {
        val h = DoublePress.handler(this)
        val mine = h == packageName
        dpStatus.text = if (mine) "Suite handles it" else "Not handled by Suite"
        dpStatus.setTextColor(if (mine) p.ok else p.sub)
        (dpDot.background as GradientDrawable).setColor(if (mine) p.ok else alpha(p.sub, 0.5f))
        val ring = DoublePress.ringChoice(this)
        dpDetail.text = when {
            mine && ring == DoublePress.RING_CYCLE -> "Double-press the power button to go Ring → Vibrate → Silent."
            mine && ring == DoublePress.RING_SILENT -> "Double-press the power button to turn silent mode on or off."
            mine && ring == DoublePress.RING_VIBRATE -> "Double-press the power button to turn vibrate mode on or off."
            mine -> "Double-press the power button to open the app picked below."
            h == null -> "Android asks which app to use each time."
            else -> "Right now it goes to ${appName(h)}."
        }
        dpMake.visibility = if (mine) View.GONE else View.VISIBLE
        dpMake.text = if (dpBusy) "…" else "Make Suite the handler"

        // the apps to pick from: tap one to use it, ✕ to remove it
        dpChoices.removeAllViews()
        val t = DoublePress.target(this)
        val apps = DoublePress.apps(this)
        dpChoices.addView(hScroll(*apps.map { pkg ->
            chip(appName(pkg), pkg == t && ring == null) { DoublePress.setTarget(this, pkg); renderDouble() }.apply {
                setOnLongClickListener {
                    if (apps.size > 1) {
                        SuiteDialog.Builder(this@MainActivity).setMessage("Remove ${appName(pkg)} from the list?")
                            .setPositiveButton("Remove") { _, _ ->
                                val rest = apps - pkg
                                DoublePress.saveApps(this@MainActivity, rest)
                                if (pkg == t) DoublePress.setTarget(this@MainActivity, rest.first())
                                renderDouble()
                            }.setNegativeButton("Cancel", null).show()
                    }
                    true
                }
            }
        }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        dpChoices.addView(textButton("Add app") { pickDoubleApp() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 2.dp; leftMargin = (-12).dp })
        if (apps.size > 1) dpChoices.addView(label("Tap an app to use it; hold it to remove it.", 12f, p.sub))

        // …or switch the sound mode instead of opening an app
        dpChoices.addView(label("Or sound mode", 12.5f, p.sub).apply { setPadding(0, 12.dp, 0, 0) })
        dpChoices.addView(hScroll(*listOf(
            DoublePress.RING_CYCLE to "Ring › Vibrate › Silent",
            DoublePress.RING_SILENT to "Silent on/off",
            DoublePress.RING_VIBRATE to "Vibrate on/off",
        ).map { (c, name) ->
            chip(name, ring == c) {
                DoublePress.setRingChoice(this, c)
                renderDouble()
                io.execute { Ringer.ensureAccess(this) }   // so changes are instant (silent needs DND access)
            }
        }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
    }

    private fun pickDoubleApp() {
        val have = DoublePress.apps(this)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(launch, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName && it !in have }.map { it to appName(it) }.sortedBy { it.second.lowercase() }
        SuiteDialog.Builder(this).setTitle("Add an app")
            .setItems(list.map { it.second }.toTypedArray()) { _, w ->
                val pkg = list[w].first
                DoublePress.saveApps(this, have + pkg)
                DoublePress.setTarget(this, pkg)
                renderDouble()
            }
            .show()
    }

    // ------------------------------------------------------------------ routines

    private lateinit var rtDot: View
    private lateinit var rtStatus: TextView
    private lateinit var rtDetail: TextView
    private lateinit var rtList: LinearLayout
    private var rtLastRun: org.json.JSONObject? = null

    private fun buildRoutinesCard(): LinearLayout {
        val card = card()
        card.addView(betaTitle("Routines"))
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rtDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        statusRow.addView(rtDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 8.dp })
        rtStatus = label("", 14.5f, p.sub, bold = true)
        statusRow.addView(rtStatus)
        card.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        rtDetail = label("", 13f, p.sub)
        card.addView(rtDetail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        rtList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(rtList)
        card.addView(textButton("New routine") { editRoutine(null) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = 8.dp; leftMargin = (-12).dp
        })
        renderRoutineList()
        return card
    }

    private fun lastRunText(id: String): String? {
        val t = rtLastRun?.optLong(id, 0L) ?: 0L
        if (t <= 0) return null
        val d = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val day = when (d.toLocalDate()) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.US))
        }
        return "Last ran $day at " + d.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun renderRoutineList() {
        rtList.removeAllViews()
        val all = Routines.load(this)
        all.forEach { r ->
            rtList.addView(View(this).apply { setBackgroundColor(p.stroke) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = 12.dp })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dp, 0, 4.dp)
                background = ripple(null, 14.dp)
                setOnClickListener { editRoutine(r) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val on = r.optBoolean("on", true)
            col.addView(label(r.optString("name"), 16f, if (on) p.fg else p.sub, bold = true))
            col.addView(label(Routines.summary(r, all), 12.5f, p.sub).apply { setPadding(0, 2.dp, 0, 0) })
            Routines.guardText(r)?.let { col.addView(label(it, 12.5f, p.sub).apply { setPadding(0, 2.dp, 0, 0) }) }
            col.addView(label("→ " + Routines.actionsText(r), 12.5f, p.sub).apply { setPadding(0, 2.dp, 0, 0) })
            lastRunText(r.optString("id"))?.let { col.addView(label(it, 11.5f, p.ok).apply { setPadding(0, 3.dp, 0, 0) }) }
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(chip(if (on) "On" else "Off", on) {
                r.put("on", !on)
                io.execute { Routines.save(this, all); main.post { renderRoutineList(); poll() } }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, 34.dp).apply { leftMargin = 10.dp })
            rtList.addView(row)
        }
    }

    private fun renderRoutines(st: org.json.JSONObject?, shellOk: Boolean) {
        val any = Components.enabled(this, Routines.KEY)
        val running = st?.optBoolean("running") == true
        rtStatus.text = if (running) "Running" else "Not running"
        rtStatus.setTextColor(if (running) p.ok else p.sub)
        (rtDot.background as GradientDrawable).setColor(if (running) p.ok else alpha(p.sub, 0.5f))
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

    private fun hScroll(vararg views: View): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v ->
            row.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, 34.dp).apply { if (i > 0) leftMargin = 6.dp })
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** Multi-choice list of user-installed apps for the "Revive apps" action. */
    private fun pickReviveApps(apps: org.json.JSONArray, done: () -> Unit) {
        val chosen = (0 until apps.length()).map { apps.getString(it) }.toMutableSet()
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = packageManager.queryIntentActivities(launch, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
            .map { it.packageName }
            .filter { it != packageName }
            .toMutableSet()
        pkgs += chosen   // keep picks that have no launcher icon
        val list = pkgs.map { it to appName(it) }.sortedWith(compareBy({ it.first !in chosen }, { it.second.lowercase() }))
        val checked = BooleanArray(list.size) { list[it].first in chosen }
        SuiteDialog.Builder(this).setTitle("Revive these apps")
            .setMultiChoiceItems(list.map { it.second }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Done") { _, _ ->
                while (apps.length() > 0) apps.remove(0)
                list.forEachIndexed { i, (pkg, _) -> if (checked[i]) apps.put(pkg) }
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(p.chip); setStroke(1.dp, p.stroke) }
            setPadding(14.dp, 0, 14.dp, 0)
        }
        nameCard.addView(nameField, LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { topMargin = 8.dp })
        body.addView(nameCard, cardParams())
        val dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dynamic)

        fun trig() = r.optJSONObject("trigger") ?: org.json.JSONObject().also { r.put("trigger", it) }
        fun actions() = r.optJSONArray("actions") ?: org.json.JSONArray().also { r.put("actions", it) }

        lateinit var rebuild: () -> Unit
        rebuild = {
            dynamic.removeAllViews()
            val t = trig()
            val type = t.optString("type")

            // ---- When
            val whenCard = card()
            whenCard.addView(label("When", 14.5f, p.fg, bold = true))
            whenCard.addView(hScroll(*Routines.TRIGGERS.map { (k, v) ->
                chip(v, k == type) { t.put("type", k); rebuild() }
            }.toTypedArray()), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            if (type == "wifi_connect" || type == "wifi_gone") {
                val ssids = t.optJSONArray("ssids") ?: org.json.JSONArray().also { t.put("ssids", it) }
                whenCard.addView(label(if (type == "wifi_connect") "Any of these networks" else "None of these networks nearby",
                    12.5f, p.sub).apply { setPadding(0, 12.dp, 0, 0) })
                for (i in 0 until ssids.length()) {
                    val name = ssids.getString(i)
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(label(name, 14.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(label("✕", 15f, p.sub).apply {
                        gravity = Gravity.CENTER
                        background = ripple(null, 16.dp)
                        setOnClickListener { ssids.remove(i); rebuild() }
                    }, LinearLayout.LayoutParams(32.dp, 32.dp))
                    whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 20.dp })
                }
                whenCard.addView(textButton("Add network") {
                    io.execute {
                        val raw = routinesCmd("networks")
                        val nets = runCatching { org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }
                            .getOrDefault(emptyList()).sortedBy { it.lowercase() }
                        main.post {
                            if (nets.isEmpty()) { toast("Couldn't read saved networks (is Shizuku running?)"); return@post }
                            SuiteDialog.Builder(this).setTitle("Saved networks")
                                .setItems(nets.toTypedArray()) { _, w -> ssids.put(nets[w]); rebuild() }.show()
                        }
                    }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = (-12).dp })
                if (type == "wifi_gone") {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val mins = t.optInt("minutes", 10)
                    row.addView(label("For $mins min", 13.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(chip("−", false) { t.put("minutes", maxOf(1, mins - if (mins > 5) 5 else 1)); rebuild() },
                        LinearLayout.LayoutParams(44.dp, 32.dp))
                    row.addView(chip("+", false) { t.put("minutes", minOf(120, mins + if (mins >= 5) 5 else 1)); rebuild() },
                        LinearLayout.LayoutParams(44.dp, 32.dp).apply { leftMargin = 6.dp })
                    whenCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 20.dp })
                }
            }
            if (type == "wifi_connect") {
                // sub-section: make sure Wi-Fi is on so the connection can happen
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
            for (i in 0 until acts.length()) {
                val a = acts.getJSONObject(i)
                val at = a.optString("type")
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label("${i + 1}.  " + (Routines.ACTIONS[at] ?: at), 14.5f, p.fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val vals = Routines.values(at)
                if (at == "revive") {
                    val apps = a.optJSONArray("apps") ?: org.json.JSONArray().also { a.put("apps", it) }
                    row.addView(chip("${apps.length()} apps", false) { pickReviveApps(apps) { rebuild() } },
                        LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp))
                }
                if (vals.isNotEmpty()) {
                    val v = a.optString("value", vals.first())
                    row.addView(chip(Routines.valueText(at, v), false) {
                        a.put("value", vals[(vals.indexOf(v) + 1) % vals.size]); rebuild()
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
                    gravity = Gravity.CENTER
                    background = ripple(null, 16.dp)
                    setOnClickListener { acts.remove(i); rebuild() }
                }, LinearLayout.LayoutParams(32.dp, 32.dp))
                doCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            }
            if (acts.length() == 0) doCard.addView(label("No actions yet", 13f, p.sub).apply { setPadding(0, 8.dp, 0, 0) })
            doCard.addView(textButton("Add action") {
                val keys = Routines.ACTIONS.keys.toList()
                SuiteDialog.Builder(this).setTitle("Add action")
                    .setItems(Routines.ACTIONS.values.toTypedArray()) { _, w ->
                        val k = keys[w]
                        acts.put(org.json.JSONObject().put("type", k).apply {
                            Routines.values(k).firstOrNull()?.let { put("value", it) }
                            if (k == "revive") put("apps", org.json.JSONArray(Routines.REVIVE_DEFAULT))
                        })
                        rebuild()
                    }.show()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp; leftMargin = (-12).dp })
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
                    }
                    io.execute { Routines.save(this, list); main.post { renderRoutineList(); poll() } }
                    dlg.dismiss()
                }.setNegativeButton("Cancel", null).show()
        }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        body.addView(textButton("Cancel") { dlg.dismiss() }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
        dlg.setContentView(FrameLayout(this).apply {
            addView(GlassBackdrop(this@MainActivity, p.base, p.glows), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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

    private fun checkUpdates() {
        io.execute {
            val r = Updates.check(this)
            main.post {
                if (isFinishing) return@post
                updateLink.visibility = if (r != null) View.VISIBLE else View.GONE
                if (r != null) {
                    updateLink.text = "Update available: ${r.version}"
                    if (Updates.firstShow(this, r)) Updates.dialog(this, r)
                }
            }
        }
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
        background = glass(24.dp)
        setPadding(18.dp, 18.dp, 18.dp, 18.dp)
    }

    /** Frosted pane: translucent fill, thin bright edge, soft sheen across the top. */
    private fun glass(radius: Int, fill: Int = p.glass): Drawable {
        val body = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(fill)
            setStroke(1.dp, p.stroke)
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.sheen, Color.TRANSPARENT)).apply {
            cornerRadius = radius.toFloat()
        }
        return LayerDrawable(arrayOf(body, sheen)).apply { setLayerInset(1, 1.dp, 1.dp, 1.dp, 0) }
    }

    private fun accentGradient(radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.accent, p.accent2)).apply {
            cornerRadius = radius.toFloat()
        }

    private fun cardParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontMedium else fontRegular
    }

    /** Card title with a small "BETA" tag after it. */
    private fun betaTitle(t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(t, 18f, p.fg, bold = true))
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
            background = ripple(if (filled) accentGradient(24.dp) else glass(24.dp, p.chip), 24.dp)
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
