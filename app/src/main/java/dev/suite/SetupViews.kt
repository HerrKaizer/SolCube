package dev.suite

import android.content.Context
import android.widget.FrameLayout

/**
 * A stage whose children are measured at their natural height and, if that is taller than the
 * stage, first loses its [OPTIONAL]-tagged parts and then is scaled down to fit instead of spilling over the views around it (longer translations
 * wrap headlines onto extra lines). Children are centred; translationX / alpha animations still work.
 */
class FitStage(ctx: Context, private val minScale: Float = 0.6f) : FrameLayout(ctx) {
    companion object {
        /** Tag for views a slide can do without (descriptions): hidden before anything is scaled. */
        const val OPTIONAL = "fit-optional"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth - paddingLeft - paddingRight
        val avail = measuredHeight - paddingTop - paddingBottom
        val wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(wSpec, hSpec)
            if (avail > 0 && c.measuredHeight > avail && hideOptional(c)) c.measure(wSpec, hSpec)
        }
    }

    private fun hideOptional(v: android.view.View): Boolean {
        if (v.tag == OPTIONAL && v.visibility != GONE) { v.visibility = GONE; return true }
        var any = false
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) any = hideOptional(v.getChildAt(i)) or any
        return any
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = (b - t - paddingTop - paddingBottom).coerceAtLeast(1)
        val w = r - l
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val ch = c.measuredHeight
            val top = paddingTop + (avail - ch) / 2
            c.layout(paddingLeft, top, paddingLeft + c.measuredWidth, top + ch)
            val s = if (ch > avail) (avail.toFloat() / ch).coerceAtLeast(minScale) else 1f
            c.pivotX = w / 2f
            c.pivotY = ch / 2f
            c.scaleX = s
            c.scaleY = s
        }
    }
}

/** Frame that is always [ratio] (height / width) of its content width, so artwork is never cropped. */
class AspectFrame(ctx: Context, private val ratio: Float) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = ((w - paddingLeft - paddingRight) * ratio).toInt() + paddingTop + paddingBottom
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
