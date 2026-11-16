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
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/** Floating CPU · GPU · battery temperature pill (formerly the TempPill app). */
class TempOverlay(private val ctx: Context) {

    companion object {
        private const val SOC_INTERVAL_MS = 2_000L
        private const val BATTERY_INTERVAL_MS = 10_000L
        @Volatile var running = false
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())

    private var cpu: Int? = null
    private var gpu: Int? = null
    private var bat: Int? = null

    private var worker: HandlerThread? = null
    private var bg: Handler? = null

    private val socTick = object : Runnable {
        override fun run() {
            cpu = Thermal.cpu()
            gpu = Thermal.gpu()
            main.post(::render)
            bg?.postDelayed(this, SOC_INTERVAL_MS)
        }
    }
    private val batteryTick = object : Runnable {
        override fun run() {
            bat = Thermal.battery(ctx)
            main.post(::render)
            bg?.postDelayed(this, BATTERY_INTERVAL_MS)
        }
    }

    fun start() {
        if (view != null) return
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        addOverlay()
        running = true
        val w = HandlerThread("temps").apply { start() }
        worker = w
        bg = Handler(w.looper).apply { post(socTick); post(batteryTick) }
    }

    fun stop() {
        running = false
        Style.prefs(ctx).unregisterOnSharedPreferenceChangeListener(styleListener)
        bg?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        bg = null; worker = null
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun render() {
        val sb = android.text.SpannableStringBuilder()
        val sep = if (isPortrait()) "\n" else " "
        fun add(v: Int?, color: Int?) {
            if (sb.isNotEmpty()) sb.append(sep)
            val start = sb.length
            sb.append(v?.toString() ?: "--")
            if (color != null) {
                sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        add(cpu, socColor(cpu))
        add(gpu, socColor(gpu))
        add(bat, batteryColor(bat))
        view?.text = sb
    }

    private val red = 0xFFFF4D4D.toInt()
    private val orange = 0xFFFFA62B.toInt()

    /** CPU/GPU: red at 85 °C and up. */
    private fun socColor(v: Int?) = if (v != null && v >= 85) red else null

    /** Battery: orange at 42–43 °C, red at 44 °C and up. */
    private fun batteryColor(v: Int?) = when {
        v == null -> null
        v >= 44 -> red
        v >= 42 -> orange
        else -> null
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        val prefs = ctx.getSharedPreferences("pos", Context.MODE_PRIVATE)
        val tv = TextView(ctx).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum" // fixed-width digits so the pill doesn't jitter
            includeFontPadding = false
            background = GradientDrawable().apply { cornerRadius = dp(100f) }
            text = "-- -- --"
        }
        view = tv
        applyStyle()
        render()
        Style.prefs(ctx).registerOnSharedPreferenceChangeListener(styleListener)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "SuiteTemp"
        }

        // Drag to move; position is remembered.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 2 || abs(dy) > 2) {
                        val (maxX, maxY) = travel()
                        params.x = (startX + dx.toInt()).coerceIn(0, maxX)
                        params.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        wm.updateViewLayout(tv, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Saved as a fraction of the free space so it lands in the same spot after rotation.
                    val (maxX, maxY) = travel()
                    val o = orientationSuffix()
                    prefs.edit()
                        .putFloat("fx$o", if (maxX > 0) params.x / maxX.toFloat() else 0f)
                        .putFloat("fy$o", if (maxY > 0) params.y / maxY.toFloat() else 0f)
                        .apply()
                }
            }
            true
        }

        placeFromPrefs()
        wm.addView(tv, params)
    }

    private fun isPortrait(): Boolean {
        val (w, h) = screenSize()
        return h > w
    }

    private fun screenSize(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    /** How far the pill can move on each axis while staying fully on screen. */
    private fun travel(): Pair<Int, Int> {
        val tv = view ?: return 0 to 0
        val spec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        tv.measure(spec, spec)
        val (w, h) = screenSize()
        return (w - tv.measuredWidth).coerceAtLeast(0) to (h - tv.measuredHeight).coerceAtLeast(0)
    }

    /** Portrait and landscape each remember their own spot. */
    private fun orientationSuffix() = if (isPortrait()) "_port" else "_land"

    private fun placeFromPrefs() {
        val prefs = ctx.getSharedPreferences("pos", Context.MODE_PRIVATE)
        val (maxX, maxY) = travel()
        val o = orientationSuffix()
        val fx = prefs.getFloat("fx$o", 0.02f).coerceIn(0f, 1f)
        val fy = prefs.getFloat("fy$o", 0.03f).coerceIn(0f, 1f)
        params.x = Math.round(fx * maxX)
        params.y = Math.round(fy * maxY)
    }

    fun onConfigurationChanged() {
        val v = view ?: return
        applyStyle()
        render()
        placeFromPrefs()
        if (v.isAttachedToWindow) wm.updateViewLayout(v, params)
    }

    // Kept as a field: SharedPreferences holds listeners weakly.
    private val styleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyStyle()
        render()
        view?.let { v ->
            if (v.isAttachedToWindow) {
                placeFromPrefs() // a bigger pill must still fit on screen
                wm.updateViewLayout(v, params)
            }
        }
    }

    /** Applies size, background opacity and number color from the saved settings. */
    private fun applyStyle() {
        val tv = view ?: return
        val p = Style.prefs(ctx)
        val scale = p.getInt(Style.KEY_SIZE, Style.DEFAULT_SIZE) / 100f
        val opacity = p.getInt(Style.KEY_BG_OPACITY, Style.DEFAULT_BG_OPACITY)
        val baseColor = if (p.getBoolean(Style.KEY_GRAY, false)) 0xFFA0A0A0.toInt() else 0xFFFFFFFF.toInt()

        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
        tv.setTextColor(baseColor)
        // Landscape: wide pill with the numbers in a row. Portrait: tall pill with them stacked.
        val portrait = isPortrait()
        val h = dp((if (portrait) 6f else 10f) * scale).toInt()
        val v = dp((if (portrait) 9f else 4f) * scale).toInt()
        tv.setPadding(h, v, h, v)
        tv.gravity = Gravity.CENTER
        tv.setLineSpacing(if (portrait) dp(3f * scale) else 0f, 1f)
        val alpha = Math.round(opacity * 2.55f)
        (tv.background as GradientDrawable).setColor((alpha shl 24) or 0x505050)
    }
}
