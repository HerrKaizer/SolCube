package dev.suite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Setup step 3: a small looping picture of the part people miss. Settings shows the pairing code,
 * you pull down the notification shade, tap "Enter code" on SolCube's notification and type the 6 digits.
 * Drawn in code (no assets) from the setup palette; still frame when animations are off.
 */
class PairHintView(
    ctx: Context,
    private val p: Look.Palette,
    private val font: Typeface,
    private val fontBold: Typeface,
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var t = 0.86f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()
    private val enterCode = I18n.t("Enter code")
    private val code = "482913"

    init { contentDescription = I18n.t("Pull down notifications, tap Enter code and type the 6 digits") }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(176f).toInt())
    }

    private fun seg(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun ease(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun withA(c: Int, f: Float) =
        Color.argb((Color.alpha(c) * f).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        r.set(x, y, x + w, y + h)
        c.drawRoundRect(r, h / 2, h / 2, fill)
    }

    override fun onDraw(c: Canvas) {
        val pw = dp(156f)
        val ph = dp(168f)
        val left = (width - pw) / 2f
        val top = dp(4f)

        // phone
        fill.color = withA(p.fg, 0.05f)
        r.set(left, top, left + pw, top + ph)
        c.drawRoundRect(r, dp(24f), dp(24f), fill)
        line.color = withA(p.fg, 0.22f)
        line.strokeWidth = dp(1.5f)
        c.drawRoundRect(r, dp(24f), dp(24f), line)

        val sL = left + dp(7f); val sT = top + dp(7f); val sR = left + pw - dp(7f); val sB = top + ph - dp(7f)
        val sw = sR - sL
        c.save()
        clip.reset()
        r.set(sL, sT, sR, sB)
        clip.addRoundRect(r, dp(18f), dp(18f), Path.Direction.CW)
        c.clipPath(clip)
        val fade = 1f - seg(0.94f, 1f)
        val layer = c.saveLayerAlpha(sL, sT, sR, sB, (255 * fade).toInt())

        // Settings → Wireless debugging behind everything
        bar(c, sL + dp(12f), sT + dp(16f), sw * 0.55f, dp(6f), withA(p.fg, 0.30f))
        for (i in 0 until 6) bar(c, sL + dp(12f), sT + dp(34f) + i * dp(16f), sw * (if (i % 2 == 0) 0.74f else 0.5f), dp(5f), withA(p.fg, 0.12f))

        // pairing dialog with the code
        val dk = ease(seg(0.02f, 0.10f))
        if (dk > 0f) {
            val cx = (sL + sR) / 2f
            val cy = sT + (sB - sT) * 0.64f
            val hw = sw * 0.40f * (0.9f + 0.1f * dk)
            val hh = dp(25f) * (0.9f + 0.1f * dk)
            fill.color = withA(p.panel, dk)
            r.set(cx - hw, cy - hh, cx + hw, cy + hh)
            c.drawRoundRect(r, dp(10f), dp(10f), fill)
            line.color = withA(p.fg, 0.16f * dk)
            line.strokeWidth = dp(1f)
            c.drawRoundRect(r, dp(10f), dp(10f), line)
            bar(c, cx - hw + dp(10f), cy - hh + dp(9f), hw * 0.9f, dp(4f), withA(p.fg, 0.22f * dk))
            text.typeface = fontBold
            text.textSize = dp(15f)
            text.letterSpacing = 0.12f
            text.textAlign = Paint.Align.CENTER
            text.color = withA(p.fg, dk)
            c.drawText("482 913", cx, cy + dp(13f), text)
        }

        // notification shade + SolCube's notification
        val sk = ease(seg(0.18f, 0.34f))
        if (sk > 0f) {
            fill.color = withA(Color.BLACK, 0.42f * sk)
            c.drawRect(sL, sT, sR, sB, fill)
            val ex = ease(seg(0.48f, 0.54f))
            val cardL = sL + dp(7f); val cardR = sR - dp(7f)
            val cardT = sT + dp(16f) - (1f - sk) * dp(80f)
            val cardH = dp(42f) + dp(24f) * ex
            fill.color = withA(p.panel, sk)
            r.set(cardL, cardT, cardR, cardT + cardH)
            c.drawRoundRect(r, dp(11f), dp(11f), fill)
            fill.color = withA(p.accent, sk)
            c.drawCircle(cardL + dp(11f), cardT + dp(11f), dp(4f), fill)
            text.typeface = fontBold
            text.textSize = dp(9f)
            text.letterSpacing = 0f
            text.textAlign = Paint.Align.LEFT
            text.color = withA(p.fg, sk)
            c.drawText("SolCube", cardL + dp(20f), cardT + dp(14.5f), text)
            // "Enter code" action
            text.typeface = fontBold
            text.textSize = dp(8.5f)
            val pillW = text.measureText(enterCode) + dp(14f)
            val pillR = cardR - dp(8f)
            r.set(pillR - pillW, cardT + dp(22f), pillR, cardT + dp(36f))
            fill.color = withA(p.accent, sk)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            text.color = withA(p.onAccent, sk)
            text.textAlign = Paint.Align.CENTER
            c.drawText(enterCode, r.centerX(), r.centerY() + dp(3f), text)
            bar(c, cardL + dp(10f), cardT + dp(27f), (pillR - pillW) - cardL - dp(22f), dp(4f), withA(p.fg, 0.18f * sk))

            // reply field with the digits being typed
            if (ex > 0f) {
                r.set(cardL + dp(8f), cardT + dp(42f), cardR - dp(8f), cardT + dp(60f))
                line.color = withA(p.accent, 0.7f * ex)
                line.strokeWidth = dp(1.2f)
                c.drawRoundRect(r, dp(8f), dp(8f), line)
                val n = (seg(0.55f, 0.80f) * 6f).toInt().coerceIn(0, 6)
                text.typeface = fontBold
                text.textSize = dp(10.5f)
                text.letterSpacing = 0.25f
                text.textAlign = Paint.Align.LEFT
                text.color = withA(p.fg, ex)
                c.drawText(code.take(n), r.left + dp(8f), r.centerY() + dp(3.8f), text)
                val ok = ease(seg(0.82f, 0.88f))
                if (ok > 0f) {
                    fill.color = withA(p.ok, ok)
                    val ccx = r.right - dp(10f); val ccy = r.centerY()
                    c.drawCircle(ccx, ccy, dp(6f) * ok, fill)
                    line.color = withA(Color.WHITE, ok)
                    line.strokeWidth = dp(1.6f)
                    c.drawLine(ccx - dp(3f), ccy, ccx - dp(0.8f), ccy + dp(2.4f), line)
                    c.drawLine(ccx - dp(0.8f), ccy + dp(2.4f), ccx + dp(3.2f), ccy - dp(2.4f), line)
                }
            }

            // finger: pulls the shade down, then taps "Enter code"
            val fa = seg(0.14f, 0.18f) * (1f - seg(0.50f, 0.55f))
            if (fa > 0f) {
                val pull = ease(seg(0.18f, 0.34f))
                val move = ease(seg(0.36f, 0.44f))
                val fx0 = (sL + sR) / 2f
                val fy0 = lerp(sT + dp(6f), sT + dp(84f), pull)
                val fx = lerp(fx0, pillR - pillW / 2f, move)
                val fy = lerp(fy0, cardT + dp(29f), move)
                val press = seg(0.44f, 0.47f) * (1f - seg(0.47f, 0.50f))
                val rip = seg(0.46f, 0.54f)
                if (rip > 0f && rip < 1f) {
                    line.color = withA(p.fg, 0.6f * (1f - rip))
                    line.strokeWidth = dp(1.5f)
                    c.drawCircle(fx, fy, dp(7f) + dp(14f) * rip, line)
                }
                fill.color = withA(p.fg, 0.55f * fa)
                c.drawCircle(fx, fy, dp(8f) * (1f - 0.25f * press), fill)
                line.color = withA(p.fg, 0.9f * fa)
                line.strokeWidth = dp(1.5f)
                c.drawCircle(fx, fy, dp(8f) * (1f - 0.25f * press), line)
            }
        }
        c.restoreToCount(layer)
        c.restore()
    }
}
