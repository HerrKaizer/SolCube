package dev.suite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Floating CPU · GPU · battery temperature pill and optional CPU · GPU load pill. */
class TempOverlay(private val ctx: Context) {

    companion object {
        private const val SOC_INTERVAL_MS = 2_000L
        private const val BATTERY_INTERVAL_MS = 10_000L
        @Volatile var running = false

        private val setMaterialMethod = runCatching {
            View::class.java.getMethod("setMaterial", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
        private val setCornerMethod = runCatching {
            View::class.java.getMethod("setMaterialCornerRadius", Float::class.javaPrimitiveType)
        }.getOrNull()
        private val setCustomMethod = runCatching {
            View::class.java.getMethod(
                "setMaterialCustomized",
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        private val clearMaterialMethod = runCatching {
            View::class.java.getMethod("clearMaterial")
        }.getOrNull()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var root: LinearLayout? = null
    private var tempView: PillView? = null
    private var loadView: PillView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())

    private var cpu: Int? = null
    private var gpu: Int? = null
    private var bat: Int? = null
    private var cpuLoad: Int? = null
    private var gpuLoad: Int? = null

    private var worker: HandlerThread? = null
    private var bg: Handler? = null

    private val socTick = object : Runnable {
        override fun run() {
            if (pm.isInteractive) {
                cpu = Thermal.cpu()
                gpu = Thermal.gpu()
                val wantLoad = Style.showLoad(ctx) && !isPortrait()
                val needTemps = cpu == null || gpu == null
                if (needTemps || wantLoad) {
                    val raw = runCatching { Shell.service(Shell.Kind.TEMP)?.command(if (wantLoad) "all" else "temps") }.getOrNull()
                    if (raw != null) {
                        val p = raw.trim().split(Regex("\\s+"))
                        if (cpu == null) cpu = p.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..150 }
                        if (gpu == null) gpu = p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..150 }
                        if (wantLoad) {
                            cpuLoad = p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..100 }?.coerceAtMost(99)
                            gpuLoad = p.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..100 }?.coerceAtMost(99)
                        } else {
                            cpuLoad = null
                            gpuLoad = null
                        }
                    } else {
                        if (Shell.state() == Shell.State.READY) Shell.start(ctx, Shell.Kind.TEMP)
                        cpuLoad = null
                        gpuLoad = null
                    }
                } else {
                    cpuLoad = null
                    gpuLoad = null
                }
                main.post(::render)
            }
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
        if (root != null) return
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        addOverlay()
        Shell.start(ctx, Shell.Kind.TEMP)
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
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        tempView = null
        loadView = null
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
        tempView?.text = sb

        fun fmt(v: Int?) = v?.toString() ?: "--"
        loadView?.let { lv ->
            lv.barCpu = cpuLoad
            lv.barGpu = gpuLoad
            if (lv.barMode) lv.invalidate() else lv.text = "${fmt(cpuLoad)}$sep${fmt(gpuLoad)}"
        }
    }

    private val red = 0xFFFF4D4D.toInt()
    private val orange = 0xFFFFA62B.toInt()
    private val loadYellow = 0xFFFFD84D.toInt()
    private val loadOrange = 0xFFFF9526.toInt()

    private fun loadBarColor(v: Int): Int = when {
        v >= 90 -> red
        v >= 75 -> loadOrange
        v >= 50 -> loadYellow
        else -> 0xFFFFFFFF.toInt()
    }

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

    /** Capsule TextView with 3D Liquid Glass surface: fill, bottom bevel, top sheen, and Fresnel rim. */
    private inner class PillView(ctx: Context) : TextView(ctx) {
        var fillColor: Int = 0
            set(value) {
                if (field != value) {
                    field = value
                    fillPaint.color = value
                    invalidate()
                }
            }
        var glass3d: Boolean = true
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }
        var strokePx: Float = 0f
            set(value) {
                if (field != value) {
                    field = value
                    rimPaint.strokeWidth = value
                    updateBoundsAndShaders(width, height)
                    invalidate()
                }
            }
        var barMode: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                    invalidate()
                }
            }
        var barScale: Float = 1f
        var barRefHeightPx: Int = 0
        var barCpu: Int? = null
        var barGpu: Int? = null

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x26FFFFFF
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x33FFFFFF
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        private val bodyRect = RectF()
        private val rimRect = RectF()
        private val barRect = RectF()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!barMode) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val barW = dp(34f * barScale).toInt()
            val barH = dp(3.5f * barScale)
            val gap = dp(3.5f * barScale)
            val contentH = maxOf((barH * 2f + gap).toInt(), barRefHeightPx)
            setMeasuredDimension(
                resolveSize(paddingLeft + barW + paddingRight, widthMeasureSpec),
                resolveSize(paddingTop + contentH + paddingBottom, heightMeasureSpec),
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateBoundsAndShaders(w, h)
        }

        private fun updateBoundsAndShaders(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            val wf = w.toFloat()
            val hf = h.toFloat()
            val half = strokePx / 2f
            bodyRect.set(0f, 0f, wf, hf)
            rimRect.set(half, half, wf - half, hf - half)

            surfacePaint.shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(0x0CFFFFFF, 0x03FFFFFF, 0x00000000, 0x0A000000),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(bodyRect.width(), bodyRect.height()) / 2f
            if (r > 0f) {
                fillPaint.color = fillColor
                canvas.drawRoundRect(bodyRect, r, r, fillPaint)
                if (glass3d) {
                    canvas.drawRoundRect(bodyRect, r, r, surfacePaint)
                    val rr = (r - strokePx / 2f).coerceAtLeast(0f)
                    if (strokePx > 0f && rr > 0f) canvas.drawRoundRect(rimRect, rr, rr, rimPaint)
                }
            }
            if (!barMode) {
                super.onDraw(canvas)
                return
            }
            val left = paddingLeft.toFloat()
            val trackW = (width - paddingLeft - paddingRight).toFloat()
            if (trackW <= 0f) return
            val barH = dp(3.5f * barScale)
            val gap = dp(3.5f * barScale)
            val topY = (height - (barH * 2f + gap)) / 2f
            val br = barH / 2f

            fun drawBar(y: Float, value: Int?) {
                barRect.set(left, y, left + trackW, y + barH)
                canvas.drawRoundRect(barRect, br, br, trackPaint)
                val v = value ?: return
                if (v <= 0) return
                val fillW = maxOf(barH, trackW * (v.coerceIn(0, 100) / 100f))
                barPaint.color = loadBarColor(v)
                barRect.set(left, y, minOf(left + trackW, left + fillW), y + barH)
                canvas.drawRoundRect(barRect, br, br, barPaint)
            }
            drawBar(topY, barCpu)
            drawBar(topY + barH + gap, barGpu)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        val prefs = ctx.getSharedPreferences("pos", Context.MODE_PRIVATE)
        fun makePill(initial: String) = PillView(ctx).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum" // fixed-width digits so the pill doesn't jitter
            includeFontPadding = false
            text = initial
        }
        val tv = makePill("-- -- --")
        val lv = makePill("-- --")
        val box = LinearLayout(ctx).apply {
            addView(tv, LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            ))
            addView(lv, LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            ))
        }
        root = box
        tempView = tv
        loadView = lv
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
        box.setOnTouchListener { _, e ->
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
                        wm.updateViewLayout(box, params)
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
        wm.addView(box, params)
        box.post { applyStyle() }
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
        val v = root ?: return 0 to 0
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(spec, spec)
        val (w, h) = screenSize()
        return (w - v.measuredWidth).coerceAtLeast(0) to (h - v.measuredHeight).coerceAtLeast(0)
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
        val v = root ?: return
        applyStyle()
        render()
        placeFromPrefs()
        if (v.isAttachedToWindow) wm.updateViewLayout(v, params)
    }

    // Kept as a field: SharedPreferences holds listeners weakly.
    private val styleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyStyle()
        render()
        root?.let { v ->
            if (v.isAttachedToWindow) {
                placeFromPrefs() // a bigger pill must still fit on screen
                wm.updateViewLayout(v, params)
            }
        }
    }

    /** Applies size, background opacity and number color from the saved settings. */
    private fun applyStyle() {
        val box = root ?: return
        val tv = tempView ?: return
        val lv = loadView ?: return
        val p = Style.prefs(ctx)
        val scale = p.getInt(Style.KEY_SIZE, Style.DEFAULT_SIZE) / 100f
        val opacity = p.getInt(Style.KEY_BG_OPACITY, Style.DEFAULT_BG_OPACITY)
        val baseColor = if (p.getBoolean(Style.KEY_GRAY, false)) 0xFFA0A0A0.toInt() else 0xFFFFFFFF.toInt()

        // Landscape: wide pill with the numbers in a row. Portrait: tall pill with them stacked.
        val portrait = isPortrait()
        val h = dp((if (portrait) 6f else 10f) * scale).toInt()
        val v = dp((if (portrait) 9f else 4f) * scale).toInt()
        val blur = Style.blur(ctx)
        val radius = dp(100f)
        val stroke = dp(0.55f * scale)

        for (pill in listOf(tv, lv)) {
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            pill.setTextColor(baseColor)
            pill.setPadding(h, v, h, v)
            pill.gravity = Gravity.CENTER
            pill.setLineSpacing(if (portrait) dp(3f * scale) else 0f, 1f)
            val blurred = blur && pill.isAttachedToWindow && setMaterialMethod != null && runCatching {
                val ok = setMaterialMethod.invoke(pill, -2, 0) as? Boolean ?: false
                if (ok) {
                    setCornerMethod?.invoke(pill, radius)
                    setCustomMethod?.invoke(
                        pill,
                        0.75f, 25.0f,
                        0x24262C, 0f, 1,
                        0, 0f, 0,
                        1.0f, 0,
                    )
                }
                ok && pill.background != null
            }.getOrDefault(false)
            if (!blurred) {
                runCatching { clearMaterialMethod?.invoke(pill) }
                pill.background = null
            }
            val fillAlpha = Math.round((if (blurred) (opacity / 100f) * 0.22f else opacity / 100f) * 255f).coerceIn(0, 255)
            val fillRgb = if (blurred) 0x22242A else 0x505050
            pill.fillColor = (fillAlpha shl 24) or fillRgb
            pill.glass3d = true
            pill.strokePx = stroke
            pill.invalidate()
        }

        // Landscape: second pill sits to the right. Portrait: hidden.
        val fm = tv.paint.fontMetricsInt
        lv.barRefHeightPx = fm.bottom - fm.top
        lv.barScale = scale
        lv.barMode = Style.loadBars(ctx)

        box.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        box.gravity = if (portrait) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        lv.visibility = if (!portrait && Style.showLoad(ctx)) View.VISIBLE else View.GONE

        val lp = lv.layoutParams as LinearLayout.LayoutParams
        val gap = dp(4f * scale).toInt()
        lp.leftMargin = if (portrait) 0 else gap
        lp.topMargin = if (portrait) gap else 0
        lv.layoutParams = lp
        lv.requestLayout()
    }
}
