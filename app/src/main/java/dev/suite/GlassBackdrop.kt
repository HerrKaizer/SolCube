package dev.suite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import java.util.Collections
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * Wallpaper behind every Suite screen: a photo (feather + satin form, one for light and one for
 * dark mode) spanning the screen width, moving at 30 % of the scroll speed, with a slow sweep of
 * light across it and a soft fade behind the title. Glass panes evaluate the same function
 * (see LiquidGlass) on pre-blurred copies: the deeper the pane, the more it blurs.
 * [base]/[glows] are kept for the old call sites.
 */
class GlassBackdrop(context: Context, @Suppress("UNUSED_PARAMETER") base: Int = 0,
                    @Suppress("UNUSED_PARAMETER") glows: List<Int> = emptyList()) : View(context) {
    private val dark = Look.isDark(context)
    private val density = resources.displayMetrics.density
    private val shader = RuntimeShader(SRC)
    private val paint = Paint()
    private val glass = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private var set: Wall? = null

    /** Seconds; continues across launches so the light isn't always in the same place. */
    private var time = ((SystemClock.elapsedRealtime() / 1000.0) % 3600.0).toFloat()
    private var parallax = 0f
    private var lastScroll = 0

    private var running = false
    private var lastFrame = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            if (lastFrame == 0L) lastFrame = now
            val dt = now - lastFrame
            if (dt >= 50_000_000L) {          // 20 fps: the light moves a couple of dp per frame
                lastFrame = now
                time += (dt / 1e9f).coerceAtMost(0.1f)
                refresh()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        load(context.applicationContext, dark) { if (isAttachedToWindow) { set = it; setScroll(lastScroll); refresh() } }
        set = cache[dark]
    }

    /** Glass panes that show this wallpaper re-draw with it. */
    fun register(v: View) { glass.add(v) }

    /** Called with the screen's scroll position. The wallpaper stops when its bottom is reached. */
    fun setScroll(y: Int) {
        lastScroll = y
        val w = set
        val max = if (w != null && width > 0)
            (width.toFloat() * w.full.height / w.full.width - OFF_DP * density - height).coerceAtLeast(0f) else 0f
        val p = (y * PARALLAX).coerceAtMost(max)
        if (p != parallax) { parallax = p; refresh() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); setScroll(lastScroll) }

    private fun refresh() {
        invalidate()
        for (v in glass.toList()) if (v.isShown) v.invalidate()
    }

    /** Sets the wallpaper uniforms on [s] (backdrop or glass shader). */
    fun applyWall(s: RuntimeShader) {
        val w = set ?: cache[dark]
        s.setFloatUniform("wReady", if (w != null) 1f else 0f)
        val any = w ?: placeholder
        s.setInputShader("w0", any.s0); s.setInputShader("w1", any.s1)
        s.setInputShader("w2", any.s2); s.setInputShader("w3", any.s3)
        s.setFloatUniform("wFullW", any.full.width.toFloat())
        s.setFloatUniform("wSmallW", any.small[0].width.toFloat())
        s.setFloatUniform("wWidth", width.coerceAtLeast(1).toFloat())
        s.setFloatUniform("wScroll", parallax)
        s.setFloatUniform("wOff", OFF_DP * density)
        s.setFloatUniform("wDens", density)
        s.setFloatUniform("wT", time)
        s.setFloatUniform("wDark", if (dark) 1f else 0f)
        val b = if (dark) DARK_BASE else LIGHT_BASE
        s.setFloatUniform("wBase", Color.red(b) / 255f, Color.green(b) / 255f, Color.blue(b) / 255f)
    }

    override fun onDraw(canvas: Canvas) {
        if (set == null) set = cache[dark]
        applyWall(shader)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun updateRunning() {
        val want = isAttachedToWindow && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        if (want == running) return
        running = want
        lastFrame = 0L
        if (want) Choreographer.getInstance().postFrameCallback(frame)
        else Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (set == null) set = cache[dark]
        updateRunning()
    }
    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }

    /** The photo plus copies blurred for glass levels 0/1/2, as shaders in bitmap-pixel space. */
    private class Wall(val full: Bitmap, val small: List<Bitmap>) {
        val s0 = BitmapShader(full, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val s1 = BitmapShader(small[0], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val s2 = BitmapShader(small[1], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val s3 = BitmapShader(small[2], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    companion object {
        const val PARALLAX = 0.3f
        /** The photo's empty top is taller than the title area: start this far down it. */
        private const val OFF_DP = 210f
        /** Blur (dp) seen through glass at nesting level 0, 1, 2. */
        val LEVEL_BLUR = floatArrayOf(10f, 17f, 24f)
        private const val SMALL = 4                       // blurred copies are 1/4 size
        private val DARK_BASE = Color.rgb(0x12, 0x0D, 0x07)
        private val LIGHT_BASE = Color.rgb(0xEE, 0xE6, 0xDA)

        private val cache = HashMap<Boolean, Wall>()
        private val loading = HashSet<Boolean>()
        private val waiting = HashMap<Boolean, MutableList<(Wall) -> Unit>>()
        private val main = Handler(Looper.getMainLooper())
        private val placeholder by lazy {
            val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            Wall(one, listOf(one, one, one))
        }

        /** For glass with no wallpaper behind it (pop-ups): the shader still needs its inputs set. */
        fun applyNone(s: RuntimeShader) {
            s.setFloatUniform("wReady", 0f)
            s.setInputShader("w0", placeholder.s0); s.setInputShader("w1", placeholder.s1)
            s.setInputShader("w2", placeholder.s2); s.setInputShader("w3", placeholder.s3)
            s.setFloatUniform("wWidth", 1f)
            s.setFloatUniform("wDens", 1f)
        }

        private fun load(ctx: Context, dark: Boolean, done: (Wall) -> Unit) {
            cache[dark]?.let { return }
            waiting.getOrPut(dark) { mutableListOf() }.add(done)
            if (!loading.add(dark)) return
            val dm = ctx.resources.displayMetrics
            val screenDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
            thread(name = "suite-wallpaper", priority = Thread.NORM_PRIORITY - 1) {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val full = BitmapFactory.decodeResource(ctx.resources, if (dark) R.drawable.wall_dark else R.drawable.wall_light, opts)
                val sw = full.width / SMALL
                val sh = full.height / SMALL
                val base = Bitmap.createScaledBitmap(full, sw, sh, true)
                val pxPerDp = sw / screenDp
                val small = LEVEL_BLUR.map { blur -> blurred(base, (blur / 2f * 0.85f * pxPerDp).toInt().coerceAtLeast(1)) }
                full.prepareToDraw()
                val w = Wall(full, small)
                main.post {
                    cache[dark] = w
                    loading.remove(dark)
                    waiting.remove(dark)?.forEach { it(w) }
                }
            }
        }

        /** Three box-blur passes each way ≈ a gaussian with sigma ≈ [r]. */
        private fun blurred(src: Bitmap, r: Int): Bitmap {
            val w = src.width
            val h = src.height
            var a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
            var b = IntArray(w * h)
            repeat(3) {
                box(a, b, w, h, r, true); val t = a; a = b; b = t
                box(a, b, w, h, r, false); val u = a; a = b; b = u
            }
            return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
        }

        private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
            val n = if (horiz) w else h
            val m = if (horiz) h else w
            val div = 2 * r + 1
            for (j in 0 until m) {
                fun at(i: Int) = src[if (horiz) j * w + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * w + j]
                var sr = 0; var sg = 0; var sb = 0
                for (i in -r..r) { val c = at(i); sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
                for (i in 0 until n) {
                    dst[if (horiz) j * w + i else i * w + j] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                    val add = at(i + r + 1)
                    val rem = at(i - r)
                    sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                    sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                    sb += (add and 255) - (rem and 255)
                }
            }
        }

        /** Wallpaper function shared by the backdrop and the glass. cp = content px, blur in dp. */
        const val WALL = """
uniform shader w0;
uniform shader w1;
uniform shader w2;
uniform shader w3;
uniform float wReady;
uniform float wFullW;
uniform float wSmallW;
uniform float wWidth;
uniform float wScroll;
uniform float wOff;
uniform float wDens;
uniform float wT;
uniform float wDark;
uniform float3 wBase;

float3 wall(float2 cp, float blur) {
    // position on the photo, in units of its width (it spans the screen width)
    float2 u = float2(cp.x, cp.y + wScroll + wOff) / wWidth;
    float3 c = wBase;
    if (wReady > 0.5) {
        if (blur < 5.0) c = w0.eval(u * wFullW).rgb;
        else if (blur < 13.5) c = w1.eval(u * wSmallW).rgb;
        else if (blur < 20.5) c = w2.eval(u * wSmallW).rgb;
        else c = w3.eval(u * wSmallW).rgb;
    }
    // a broad, slow band of light drifting diagonally across every 45 s; it brightens the
    // feather and the satin more than the background
    float s = fract(wT / 45.0) * 3.2 - 1.1;
    float v = u.x * 0.55 + (u.y - wOff / wWidth) * 0.3 - s;
    float l = dot(c, float3(0.2126, 0.7152, 0.0722));
    float3 lc = wDark > 0.5 ? float3(1.0, 0.906, 0.76) : float3(1.0);
    c += lc * exp(-v * v / 0.03) * (0.25 + 0.75 * l) * (wDark > 0.5 ? 0.07 : 0.05);
    // soft fade behind the title (screen space)
    float k = smoothstep(230.0, 60.0, cp.y / wDens);
    c = wDark > 0.5 ? c * (1.0 - 0.45 * k) : mix(c, float3(0.957, 0.933, 0.902), 0.30 * k);
    return clamp(c, 0.0, 1.0);
}
"""

        private const val SRC = WALL + """
half4 main(float2 fc) {
    float3 c = wall(fc, 0.0);
    float n = fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453) - 0.5;   // dither against banding
    return half4(half3(c + n / 255.0), 1.0);
}
"""
    }
}
