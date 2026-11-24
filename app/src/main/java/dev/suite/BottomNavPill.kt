package dev.suite

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

data class BottomTab(val key: String, val label: String, val icon: Int)

/**
 * Floating LiquidGlass bottom navigation pill bar with an animated, slideable glowing gold thumb.
 * Inactive tabs show icons; the active (or scrubbed) tab expands horizontally to show icon + label.
 */
class BottomNavPill(
    ctx: Context,
    private val p: Look.Palette,
    fontMedium: Typeface,
    private val dark: Boolean,
) : View(ctx) {

    companion object {
        const val ICON_SHORTCUTS = 0
        const val ICON_AUTOMATION = 1
        const val ICON_GESTURES = 2
        const val ICON_GAMING = 3
        const val ICON_AUDIO = 4
        const val ICON_SETTINGS = 5

        private const val LIQUID_BAR_SRC = """
uniform shader src;
uniform float2 size;
uniform float radius;
uniform float offset;
uniform float4 tint;
uniform float bevel;
uniform float refr;
uniform float sat;
uniform float tame;
uniform float rimA;
uniform float rimSide;
uniform float rimW;
uniform float bandA;
uniform float bandW;
uniform float4 rimTint;

float sdRR(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float3 sampleSrc(float2 cp) {
    half4 c = src.eval(cp);
    float3 rgb = float3(c.rgb);
    if (c.a > 0.001) rgb /= c.a;
    return clamp(rgb, 0.0, 1.0);
}

half4 main(float2 fc) {
    float2 localFc = fc - float2(offset);
    float2 b = size * 0.5;
    float2 p = localFc - b;
    float r = min(radius, min(b.x, b.y));
    float d = sdRR(p, b, r);
    float cov = clamp(0.5 - d, 0.0, 1.0);
    if (cov <= 0.0) return half4(0.0);

    float depth = max(-d, 0.0);
    float edge = 1.0 - clamp(depth / bevel, 0.0, 1.0);
    float k = edge * edge * edge;

    float2 g = float2(sdRR(p + float2(1.0, 0.0), b, r) - sdRR(p - float2(1.0, 0.0), b, r),
                      sdRR(p + float2(0.0, 1.0), b, r) - sdRR(p - float2(0.0, 1.0), b, r));
    float2 n = g / max(length(g), 0.0001);
    float2 dv = n * refr * k;

    float3 base = sampleSrc(fc + dv);
    if (k > 0.002) {
        base = float3(base.r, sampleSrc(fc + dv * 1.12).g, sampleSrc(fc + dv * 1.24).b);
    }
    float l = dot(base, float3(0.2126, 0.7152, 0.0722));
    base = clamp(mix(float3(l), base, sat), 0.0, 1.0);
    base *= 1.0 - tame * l;

    float4 P = float4(base, 1.0);
    P = P * (1.0 - tint.a) + float4(tint.rgb * tint.a, tint.a);

    float dir = (rimSide + (1.0 - rimSide) * n.y * n.y) * (n.y > 0.0 ? 1.0 : 0.8);
    float rim = rimA * dir * exp(-pow(depth / rimW, 1.6));
    float band = bandA * exp(-depth / bandW);
    float h = clamp(rim + band, 0.0, 1.0);
    P = P * (1.0 - h) + float4(rimTint.rgb * h, h);
    return half4(P * cov);
}
"""

        fun drawTabIcon(
            canvas: Canvas,
            icon: Int,
            cx: Float,
            cy: Float,
            boxPx: Float,
            d: Float,
            strokePaint: Paint,
            fillPaint: Paint,
            path: Path,
            rect: RectF,
        ) {
            val s = boxPx * 0.5f
            when (icon) {
                ICON_SHORTCUTS -> {
                    path.reset()
                    path.moveTo(cx + 1.2f * d, cy - s)
                    path.lineTo(cx - 4.8f * d, cy + 0.8f * d)
                    path.lineTo(cx - 0.2f * d, cy + 0.8f * d)
                    path.lineTo(cx - 1.4f * d, cy + s)
                    path.lineTo(cx + 4.8f * d, cy - 0.8f * d)
                    path.lineTo(cx + 0.2f * d, cy - 0.8f * d)
                    path.close()
                    canvas.drawPath(path, strokePaint)
                }
                ICON_AUTOMATION -> {
                    val rOut = s * 0.88f
                    val rIn = s * 0.30f
                    val mx = cx - 1.2f * d
                    val my = cy + 0.8f * d
                    path.reset()
                    path.moveTo(mx, my - rOut)
                    path.lineTo(mx + rIn, my - rIn)
                    path.lineTo(mx + rOut, my)
                    path.lineTo(mx + rIn, my + rIn)
                    path.lineTo(mx, my + rOut)
                    path.lineTo(mx - rIn, my + rIn)
                    path.lineTo(mx - rOut, my)
                    path.lineTo(mx - rIn, my - rIn)
                    path.close()
                    canvas.drawPath(path, strokePaint)

                    val sx = cx + 5.2f * d
                    val sy = cy - 5.2f * d
                    val sr = 2.4f * d
                    canvas.drawLine(sx, sy - sr, sx, sy + sr, strokePaint)
                    canvas.drawLine(sx - sr, sy, sx + sr, sy, strokePaint)
                }
                ICON_GESTURES -> {
                    path.reset()
                    path.moveTo(cx - 5.5f * d, cy + 5.0f * d)
                    path.cubicTo(
                        cx - 7.0f * d, cy - 5.0f * d,
                        cx + 0.5f * d, cy - 7.5f * d,
                        cx + 0.5f * d, cy - 0.5f * d,
                    )
                    path.cubicTo(
                        cx + 0.5f * d, cy + 6.5f * d,
                        cx + 6.5f * d, cy + 4.0f * d,
                        cx + 5.8f * d, cy - 5.0f * d,
                    )
                    canvas.drawPath(path, strokePaint)
                    canvas.drawCircle(cx - 5.5f * d, cy + 5.0f * d, 1.8f * d, fillPaint)
                }
                ICON_GAMING -> {
                    val hw = s * 0.96f
                    val hh = s * 0.62f
                    rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
                    canvas.drawRoundRect(rect, 4.5f * d, 4.5f * d, strokePaint)
                    val dx = cx - 3.6f * d
                    canvas.drawLine(dx - 2.0f * d, cy, dx + 2.0f * d, cy, strokePaint)
                    canvas.drawLine(dx, cy - 2.0f * d, dx, cy + 2.0f * d, strokePaint)
                    canvas.drawCircle(cx + 3.2f * d, cy - 1.4f * d, 1.15f * d, fillPaint)
                    canvas.drawCircle(cx + 5.0f * d, cy + 1.4f * d, 1.15f * d, fillPaint)
                }
                ICON_AUDIO -> {
                    path.reset()
                    path.moveTo(cx - 6.2f * d, cy - 2.4f * d)
                    path.lineTo(cx - 3.4f * d, cy - 2.4f * d)
                    path.lineTo(cx + 0.4f * d, cy - 5.6f * d)
                    path.lineTo(cx + 0.4f * d, cy + 5.6f * d)
                    path.lineTo(cx - 3.4f * d, cy + 2.4f * d)
                    path.lineTo(cx - 6.2f * d, cy + 2.4f * d)
                    path.close()
                    canvas.drawPath(path, strokePaint)
                    rect.set(cx - 0.4f * d, cy - 3.8f * d, cx + 4.0f * d, cy + 3.8f * d)
                    canvas.drawArc(rect, -55f, 110f, false, strokePaint)
                    rect.set(cx + 0.6f * d, cy - 6.4f * d, cx + 6.8f * d, cy + 6.4f * d)
                    canvas.drawArc(rect, -55f, 110f, false, strokePaint)
                }
                ICON_SETTINGS -> {
                    val hw = s * 0.85f
                    val ys = arrayOf(cy - 5.0f * d, cy, cy + 5.0f * d)
                    val kx = arrayOf(cx + 2.4f * d, cx - 2.6f * d, cx + 1.2f * d)
                    val kr = 1.9f * d
                    for (i in 0..2) {
                        val y = ys[i]
                        val k = kx[i]
                        if (k - kr > cx - hw) canvas.drawLine(cx - hw, y, k - kr, y, strokePaint)
                        if (k + kr < cx + hw) canvas.drawLine(k + kr, y, cx + hw, y, strokePaint)
                        canvas.drawCircle(k, y, kr, strokePaint)
                    }
                }
            }
        }
    }

    private val d = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val pad = 5f * d
    private val outerRadius = 27f * d
    private val iconBox = 17f * d
    private val iconLabelGap = 6f * d
    private val minSlotW = 34f * d

    private val outerGlass = LiquidGlass(p, outerRadius, dark, d, level = 0).apply { callback = this@BottomNavPill }
    private var backdropSources: () -> List<View> = { emptyList() }
    private val padRefr = (16f * d).toInt()
    private val liveBlurNode = RenderNode("bottomPillBlur")
    private val liquidShader = RuntimeShader(LIQUID_BAR_SRC).apply {
        val barTint = if (dark) Color.argb(72, 0x18, 0x14, 0x0F) else Color.argb(88, 255, 252, 246)
        setFloatUniform("radius", outerRadius)
        setFloatUniform("offset", padRefr.toFloat())
        setFloatUniform(
            "tint",
            Color.red(barTint) / 255f,
            Color.green(barTint) / 255f,
            Color.blue(barTint) / 255f,
            Color.alpha(barTint) / 255f,
        )
        setFloatUniform("bevel", 14f * d)
        setFloatUniform("refr", 11f * d)
        setFloatUniform("sat", 1.22f)
        setFloatUniform("tame", if (dark) 0.30f else 0f)
        setFloatUniform("rimA", if (dark) 0.65f else 0.90f)
        setFloatUniform("rimSide", if (dark) 0.22f else 0.42f)
        setFloatUniform("rimW", 1.15f * d)
        setFloatUniform("bandA", if (dark) 0.09f else 0.12f)
        setFloatUniform("bandW", 1.9f * d)
        if (dark) setFloatUniform("rimTint", 1f, 0.949f, 0.839f, 1f)
        else setFloatUniform("rimTint", 1f, 1f, 1f, 1f)
    }
    private var recordingBackdrop = false
    private var backdropDirty = true
    private var lastNodeLeft = Int.MIN_VALUE
    private var lastNodeTop = Int.MIN_VALUE
    private var lastNodeW = -1
    private var lastNodeH = -1
    private val inactiveIconColor = if (dark) Color.rgb(0xD8, 0xC6, 0xA8) else Color.rgb(0x6B, 0x5D, 0x4A)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(if (dark) 115 else 38, 0, 0, 0)
        maskFilter = BlurMaskFilter(12f * d, BlurMaskFilter.Blur.NORMAL)
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = p.fill
        if (p.halo != 0) setShadowLayer(8f * d, 0f, 0f, p.halo)
        else setShadowLayer(4f * d, 0f, 1.5f * d, alphaColor(Color.BLACK, if (dark) 0.45f else 0.18f))
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (p.fillStroke != 0) 1.2f * d else 1f * d
        color = if (p.fillStroke != 0) p.fillStroke else alphaColor(Color.WHITE, if (dark) 0.25f else 0.45f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12.5f, resources.displayMetrics)
        typeface = fontMedium
        textAlign = Paint.Align.LEFT
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.75f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var tabs: List<BottomTab> = emptyList()
    private var labelWidths = FloatArray(0)
    private var expand = FloatArray(0)
    private var extra = FloatArray(0)
    private var slotLeft = FloatArray(0)
    private var slotRight = FloatArray(0)

    private var activeIdx = 0
    private var pos = 0f
    private var onSelect: (Int) -> Unit = {}

    private var pressed = false
    private var lift = 0f
    private var lastShadowLift = -1f
    private var dragging = false
    private var downX = 0f
    private var lastTickIdx = 0

    private val shadowRect = RectF()
    private val thumbRect = RectF()
    private val iconRect = RectF()
    private val iconPath = Path()

    private var animRunning = false
    private var animLastNs = 0L
    private val animFrame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!animRunning || !isAttachedToWindow || tabs.isEmpty()) {
                animRunning = false
                return
            }
            if (animLastNs == 0L) animLastNs = now
            val dt = ((now - animLastNs) / 1e9f).coerceIn(0.001f, 0.05f)
            animLastNs = now

            var more = false
            val targetLift = if (pressed || dragging) 1f else 0f
            val dl = targetLift - lift
            if (abs(dl) < 0.004f) {
                lift = targetLift
            } else {
                val la = 1f - exp(-dt * (if (targetLift > lift) 22f else 16f))
                lift += dl * la
                more = true
            }

            if (!dragging) {
                val a = 1f - exp(-dt * 18f)
                val targetPos = activeIdx.toFloat()
                val dp = targetPos - pos
                if (abs(dp) < 0.003f) {
                    pos = targetPos
                } else {
                    pos += dp * a
                    more = true
                }
                for (i in tabs.indices) {
                    val te = if (i == activeIdx) 1f else 0f
                    val de = te - expand[i]
                    if (abs(de) < 0.003f) {
                        expand[i] = te
                    } else {
                        expand[i] += de * a
                        more = true
                    }
                }
            }
            invalidate()
            if (more) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                animRunning = false
            }
        }
    }

    fun bind(tabs: List<BottomTab>, initialIndex: Int, onSelect: (Int) -> Unit) {
        this.tabs = tabs
        this.onSelect = onSelect
        val n = tabs.size
        labelWidths = FloatArray(n) { i -> labelPaint.measureText(tabs[i].label) }
        expand = FloatArray(n)
        extra = FloatArray(n)
        slotLeft = FloatArray(n)
        slotRight = FloatArray(n)
        activeIdx = initialIndex.coerceIn(0, (n - 1).coerceAtLeast(0))
        pos = activeIdx.toFloat()
        if (n > 0) expand[activeIdx] = 1f
        invalidate()
    }

    fun select(index: Int, notify: Boolean = false) {
        if (tabs.isEmpty()) return
        val next = index.coerceIn(0, tabs.lastIndex)
        val changed = next != activeIdx
        activeIdx = next
        if (!isAttachedToWindow || !isLaidOut) {
            pos = next.toFloat()
            for (i in tabs.indices) expand[i] = if (i == next) 1f else 0f
            invalidate()
        } else {
            startAnim()
        }
        if (notify && changed) onSelect(next)
    }

    private fun startAnim() {
        if (!isAttachedToWindow) return
        if (!animRunning) {
            animRunning = true
            animLastNs = 0L
            Choreographer.getInstance().postFrameCallback(animFrame)
        }
    }

    private fun computeSlots() {
        val n = tabs.size
        if (n == 0 || width <= 0) return
        val trackLeft = pad
        val trackW = (width - 2f * pad).coerceAtLeast(1f)
        var totalExtra = 0f
        for (i in 0 until n) {
            val e = expand[i] * (labelWidths[i] + 8f * d)
            extra[i] = e
            totalExtra += e
        }
        val maxExtra = (trackW - n * minSlotW).coerceAtLeast(0f)
        val scale = if (totalExtra > maxExtra && totalExtra > 0f) maxExtra / totalExtra else 1f
        val scaledExtra = totalExtra * scale
        val baseW = (trackW - scaledExtra) / n
        var x = trackLeft
        for (i in 0 until n) {
            val w = baseW + extra[i] * scale
            slotLeft[i] = x
            x += w
            slotRight[i] = if (i == n - 1) trackLeft + trackW else x
        }
    }

    fun setBackdropSources(sources: () -> List<View>) {
        backdropSources = sources
        invalidateBackdrop()
    }

    fun invalidateBackdrop() {
        backdropDirty = true
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdropDirty = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backdropDirty = true
        if (w > 0 && h > 0) {
            liquidShader.setFloatUniform("size", w.toFloat(), h.toFloat())
            liquidShader.setFloatUniform("radius", outerRadius)
            liquidShader.setFloatUniform("offset", padRefr.toFloat())
            val blur = RenderEffect.createBlurEffect(14f * d, 14f * d, Shader.TileMode.CLAMP)
            val glass = RenderEffect.createRuntimeShaderEffect(liquidShader, "src")
            liveBlurNode.setRenderEffect(RenderEffect.createChainEffect(glass, blur))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = tabs.size
        if (recordingBackdrop || n == 0 || width <= 0 || height <= 0) return
        Glow.request(this)

        shadowRect.set(0f, 3f * d, width.toFloat(), height.toFloat() + 3f * d)
        canvas.drawRoundRect(shadowRect, outerRadius, outerRadius, shadowPaint)

        val sources = if (canvas.isHardwareAccelerated) backdropSources() else emptyList()
        if (sources.isNotEmpty()) {
            val nodeW = width + padRefr * 2
            val nodeH = height + padRefr * 2
            if (backdropDirty || !liveBlurNode.hasDisplayList() ||
                left != lastNodeLeft || top != lastNodeTop || nodeW != lastNodeW || nodeH != lastNodeH
            ) {
                liveBlurNode.setPosition(0, 0, nodeW, nodeH)
                val rc = liveBlurNode.beginRecording(nodeW, nodeH)
                recordingBackdrop = true
                try {
                    rc.translate(-(left - padRefr).toFloat(), -(top - padRefr).toFloat())
                    for (src in sources) {
                        if (src.isShown) {
                            rc.save()
                            rc.translate(src.left.toFloat(), src.top.toFloat())
                            src.draw(rc)
                            rc.restore()
                        }
                    }
                } finally {
                    recordingBackdrop = false
                    liveBlurNode.endRecording()
                }
                lastNodeLeft = left
                lastNodeTop = top
                lastNodeW = nodeW
                lastNodeH = nodeH
                backdropDirty = false
            }
            canvas.save()
            canvas.translate(-padRefr.toFloat(), -padRefr.toFloat())
            canvas.drawRenderNode(liveBlurNode)
            canvas.restore()
        } else {
            outerGlass.setBounds(0, 0, width, height)
            outerGlass.draw(canvas)
        }

        computeSlots()

        val i0 = floor(pos).toInt().coerceIn(0, n - 1)
        val i1 = (i0 + 1).coerceAtMost(n - 1)
        val frac = (pos - i0).coerceIn(0f, 1f)
        val thumbL = lerp(slotLeft[i0], slotLeft[i1], frac)
        val thumbR = lerp(slotRight[i0], slotRight[i1], frac)
        val thumbH = (height - 2f * pad).coerceAtLeast(1f)
        val extraY = 9f * d * lift
        val extraX = 3.5f * d * lift
        if (abs(lift - lastShadowLift) > 0.05f) {
            lastShadowLift = lift
            if (p.halo != 0) thumbPaint.setShadowLayer((8f + 3f * lift) * d, 0f, 0f, p.halo)
            else thumbPaint.setShadowLayer((4f + 2.5f * lift) * d, 0f, (1.5f + 1.5f * lift) * d,
                alphaColor(Color.BLACK, if (dark) 0.45f else 0.22f))
        }

        thumbRect.set(thumbL - extraX, pad - extraY, thumbR + extraX, pad + thumbH + extraY)
        val r = thumbRect.height() * 0.5f
        canvas.drawRoundRect(thumbRect, r, r, thumbPaint)
        val rimInset = rimPaint.strokeWidth * 0.5f
        thumbRect.inset(rimInset, rimInset)
        canvas.drawRoundRect(thumbRect, (r - rimInset).coerceAtLeast(0f), (r - rimInset).coerceAtLeast(0f), rimPaint)

        val cy = height * 0.5f
        val fm = labelPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) * 0.5f

        for (i in 0 until n) {
            val sl = slotLeft[i]
            val sr = slotRight[i]
            val scx = (sl + sr) * 0.5f
            val t = expand[i].coerceIn(0f, 1f)
            val color = blendColor(inactiveIconColor, p.onAccent, t)

            val labelTotal = (iconLabelGap + labelWidths[i]) * t
            val iconCx = scx - labelTotal * 0.5f
            val itemScale = 1f + 0.06f * lift * t

            if (itemScale > 1.001f) {
                canvas.save()
                canvas.scale(itemScale, itemScale, scx, cy)
            }
            drawIcon(canvas, tabs[i].icon, iconCx, cy, color)

            val labelAlpha = ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
            if (labelAlpha > 0.01f) {
                labelPaint.color = alphaColor(color, labelAlpha)
                val labelX = iconCx + iconBox * 0.5f + iconLabelGap
                canvas.save()
                canvas.clipRect(sl - extraX, pad - extraY, sr + extraX, height - pad + extraY)
                canvas.drawText(tabs[i].label, labelX, baseline, labelPaint)
                canvas.restore()
            }
            if (itemScale > 1.001f) canvas.restore()
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Int, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        iconFillPaint.color = color
        drawTabIcon(canvas, icon, cx, cy, iconBox, d, iconPaint, iconFillPaint, iconPath, iconRect)
    }

    private fun hitSlot(x: Float): Int {
        val n = tabs.size
        if (n <= 1 || width <= 0) return 0
        if (slotRight.size != n || slotRight[n - 1] <= 0f) computeSlots()
        for (i in 0 until n) {
            if (x <= slotRight[i] || i == n - 1) return i.coerceIn(0, n - 1)
        }
        return n - 1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val n = tabs.size
        if (n == 0 || width <= 0) return false
        val trackW = (width - 2f * pad).coerceAtLeast(1f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                downX = ev.x
                dragging = false
                val tapped = hitSlot(ev.x)
                if (tapped != activeIdx) {
                    activeIdx = tapped
                    lastTickIdx = tapped
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSelect(tapped)
                } else {
                    lastTickIdx = activeIdx
                }
                startAnim()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > touchSlop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    pressed = true
                    if (lift < 0.996f) startAnim()
                    pos = ((ev.x - pad) / trackW * n - 0.5f).coerceIn(0f, (n - 1).toFloat())
                    for (i in 0 until n) expand[i] = (1f - abs(pos - i)).coerceIn(0f, 1f)
                    val nearest = pos.roundToInt().coerceIn(0, n - 1)
                    if (nearest != lastTickIdx) {
                        lastTickIdx = nearest
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        activeIdx = nearest
                        onSelect(nearest)
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    dragging = false
                    startAnim()
                    return true
                }
                if (!dragging) {
                    startAnim()
                } else {
                    dragging = false
                    val target = pos.roundToInt().coerceIn(0, n - 1)
                    val changed = target != activeIdx
                    activeIdx = target
                    startAnim()
                    if (changed) onSelect(target)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun alphaColor(c: Int, a: Float): Int = Color.argb(
        (Color.alpha(c) * a).toInt().coerceIn(0, 255),
        Color.red(c),
        Color.green(c),
        Color.blue(c),
    )

    private fun blendColor(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        return Color.argb(
            (Color.alpha(a) * inv + Color.alpha(b) * t).toInt(),
            (Color.red(a) * inv + Color.red(b) * t).toInt(),
            (Color.green(a) * inv + Color.green(b) * t).toInt(),
            (Color.blue(a) * inv + Color.blue(b) * t).toInt(),
        )
    }

    override fun onDetachedFromWindow() {
        animRunning = false
        Choreographer.getInstance().removeFrameCallback(animFrame)
        super.onDetachedFromWindow()
    }
}
