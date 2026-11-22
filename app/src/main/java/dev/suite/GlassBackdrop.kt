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
 * Wallpaper behind every SolCube screen: a photo (feather + satin form, one for light and one for
 * dark mode) spanning the screen width, moving at 30 % of the scroll speed, with a slow sweep of
 * light across it and a soft fade behind the title. Glass panes evaluate the same function
 * (see LiquidGlass) on pre-blurred copies: the deeper the pane, the more it blurs.
 * [base]/[glows] are kept for the old call sites.
 */
class GlassBackdrop(context: Context, @Suppress("UNUSED_PARAMETER") base: Int = 0,
                    @Suppress("UNUSED_PARAMETER") glows: List<Int> = emptyList()) : View(context) {
    private val dark = Look.isDark(context)
    private val key = dark
    /** How far down the photo the screen starts (negative: the photo starts below the top edge). */
    private val offDp get() = OFF_DP
    private val density = resources.displayMetrics.density
    private val shader = RuntimeShader(SRC)
    private val paint = Paint()
    private val glass = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val visRect = android.graphics.Rect()
    private var set: Wall? = null

    /** Seconds; continues across launches so the light isn't always in the same place. */
    private var time = ((SystemClock.elapsedRealtime() / 1000.0) % 3600.0).toFloat()
    private var parallax = 0f
    private var lastScroll = 0

    private var running = false
    private var lastFrame = 0L
    // "On open": the sway plays for SWAY_NS after the screen opens or comes back, slows down and
    // stops. A still screen draws nothing, so the GPU can rest (it ran ~45 % busy at idle before).
    private var swayStart = 0L
    private val motion get() = motion(context)
    // sway strength: eases in from the neutral pose, out to it as the sway stops. At 0 the shaders
    // (this one and every glass pane's) skip the warp math entirely.
    private var amp = 0f

    /** The setting changed: apply it now. */
    fun motionChanged() { running = false; amp = 0f; Choreographer.getInstance().removeFrameCallback(frame); updateRunning() }
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            if (lastFrame == 0L) lastFrame = now
            if (swayStart == 0L) swayStart = now
            val dt = now - lastFrame
            if (dt >= 15_000_000L) {          // 60 fps: silky-smooth slow sway
                lastFrame = now
                var speed = 1f
                if (motion == MOTION_ON_OPEN) {
                    val left = (SWAY_NS - (now - swayStart)) / SLOW_NS.toFloat()   // 1 → 0 over the last SLOW_NS
                    if (left <= 0f) { running = false; amp = 0f; refresh(); return }
                    speed = left.coerceAtMost(1f).let { it * it * (3 - 2 * it) }
                }
                val rampIn = ((now - swayStart) / 1e9f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                amp = minOf(speed, rampIn)
                time += (dt / 1e9f).coerceAtMost(0.1f) * speed
                refresh()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        load(context.applicationContext, dark) { if (isAttachedToWindow) { set = it; setScroll(lastScroll); refresh() } }
        set = cache[key]
    }

    /** Glass panes that show this wallpaper re-draw with it. */
    fun register(v: View) { glass.add(v) }

    private var range = 0

    /** Called with the screen's scroll position. On long pages the wallpaper moves slower, so it
     *  keeps moving all the way down and reaches its bottom exactly at the end of the page. */
    fun setScroll(y: Int, sv: android.view.ViewGroup? = null) {
        lastScroll = y
        if (sv != null && sv.childCount > 0) range = (sv.getChildAt(0).height - sv.height).coerceAtLeast(0)
        val w = set
        val max = if (w != null && width > 0)
            (width.toFloat() * w.full.height / w.full.width - offDp * density - height).coerceAtLeast(0f) else 0f
        val k = if (range > 0) minOf(PARALLAX, max / range) else PARALLAX
        val p = (y * k).coerceAtMost(max)
        if (p != parallax) { parallax = p; refresh() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); setScroll(lastScroll) }

    private fun refresh() {
        invalidate()
        // only panes that are on screen: isShown is also true for ones scrolled out of view, and
        // re-drawing every pane on the page each frame cost most of the frame budget
        for (v in glass.toList()) if (v.isShown && v.getGlobalVisibleRect(visRect)) v.invalidate()
    }

    /** Sets the wallpaper uniforms on [s] (backdrop or glass shader). */
    fun applyWall(s: RuntimeShader) {
        val w = set ?: cache[key]
        s.setFloatUniform("wReady", if (w != null) 1f else 0f)
        val any = w ?: placeholder
        s.setInputShader("w0", any.s0); s.setInputShader("w1", any.s1)
        s.setInputShader("w2", any.s2); s.setInputShader("w3", any.s3)
        s.setFloatUniform("wFullW", any.full.width.toFloat())
        s.setFloatUniform("wSmallW", any.small[0].width.toFloat())
        s.setFloatUniform("wWidth", width.coerceAtLeast(1).toFloat())
        s.setFloatUniform("wScroll", parallax)
        s.setFloatUniform("wOff", offDp * density)
        // zoom around the middle of the screen
        s.setFloatUniform("wZoom", ZOOM)
        s.setFloatUniform("wAspect", any.full.height.toFloat() / any.full.width)
        s.setFloatUniform("wPivot", (height / 2f + parallax + offDp * density) / width.coerceAtLeast(1))
        s.setFloatUniform("wDens", density)
        s.setFloatUniform("wT", time)
        s.setFloatUniform("wAmp", 0f)   // no sway
        s.setFloatUniform("wDark", if (dark) 1f else 0f)
        val b = if (dark) DARK_BASE else LIGHT_BASE
        s.setFloatUniform("wBase", Color.red(b) / 255f, Color.green(b) / 255f, Color.blue(b) / 255f)
    }

    private var halfNode: android.graphics.RenderNode? = null

    override fun onDraw(canvas: Canvas) {
        if (set == null) set = cache[key]
        applyWall(shader)
        paint.shader = shader
        if (halfRes(context) && canvas.isHardwareAccelerated && width > 1 && height > 1) {
            // (test switch) render into an offscreen layer at half width and height - a quarter of the
            // pixels - and scale it up. The shader still sees full-size coordinates, so nothing moves.
            val hw = width / 2; val hh = height / 2
            val n = halfNode ?: android.graphics.RenderNode("wallHalf").also { halfNode = it }
            n.setPosition(0, 0, hw, hh)
            n.setUseCompositingLayer(true, null)
            val rc = n.beginRecording(hw, hh)
            rc.scale(hw / width.toFloat(), hh / height.toFloat())
            rc.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            n.endRecording()
            canvas.save()
            canvas.scale(width / hw.toFloat(), height / hh.toFloat())
            canvas.drawRenderNode(n)
            canvas.restore()
            return
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun updateRunning() {
        // paused while a pop-up (or anything else) has focus: the window behind it would otherwise be
        // re-drawn and re-blurred under the pop-up every frame, which made pop-ups stutter
        val want = isAttachedToWindow && windowVisibility == VISIBLE && hasWindowFocus() && ValueAnimator.areAnimatorsEnabled()
            && motion != MOTION_OFF
        if (want == running) return
        running = want
        if (!want && amp > 0f) { amp = 0f; refresh() }   // settle to the neutral pose (behind the pop-up)
        lastFrame = 0L
        swayStart = 0L   // each time the screen comes back, the sway plays again
        if (want) Choreographer.getInstance().postFrameCallback(frame)
        else Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (set == null) set = cache[key]
        updateRunning()
    }
    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); updateRunning() }

    /** The photo plus copies blurred for glass levels 0/1/2, as shaders in bitmap-pixel space. */
    private class Wall(val full: Bitmap, val small: List<Bitmap>) {
        val s0 = smooth(full)
        val s1 = smooth(small[0])
        val s2 = smooth(small[1])
        val s3 = smooth(small[2])
        /** Bilinear: without it the shader samples the nearest pixel and the photo looks blocky. */
        private fun smooth(b: Bitmap) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
    }

    companion object {
        const val PARALLAX = 0.3f
        /** Both photos (grass + satin ribbon, dark and light) are fitted to the screen, then moved
         *  this far up (dp) and zoomed this much around the screen's middle (set by the user). */
        private const val OFF_DP = 20f
        private const val ZOOM = 1.10f
        /** Blur (dp) seen through glass at nesting level 0, 1, 2. */
        val LEVEL_BLUR = floatArrayOf(10f, 17f, 24f)

        // Settings → Moving wallpaper
        const val MOTION_OFF = 0
        const val MOTION_ON_OPEN = 1   // default
        const val MOTION_ALWAYS = 2
        const val KEY_MOTION = "wall_motion"
        const val SWAY_NS = 6_000_000_000L    // how long the sway plays after opening
        const val SLOW_NS = 1_500_000_000L    // the last part of that, slowing to a stop

        fun motion(ctx: Context) = Components.prefs(ctx).getInt(KEY_MOTION, MOTION_ON_OPEN)
        // temporary: draw the wallpaper at half resolution (to compare)
        const val KEY_HALF = "wall_half"
        fun halfRes(ctx: Context) = Components.prefs(ctx).getBoolean(KEY_HALF, false)
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
            s.setFloatUniform("wZoom", 1f)
            s.setFloatUniform("wAspect", 1f)
        }

        private fun load(ctx: Context, dark: Boolean, done: (Wall) -> Unit) {
            val key = dark
            cache[key]?.let { return }
            waiting.getOrPut(key) { mutableListOf() }.add(done)
            if (!loading.add(key)) return
            val dm = ctx.resources.displayMetrics
            val screenDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
            val screenRatio = maxOf(dm.widthPixels, dm.heightPixels).toFloat() / minOf(dm.widthPixels, dm.heightPixels)
            thread(name = "suite-wallpaper", priority = Thread.NORM_PRIORITY - 1) {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var full = BitmapFactory.decodeResource(ctx.resources, if (dark) R.drawable.wall_dark else R.drawable.wall_light, opts)
                run {
                    // the photos are about the screen's own shape: trim the sides (if needed) so each
                    // is at least as tall as the screen at screen width
                    val wantW = (full.height / screenRatio).toInt().coerceAtMost(full.width)
                    if (wantW < full.width) full = Bitmap.createBitmap(full, (full.width - wantW) / 2, 0, wantW, full.height)
                }
                val sw = full.width / SMALL
                val sh = full.height / SMALL
                val base = Bitmap.createScaledBitmap(full, sw, sh, true)
                val pxPerDp = sw / screenDp
                val small = LEVEL_BLUR.map { blur -> blurred(base, (blur / 2f * 0.85f * pxPerDp).toInt().coerceAtLeast(1)) }
                full.prepareToDraw()
                val w = Wall(full, small)
                main.post {
                    cache[key] = w
                    loading.remove(key)
                    waiting.remove(key)?.forEach { it(w) }
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
uniform float wZoom;
uniform float wPivot;
uniform float wAspect;   // photo height / width
uniform float wDens;
uniform float wT;
uniform float wAmp;   // sway strength 0..1 (0 = still: the warp is skipped)
uniform float wDark;
uniform float3 wBase;

float3 wall(float2 cp, float blur) {
    // position on the photo, in units of its width (it spans the screen width)
    float2 u = float2(cp.x, cp.y + wScroll + wOff) / wWidth;
    float t = wT;
    // the picture itself stays still (no sway, no drift). (wAmp is no longer used.)
    u =(u - float2(0.5, wPivot)) / max(wZoom, 0.1) + float2(0.5, wPivot);
    u.x += wAmp * 0.0;
    float3 c = wBase;
    if (wReady > 0.5) {
        if (blur < 5.0) c = w0.eval(u * wFullW).rgb;
        else if (blur < 13.5) c = w1.eval(u * wSmallW).rgb;
        else if (blur < 20.5) c = w2.eval(u * wSmallW).rgb;
        else c = w3.eval(u * wSmallW).rgb;
        // past the photo's edges (moved or zoomed out): its own backdrop colour instead of smeared edge pixels
        float past = max(max(-u.x, u.x - 1.0), max(-u.y, u.y - wAspect));
        float3 bg = w3.eval(float2(0.03, 0.03) * wSmallW).rgb;
        c = mix(c, bg, smoothstep(0.0, 0.05, past));
    }
    // a broad, slow band of light drifting diagonally across every 45 s; it brightens the
    // feather and the satin more than the background
    float sw = fract(t / 30.0) * 3.2 - 1.1;
    float v = u.x * 0.55 + (u.y - wOff / wWidth) * 0.3 - sw;
    float l = dot(c, float3(0.2126, 0.7152, 0.0722));
    float3 lc = wDark > 0.5 ? float3(1.0, 0.906, 0.76) : float3(1.0);
    c += lc * exp(-v * v / 0.04) * l * l * (wDark > 0.5 ? 0.16 : 0.10);   // catches the lit feather and satin
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
