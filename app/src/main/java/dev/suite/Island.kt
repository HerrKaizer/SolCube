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
        val pillH: Float,       // pill height
        val textPx: Float,       // text size in px (proportional to pillH)
        val leftLimit: Float,    // never grow past this (near left edge, may cover clock)
        val dropIn: Boolean,     // true = landscape or no usable top-center cutout
        val screenW: Int,
        val winH: Int,
        val safeLeft: Float = 0f,
        val safeRight: Float = 0f,
    )

    private fun computeGeo(ctx: Context, wm: WindowManager, viewW: Int = 0, viewH: Int = 0): Geo {
        val metrics = wm.maximumWindowMetrics
        val bounds = metrics.bounds
        val dens = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val rot = display?.rotation ?: android.view.Surface.ROTATION_0
        val rotated = rot == android.view.Surface.ROTATION_90 || rot == android.view.Surface.ROTATION_270
        val natLand = display?.mode?.let { it.physicalWidth > it.physicalHeight } ?: false
        val cfgLand = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val shortSide = minOf(bounds.width(), bounds.height()).coerceAtLeast(1)
        val longSide = maxOf(bounds.width(), bounds.height()).coerceAtLeast(1)
        val viewLand = viewW > 0 && viewW > shortSide + 16
        val land = viewLand || cfgLand || (if (display != null) natLand != rotated else bounds.width() > bounds.height())
        val screenW = if (land) longSide else shortSide
        val screenH = if (land) shortSide else longSide

        val insetsTop = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallbackBarH = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else (36f * dens).toInt()
        val statusBarH = (if (insetsTop > 0) insetsTop else fallbackBarH).toFloat().coerceAtLeast(24f * dens)

        val cutout = metrics.windowInsets.displayCutout ?: display?.cutout

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
                val rawY = if (floatingHole) pathBounds.centerY() else topRect.bottom - holeR
                val minY = holeR + 2f * dens
                val maxY = (maxOf(statusBarH, topRect.bottom.toFloat()) - holeR).coerceAtLeast(minY)
                val holeY = rawY.coerceIn(minY, maxY)
                val minPillH = minOf(23f * dens, statusBarH * 0.72f)
                val maxPillH = maxOf(minPillH, minOf(statusBarH * 0.85f, 30f * dens))
                val pillH = maxOf(holeR * 2f * 1.21f, minPillH).coerceAtMost(maxPillH)
                val pillY = maxOf(holeY, pillH / 2f + 2f * dens)
                val gap = maxOf(holeR * 0.50f, 4f * dens)
                val leftLimit = maxOf(8f * dens, (2f * holeR + gap) * 0.25f + 2f * dens)
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
                        textPx = pillH * 0.606f,
                        leftLimit = leftLimit,
                        dropIn = false,
                        screenW = screenW,
                        winH = winH,
                    )
                }
            }
        }

        var cutL = 0f
        var cutR = 0f
        if (land && cutout != null) {
            if (bounds.width() > bounds.height()) {
                cutL = cutout.safeInsetLeft.toFloat()
                cutR = cutout.safeInsetRight.toFloat()
            } else when (rot) {
                android.view.Surface.ROTATION_90 -> {
                    cutL = cutout.safeInsetTop.toFloat()
                    cutR = cutout.safeInsetBottom.toFloat()
                }
                android.view.Surface.ROTATION_270 -> {
                    cutL = cutout.safeInsetBottom.toFloat()
                    cutR = cutout.safeInsetTop.toFloat()
                }
            }
        }
        val pillH = if (land) {
            28.5f * dens
        } else {
            val minPillH = 23f * dens
            val maxPillH = maxOf(minPillH, minOf(statusBarH * 0.85f, 30f * dens))
            (25f * dens).coerceIn(minPillH, maxPillH)
        }
        val textPx = pillH * (if (land) 0.59f else 0.606f)
        val pillY = maxOf(statusBarH * 0.5f, pillH * 0.5f + (if (land) 9f else 8f) * dens)
        val winH = maxOf((pillY * 1.35f + pillH * 1.0f).toInt(), (statusBarH * 1.8f).toInt(), (72f * dens).toInt())
        return Geo(
            holeX = screenW / 2f,
            holeY = pillY,
            holeR = pillH * 0.42f,
            gap = 0f,
            pillY = pillY,
            pillH = pillH,
            textPx = textPx,
            leftLimit = 0f,
            dropIn = true,
            screenW = screenW,
            winH = winH,
            safeLeft = maxOf(12f * dens, cutL + 8f * dens),
            safeRight = maxOf(12f * dens, cutR + 8f * dens),
        )
    }

    /** [still] keeps it out until hide() (for the editor). */
    /** [onTap]: the pill takes a tap (its window is then only as wide as the pill area) and stays out 6 s. */
    fun show(ctx: Context, text: String, ok: Boolean = true, still: Boolean = false, waited: Int = 0, onTap: (() -> Unit)? = null) {
        if (SetupActivity.isActive || PairingService.isRunning) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { show(ctx, text, ok, still, waited, onTap) }; return }
        if (SetupActivity.isActive || PairingService.isRunning) return
        current?.let {
            if (!it.removed) {
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
        if (a11y == null && waited < 20 && SolCubeA11y.isEnabled(app)) {
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
        val allowMulti = !narrow
        val g0 = computeGeo(vctx, wm)
        val dens = vctx.resources.displayMetrics.density.coerceAtLeast(1f)
        fun calcOffX(g: Geo) = if (narrow && !g.dropIn) (g.leftLimit - 20f * dens).coerceAtLeast(0f) else 0f
        fun calcW(g: Geo, off: Float) = when {
            !narrow -> WindowManager.LayoutParams.MATCH_PARENT
            g.dropIn -> minOf((g.screenW * 0.85f).toInt(), (440f * dens).toInt())
            else -> (g.holeX + g.holeR + 4f * dens - off).toInt().coerceAtLeast(1)
        }
        fun calcGravity(g: Geo) = android.view.Gravity.TOP or
            (if (g.dropIn) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START)

        val offX0 = calcOffX(g0)
        val lp = WindowManager.LayoutParams(
            calcW(g0, offX0), g0.winH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (narrow) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = calcGravity(g0)
            x = offX0.toInt()
            alpha = 1f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "SolCubeIsland"
        }
        val view = PillView(vctx, wm, g0, text, ok, still, offX0, allowMulti, onTap, { v, ng ->
            val newOff = calcOffX(ng)
            v.offX = newOff
            val nw = calcW(ng, newOff)
            val nh = ng.winH
            val ngGrav = calcGravity(ng)
            val nx = newOff.toInt()
            if (lp.width != nw || lp.height != nh || lp.gravity != ngGrav || lp.x != nx) {
                lp.width = nw
                lp.height = nh
                lp.gravity = ngGrav
                lp.x = nx
                if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, lp) }
            }
        }) { v ->
            if (v is PillView) v.removed = true
            runCatching { wm.removeView(v) }
            if (current === v) current = null
            val ps = pending.toList(); val pc = pendingCtx
            pending.clear(); pendingCtx = null
            // the first comes out, the rest queue up on it
            if (pc != null) ps.forEach { p -> show(pc, p.first, p.second, p.third) }
        }
        try { wm.addView(view, lp); current = view } catch (e: Exception) { Log.w(TAG, "island", e) }
    }

    fun hide() = main.post {
        pending.clear()
        pendingCtx = null
        current?.close()
    }

    private class PillView(
        ctx: Context,
        private val wm: WindowManager,
        private var g: Geo,
        private var text: String,
        private var ok: Boolean,
        private var still: Boolean,
        var offX: Float,   // the window's left edge in screen pixels (geometry is in screen pixels)
        private val allowMulti: Boolean,
        private val onTap: (() -> Unit)?,
        private val onGeoChanged: (PillView, Geo) -> Unit,
        private val onGone: (View) -> Unit,
    ) : View(ctx) {
        private class LandPill(
            val text: String,
            val ok: Boolean,
            var still: Boolean,
            var w: Float,
            var s: Float = 0f,
            var opening: Boolean = true,
            var closing: Boolean = false,
            var readableAt: Long = 0L,
            var curCx: Float = 0f,
            var fromCx: Float = 0f,
            var targetCx: Float = 0f,
            var anim: ValueAnimator? = null,
            var hold: Runnable? = null,
        )

        private val dens = resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Fonts.get(ctx, 560)
        }
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (g.pillH * 0.088f).coerceAtLeast(1.5f * dens)
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
        var removed = false

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

        // landscape multi-pill row: multiple concurrent pills arranged across the status bar
        private val landPills = ArrayList<LandPill>()
        private val landOverflow = ArrayDeque<Triple<String, Boolean, Boolean>>()
        private var rowAnim: ValueAnimator? = null

        private val isLandMulti get() = g.dropIn && allowMulti
        private val pad get() = g.pillH * 0.42f
        private val icon get() = g.pillH * 0.46f
        private val iconGap get() = g.pillH * 0.24f
        private val right get() = g.holeX - g.holeR - g.gap
        /** The widest the pill may get: up to the left margin in portrait (may cover the clock), capped per pill in landscape. */
        private val maxW get() = if (g.dropIn) minOf(width.toFloat().takeIf { it > 0f } ?: g.screenW.toFloat(), 440f * dens) * 0.9f else (right - g.leftLimit).coerceAtLeast(g.pillH)
        private val pillGap get() = g.pillH * 0.30f
        private val landViewW get() = width.toFloat().takeIf { it > 0f } ?: g.screenW.toFloat()
        private val landBarLeft get() = g.safeLeft.coerceAtMost(landViewW * 0.25f)
        private val landBarRight get() = (landViewW - g.safeRight).coerceAtLeast(landBarLeft + g.pillH * 2f)
        private val landAvailW get() = (landBarRight - landBarLeft).coerceAtLeast(g.pillH)
        private val landSingleMaxW get() = minOf(landAvailW, 420f * dens)

        init {
            if (isLandMulti) addLandPill(text, ok, still) else open()
        }

        private fun refreshGeo(w: Int = width, h: Int = height) {
            val ng = computeGeo(context, wm, w, h)
            if (ng == g) return
            val wasMulti = isLandMulti
            g = ng
            mark.strokeWidth = (g.pillH * 0.088f).coerceAtLeast(1.5f * dens)
            onGeoChanged(this, ng)
            val nowMulti = isLandMulti
            when {
                !wasMulti && nowMulti -> {
                    anim?.cancel(); anim = null
                    swapAnim?.cancel(); swapAnim = null
                    removeCallbacks(hold); removeCallbacks(next)
                    swap = 1f
                    val first = LandPill(
                        text = text, ok = ok, still = still,
                        w = fullWidth(text, landSingleMaxW),
                        s = s, opening = opening, closing = !opening, readableAt = readableAt,
                    )
                    landPills.add(first)
                    relayoutLandPills(animate = false)
                    when {
                        first.closing -> closeLandPill(first)
                        first.s < 0.99f -> openLandPill(first)
                        else -> { first.opening = false; scheduleLandHold(first) }
                    }
                    val queued = queue.toList()
                    queue.clear()
                    queued.forEach { q -> addLandPill(q.first, q.second, false) }
                }
                wasMulti && !nowMulti -> {
                    rowAnim?.cancel(); rowAnim = null
                    val snapshot = landPills.toList()
                    snapshot.forEach { lp ->
                        lp.anim?.cancel(); lp.anim = null
                        lp.hold?.let { removeCallbacks(it) }; lp.hold = null
                    }
                    val head = snapshot.firstOrNull { !it.closing } ?: snapshot.firstOrNull()
                    if (head != null) {
                        text = head.text; ok = head.ok; still = head.still
                        s = head.s; opening = !head.closing; readableAt = head.readableAt; swap = 1f
                        for (lp in snapshot) {
                            if (lp !== head && !lp.closing) queue.addLast(lp.text to lp.ok)
                        }
                        while (landOverflow.isNotEmpty()) {
                            val o = landOverflow.removeFirst()
                            queue.addLast(o.first to o.second)
                        }
                        landPills.clear()
                        if (opening) {
                            if (s < 0.99f) open() else afterText()
                        } else {
                            close()
                        }
                    } else {
                        landPills.clear()
                        landOverflow.clear()
                        if (!removed) { removed = true; onGone(this) }
                    }
                }
                wasMulti && nowMulti -> {
                    for (lp in landPills) lp.w = fullWidth(lp.text, landSingleMaxW)
                    relayoutLandPills(animate = false)
                }
            }
            invalidate()
        }

        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            refreshGeo()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && (w != oldw || h != oldh)) {
                val prevG = g
                refreshGeo(w, h)
                if (prevG == g && isLandMulti && (oldw > 0 || abs(w - g.screenW) > 1)) {
                    for (lp in landPills) lp.w = fullWidth(lp.text, landSingleMaxW)
                    relayoutLandPills(animate = false)
                }
            }
        }

        private fun shown(t: String = text, limitW: Float = maxW): String {
            txt.textSize = g.textPx
            val room = limitW - pad * 2 - icon - iconGap
            return android.text.TextUtils.ellipsize(t, android.text.TextPaint(txt), maxOf(0f, room), android.text.TextUtils.TruncateAt.END).toString()
        }

        private fun fullWidth(t: String = text, limitW: Float = maxW): Float {
            txt.textSize = g.textPx
            return min(limitW, maxOf(g.pillH, pad + icon + iconGap + txt.measureText(shown(t, limitW)) + pad))
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

        val closing get() = if (isLandMulti) {
            landPills.isNotEmpty() && landPills.all { it.closing } && landOverflow.isEmpty()
        } else {
            !opening
        }

        fun close() {
            if (isLandMulti) {
                landOverflow.clear()
                rowAnim?.cancel(); rowAnim = null
                if (landPills.isEmpty()) {
                    if (!removed) { removed = true; onGone(this) }
                    return
                }
                landPills.toList().forEach { lp -> if (!lp.closing) closeLandPill(lp) }
                return
            }
            removeCallbacks(hold); removeCallbacks(next); opening = false
            val from = s
            // no bounce on the way back: a smooth glide into the hole
            run(550, { f -> s = from * (1 - ease(f)) }) { if (!removed) { removed = true; onGone(this) } }
        }

        /** A new notice while this one is out (or coming out): queued, shown after the current one. */
        fun replace(newText: String, o: Boolean, st: Boolean) {
            if (isLandMulti) {
                addLandPill(newText, o, st)
                return
            }
            if (st) still = true
            val last = queue.lastOrNull() ?: (text to ok)
            if (last.first == newText && last.second == o) {   // the same again: just keep it out longer
                if (queue.isEmpty() && anim?.isRunning != true && swapAnim?.isRunning != true) afterText()
                return
            }
            queue.addLast(newText to o)
            if (anim?.isRunning != true && swapAnim?.isRunning != true) afterText()
        }

        private fun addLandPill(newText: String, newOk: Boolean, st: Boolean) {
            val existing = landPills.firstOrNull { !it.closing && it.text == newText && it.ok == newOk }
            if (existing != null) {
                if (st) existing.still = true
                if (!existing.opening) scheduleLandHold(existing)
                return
            }
            val queuedLast = landOverflow.lastOrNull()
            if (queuedLast != null && queuedLast.first == newText && queuedLast.second == newOk) return
            val w = fullWidth(newText, landSingleMaxW)
            val needed = landPills.sumOf { it.w.toDouble() }.toFloat() + pillGap * landPills.size + w
            if (landPills.isEmpty() || needed <= landAvailW) {
                val lp = LandPill(newText, newOk, st, w)
                landPills.add(lp)
                relayoutLandPills(animate = landPills.size > 1, newlyAdded = lp)
                openLandPill(lp)
            } else {
                landOverflow.addLast(Triple(newText, newOk, st))
                nudgeOldestForOverflow()
            }
        }

        private fun relayoutLandPills(animate: Boolean, newlyAdded: LandPill? = null) {
            if (landPills.isEmpty()) {
                rowAnim?.cancel(); rowAnim = null
                return
            }
            val totalW = landPills.sumOf { it.w.toDouble() }.toFloat() + pillGap * (landPills.size - 1)
            val center = (landBarLeft + landBarRight) / 2f
            val startX = (center - totalW / 2f).coerceIn(landBarLeft, maxOf(landBarLeft, landBarRight - totalW))
            var x = startX
            for (lp in landPills) {
                lp.targetCx = x + lp.w / 2f
                x += lp.w + pillGap
            }
            val allFresh = !animate || landPills.all { it === newlyAdded || it.s < 0.05f }
            if (allFresh) {
                rowAnim?.cancel(); rowAnim = null
                for (lp in landPills) {
                    lp.fromCx = lp.targetCx
                    lp.curCx = lp.targetCx
                }
                invalidate()
                return
            }
            for (lp in landPills) {
                if (lp !== newlyAdded) lp.fromCx = lp.curCx
            }
            if (newlyAdded != null) {
                val idx = landPills.indexOf(newlyAdded)
                val prev = if (idx > 0) landPills[idx - 1] else null
                newlyAdded.fromCx = if (prev != null) {
                    maxOf(newlyAdded.targetCx, prev.curCx + prev.w / 2f + pillGap + newlyAdded.w / 2f)
                } else {
                    newlyAdded.targetCx
                }
                newlyAdded.curCx = newlyAdded.fromCx
            }
            rowAnim?.cancel()
            rowAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { a ->
                    val k = ease(a.animatedValue as Float)
                    for (lp in landPills) lp.curCx = lp.fromCx + (lp.targetCx - lp.fromCx) * k
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (rowAnim === a) {
                            rowAnim = null
                            for (lp in landPills) { lp.fromCx = lp.targetCx; lp.curCx = lp.targetCx }
                            invalidate()
                        }
                    }
                })
                start()
            }
        }

        private fun openLandPill(lp: LandPill) {
            lp.hold?.let { removeCallbacks(it) }; lp.hold = null
            lp.anim?.cancel()
            lp.opening = true; lp.closing = false
            lp.readableAt = System.currentTimeMillis() + 300
            val from = lp.s
            lp.anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (springSec * 1000).toLong()
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { lp.s = from + (1 - from) * spring((it.animatedValue as Float) * springSec); invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (lp.anim === a && !removed) {
                            lp.anim = null
                            lp.s = 1f; lp.opening = false; invalidate()
                            scheduleLandHold(lp)
                        }
                    }
                })
                start()
            }
        }

        private fun scheduleLandHold(lp: LandPill) {
            lp.hold?.let { removeCallbacks(it) }; lp.hold = null
            if (lp.still || lp.closing || removed) return
            val head = landPills.firstOrNull { !it.closing && !it.still }
            val delay = if (landOverflow.isNotEmpty() && lp === head) {
                maxOf(0L, minShow - (System.currentTimeMillis() - lp.readableAt))
            } else {
                3500L
            }
            val r = Runnable { closeLandPill(lp) }
            lp.hold = r
            postDelayed(r, delay)
        }

        private fun nudgeOldestForOverflow() {
            val head = landPills.firstOrNull { !it.closing && !it.still } ?: return
            if (!head.opening) scheduleLandHold(head)
        }

        private fun closeLandPill(lp: LandPill) {
            lp.hold?.let { removeCallbacks(it) }; lp.hold = null
            lp.anim?.cancel()
            lp.opening = false; lp.closing = true
            val from = lp.s
            lp.anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 550
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { lp.s = from * (1 - ease(it.animatedValue as Float)); invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (lp.anim === a && !removed) {
                            lp.anim = null
                            landPills.remove(lp)
                            val spawned = drainLandOverflow()
                            if (landPills.isEmpty() && landOverflow.isEmpty()) {
                                removed = true
                                onGone(this@PillView)
                            } else if (!spawned) {
                                relayoutLandPills(animate = true)
                            }
                        }
                    }
                })
                start()
            }
        }

        private fun drainLandOverflow(): Boolean {
            var any = false
            while (landOverflow.isNotEmpty()) {
                val (t, o, st) = landOverflow.first()
                val w = fullWidth(t, landSingleMaxW)
                val needed = landPills.sumOf { it.w.toDouble() }.toFloat() + pillGap * landPills.size + w
                if (landPills.isEmpty() || needed <= landAvailW) {
                    landOverflow.removeFirst()
                    val lp = LandPill(t, o, st, w)
                    landPills.add(lp)
                    relayoutLandPills(animate = landPills.size > 1, newlyAdded = lp)
                    openLandPill(lp)
                    any = true
                } else {
                    nudgeOldestForOverflow()
                    break
                }
            }
            return any
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
            removed = true
            removeCallbacks(hold); removeCallbacks(next)
            anim?.cancel(); anim = null
            swapAnim?.cancel(); swapAnim = null
            rowAnim?.cancel(); rowAnim = null
            landPills.forEach { lp ->
                lp.hold?.let { removeCallbacks(it) }; lp.hold = null
                lp.anim?.cancel(); lp.anim = null
            }
        }

        private fun ease(x: Float) = x * x * (3 - 2 * x)

        override fun onDraw(c: Canvas) {
            if (isLandMulti) { drawLandMulti(c); return }
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

        /** Landscape multi-pill row: independent non-overlapping capsules centered across the status bar. */
        private fun drawLandMulti(c: Canvas) {
            for (lp in landPills) {
                if (lp.s <= 0.001f) continue
                val p = lp.s.coerceAtLeast(0f)
                val h = g.pillH * (0.6f + 0.4f * (p / 0.6f).coerceIn(0f, 1f))
                val r = h / 2f
                val by = -g.pillH + (g.pillY + g.pillH) * p
                val w = h + (lp.w - h) * ((p - 0.08f) / 0.72f).coerceIn(0f, 1f)
                rect.set(lp.curCx - w / 2f, by - r, lp.curCx + w / 2f, by + r)
                c.drawRoundRect(rect, r, r, bg)
                drawRim(c, p, r, true)
                val ta = ((p - 0.62f) / 0.3f).coerceIn(0f, 1f)
                if (ta > 0f) {
                    c.save()
                    c.clipRect(rect)
                    content(c, lp.text, lp.ok, ta, by, landSingleMaxW)
                    c.restore()
                }
            }
        }

        private fun drawRim(c: Canvas, p: Float, r: Float, showRim: Boolean) {
            val ra = ((p - 0.62f) / 0.3f).coerceIn(0f, 1f)
            if (showRim && ra > 0f) {
                rim.color = Color.argb((0x33 * ra).toInt(), 255, 255, 255)
                val hw = rim.strokeWidth / 2
                rimRect.set(rect.left + hw, rect.top + hw, rect.right - hw, rect.bottom - hw)
                c.drawRoundRect(rimRect, r - hw, r - hw, rim)
            }
        }

        private fun rimAndText(c: Canvas, p: Float, r: Float, by: Float, showRim: Boolean) {
            drawRim(c, p, r, showRim)

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

        private fun content(c: Canvas, t: String, good: Boolean, alpha: Float, by: Float, limitW: Float = maxW) {
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
            c.drawText(shown(t, limitW), left + ic + iconGap, by - (fm.ascent + fm.descent) / 2, txt)
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
 * Authenticated localhost server (127.0.0.1:47322) that lets SolCube's shell-side services
 * display status pills directly when ROM broadcast restrictions (e.g. OriginOS install_broadcast_control)
 * drop shell `am broadcast` intents.
 */
object NoticeHost {
    const val PORT = 47322
    private const val TAG = "SolCube"
    @Volatile private var running = false
    @Volatile private var server: java.net.ServerSocket? = null
    @Volatile private var appCtx: Context? = null
    private var gen = 0

    @Synchronized fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (running) return
        running = true
        val myGen = ++gen
        Thread({ serve(myGen) }, "notice-server").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        running = false
        gen++
        runCatching { server?.close() }
        server = null
    }

    private fun serve(myGen: Int) {
        while (running && gen == myGen) {
            try {
                val ss = java.net.ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), PORT), 8)
                synchronized(this) {
                    if (!running || gen != myGen) { runCatching { ss.close() }; return }
                    server = ss
                }
                Log.i(TAG, "notice host listening on $PORT")
                while (running && gen == myGen && !ss.isClosed) {
                    val s = ss.accept()
                    Thread { runCatching { handle(s) } }.start()
                }
            } catch (e: Exception) {
                if (running && gen == myGen) {
                    Log.w(TAG, "notice server: ${e.message}")
                    runCatching { Thread.sleep(3000) }
                }
            }
        }
    }

    private fun handle(s: java.net.Socket) {
        val ctx = appCtx ?: return
        s.use { sock ->
            sock.soTimeout = 6000
            sock.tcpNoDelay = true
            val reader = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val out = sock.getOutputStream()
            val secret = Ipc.secret(ctx)
            val ns = Ipc.nonce()
            out.write("hello $ns\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val auth = reader.readLine()?.trim()?.split(" ") ?: return
            if (auth.size != 3 || auth[0] != "auth" || !Ipc.same(auth[2], Ipc.mac(secret, "client|$ns|${auth[1]}"))) {
                Log.w(TAG, "notice port: rejected unauthenticated connection")
                return
            }
            out.write("auth ${Ipc.mac(secret, "server|${auth[1]}|$ns")}\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val line = reader.readLine() ?: return
            val o = org.json.JSONObject(line)
            val reply = when (o.optString("cmd")) {
                "broadcast" -> {
                    val action = o.optString("action")
                    if (action == "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION") {
                        val comp = android.content.ComponentName.unflattenFromString(o.optString("component"))
                        if (comp != null) {
                            ctx.sendBroadcast(
                                Intent(action)
                                    .setComponent(comp)
                                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                                    .putExtra("android.media.extra.AUDIO_SESSION", o.optInt("audioSession", 0))
                                    .putExtra("android.media.extra.PACKAGE_NAME", o.optString("pkg"))
                            )
                        }
                    }
                    "ok"
                }
                "get_feature" -> "ok:${TestReceiver.getFeature(ctx, o.optString("feature"))}"
                "set_feature" -> {
                    val value = if (o.has("value") && !o.isNull("value")) o.optString("value") else null
                    val on = if (value != null) value == "on" else o.optBoolean("on", false)
                    TestReceiver.setFeature(ctx, o.optString("feature"), value, on)
                    "ok"
                }
                "trigger_cts" -> "ok:${TestReceiver.triggerCts(ctx)}"
                "rt_run" -> {
                    val id = o.optString("id")
                    if (id.isNotEmpty()) Thread { runCatching { Shell.command(ctx, Shell.Kind.ROUTINES, "run $id") } }.start()
                    "ok"
                }
                "rt_trigger" -> {
                    val trig = o.optString("trigger")
                    val matched = trig.isNotEmpty() && Routines.trigger(ctx, trig)
                    "ok:${if (matched) "matched" else "none"}"
                }
                else -> {
                    val text = o.optString("text")
                    val ok = o.optBoolean("ok", true)
                    if (text.isNotEmpty()) Island.show(ctx, text, ok)
                    "ok"
                }
            }
            out.write("$reply\n".toByteArray(Charsets.UTF_8))
            out.flush()
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
