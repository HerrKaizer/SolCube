package dev.suite

import android.content.Context
import android.view.ViewGroup

/** Lays children left to right and wraps onto new lines (chip rows, so nothing hides off-screen). */
class FlowRow(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams
            c.measure(
                if (lp.width >= 0) MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                if (lp.height >= 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + lineH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}
