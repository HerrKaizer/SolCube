package dev.suite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/**
 * Fixed backdrop of large, soft colour glows. The translucent cards scroll over
 * it, which is what gives them the frosted-glass look.
 */
class GlassBackdrop(context: Context, private val base: Int, private val glows: List<Int>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // (x, y, radius) as fractions of width/height/width
    private val spots = listOf(
        floatArrayOf(0.10f, 0.06f, 0.85f),
        floatArrayOf(1.00f, 0.32f, 0.80f),
        floatArrayOf(0.05f, 0.68f, 0.90f),
        floatArrayOf(0.90f, 0.98f, 0.85f),
    )

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(base)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        spots.forEachIndexed { i, s ->
            val color = glows[i % glows.size]
            val r = s[2] * w
            paint.shader = RadialGradient(
                s[0] * w, s[1] * h, r,
                intArrayOf(color, Color.argb(Color.alpha(color) / 3, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(s[0] * w, s[1] * h, r, paint)
        }
    }
}
