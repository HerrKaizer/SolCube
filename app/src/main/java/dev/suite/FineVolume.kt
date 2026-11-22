package dev.suite

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(30, 0, 0, 0)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = gold }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = if (p.halo != 0) p.halo else Color.argb(90, Color.red(gold), Color.green(gold), Color.blue(gold))
        maskFilter = BlurMaskFilter(7f * dens, BlurMaskFilter.Blur.NORMAL)
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(0x22, 0x21, 0x20) else Color.rgb(0xF1, 0xEE, 0xE8) }
    private val discRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * dens
        color = if (dark) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 5f * dens; color = p.fg }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(0xF4, 0xEF, 0xE6) else Color.WHITE }
    private val thumbRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * dens; color = gold }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; color = p.fg; textAlign = Paint.Align.CENTER }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; color = p.sub; textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    private val start = 135f
    private val sweep = 270f

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = if (knob) (200 * dens).toInt() else (64 * dens).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        Glow.request(this)
        if (knob) drawKnob(c) else drawSlider(c)
    }

    private fun drawKnob(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f + 6 * dens
        val r = minOf(width, height) / 2f - 16 * dens
        val stroke = 7f * dens
        track.strokeWidth = stroke; fill.strokeWidth = stroke; halo.strokeWidth = stroke * 1.6f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, start, sweep, false, track)
        val s = sweep * value / 100f
        if (s > 0.5f) { c.drawArc(oval, start, s, false, halo); c.drawArc(oval, start, s, false, fill) }
        // the knob itself, with a pointer
        val kr = r - 18 * dens
        c.drawCircle(cx, cy, kr, disc)
        c.drawCircle(cx, cy, kr, discRim)
        val a = Math.toRadians((start + s).toDouble())
        val r1 = kr * 0.62f; val r2 = kr * 0.84f
        c.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), tick)
        text.textSize = 30 * dens
        val fm = text.fontMetrics
        c.drawText("$value%", cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawSlider(c: Canvas) {
        val pad = 16 * dens
        val cy = height / 2f
        val stroke = 10f * dens
        track.strokeWidth = stroke; fill.strokeWidth = stroke; halo.strokeWidth = stroke * 1.4f
        val x0 = pad; val x1 = width - pad - 58 * dens     // room for the % on the right
        c.drawLine(x0, cy, x1, cy, track)
        val x = x0 + (x1 - x0) * value / 100f
        if (value > 0) { c.drawLine(x0, cy, x, cy, halo); c.drawLine(x0, cy, x, cy, fill) }
        c.drawCircle(x, cy, 12 * dens, thumb)
        c.drawCircle(x, cy, 12 * dens, thumbRim)
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
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) update(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun onRing(x: Float, y: Float): Boolean {
        val cx = width / 2f; val cy = height / 2f + 6 * dens
        val r = minOf(width, height) / 2f - 16 * dens
        val d = kotlin.math.hypot(x - cx, y - cy)
        return d < r + 24 * dens
    }

    private fun update(x: Float, y: Float) {
        val v = if (knob) {
            val cx = width / 2f; val cy = height / 2f + 6 * dens
            var deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
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
