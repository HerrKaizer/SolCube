package dev.suite

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * SolCube's status pill (Samsung position, vivo-island motion): a small dark pill in the status bar
 * between the clock and the punch hole. It comes out of the hole like a water drop: a blob
 * leaves the hole, stays joined to it by a thinning neck (metaball) until it snaps free, then
 * stretches into the pill and the text fades in. Closing runs the same in reverse.
 * Used for routines and the double-press NFC switch. Drawn in the accessibility overlay so it
 * sits above the status bar; never takes touches.
 */
object Island {
    private const val TAG = "SolCube"
    private val main = Handler(Looper.getMainLooper())
    private var current: PillView? = null
    private val pending = ArrayList<Triple<String, Boolean, Boolean>>()   // wait for the current pill to go back in
    private var pendingCtx: Context? = null

    /** Geometry in screen pixels (set once with the temporary editor). */
    // defaults = what the user set with the editor on 2026-11-16 (vivo X300 Ultra, 1440x3168)
    data class Geo(
        var holeX: Float = 720f, var holeY: Float = 80f, var holeR: Float = 42f,   // the punch hole
        var gap: Float = 21f,          // from the hole's edge to the pill's right end
        var pillY: Float = 80f,        // pill centre line
        var pillH: Float = 99f,        // pill height
        var textSp: Float = 15f,
        var leftLimit: Float = 190f,   // never grow past this (the clock)
    )

    var geo = Geo()
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = Components.prefs(ctx)
        if (!p.contains("pill_holeX")) return
        geo = Geo(p.getFloat("pill_holeX", geo.holeX), p.getFloat("pill_holeY", geo.holeY), p.getFloat("pill_holeR", geo.holeR),
            p.getFloat("pill_gap", geo.gap), p.getFloat("pill_pillY", geo.pillY), p.getFloat("pill_pillH", geo.pillH),
            p.getFloat("pill_textSp", geo.textSp), p.getFloat("pill_left", geo.leftLimit))
    }

    fun save(ctx: Context) {
        Components.prefs(ctx).edit()
            .putFloat("pill_holeX", geo.holeX).putFloat("pill_holeY", geo.holeY).putFloat("pill_holeR", geo.holeR)
            .putFloat("pill_gap", geo.gap).putFloat("pill_pillY", geo.pillY).putFloat("pill_pillH", geo.pillH)
            .putFloat("pill_textSp", geo.textSp).putFloat("pill_left", geo.leftLimit).apply()
        current?.invalidate()
    }

    /** [still] keeps it out until hide() (for the editor). */
    fun show(ctx: Context, text: String, ok: Boolean = true, still: Boolean = false, waited: Int = 0) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { show(ctx, text, ok, still, waited) }; return }
        load(ctx)
        current?.let {
            if (it.isAttachedToWindow) {
                // going back in: let it finish, then come out fresh (starting mid-way looked broken)
                if (it.closing) { pending += Triple(text, ok, still); pendingCtx = ctx.applicationContext; return }
                it.replace(text, ok, still); return
            }
        }
        val a11y = SolCubeA11y.instance
        val app = ctx.applicationContext
        // A notice usually wakes SolCube from vivo's freezer, and the accessibility service connects a
        // moment after the app starts. Showing right away fell back to the app overlay, which sits
        // UNDER the status bar (the notification icons drew over the pill): wait for it, up to 2 s.
        if (a11y == null && waited < 20 && a11yEnabled(app)) {
            main.postDelayed({ show(app, text, ok, still, waited + 1) }, 100)
            return
        }
        if (a11y == null && !Settings.canDrawOverlays(app)) return
        val vctx: Context = a11y ?: app
        val wm = vctx.getSystemService(WindowManager::class.java)
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // Fallback (accessibility service dropped by vivo): Android 12+ draws a pass-through
        // (NOT_TOUCHABLE) app overlay at most 80 % opaque, which made the pill see-through. So
        // that window takes touches instead, and is only as wide as the pill's area.
        val overlay = a11y == null
        val offX = if (overlay) (geo.leftLimit - 80f).coerceAtLeast(0f) else 0f
        val view = PillView(vctx, text, ok, still, offX) { v ->
            runCatching { wm.removeView(v) }
            if (current === v) current = null
            val ps = pending.toList(); val pc = pendingCtx
            pending.clear(); pendingCtx = null
            // the first comes out, the rest queue up on it
            if (pc != null) ps.forEach { p -> show(pc, p.first, p.second, p.third) }
        }
        val lp = WindowManager.LayoutParams(
            if (overlay) (geo.holeX + geo.holeR + 12f - offX).toInt() else WindowManager.LayoutParams.MATCH_PARENT, 260, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (overlay) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = offX.toInt()
            alpha = 1f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "SolCubeIsland"
        }
        try { wm.addView(view, lp); current = view } catch (e: Exception) { Log.w(TAG, "island", e) }
    }

    private fun a11yEnabled(ctx: Context): Boolean = runCatching {
        Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
            .split(':').any { it.equals("${ctx.packageName}/${SolCubeA11y::class.java.name}", true) || it.equals("${ctx.packageName}/.SolCubeA11y", true) }
    }.getOrDefault(false)

    fun hide() = main.post { current?.close() }

    private class PillView(
        ctx: Context, private var text: String, private var ok: Boolean, private var still: Boolean,
        private val offX: Float,   // the window's left edge in screen pixels (geometry is in screen pixels)
        private val onGone: (View) -> Unit,
    ) : View(ctx) {
        private val dens = resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = runCatching {
                Typeface.Builder(ctx.assets, "fonts/GoogleSansFlex.ttf").setFontVariationSettings("'wght' 560").build()
            }.getOrNull() ?: Typeface.DEFAULT_BOLD
        }
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * dens
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // a faint hairline so the black pill still reads on a black status bar
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * dens
        }
        private val rimRect = RectF()
        private val rect = RectF()
        private val path = Path()
        private var s = 0f          // 0 = inside the hole, 1 = in place (the spring goes past 1 and back)
        private var opening = true
        private var snapped = false
        private var anim: ValueAnimator? = null
        private val hold = Runnable { close() }

        // notices that arrive while this one is out: each is shown for at least minShow, the text
        // cross-fades and the pill eases to the new width
        private val queue = ArrayDeque<Pair<String, Boolean>>()
        private var prevText = ""
        private var prevOk = true
        private var swap = 1f            // 0 → 1 while the text changes
        private var swapAnim: ValueAnimator? = null
        private var readableAt = 0L      // when the current text could first be read
        private val minShow get() = 1600L
        private val next = Runnable { swapNext() }

        init { open() }

        private val g get() = Island.geo
        private val pad get() = g.pillH * 0.42f
        private val icon get() = g.pillH * 0.46f
        private val right get() = g.holeX - g.holeR - g.gap

        private fun shown(t: String = text): String {
            txt.textSize = g.textSp * dens
            val room = right - g.leftLimit - pad * 2 - icon - 6 * dens
            return android.text.TextUtils.ellipsize(t, android.text.TextPaint(txt), maxOf(0f, room), android.text.TextUtils.TruncateAt.END).toString()
        }

        private fun fullWidth(t: String = text): Float {
            txt.textSize = g.textSp * dens
            return min(right - g.leftLimit, maxOf(g.pillH, pad + icon + 6 * dens + txt.measureText(shown(t)) + pad))
        }

        /** Width now: between the old and the new text's while they swap. */
        private fun targetWidth(): Float {
            if (swap >= 1f) return fullWidth()
            val from = fullWidth(prevText)
            return from + (fullWidth() - from) * ease(swap)
        }

        /** Runs [frame] with 0..1 over [ms] (linear; the curves are in the frames). */
        private fun run(ms: Long, frame: (Float) -> Unit, end: () -> Unit) {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { frame(it.animatedValue as Float); invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { if (anim === a) end() }
                })
                start()
            }
        }

        /**
         * Damped spring 0 → 1 (ζ 0.42): shoots ~25% past its place (pulling the neck until it
         * breaks), swings back towards the hole past its place (the snap back), settles.
         */
        private fun spring(sec: Float): Float {
            val z = 0.42f; val w = 9.5f
            val q = kotlin.math.sqrt(1 - z * z)
            return 1 - kotlin.math.exp(-z * w * sec) * (cos(w * q * sec) + z / q * sin(w * q * sec))
        }

        // a getter, not a field: open() runs from init {}, before fields declared down here exist
        // (as a field it was still 0 there, so the way out ran in 0 ms and the neck never broke)
        private val springSec get() = 1.5f

        private fun open() {
            opening = true; snapped = false
            val from = s
            readableAt = System.currentTimeMillis() + 300
            run((springSec * 1000).toLong(), { f -> s = from + (1 - from) * spring(f * springSec) }) {
                s = 1f; snapped = true; invalidate(); afterText()
            }
        }

        val closing get() = !opening

        fun close() {
            removeCallbacks(hold); removeCallbacks(next); opening = false
            val from = s
            // no bounce on the way back: a smooth glide into the hole
            run(550, { f -> s = from * (1 - ease(f)) }) { onGone(this) }
        }

        /** A new notice while this one is out (or coming out): queued, shown after the current one. */
        fun replace(newText: String, o: Boolean, st: Boolean) {
            if (st) still = true
            val last = queue.lastOrNull() ?: (text to ok)
            if (last.first == newText && last.second == o) {   // the same again: just keep it out longer
                if (queue.isEmpty() && anim?.isRunning != true && swapAnim?.isRunning != true) afterText()
                return
            }
            queue.addLast(newText to o)
            if (anim?.isRunning != true && swapAnim?.isRunning != true) afterText()
        }

        /** The current text is out: wait, then the next queued one, or go back in. */
        private fun afterText() {
            removeCallbacks(hold); removeCallbacks(next)
            if (queue.isNotEmpty()) postDelayed(next, maxOf(0L, minShow - (System.currentTimeMillis() - readableAt)))
            else if (!still) postDelayed(hold, 3500)
        }

        private fun swapNext() {
            val n = queue.removeFirstOrNull() ?: return
            prevText = text; prevOk = ok
            text = n.first; ok = n.second
            swap = 0f
            readableAt = System.currentTimeMillis() + 200
            swapAnim?.cancel()
            swapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { swap = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (swapAnim === a) { swap = 1f; invalidate(); if (!closing) afterText() }
                    }
                })
                start()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(hold); removeCallbacks(next); swapAnim?.cancel()
        }

        private fun ease(x: Float) = x * x * (3 - 2 * x)

        override fun onDraw(c: Canvas) {
            if (s <= 0.001f) return
            c.translate(-offX, 0f)
            val p = s.coerceAtLeast(0f)
            // it is a pill from the first frame: it starts as the hole itself and, while its right
            // end travels out, gets taller and longer at the same time
            val h = 2 * g.holeR + (g.pillH - 2 * g.holeR) * (p / 0.6f).coerceIn(0f, 1f)
            val r = h / 2
            val travel = 2 * g.holeR + g.gap
            val rightNow = g.holeX + g.holeR - p * travel        // p = 1 → its place, > 1 further out
            val by = g.holeY + (g.pillY - g.holeY) * p.coerceIn(0f, 1f)
            val w = 2 * r + (targetWidth() - 2 * r) * ((p - 0.08f) / 0.72f).coerceIn(0f, 1f)
            rect.set(rightNow - w, by - r, rightNow, by + r)

            c.drawCircle(g.holeX, g.holeY, g.holeR, bg)
            // neck: a waist between the hole and the pill's end, thick while they touch and thinner
            // the further they part. Opening: it breaks once the spring pulls the pill ~1.45x the
            // final gap away and stays broken through the snap back. Closing: it forms again as a
            // thin thread as soon as the pill moves back in, thickening until they merge.
            val gapNow = (g.holeX - g.holeR) - rightNow
            val breakAt = maxOf(g.gap * 1.45f, 6 * dens)
            // opening: joined until the swing out passes the break point, then free.
            // closing: the neck forms as a thin thread as soon as it moves in from its place and
            // thickens until they merge.
            val limit = if (opening) breakAt else g.gap + 1f
            val joined = if (opening) {
                if (!snapped && gapNow > breakAt) snapped = true
                !snapped
            } else true
            if (joined && gapNow < limit)
                neck(c, g.holeX, g.holeY, g.holeR, rightNow - r, by, r, gapNow, limit)
            c.drawRoundRect(rect, r, r, bg)
            // outline only once the pill has left the hole (an outline round the camera, or across the
            // neck, would show the seam), fading in with the text
            val neckShown = joined && gapNow < limit
            val ra = ((p - 0.62f) / 0.3f).coerceIn(0f, 1f)
            if (!neckShown && ra > 0f) {
                rim.color = Color.argb((0x33 * ra).toInt(), 255, 255, 255)
                val hw = rim.strokeWidth / 2
                rimRect.set(rect.left + hw, rect.top + hw, rect.right - hw, rect.bottom - hw)
                c.drawRoundRect(rimRect, r - hw, r - hw, rim)
            }

            val ta = ((p - 0.62f) / 0.3f).coerceIn(0f, 1f)
            if (ta <= 0f) return
            c.save()
            c.clipRect(rect)
            when {
                // old text fades out over the first half of a swap, the new one in over the second
                swap >= 1f -> content(c, text, ok, ta, by)
                swap < 0.5f -> content(c, prevText, prevOk, ta * (1 - swap * 2), by)
                else -> content(c, text, ok, ta * (swap * 2 - 1), by)
            }
            c.restore()
        }

        private fun content(c: Canvas, t: String, good: Boolean, alpha: Float, by: Float) {
            if (alpha <= 0f) return
            val a = (alpha * 255).toInt()
            val left = rect.left + pad
            mark.color = if (good) Color.argb(a, 0x7E, 0xE0, 0x9A) else Color.argb(a, 0xF0, 0xB0, 0x5A)
            val ic = icon
            val m = Path()
            if (good) {
                m.moveTo(left, by); m.lineTo(left + ic * 0.38f, by + ic * 0.34f); m.lineTo(left + ic, by - ic * 0.36f)
            } else {
                m.moveTo(left + ic * 0.15f, by - ic * 0.35f); m.lineTo(left + ic * 0.85f, by + ic * 0.35f)
                m.moveTo(left + ic * 0.85f, by - ic * 0.35f); m.lineTo(left + ic * 0.15f, by + ic * 0.35f)
            }
            c.drawPath(m, mark)
            txt.textSize = g.textSp * dens
            txt.alpha = a
            val fm = txt.fontMetrics
            c.drawText(shown(t), left + ic + 6 * dens, by - (fm.ascent + fm.descent) / 2, txt)
        }

        /**
         * Waist between circle 1 (the hole) and circle 2 (the pill's near end). Its half-thickness
         * goes from 90% of the smaller radius while they touch down to ~18% at the break point;
         * it joins each circle a bit above/below the waist so the sides curve inwards.
         */
        private fun neck(c: Canvas, x1: Float, y1: Float, r1: Float, x2: Float, y2: Float, r2: Float, gapNow: Float, breakAt: Float) {
            val dx = x2 - x1; val dy = y2 - y1
            val d = hypot(dx, dy)
            if (d < 1f) return
            val k = (gapNow / breakAt).coerceIn(0f, 1f)
            val w = min(r1, r2) * (0.9f - 0.72f * k)
            val ux = dx / d; val uy = dy / d          // hole → pill
            val nx = -uy; val ny = ux
            val s1 = min(0.97f, w * 1.35f / r1); val c1 = kotlin.math.sqrt(1 - s1 * s1)
            val s2 = min(0.97f, w * 1.35f / r2); val c2 = kotlin.math.sqrt(1 - s2 * s2)
            // where the waist meets each circle
            val ax = x1 + r1 * (c1 * ux + s1 * nx); val ay = y1 + r1 * (c1 * uy + s1 * ny)
            val bx = x1 + r1 * (c1 * ux - s1 * nx); val by = y1 + r1 * (c1 * uy - s1 * ny)
            val cx = x2 + r2 * (-c2 * ux + s2 * nx); val cy = y2 + r2 * (-c2 * uy + s2 * ny)
            val ex = x2 + r2 * (-c2 * ux - s2 * nx); val ey = y2 + r2 * (-c2 * uy - s2 * ny)
            // the waist itself, halfway between the facing edges
            val mx = (x1 + ux * r1 + x2 - ux * r2) / 2; val my = (y1 + uy * r1 + y2 - uy * r2) / 2
            val tx = mx + nx * w; val ty = my + ny * w
            val qx = mx - nx * w; val qy = my - ny * w
            val l1 = maxOf(2f, ((tx - ax) * ux + (ty - ay) * uy) * 0.55f)
            val l2 = maxOf(2f, ((cx - tx) * ux + (cy - ty) * uy) * 0.55f)
            path.reset()
            path.moveTo(ax, ay)
            path.cubicTo(ax + ux * l1, ay + uy * l1, tx - ux * l1, ty - uy * l1, tx, ty)
            path.cubicTo(tx + ux * l2, ty + uy * l2, cx - ux * l2, cy - uy * l2, cx, cy)
            path.lineTo(ex, ey)
            path.cubicTo(ex - ux * l2, ey - uy * l2, qx + ux * l2, qy + uy * l2, qx, qy)
            path.cubicTo(qx - ux * l1, qy - uy * l1, bx + ux * l1, by + uy * l1, bx, by)
            path.close()
            c.drawPath(path, bg)
        }
    }
}

/**
 * The shell side (routines engine, NFC session) asks for a notice:
 *   am broadcast -n dev.suite/.IslandReceiver --es text "Morning" [--ez ok false]
 * Only shell/system can send it (manifest permission).
 */
class IslandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: intent.getStringExtra("title") ?: return
        Island.show(context, text, intent.getBooleanExtra("ok", true))
    }
}
