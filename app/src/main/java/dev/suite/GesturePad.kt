package dev.suite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A box to draw a gesture in (test pad and recorder). Shows every finger's trail. What gets
 * recognised is the "pen": the point between the fingers that are down (one finger: that finger),
 * kept continuous when a finger lands or lifts, the same way the live listener does it. A stroke
 * lasts until every finger is up; the drawing ends [gapMs] later, so a cross can be two strokes.
 * Coordinates are view pixels.
 */
class GesturePad(ctx: Context, private val ink: Int, private val frame: Int, private val hintColor: Int) : View(ctx) {
    var hint = "Draw here"
        set(v) { field = v; invalidate() }
    /** strokes (pen path) and the most fingers that were down at once */
    var onDrawing: ((List<FloatArray>, Int) -> Unit)? = null
    var gapMs = 600L

    private val d = resources.displayMetrics.density
    private val trails = mutableListOf<ArrayList<Float>>()          // every finger's path, for drawing
    private val live = HashMap<Int, ArrayList<Float>>()             // pointer id → its trail
    private val strokes = mutableListOf<FloatArray>()               // pen strokes, for recognising
    private var pen = ArrayList<Float>()
    private var penX = 0f; private var penY = 0f; private var offX = 0f; private var offY = 0f
    private var lastIds = emptySet<Int>()
    private var fingers = 0
    private var done = false
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = ink
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = frame }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hintColor; textSize = 15f * d; textAlign = Paint.Align.CENTER }
    private val finish = Runnable {
        done = true
        invalidate()
        if (strokes.isNotEmpty()) onDrawing?.invoke(strokes.map { it.copyOf() }, fingers)
    }

    fun clear() {
        removeCallbacks(finish)
        trails.clear(); live.clear(); strokes.clear(); pen = ArrayList(); lastIds = emptySet(); fingers = 0; done = false
        invalidate()
    }

    private fun centroid(e: MotionEvent, skip: Int = -1): FloatArray {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        return if (n == 0) floatArrayOf(penX - offX, penY - offY) else floatArrayOf(sx / n, sy / n)
    }

    private fun penTo(c: FloatArray) {
        penX = c[0] + offX; penY = c[1] + offY
        pen.add(penX); pen.add(penY)
    }

    /** The set of fingers changed: carry the pen on from where it was, no jump. */
    private fun rebase(c: FloatArray) { offX = penX - c[0]; offY = penY - c[1] }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(finish)
                if (done) { trails.clear(); strokes.clear(); fingers = 0; done = false }
                live.clear()
                val t = arrayListOf(e.x, e.y); trails += t; live[e.getPointerId(0)] = t
                pen = ArrayList(); offX = 0f; offY = 0f
                penX = e.x; penY = e.y; pen.add(penX); pen.add(penY)
                fingers = maxOf(fingers, 1)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val t = arrayListOf(e.getX(i), e.getY(i)); trails += t; live[e.getPointerId(i)] = t
                fingers = maxOf(fingers, e.pointerCount)
                rebase(centroid(e))
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    for (i in 0 until e.pointerCount) live[e.getPointerId(i)]?.apply { add(e.getHistoricalX(i, h)); add(e.getHistoricalY(i, h)) }
                    var sx = 0f; var sy = 0f
                    for (i in 0 until e.pointerCount) { sx += e.getHistoricalX(i, h); sy += e.getHistoricalY(i, h) }
                    penTo(floatArrayOf(sx / e.pointerCount, sy / e.pointerCount))
                }
                for (i in 0 until e.pointerCount) live[e.getPointerId(i)]?.apply { add(e.getX(i)); add(e.getY(i)) }
                penTo(centroid(e))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                live.remove(e.getPointerId(e.actionIndex))
                rebase(centroid(e, skip = e.actionIndex))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                live.clear()
                if (pen.size >= 4) strokes += pen.toFloatArray()
                pen = ArrayList()
                postDelayed(finish, gapMs)
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(framePaint.strokeWidth, framePaint.strokeWidth, width - framePaint.strokeWidth, height - framePaint.strokeWidth)
        c.drawRoundRect(r, 18f * d, 18f * d, framePaint)
        if (trails.isEmpty()) {
            c.drawText(hint, width / 2f, height / 2f + hintPaint.textSize / 3, hintPaint)
            return
        }
        inkPaint.alpha = if (done) 140 else 255
        for (s in trails) {
            if (s.size < 4) continue
            val p = Path()
            p.moveTo(s[0], s[1])
            var i = 2
            while (i + 1 < s.size) { p.lineTo(s[i], s[i + 1]); i += 2 }
            c.drawPath(p, inkPaint)
        }
    }

    override fun onDetachedFromWindow() { removeCallbacks(finish); super.onDetachedFromWindow() }
}
