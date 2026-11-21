package dev.suite

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.util.WeakHashMap

/**
 * Liquid glass for every surface in Suite (AGSL, API 33+).
 *
 * Each pane is a slab of glass with a rounded bevel along its edge:
 *  - it shows the GlassBackdrop wallpaper behind it, blurred more the deeper it is nested
 *    (10 / 17 / 24 dp for levels 0 / 1 / 2, like stacked frosted panes); nested panes also get
 *    their parents' tint; in dark mode bright things behind the glass are toned down so text
 *    on it stays readable;
 *  - inside the bevel the backdrop is refracted outwards (with a touch of chromatic split), so the
 *    edge looks thick and curved;
 *  - a crisp rim, brightest along the bottom and top edges, plus a soft band of light just
 *    inside it (matched to the OriginOS control centre with a laptop prototype);
 *  - filled buttons drop a soft shadow outside their outline; the big cards on the wallpaper
 *    have none (small cards get theirs from FloatingCardHost).
 * Pop-ups have no GlassBackdrop: there the pane is a translucent tint over the window blur.
 * Panes re-draw whenever their view moves on screen (scrolling, sections opening above them).
 */
class LiquidGlass(
    private val p: Look.Palette,
    private val radius: Float,
    private val dark: Boolean,
    private val density: Float,
    /** 0 = on the backdrop, 1 = inside a card, 2 = inside a small card. */
    private val level: Int = 0,
    /** Own tint (straight ARGB). Null = clear glass for this level. */
    tint: Int? = null,
    /** Highlight strength multiplier (lower for filled buttons). */
    shine: Float = 1f,
    /** Soft shadow outside the outline (nested panes only, never level 0). Small cards get
     *  theirs from FloatingCardHost and the chevron badges from their elevation, so it's off by default. */
    shadow: Boolean = false,
    /** Soft coloured halo around the pane (0 = none), e.g. the glowing gold buttons. */
    halo: Int = 0,
    /** Thin coloured rim on the outline (0 = none). */
    stroke: Int = 0,
) : Drawable() {
    private val haloPaint = if (halo == 0) null else Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = halo
        maskFilter = BlurMaskFilter(9f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val strokePaint = if (stroke == 0) null else Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke; style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }

    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val loc = IntArray(2)
    private val cloc = IntArray(2)
    private val tintColor = tint ?: clearTint(level, dark)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor))
    }
    private val shadowOn = shadow && level > 0
    private val shadowDy = if (level == 1) 2f * density else 1.5f * density
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val filled = tint != null && Color.alpha(tint) > 200
        color = when {
            dark -> Color.argb(if (level == 1) 102 else 77, 0, 0, 0)
            filled -> Color.argb(31, 40, 30, 20)
            else -> Color.argb(if (level == 1) 20 else 15, 60, 45, 30)
        }
        maskFilter = BlurMaskFilter((if (level == 1) 7f else 4.5f) * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val shadowRect = RectF()

    init {
        // parents' clear tints, already "baked into" what this pane sees behind it
        var under = 0f
        for (l in 0 until level) under = 1f - (1f - under) * (1f - Color.alpha(clearTint(l, dark)) / 255f)
        shader.setFloatUniform("underA", under)
        val uc = clearTint(0, dark)
        shader.setFloatUniform("underC", Color.red(uc) / 255f, Color.green(uc) / 255f, Color.blue(uc) / 255f)
        shader.setFloatUniform("tint", Color.red(tintColor) / 255f, Color.green(tintColor) / 255f,
            Color.blue(tintColor) / 255f, Color.alpha(tintColor) / 255f)
        shader.setFloatUniform("blurDp", GlassBackdrop.LEVEL_BLUR[level.coerceIn(0, 2)])
        // numbers matched against OriginOS screenshots with a laptop prototype (control centre tiles)
        shader.setFloatUniform("sat", 1.15f)
        shader.setFloatUniform("tame", if (dark) 0.45f else 0f)
        shader.setFloatUniform("rimA", (if (dark) 0.55f else 0.85f) * shine)
        shader.setFloatUniform("rimSide", if (dark) 0.18f else 0.40f)
        shader.setFloatUniform("rimW", 1f * density)
        shader.setFloatUniform("bandA", (if (dark) 0.075f else 0.10f) * shine)
        shader.setFloatUniform("bandW", 1.7f * density)
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
        path.reset()
        val r = minOf(radius, rect.width() / 2, rect.height() / 2)
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        shader.setFloatUniform("size", rect.width(), rect.height())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("bevel", 14f * density)
        shader.setFloatUniform("refr", 10f * density)
    }

    private fun host(): View? {
        var cb: Any? = callback
        while (cb is Drawable) cb = cb.callback
        return cb as? View
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        // (test switch RenderTest.flatGlass: panes at this level and deeper drawn as plain tinted shapes)
        if (!canvas.isHardwareAccelerated || level >= RenderTest.flatGlassFrom) {
            canvas.drawPath(path, fallbackPaint)
            canvas.restore()
            return
        }

        // The shadow lies outside the outline. If the parent clips its children, all that's left of
        // it is the bit inside the view's rectangle around the rounded corners, cut off square:
        // faint dark corners on every chip. Only draw it where it can show whole.
        val parentClips = (host()?.parent as? android.view.ViewGroup)?.clipChildren != false
        if (shadowOn && !parentClips) {
            canvas.save()
            canvas.clipOutPath(path)
            shadowRect.set(rect); shadowRect.offset(0f, shadowDy)
            val r = minOf(radius, rect.width() / 2, rect.height() / 2)
            canvas.drawRoundRect(shadowRect, r, r, shadowPaint)
            canvas.restore()
        }
        if (haloPaint != null) Glow.request(host())
        if (haloPaint != null && !parentClips) {
            canvas.save()
            canvas.clipOutPath(path)
            val r = minOf(radius, rect.width() / 2, rect.height() / 2)
            canvas.drawRoundRect(rect, r, r, haloPaint)
            canvas.restore()
        }

        val view = host()
        var sees = 0f
        if (view != null) {
            track(view)
            val content = view.rootView.findViewById<View>(android.R.id.content)
            val wall = content?.let { findBackdrop(it) }
            if (content != null && wall != null && wall.width > 0) {
                wall.register(view)
                view.getLocationInWindow(loc)
                content.getLocationInWindow(cloc)
                wall.applyWall(shader)
                shader.setFloatUniform("origin", (loc[0] - cloc[0] + b.left).toFloat(), (loc[1] - cloc[1] + b.top).toFloat())
                sees = 1f
            }
        }
        if (sees == 0f) { GlassBackdrop.applyNone(shader); shader.setFloatUniform("origin", 0f, 0f) }
        shader.setFloatUniform("hasBd", sees)
        paint.shader = shader
        canvas.drawRect(rect, paint)
        strokePaint?.let { sp ->
            val r = minOf(radius, rect.width() / 2, rect.height() / 2)
            val i = sp.strokeWidth / 2
            canvas.drawRoundRect(rect.left + i, rect.top + i, rect.right - i, rect.bottom - i, r - i, r - i, sp)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Clear glass tint per nesting level: white by day, smoky near-black by night. */
        fun clearTint(level: Int, dark: Boolean): Int = if (dark) when (level) {
            0 -> Color.argb(77, 0x15, 0x16, 0x1C)
            1 -> Color.argb(46, 0x15, 0x16, 0x1C)
            else -> Color.argb(41, 0x15, 0x16, 0x1C)
        } else when (level) {
            0 -> Color.argb(77, 255, 255, 255)
            1 -> Color.argb(41, 255, 255, 255)
            else -> Color.argb(56, 255, 255, 255)
        }

        /** Screens built on a GlassBackdrop: content → FrameLayout → [GlassBackdrop, …]. */
        private fun findBackdrop(content: View): GlassBackdrop? {
            val frame = (content as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return null
            return if (frame.childCount > 0) frame.getChildAt(0) as? GlassBackdrop else null
        }

        private val tracked = WeakHashMap<View, Boolean>()

        /** Re-draws [view] whenever its position in the window changes. */
        private fun track(view: View) {
            if (tracked.containsKey(view)) return
            tracked[view] = true
            val last = IntArray(2).also { view.getLocationInWindow(it) }
            val now = IntArray(2)
            val listener = ViewTreeObserver.OnPreDrawListener {
                if (view.isShown) {
                    view.getLocationInWindow(now)
                    if (now[0] != last[0] || now[1] != last[1]) {
                        last[0] = now[0]; last[1] = now[1]
                        view.invalidate()
                    }
                }
                true
            }
            if (view.isAttachedToWindow) view.viewTreeObserver.addOnPreDrawListener(listener)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnPreDrawListener(listener) }
                override fun onViewDetachedFromWindow(v: View) { v.viewTreeObserver.removeOnPreDrawListener(listener) }
            })
        }

        private const val SRC = GlassBackdrop.WALL + """
uniform float2 size;
uniform float radius;
uniform float2 origin;
uniform float hasBd;
uniform float underA;
uniform float3 underC;
uniform float4 tint;
uniform float blurDp;
uniform float bevel;
uniform float refr;
uniform float sat;
uniform float tame;
uniform float rimA;
uniform float rimSide;
uniform float rimW;
uniform float bandA;
uniform float bandW;

float sdRR(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fc) {
    float2 b = size * 0.5;
    float2 p = fc - b;
    float r = min(radius, min(b.x, b.y));
    float d = sdRR(p, b, r);
    float cov = clamp(0.5 - d, 0.0, 1.0);
    if (cov <= 0.0) return half4(0.0);

    // Interior fast path: deeper than the bevel and the rim/band glow, those add nothing (bevel
    // ends at depth == bevel; the band is < 0.1 % beyond 7 bandW, the rim far sooner), so skip the
    // edge normal, the refraction and the glow. Most of every pane is interior.
    float depth0 = -d;
    if (depth0 > max(bevel, 7.0 * bandW) + 1.0) {
        float4 Q = float4(0.0);
        if (hasBd > 0.5) {
            float3 base = wall(origin + fc, blurDp);
            float l = dot(base, float3(0.2126, 0.7152, 0.0722));
            base = clamp(mix(float3(l), base, sat), 0.0, 1.0);
            base *= 1.0 - tame * l;
            base = mix(base, underC, underA);
            Q = float4(base, 1.0);
        }
        Q = Q * (1.0 - tint.a) + float4(tint.rgb * tint.a, tint.a);
        return half4(Q * cov);
    }

    float2 g = float2(sdRR(p + float2(1.0, 0.0), b, r) - sdRR(p - float2(1.0, 0.0), b, r),
                      sdRR(p + float2(0.0, 1.0), b, r) - sdRR(p - float2(0.0, 1.0), b, r));
    float2 n = g / max(length(g), 0.0001);
    float depth = max(-d, 0.0);
    float edge = 1.0 - clamp(depth / bevel, 0.0, 1.0);   // 1 at the rim, 0 on the flat top
    float k = edge * edge * edge;

    float4 P = float4(0.0);
    if (hasBd > 0.5) {
        float2 cp = origin + fc;
        float2 dv = n * refr * k;                          // the curved edge bends in what lies beyond it
        float3 base = wall(cp + dv, blurDp);
        if (k > 0.002) base = float3(base.r, wall(cp + dv * 1.12, blurDp).g, wall(cp + dv * 1.24, blurDp).b);
        float l = dot(base, float3(0.2126, 0.7152, 0.0722));
        base = clamp(mix(float3(l), base, sat), 0.0, 1.0);
        base *= 1.0 - tame * l;                            // dark glass tames bright things behind it
        base = mix(base, underC, underA);
        P = float4(base, 1.0);
    }
    P = P * (1.0 - tint.a) + float4(tint.rgb * tint.a, tint.a);

    // crisp rim (strongest along the bottom, then the top) plus a soft band of light inside it
    float dir = (rimSide + (1.0 - rimSide) * n.y * n.y) * (n.y > 0.0 ? 1.0 : 0.8);
    float rim = rimA * dir * exp(-pow(depth / rimW, 1.6));
    float band = bandA * exp(-depth / bandW);
    float h = clamp(rim + band, 0.0, 1.0);
    P = P * (1.0 - h) + float4(h);
    return half4(P * cov);
}
"""
    }
}

/**
 * The gold glow around filled controls lies outside their bounds. Any container that clips its
 * children cuts it off square, so the first time a glowing control draws, its containers (up to
 * the scroll view, which must keep clipping) stop clipping.
 */
object Glow {
    private val done = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun hostOf(d: Drawable): View? {
        var cb: Any? = d.callback
        while (cb is Drawable) cb = cb.callback
        return cb as? View
    }

    /** Call from draw(); does the work once per view, after the current frame. */
    fun request(v: View?) {
        if (v == null || !done.add(v)) return
        v.post { unclip(v) }
    }

    fun unclip(v: View) {
        var p = v.parent
        var n = 0
        while (p is ViewGroup && p !is android.widget.ScrollView && p !is android.widget.HorizontalScrollView && n < 10) {
            // Re-set even when already false: setClipChildren only pushes the flag to the children
            // that exist at the time, so children added later still clip their own drawing.
            p.clipChildren = true
            p.clipChildren = false
            p.clipToPadding = false
            p.invalidate()
            if (p.id == android.R.id.content) break
            p = p.parent
            n++
        }
        v.invalidate()
    }
}

/** A glowing gold pill (switch tracks): halo outside the bounds, top→bottom gradient, bright rim. */
class GlowPill(
    private val top: Int, private val bottom: Int, rim: Int, halo: Int, density: Float,
    private val w: Int = -1, private val h: Int = -1,
) : Drawable() {
    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = halo; maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = rim; style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val r = RectF()

    override fun onBoundsChange(b: Rect) {
        r.set(b)
        fillPaint.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, top, bottom,
            android.graphics.Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (r.isEmpty) return
        Glow.request(Glow.hostOf(this))
        val rad = r.height() / 2
        if (Color.alpha(haloPaint.color) > 0) canvas.drawRoundRect(r, rad, rad, haloPaint)
        canvas.drawRoundRect(r, rad, rad, fillPaint)
        val i = rimPaint.strokeWidth / 2
        canvas.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, rad - i, rad - i, rimPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
