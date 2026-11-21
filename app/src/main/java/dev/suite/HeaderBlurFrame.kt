package dev.suite

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.widget.FrameLayout

/**
 * The scrolling page's wrapper, with the blur under the title drawn only where it shows.
 *
 * Before, the header blur was a RenderEffect on this whole view: three blur passes over the full
 * screen-sized layer every frame the page changed, although only the strip under the title is
 * ever blurred (measured: the biggest single part of SolCube's GPU load while scrolling).
 *
 * Now the page draws itself normally below [stripBottom], and the strip above it is drawn from a
 * strip-sized copy with [effect] on it. The copy only references the children's own drawings, so
 * it follows scrolling and changes by itself: nothing is re-recorded per frame. The effect's
 * coordinates are the same as before (this view and the copy both start at the window's top).
 */
class HeaderBlurFrame(ctx: Context) : FrameLayout(ctx) {
    var effect: RenderEffect? = null
        set(v) {
            if (field === v) return
            field = v
            strip.setRenderEffect(v)
            invalidate()
        }

    /** Bottom of the blurred/faded strip, in this view's pixels (the effect is plain sharp below it). */
    var stripBottom = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    private val strip = RenderNode("headerStrip")
    private var recording = false

    // dispatchDraw, not draw: a layout without a background skips draw() and calls this directly
    override fun dispatchDraw(canvas: Canvas) {
        val e = effect
        val sb = stripBottom.coerceIn(0, height)
        if (e == null || sb <= 0 || recording || !canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas)
            return
        }
        // the page, sharp, below the strip
        canvas.save()
        canvas.clipRect(0, sb, width, height)
        super.dispatchDraw(canvas)
        canvas.restore()
        // the strip: the same drawing, in a layer only as big as the strip, blurred and faded
        strip.setPosition(0, 0, width, sb)
        val rc = strip.beginRecording(width, sb)
        recording = true
        try {
            super.dispatchDraw(rc)
        } finally {
            recording = false
            strip.endRecording()
        }
        canvas.drawRenderNode(strip)
    }
}
