package dev.suite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.suite.shell.Gestures
import kotlin.math.hypot

/**
 * Compact vector preview of a built-in swipe/shape/digit or user-recorded custom gesture.
 * Built-ins use hand-crafted geometric paths; custom gestures use quadratic midpoint smoothing.
 */
class GestureThumbView(
    ctx: Context,
    gestureId: String,
    strokeColor: Int,
    dotColor: Int,
) : View(ctx) {

    private val d = resources.displayMetrics.density
    private var gid = gestureId
    private var tplPts: FloatArray? = null

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.6f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.15f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val arcRect = RectF()

    init {
        applyColors(strokeColor, dotColor)
        loadTemplate()
    }

    private fun applyColors(strokeColor: Int, dotColor: Int) {
        strokePaint.color = strokeColor
        glowPaint.color = Color.argb(38, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
        dotPaint.color = dotColor
        dotHaloPaint.color = Color.argb(68, Color.red(dotColor), Color.green(dotColor), Color.blue(dotColor))
    }

    fun setColors(strokeColor: Int, dotColor: Int) {
        if (strokePaint.color == strokeColor && dotPaint.color == dotColor) return
        applyColors(strokeColor, dotColor)
        invalidate()
    }

    private fun loadTemplate() {
        tplPts = if (Gestures.builtinName(gid) != null) {
            null
        } else {
            GestureStore.templates(context).firstOrNull { it.id == gid }?.pts
        }
    }

    private fun roundedPoly(
        points: List<Pair<Float, Float>>,
        closed: Boolean,
        cornerFrac: Float,
        mx: (Float) -> Float,
        my: (Float) -> Float,
    ): Pair<Float, Float> {
        fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) =
            hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()).toFloat().coerceAtLeast(1e-4f)

        fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, t: Float): Pair<Float, Float> =
            (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)

        if (!closed) {
            val first = points.first()
            path.moveTo(mx(first.first), my(first.second))
            for (i in 1 until points.size - 1) {
                val prev = points[i - 1]
                val cur = points[i]
                val next = points[i + 1]
                val d1 = minOf(cornerFrac, dist(cur, prev) * 0.35f) / dist(cur, prev)
                val d2 = minOf(cornerFrac, dist(cur, next) * 0.35f) / dist(cur, next)
                val enter = lerp(cur, prev, d1)
                val exit = lerp(cur, next, d2)
                path.lineTo(mx(enter.first), my(enter.second))
                path.quadTo(mx(cur.first), my(cur.second), mx(exit.first), my(exit.second))
            }
            val last = points.last()
            path.lineTo(mx(last.first), my(last.second))
            return mx(first.first) to my(first.second)
        }

        val count = points.size
        val startEnter = lerp(points[0], points[1], minOf(cornerFrac, dist(points[0], points[1]) * 0.35f) / dist(points[0], points[1]))
        path.moveTo(mx(startEnter.first), my(startEnter.second))
        for (i in 1..count) {
            val prev = points[(i - 1) % count]
            val cur = points[i % count]
            val next = points[(i + 1) % count]
            val d1 = minOf(cornerFrac, dist(cur, prev) * 0.35f) / dist(cur, prev)
            val d2 = minOf(cornerFrac, dist(cur, next) * 0.35f) / dist(cur, next)
            val enter = lerp(cur, prev, d1)
            val exit = lerp(cur, next, d2)
            path.lineTo(mx(enter.first), my(enter.second))
            path.quadTo(mx(cur.first), my(cur.second), mx(exit.first), my(exit.second))
        }
        path.close()
        return mx(points[0].first) to my(points[0].second)
    }

    private fun buildBuiltin(left: Float, top: Float, size: Float): Pair<Float, Float>? {
        fun mx(u: Float) = left + u * size
        fun my(v: Float) = top + v * size

        path.reset()
        return when (gid) {
            "swipe_up" -> {
                path.moveTo(mx(0.50f), my(0.90f))
                path.lineTo(mx(0.50f), my(0.12f))
                path.moveTo(mx(0.24f), my(0.38f))
                path.lineTo(mx(0.50f), my(0.12f))
                path.lineTo(mx(0.76f), my(0.38f))
                mx(0.50f) to my(0.90f)
            }
            "swipe_down" -> {
                path.moveTo(mx(0.50f), my(0.10f))
                path.lineTo(mx(0.50f), my(0.88f))
                path.moveTo(mx(0.24f), my(0.62f))
                path.lineTo(mx(0.50f), my(0.88f))
                path.lineTo(mx(0.76f), my(0.62f))
                mx(0.50f) to my(0.10f)
            }
            "swipe_left" -> {
                path.moveTo(mx(0.90f), my(0.50f))
                path.lineTo(mx(0.12f), my(0.50f))
                path.moveTo(mx(0.38f), my(0.24f))
                path.lineTo(mx(0.12f), my(0.50f))
                path.lineTo(mx(0.38f), my(0.76f))
                mx(0.90f) to my(0.50f)
            }
            "swipe_right" -> {
                path.moveTo(mx(0.10f), my(0.50f))
                path.lineTo(mx(0.88f), my(0.50f))
                path.moveTo(mx(0.62f), my(0.24f))
                path.lineTo(mx(0.88f), my(0.50f))
                path.lineTo(mx(0.62f), my(0.76f))
                mx(0.10f) to my(0.50f)
            }
            "circle" -> {
                arcRect.set(mx(0.13f), my(0.13f), mx(0.87f), my(0.87f))
                path.addOval(arcRect, Path.Direction.CW)
                mx(0.50f) to my(0.13f)
            }
            "square" -> {
                arcRect.set(mx(0.15f), my(0.15f), mx(0.85f), my(0.85f))
                val r = size * 0.14f
                path.addRoundRect(arcRect, r, r, Path.Direction.CW)
                mx(0.27f) to my(0.15f)
            }
            "triangle" -> roundedPoly(
                listOf(0.50f to 0.14f, 0.87f to 0.84f, 0.13f to 0.84f),
                closed = true,
                cornerFrac = 0.08f,
                mx = ::mx,
                my = ::my,
            )
            "x" -> {
                path.moveTo(mx(0.18f), my(0.18f))
                path.lineTo(mx(0.82f), my(0.82f))
                path.moveTo(mx(0.82f), my(0.18f))
                path.lineTo(mx(0.18f), my(0.82f))
                mx(0.18f) to my(0.18f)
            }
            "check" -> roundedPoly(
                listOf(0.13f to 0.54f, 0.39f to 0.79f, 0.87f to 0.22f),
                closed = false,
                cornerFrac = 0.06f,
                mx = ::mx,
                my = ::my,
            )
            "caret_up" -> roundedPoly(
                listOf(0.14f to 0.70f, 0.50f to 0.26f, 0.86f to 0.70f),
                closed = false,
                cornerFrac = 0.06f,
                mx = ::mx,
                my = ::my,
            )
            "caret_down" -> roundedPoly(
                listOf(0.14f to 0.30f, 0.50f to 0.74f, 0.86f to 0.30f),
                closed = false,
                cornerFrac = 0.06f,
                mx = ::mx,
                my = ::my,
            )
            "lt" -> roundedPoly(
                listOf(0.70f to 0.14f, 0.26f to 0.50f, 0.70f to 0.86f),
                closed = false,
                cornerFrac = 0.06f,
                mx = ::mx,
                my = ::my,
            )
            "gt" -> roundedPoly(
                listOf(0.30f to 0.14f, 0.74f to 0.50f, 0.30f to 0.86f),
                closed = false,
                cornerFrac = 0.06f,
                mx = ::mx,
                my = ::my,
            )
            "d2" -> {
                path.moveTo(mx(0.25f), my(0.30f))
                path.cubicTo(mx(0.27f), my(0.08f), mx(0.76f), my(0.08f), mx(0.75f), my(0.33f))
                path.cubicTo(mx(0.74f), my(0.50f), mx(0.40f), my(0.66f), mx(0.23f), my(0.86f))
                path.lineTo(mx(0.79f), my(0.86f))
                mx(0.25f) to my(0.30f)
            }
            "d3" -> {
                path.moveTo(mx(0.26f), my(0.23f))
                path.cubicTo(mx(0.36f), my(0.08f), mx(0.74f), my(0.10f), mx(0.72f), my(0.31f))
                path.cubicTo(mx(0.70f), my(0.45f), mx(0.54f), my(0.48f), mx(0.42f), my(0.48f))
                path.cubicTo(mx(0.64f), my(0.48f), mx(0.78f), my(0.58f), mx(0.74f), my(0.74f))
                path.cubicTo(mx(0.70f), my(0.90f), mx(0.35f), my(0.91f), mx(0.24f), my(0.76f))
                mx(0.26f) to my(0.23f)
            }
            "d4" -> {
                path.moveTo(mx(0.63f), my(0.14f))
                path.lineTo(mx(0.21f), my(0.62f))
                path.lineTo(mx(0.81f), my(0.62f))
                path.moveTo(mx(0.63f), my(0.14f))
                path.lineTo(mx(0.63f), my(0.86f))
                mx(0.63f) to my(0.14f)
            }
            "d5" -> {
                path.moveTo(mx(0.73f), my(0.15f))
                path.lineTo(mx(0.34f), my(0.15f))
                path.lineTo(mx(0.30f), my(0.46f))
                path.cubicTo(mx(0.46f), my(0.38f), mx(0.77f), my(0.44f), mx(0.76f), my(0.66f))
                path.cubicTo(mx(0.75f), my(0.86f), mx(0.41f), my(0.91f), mx(0.24f), my(0.76f))
                mx(0.73f) to my(0.15f)
            }
            "d6" -> {
                path.moveTo(mx(0.66f), my(0.14f))
                path.cubicTo(mx(0.34f), my(0.17f), mx(0.22f), my(0.44f), mx(0.24f), my(0.67f))
                path.cubicTo(mx(0.26f), my(0.89f), mx(0.76f), my(0.89f), mx(0.76f), my(0.66f))
                path.cubicTo(mx(0.76f), my(0.45f), mx(0.34f), my(0.44f), mx(0.24f), my(0.64f))
                mx(0.66f) to my(0.14f)
            }
            "d7" -> {
                path.moveTo(mx(0.22f), my(0.16f))
                path.lineTo(mx(0.78f), my(0.16f))
                path.quadTo(mx(0.56f), my(0.48f), mx(0.36f), my(0.86f))
                mx(0.22f) to my(0.16f)
            }
            "d8" -> {
                path.moveTo(mx(0.50f), my(0.48f))
                path.cubicTo(mx(0.25f), my(0.42f), mx(0.27f), my(0.12f), mx(0.50f), my(0.12f))
                path.cubicTo(mx(0.73f), my(0.12f), mx(0.75f), my(0.42f), mx(0.50f), my(0.48f))
                path.cubicTo(mx(0.21f), my(0.54f), mx(0.23f), my(0.88f), mx(0.50f), my(0.88f))
                path.cubicTo(mx(0.77f), my(0.88f), mx(0.79f), my(0.54f), mx(0.50f), my(0.48f))
                mx(0.50f) to my(0.48f)
            }
            "d9" -> {
                path.moveTo(mx(0.74f), my(0.34f))
                path.cubicTo(mx(0.74f), my(0.11f), mx(0.24f), my(0.11f), mx(0.24f), my(0.34f))
                path.cubicTo(mx(0.24f), my(0.55f), mx(0.72f), my(0.55f), mx(0.74f), my(0.34f))
                path.cubicTo(mx(0.76f), my(0.62f), mx(0.64f), my(0.86f), mx(0.31f), my(0.86f))
                mx(0.74f) to my(0.34f)
            }
            else -> null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = 4.5f * d
        val boxW = (w - pad * 2f).coerceAtLeast(1f)
        val boxH = (h - pad * 2f).coerceAtLeast(1f)
        val size = minOf(boxW, boxH)
        val left = (w - size) * 0.5f
        val top = (h - size) * 0.5f

        val builtinStart = buildBuiltin(left, top, size)
        if (builtinStart != null) {
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, strokePaint)
            canvas.drawCircle(builtinStart.first, builtinStart.second, 3.6f * d, dotHaloPaint)
            canvas.drawCircle(builtinStart.first, builtinStart.second, 2.0f * d, dotPaint)
            return
        }

        val pts = tplPts ?: return
        val n = pts.size / 2
        if (n < 2) return

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val spanX = (maxX - minX).coerceAtLeast(0.05f)
        val spanY = (maxY - minY).coerceAtLeast(0.05f)
        val scale = minOf(boxW / spanX, boxH / spanY) * 0.88f
        val midX = (minX + maxX) * 0.5f
        val midY = (minY + maxY) * 0.5f
        val cx = w * 0.5f
        val cy = h * 0.5f

        fun tx(nx: Float) = cx + (nx - midX) * scale
        fun ty(ny: Float) = cy + (ny - midY) * scale

        path.reset()
        var prevNx = pts[0]
        var prevNy = pts[1]
        var prevPx = tx(prevNx)
        var prevPy = ty(prevNy)
        val startPx = prevPx
        val startPy = prevPy
        path.moveTo(startPx, startPy)
        var strokeMoved = true
        for (i in 1 until n) {
            val nx = pts[i * 2]
            val ny = pts[i * 2 + 1]
            val jump = hypot((nx - prevNx).toDouble(), (ny - prevNy).toDouble()).toFloat()
            val px = tx(nx)
            val py = ty(ny)
            if (jump > 0.45f) {
                if (!strokeMoved) path.lineTo(prevPx, prevPy)
                path.moveTo(px, py)
                strokeMoved = true
            } else {
                if (strokeMoved) {
                    path.lineTo((prevPx + px) * 0.5f, (prevPy + py) * 0.5f)
                    strokeMoved = false
                } else {
                    path.quadTo(prevPx, prevPy, (prevPx + px) * 0.5f, (prevPy + py) * 0.5f)
                }
            }
            prevNx = nx
            prevNy = ny
            prevPx = px
            prevPy = py
        }
        if (!strokeMoved) path.lineTo(prevPx, prevPy)

        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, strokePaint)
        canvas.drawCircle(startPx, startPy, 3.6f * d, dotHaloPaint)
        canvas.drawCircle(startPx, startPy, 2.0f * d, dotPaint)
    }
}
