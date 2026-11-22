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
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowInsets
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

    /** Live geometry in screen pixels, derived per display from WindowMetrics + DisplayCutout. */
    private data class Geo(
        val holeX: Float, val holeY: Float, val holeR: Float,
        val gap: Float,          // from the hole's edge to the pill's right end
        val pillY: Float,        // pill centre line
        val pillH: Float,        // pill height
        val textPx: Float,       // text size in px (proportional to pillH)
        val leftLimit: Float,    // never grow past this (the clock)
        val dropIn: Boolean,     // true = landscape or no usable top-center cutout
        val screenW: Int,
        val winH: Int,
    )

    private fun computeGeo(ctx: Context, wm: WindowManager): Geo {
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val screenW = bounds.width().coerceAtLeast(1)
        val screenH = bounds.height().coerceAtLeast(1)
        val dens = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
        val land = screenW > screenH

        val insetsTop = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallbackBarH = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else (36f * dens).toInt()
        val statusBarH = (if (insetsTop > 0) insetsTop else fallbackBarH).toFloat().coerceAtLeast(24f * dens)

        val cutout = metrics.windowInsets.displayCutout
            ?: ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.cutout

        if (!land && cutout != null) {
            val topRect = cutout.boundingRectTop
            if (!topRect.isEmpty && topRect.width() > 0 && topRect.width() < screenW * 0.55f) {
                val pathBounds = RectF()
                val cp = cutout.cutoutPath
                @Suppress("DEPRECATION")
                val hasPath = cp != null && run {
                    cp.computeBounds(pathBounds, true)
                    !pathBounds.isEmpty &&
                        pathBounds.left >= topRect.left - 4f &&
                        pathBounds.right <= topRect.right + 4f &&
                        pathBounds.bottom <= topRect.bottom + 4f &&
                        pathBounds.height() <= maxOf(statusBarH * 1.5f, 60f * dens)
                }
                val floatingHole = hasPath && pathBounds.top > 2f
                val maxHoleR = minOf(statusBarH * 0.36f, 13f * dens)
                val minHoleR = (6f * dens).coerceAtMost(maxHoleR)
                val rawR = if (floatingHole) {
                    minOf(pathBounds.width(), pathBounds.height()) / 2f
                } else {
                    (if (hasPath) pathBounds.width() else topRect.width().toFloat()) / 2f
                }
                val holeR = rawR.coerceIn(minHoleR, maxHoleR)
                val srcLeft = if (hasPath) pathBounds.left else topRect.left.toFloat()
                val srcWidth = if (hasPath) pathBounds.width() else topRect.width().toFloat()
                val holeX = if (srcWidth > holeR * 2.2f) srcLeft + holeR else srcLeft + srcWidth / 2f
                val rawY = when {
                    floatingHole -> pathBounds.centerY()
                    topRect.bottom <= statusBarH * 0.92f -> topRect.bottom - holeR
                    else -> statusBarH * 0.5f
                }
                val minY = holeR + 2f * dens
                val maxY = (statusBarH - holeR).coerceAtLeast(minY)
                val holeY = rawY.coerceIn(minY, maxY)
                val minPillH = minOf(22f * dens, statusBarH * 0.72f)
                val maxPillH = maxOf(minPillH, minOf(statusBarH * 0.85f, 29f * dens))
                val pillH = maxOf(holeR * 2f * 1.18f, minPillH).coerceAtMost(maxPillH)
                val pillY = maxOf(holeY, pillH / 2f + 2f * dens)
                val gap = maxOf(holeR * 0.50f, 4f * dens)
                val leftLimit = maxOf(54f * dens, screenW * 0.132f)
                val right = holeX - holeR - gap
                if (right - leftLimit >= pillH * 2.2f) {
                    val winH = maxOf((pillY + pillH * 1.1f).toInt(), (statusBarH * 1.6f).toInt(), (64f * dens).toInt())
                    return Geo(
                        holeX = holeX,
                        holeY = holeY,
                        holeR = holeR,
                        gap = gap,
                        pillY = pillY,
                        pillH = pillH,
                        textPx = pillH * 0.55f,
                        leftLimit = leftLimit,
                        dropIn = false,
                        screenW = screenW,
                        winH = winH,
                    )
                }
            }
        }

        val minPillH = 22f * dens
        val maxPillH = maxOf(minPillH, minOf(statusBarH * 0.85f, 28f * dens))
        val pillH = (26f * dens).coerceIn(minPillH, maxPillH)
        val pillY = maxOf(statusBarH * 0.5f, pillH * 0.5f + 8f * dens)
        val winH = maxOf((pillY * 1.35f + pillH * 0.9f).toInt(), (statusBarH * 1.8f).toInt(), (72f * dens).toInt())
        return Geo(
            holeX = screenW / 2f,
            holeY = pillY,
            holeR = pillH * 0.42f,
            gap = 0f,
            pillY = pillY,
            pillH = pillH,
            textPx = pillH * 0.55f,
            leftLimit = 0f,
            dropIn = true,
            screenW = screenW,
            winH = winH,
        )
    }

    /** [still] keeps it out until hide() (for the editor). */
    /** [onTap]: the pill takes a tap (its window is then only as wide as the pill area) and stays out 6 s. */
    fun show(ctx: Context, text: String, ok: Boolean = true, still: Boolean = false, waited: Int = 0, onTap: (() -> Unit)? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { show(ctx, text, ok, still, waited, onTap) }; return }
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
            main.postDelayed({ show(app, text, ok, still, waited + 1, onTap) }, 100)
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
        val narrow = overlay || onTap != null   // a window that takes touches is kept to the pill area
        val g = computeGeo(vctx, wm)
        val dens = vctx.resources.displayMetrics.density.coerceAtLeast(1f)
        val offX = if (narrow && !g.dropIn) (g.leftLimit - 20f * dens).coerceAtLeast(0f) else 0f
        val view = PillView(vctx, g, text, ok, still, offX, onTap) { v ->
            runCatching { wm.removeView(v) }
            if (current === v) current = null
            val ps = pending.toList(); val pc = pendingCtx
            pending.clear(); pendingCtx = null
            // the first comes out, the rest queue up on it
            if (pc != null) ps.forEach { p -> show(pc, p.first, p.second, p.third) }
        }
        val lp = WindowManager.LayoutParams(
            when {
                !narrow -> WindowManager.LayoutParams.MATCH_PARENT
                g.dropIn -> minOf((g.screenW * 0.85f).toInt(), (360f * dens).toInt())
                else -> (g.holeX + g.holeR + 4f * dens - offX).toInt().coerceAtLeast(1)
            }, g.winH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (narrow) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or
                (if (g.dropIn) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START)
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
        ctx: Context, private val g: Geo, private var text: String, private var ok: Boolean, private var still: Boolean,
        private val offX: Float,   // the window's left edge in screen pixels (geometry is in screen pixels)
        private val onTap: (() -> Unit)?,
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
            strokeWidth = (g.pillH * 0.085f).coerceAtLeast(1.5f * dens)
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

        private val pad get() = g.pillH * 0.42f
        private val icon get() = g.pillH * 0.46f
        private val iconGap get() = g.pillH * 0.20f
        private val right get() = g.holeX - g.holeR - g.gap
        /** The widest the pill may get: up to the clock in portrait, under half the screen in landscape. */
        private val maxW get() = if (g.dropIn) minOf(width.toFloat(), 360f * dens) * 0.9f else (right - g.leftLimit).coerceAtLeast(g.pillH)

        private fun shown(t: String = text): String {
            txt.textSize = g.textPx
            val room = maxW - pad * 2 - icon - iconGap
            return android.text.TextUtils.ellipsize(t, android.text.TextPaint(txt), maxOf(0f, room), android.text.TextUtils.TruncateAt.END).toString()
        }

        private fun fullWidth(t: String = text): Float {
            txt.textSize = g.textPx
            return min(maxW, maxOf(g.pillH, pad + icon + iconGap + txt.measureText(shown(t)) + pad))
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
            else if (!still) postDelayed(hold, if (onTap != null) 6000 else 3500)
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

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            val tap = onTap ?: return false
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP && !closing) { runCatching { tap() }; close() }
            return true
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(hold); removeCallbacks(next); swapAnim?.cancel()
        }

        private fun ease(x: Float) = x * x * (3 - 2 * x)

        override fun onDraw(c: Canvas) {
            if (s <= 0.001f) return
            if (g.dropIn) { drawLand(c); return }
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
            rimAndText(c, p, r, by, !neckShown)
        }

        /**
         * Landscape: no punch hole up there, so no drop and no neck. The pill springs down from
         * above the top edge to the top centre (same spring: overshoots a little, settles) and
         * glides back up when it closes.
         */
        private fun drawLand(c: Canvas) {
            val p = s.coerceAtLeast(0f)
            val h = g.pillH * (0.6f + 0.4f * (p / 0.6f).coerceIn(0f, 1f))
            val r = h / 2
            val cx = width / 2f
            val by = -g.pillH + (g.pillY + g.pillH) * p           // p = 1 → its place, > 1 a bit lower
            val w = h + (targetWidth() - h) * ((p - 0.08f) / 0.72f).coerceIn(0f, 1f)
            rect.set(cx - w / 2, by - r, cx + w / 2, by + r)
            c.drawRoundRect(rect, r, r, bg)
            rimAndText(c, p, r, by, true)
        }

        private fun rimAndText(c: Canvas, p: Float, r: Float, by: Float, showRim: Boolean) {
            val ra = ((p - 0.62f) / 0.3f).coerceIn(0f, 1f)
            if (showRim && ra > 0f) {
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
            txt.textSize = g.textPx
            txt.alpha = a
            val fm = txt.fontMetrics
            c.drawText(shown(t), left + ic + iconGap, by - (fm.ascent + fm.descent) / 2, txt)
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
