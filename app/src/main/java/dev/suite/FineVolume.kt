package dev.suite

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fine media volume. vivo's media stream already has 150 steps (getStreamMaxVolume(MUSIC) = 150),
 * the volume buttons just jump 10 at a time. SolCube sets the index directly, so 1 % = 1.5 steps.
 */
object FineVolume {
    const val PREF_STYLE = "vol_style"   // "knob" | "slider"

    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun max(ctx: Context): Int = am(ctx).getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun percent(ctx: Context): Int =
        (am(ctx).getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max(ctx)).roundToInt()

    fun set(ctx: Context, pct: Int) {
        val idx = (pct.coerceIn(0, 100) * max(ctx) / 100f).roundToInt()
        runCatching { am(ctx).setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
    }

    fun knob(ctx: Context) = Components.prefs(ctx).getString(PREF_STYLE, "knob") != "slider"
    fun setKnob(ctx: Context, knob: Boolean) =
        Components.prefs(ctx).edit().putString(PREF_STYLE, if (knob) "knob" else "slider").apply()
}

/**
 * 0–100 control drawn as a knob (270° arc, drag round it) or a slider (drag along it). Gold value
 * arc / fill with a soft halo, like the app's switches.
 */
class VolumeDial(
    ctx: Context, private val p: Look.Palette, private val dark: Boolean,
    private val font: Typeface, var knob: Boolean,
    private val onChange: (Int) -> Unit,
) : View(ctx) {
    private val dens = resources.displayMetrics.density
    var value = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    private var dragging = false
    val isDragging get() = dragging

    private val gold = (p.fill or 0xFF000000.toInt())
    private val warmCore = if (dark) Color.rgb(0xFF, 0xEC, 0xB8) else Color.rgb(0xFF, 0xE1, 0x99)

    private val tickOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * dens
        color = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(38, 24, 23, 28)
    }
    private val tickOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.0f * dens
        color = gold
    }
    private val trackDepth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = if (dark) Color.argb(140, 0, 0, 0) else Color.argb(34, 40, 30, 20)
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = if (dark) Color.argb(42, 255, 255, 255) else Color.argb(26, 0, 0, 0)
    }
    private val trackCatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = if (dark) Color.argb(26, 255, 255, 255) else Color.argb(95, 255, 255, 255)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = gold }
    private val fillSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = if (dark) Color.argb(110, 255, 246, 214) else Color.argb(72, 255, 250, 236)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = if (p.halo != 0) p.halo else Color.argb(90, Color.red(gold), Color.green(gold), Color.blue(gold))
        maskFilter = BlurMaskFilter(7f * dens, BlurMaskFilter.Blur.NORMAL)
    }

    // 3D knob body layers
    private val castShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ambientOcclusion = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.argb(150, 0, 0, 0) else Color.argb(42, 20, 15, 10)
        maskFilter = BlurMaskFilter(4f * dens, BlurMaskFilter.Blur.NORMAL)
    }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * dens
    }
    private val innerChamferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * dens
    }
    private val pointerGroove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6.2f * dens
        color = if (dark) Color.argb(200, 8, 7, 6) else Color.argb(56, 30, 24, 18)
    }
    private val pointerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 7.0f * dens
        color = Color.argb(if (dark) 125 else 85, Color.red(gold), Color.green(gold), Color.blue(gold))
        maskFilter = BlurMaskFilter(4.5f * dens, BlurMaskFilter.Blur.NORMAL)
    }
    private val pointerLed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.6f * dens
        color = gold
    }
    private val pointerCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * dens
        color = warmCore
    }

    private val sliderShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.argb(135, 0, 0, 0) else Color.argb(42, 20, 15, 10)
        maskFilter = BlurMaskFilter(3.5f * dens, BlurMaskFilter.Blur.NORMAL)
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(0xF4, 0xEF, 0xE6) else Color.WHITE }
    private val thumbRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * dens; color = gold }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        color = p.fg
        textAlign = Paint.Align.CENTER
        setShadowLayer(
            2.5f * dens,
            0f,
            1.2f * dens,
            if (dark) Color.argb(170, 0, 0, 0) else Color.argb(50, 255, 255, 255),
        )
    }
    private val oval = RectF()
    private val bevelRect = RectF()

    private var shaderW = -1
    private var shaderH = -1

    private val start = 135f
    private val sweep = 270f

    private fun knobCenterY() = height / 2f + 4f * dens
    private fun trackRadius() = minOf(width, height) / 2f - 18f * dens

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = if (knob) (214 * dens).toInt() else (64 * dens).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderW = -1
        shaderH = -1
    }

    private fun ensureShaders(cx: Float, cy: Float, kr: Float) {
        if (shaderW == width && shaderH == height) return
        shaderW = width
        shaderH = height

        val shY = cy + 6.5f * dens
        val shR = kr * 1.15f
        castShadow.shader = RadialGradient(
            cx, shY, shR,
            intArrayOf(
                if (dark) Color.argb(185, 0, 0, 0) else Color.argb(58, 25, 20, 14),
                if (dark) Color.argb(95, 0, 0, 0) else Color.argb(26, 25, 20, 14),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0.55f, 0.80f, 1.0f),
            Shader.TileMode.CLAMP,
        )

        bevelPaint.shader = LinearGradient(
            cx - kr, cy - kr, cx + kr, cy + kr,
            if (dark) {
                intArrayOf(Color.rgb(0x46, 0x42, 0x3D), Color.rgb(0x26, 0x24, 0x22), Color.rgb(0x11, 0x10, 0x0F))
            } else {
                intArrayOf(Color.rgb(0xFF, 0xFD, 0xFA), Color.rgb(0xE9, 0xE3, 0xDA), Color.rgb(0xC9, 0xC1, 0xB6))
            },
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )

        sheenPaint.shader = SweepGradient(
            cx, cy,
            if (dark) {
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(30, 255, 238, 204),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(24, 255, 240, 212),
                    Color.argb(0, 255, 255, 255),
                )
            } else {
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(52, 255, 255, 255),
                    Color.argb(0, 0, 0, 0),
                    Color.argb(44, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                )
            },
            floatArrayOf(0f, 0.22f, 0.50f, 0.76f, 1f),
        )

        val capR = kr * 0.84f
        capPaint.shader = RadialGradient(
            cx - capR * 0.26f, cy - capR * 0.28f, capR * 1.25f,
            if (dark) {
                intArrayOf(Color.rgb(0x2D, 0x2A, 0x27), Color.rgb(0x1D, 0x1B, 0x19), Color.rgb(0x12, 0x11, 0x10))
            } else {
                intArrayOf(Color.rgb(0xFF, 0xFE, 0xFC), Color.rgb(0xF2, 0xED, 0xE5), Color.rgb(0xDE, 0xD7, 0xCC))
            },
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )

        outerRimPaint.shader = LinearGradient(
            cx - kr, cy - kr, cx + kr, cy + kr,
            if (dark) {
                intArrayOf(Color.argb(140, 255, 242, 218), Color.argb(48, 255, 255, 255), Color.argb(165, 0, 0, 0))
            } else {
                intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(75, 255, 255, 255), Color.argb(75, 45, 35, 25))
            },
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )

        innerChamferPaint.shader = LinearGradient(
            cx - capR, cy - capR, cx + capR, cy + capR,
            if (dark) {
                intArrayOf(Color.argb(135, 0, 0, 0), Color.argb(26, 255, 255, 255), Color.argb(85, 255, 232, 192))
            } else {
                intArrayOf(Color.argb(62, 45, 35, 25), Color.argb(55, 255, 255, 255), Color.argb(185, 255, 255, 255))
            },
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(c: Canvas) {
        Glow.request(this)
        if (knob) drawKnob(c) else drawSlider(c)
    }

    private fun drawKnob(c: Canvas) {
        val cx = width / 2f
        val cy = knobCenterY()
        val r = trackRadius()
        val kr = (r - 18f * dens).coerceAtLeast(24f * dens)
        ensureShaders(cx, cy, kr)

        // 1. Subtle radial scale ticks just outside the value arc
        val tickCount = 26
        val tickIn = r + 7.5f * dens
        val tickOut = r + 11.2f * dens
        val tickOutMajor = r + 12.6f * dens
        val valFrac = value / 100f
        for (i in 0..tickCount) {
            val f = i / tickCount.toFloat()
            val rad = Math.toRadians((start + sweep * f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val major = i % 13 == 0 || i % 2 == 0
            val outer = if (i % 13 == 0) tickOutMajor else if (major) tickOut else tickIn + 2.2f * dens
            val active = value > 0 && f <= valFrac + 0.001f
            c.drawLine(cx + cosA * tickIn, cy + sinA * tickIn, cx + cosA * outer, cy + sinA * outer, if (active) tickOnPaint else tickOffPaint)
        }

        // 2. Recessed 270 deg track groove + active golden arc
        val stroke = 7f * dens
        trackDepth.strokeWidth = stroke + 1.8f * dens
        track.strokeWidth = stroke
        trackCatch.strokeWidth = stroke * 0.45f
        fill.strokeWidth = stroke
        fillSheen.strokeWidth = stroke * 0.34f
        halo.strokeWidth = stroke * (if (dragging) 1.85f else 1.55f)

        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, start, sweep, false, trackDepth)
        c.drawArc(oval, start, sweep, false, track)
        bevelRect.set(cx - r, cy - r + 0.8f * dens, cx + r, cy + r + 0.8f * dens)
        c.drawArc(bevelRect, start, sweep, false, trackCatch)

        val s = sweep * value / 100f
        if (s > 0.5f) {
            c.drawArc(oval, start, s, false, halo)
            c.drawArc(oval, start, s, false, fill)
            c.drawArc(oval, start, s, false, fillSheen)
        }

        // 3. Soft cast shadow + tight ambient occlusion under the physical knob body
        c.drawCircle(cx, cy + 6.5f * dens, kr * 1.15f, castShadow)
        c.drawCircle(cx, cy + 1.8f * dens, kr + 1.2f * dens, ambientOcclusion)

        // 4. Machined outer bevel + angle-rotating anisotropic sheen
        c.drawCircle(cx, cy, kr, bevelPaint)
        c.save()
        c.rotate(start + s, cx, cy)
        c.drawCircle(cx, cy, kr * 0.96f, sheenPaint)
        c.restore()
        c.drawCircle(cx, cy, kr - 0.6f * dens, outerRimPaint)

        // 5. Recessed inner cap + chamfer rim
        val capR = kr * 0.84f
        c.drawCircle(cx, cy, capR, capPaint)
        c.drawCircle(cx, cy, capR, innerChamferPaint)

        // 6. Recessed glowing pointer notch
        val a = Math.toRadians((start + s).toDouble())
        val cosP = cos(a).toFloat()
        val sinP = sin(a).toFloat()
        val r1 = kr * 0.55f
        val r2 = kr * 0.76f
        val px1 = cx + cosP * r1
        val py1 = cy + sinP * r1
        val px2 = cx + cosP * r2
        val py2 = cy + sinP * r2
        c.drawLine(px1, py1, px2, py2, pointerGroove)
        c.drawLine(px1, py1, px2, py2, pointerGlow)
        c.drawLine(px1, py1, px2, py2, pointerLed)
        c.drawLine(px1, py1, px2, py2, pointerCore)

        // 7. Embossed center percentage readout
        text.textSize = 29f * dens
        text.textAlign = Paint.Align.CENTER
        val fm = text.fontMetrics
        c.drawText("$value%", cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun drawSlider(c: Canvas) {
        val pad = 16 * dens
        val cy = height / 2f
        val stroke = 10f * dens
        trackDepth.strokeWidth = stroke + 1.6f * dens
        track.strokeWidth = stroke
        fill.strokeWidth = stroke
        fillSheen.strokeWidth = stroke * 0.34f
        halo.strokeWidth = stroke * 1.4f
        val x0 = pad; val x1 = width - pad - 58 * dens     // room for the % on the right
        c.drawLine(x0, cy, x1, cy, trackDepth)
        c.drawLine(x0, cy, x1, cy, track)
        val x = x0 + (x1 - x0) * value / 100f
        if (value > 0) {
            c.drawLine(x0, cy, x, cy, halo)
            c.drawLine(x0, cy, x, cy, fill)
            c.drawLine(x0, cy, x, cy, fillSheen)
        }
        val tr = 12f * dens
        c.drawCircle(x, cy + 2.5f * dens, tr * 1.05f, sliderShadow)
        thumb.shader = RadialGradient(
            x - tr * 0.32f, cy - tr * 0.34f, tr * 1.35f,
            if (dark) intArrayOf(Color.WHITE, Color.rgb(0xE8, 0xDF, 0xD2), Color.rgb(0xB8, 0xAE, 0xA0))
            else intArrayOf(Color.WHITE, Color.rgb(0xF6, 0xF2, 0xEB), Color.rgb(0xD8, 0xD1, 0xC6)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawCircle(x, cy, tr, thumb)
        c.drawCircle(x, cy, tr, thumbRim)
        text.textSize = 20 * dens
        text.textAlign = Paint.Align.RIGHT
        val fm = text.fontMetrics
        c.drawText("$value%", width - pad / 2, cy - (fm.ascent + fm.descent) / 2, text)
        text.textAlign = Paint.Align.CENTER
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (knob && !onRing(e.x, e.y)) return false
                dragging = true
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) update(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun onRing(x: Float, y: Float): Boolean {
        val cx = width / 2f; val cy = knobCenterY()
        val r = trackRadius()
        val d = kotlin.math.hypot(x - cx, y - cy)
        return d < r + 24 * dens
    }

    private fun update(x: Float, y: Float) {
        val v = if (knob) {
            val cx = width / 2f; val cy = knobCenterY()
            val deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
            var a = (deg - start + 720f) % 360f
            // the gap at the bottom: snap to whichever end is nearer, and never jump across it
            if (a > sweep) a = if (a < sweep + (360f - sweep) / 2) sweep else 0f
            val nv = (a / sweep * 100f).roundToInt()
            if (kotlin.math.abs(nv - value) > 60) value else nv
        } else {
            val pad = 16 * dens
            val x0 = pad; val x1 = width - pad - 58 * dens
            ((x - x0) / (x1 - x0) * 100f).roundToInt().coerceIn(0, 100)
        }
        if (v != value) {
            value = v
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onChange(v)
        }
    }
}
