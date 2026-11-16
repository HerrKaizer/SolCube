package dev.suite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Stream mode's on-phone controls: a small draggable bubble that opens a panel with
 * Start/Stop stream, Exit (stream off + normal screen) and the phone brightness.
 * The window also carries the brightness override, so the phone stays dim while it's shown.
 * It watches the shell side: if the screen was put back from elsewhere (laptop Ctrl+Alt+X,
 * cable pulled), it ends stream mode here too.
 */
class StreamOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("stream-ui").apply { start() }
    private val bg = Handler(worker.looper)
    private val prefs = ctx.getSharedPreferences("pos", Context.MODE_PRIVATE)

    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var streamBtn: TextView
    private lateinit var dimText: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var streaming = false
    private var helper = false
    private var seenWide = false
    private val startedAt = System.currentTimeMillis()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private val ink = 0xE6101012.toInt()
    private val fg = 0xFFF3EEE6.toInt()
    private val sub = 0xFF9A948C.toInt()
    private val accent = 0xFFECD3A6.toInt()

    fun start() {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        build()
        bg.post(poll)
    }

    fun stop() {
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        main.removeCallbacksAndMessages(null)
        if (::root.isInitialized) runCatching { wm.removeView(root) }
    }

    // ------------------------------------------------------------------ status

    private val poll = object : Runnable {
        override fun run() {
            val s = Components.read(Shell.Kind.STREAM)
            if (s != null) {
                val wide = s.optBoolean("wide")
                if (wide) seenWide = true
                streaming = s.optBoolean("streaming")
                helper = s.optBoolean("helper")
                // put back from elsewhere (laptop Ctrl+Alt+X, cable pulled): leave stream mode
                if (!wide && seenWide && System.currentTimeMillis() - startedAt > 4000) {
                    Components.setStreamMode(ctx, false)
                    return
                }
            }
            main.post(::render)
            bg.postDelayed(this, 2000)
        }
    }

    private fun render() {
        if (!::root.isInitialized) return
        streamBtn.text = when {
            !helper -> "Laptop not found"
            streaming -> "Stop stream"
            else -> "Start stream"
        }
        bubble.text = if (streaming) "●" else "○"
        dimText.text = "Brightness ${Components.dim(ctx)}%"
    }

    // ------------------------------------------------------------------ layout

    private fun pill(t: String, filled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) 0xFF1C1610.toInt() else fg)
        gravity = Gravity.CENTER
        setPadding(dp(14f), 0, dp(14f), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(if (filled) accent else 0x14FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        bubble = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(accent)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99101012.toInt()); setStroke(dp(1f), 0x33FFFFFF) }
        }
        root.addView(bubble, LinearLayout.LayoutParams(dp(40f), dp(40f)))

        // two rows so it fits a portrait phone too: [stream][exit] / [brightness − +]
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(26f).toFloat(); setColor(ink); setStroke(dp(1f), 0x22FFFFFF) }
            visibility = View.GONE
        }
        fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun lp(first: Boolean = false) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply { if (!first) leftMargin = dp(6f) }
        val top = row()
        streamBtn = pill("Start stream", true) { action(if (streaming) "stream off" else "stream on") }
        top.addView(streamBtn, lp(true))
        top.addView(pill("Exit", false) {
            collapse()
            bg.post { Components.setStreamMode(ctx, false) }
        }, lp())
        panel.addView(top)
        val nav = row()
        nav.addView(pill("Back", false) { key(4) }, lp(true))
        nav.addView(pill("Home", false) { key(3) }, lp())
        panel.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        apps = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(apps)
        val bottom = row()
        dimText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(sub)
            setPadding(dp(8f), 0, dp(4f), 0)
        }
        bottom.addView(dimText)
        bottom.addView(pill("−", false) { setDim(Components.dim(ctx) - 5) }, lp())
        bottom.addView(pill("+", false) { setDim(Components.dim(ctx) + 5) }, lp())
        panel.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        root.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6f) })

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "SuiteStream"
            screenBrightness = Components.dim(ctx) / 100f
            x = prefs.getInt("stream_x", dp(12f))
            y = prefs.getInt("stream_y", dp(12f))
        }

        // bubble: tap opens/closes the panel, drag moves it (position remembered)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || abs(dx) > dp(6f) || abs(dy) > dp(6f)) {
                        moved = true
                        params.x = (startX + dx.toInt()).coerceAtLeast(0)
                        params.y = (startY + dy.toInt()).coerceAtLeast(0)
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("stream_x", params.x).putInt("stream_y", params.y).apply()
                    else if (panel.visibility == View.VISIBLE) collapse() else expand()
                }
            }
            true
        }

        render()
        wm.addView(root, params)
    }

    private lateinit var apps: LinearLayout

    private fun expand() {
        buildApps()
        panel.visibility = View.VISIBLE
        render()
    }

    /** One launch button per app from Suite's "Apps on the bubble" list, two per row. */
    private fun buildApps() {
        apps.removeAllViews()
        val pm = ctx.packageManager
        var r: LinearLayout? = null
        Components.streamApps(ctx).filter { pm.getLaunchIntentForPackage(it) != null }.forEachIndexed { i, pkg ->
            if (i % 2 == 0) {
                r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                apps.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
            }
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            r!!.addView(pill(name, false) { launch(pkg) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply { if (i % 2 == 1) leftMargin = dp(6f) })
        }
    }

    private fun launch(pkg: String) {
        collapse()
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.widget.Toast.makeText(ctx, "Couldn't open it", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun key(code: Int) {
        collapse()
        bg.post { Components.streamCmd(ctx, "key $code") }
    }

    private fun collapse() { panel.visibility = View.GONE }

    private fun action(cmd: String) {
        streamBtn.text = "…"
        bg.post {
            val r = Components.streamCmd(ctx, cmd)
            main.post {
                if (r == "no-helper") android.widget.Toast.makeText(ctx, "Laptop helper not found — is the cable in and the helper running?", android.widget.Toast.LENGTH_LONG).show()
            }
            bg.removeCallbacks(poll)
            bg.post(poll)
        }
    }

    private fun setDim(v: Int) {
        val pct = v.coerceIn(1, 100)
        Components.prefs(ctx).edit().putInt(Components.KEY_DIM, pct).apply()
        params.screenBrightness = pct / 100f
        runCatching { wm.updateViewLayout(root, params) }
        render()
    }
}
