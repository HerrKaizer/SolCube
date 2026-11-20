package dev.suite

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** AGSL for SuiteDialog's list edges (see SuiteDialog.Builder.edgeEffect). No pow(), no reversed smoothstep. */
private const val EDGE_SRC = """
uniform shader src;
uniform float h;
uniform float e;
uniform float top;
uniform float bot;
uniform float blurred;

half4 main(float2 fc) {
    float wt = top * clamp(1.0 - fc.y / e, 0.0, 1.0);
    float wb = bot * clamp(1.0 - (h - fc.y) / e, 0.0, 1.0);
    float w = max(wt, wb);
    float fade = 1.0 - 0.9 * w * w;
    float k = blurred > 0.5 ? w : 1.0 - w;
    return src.eval(fc) * half(k * fade);
}
"""

/** The app's look ("champagne & ink"), shared by the main screen and the pop-ups. */
object Look {
    data class Palette(
        val base: Int, val glows: List<Int>,
        val glass: Int, val sheen: Int, val stroke: Int, val chip: Int,
        val fg: Int, val sub: Int, val accent: Int, val accent2: Int, val onAccent: Int, val link: Int, val warn: Int, val ok: Int,
        val panel: Int,   // solid surface for pop-ups
    )

    const val THEME_LIGHT = 0
    const val THEME_SYSTEM = 1
    const val THEME_DARK = 2

    fun themeMode(ctx: Context): Int =
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("theme_mode", THEME_SYSTEM)

    fun setThemeMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putInt("theme_mode", mode.coerceIn(0, 2)).apply()
    }

    fun isDark(ctx: Context): Boolean = when (themeMode(ctx)) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun white(a: Float) = Color.argb((a * 255).toInt(), 255, 255, 255)

    // "Champagne & ink": near-black with muted plum / deep teal / bronze glows and champagne-gold
    // buttons at night; warm porcelain with sand / sage / blush glows and ink buttons by day.
    fun palette(ctx: Context) = if (isDark(ctx)) Palette(
        // almost pitch black; the glows are barely-there tints
        base = Color.rgb(0x03, 0x03, 0x04),
        glows = listOf(Color.rgb(0x17, 0x0E, 0x1A), Color.rgb(0x08, 0x15, 0x18), Color.rgb(0x1B, 0x12, 0x0A), Color.rgb(0x0A, 0x0D, 0x1A)),
        glass = white(0.045f), sheen = white(0.035f), stroke = white(0.09f), chip = white(0.045f),
        fg = Color.rgb(0xF3, 0xEE, 0xE6), sub = Color.rgb(0xBD, 0xB6, 0xAC),
        accent = Color.rgb(0xEC, 0xD3, 0xA6), accent2 = Color.rgb(0xBF, 0x93, 0x63),
        onAccent = Color.rgb(0x1C, 0x16, 0x10), link = Color.rgb(0xE2, 0xC2, 0x8E),
        warn = Color.rgb(0xF0, 0xB0, 0x5A), ok = Color.rgb(0x9C, 0xD4, 0xAE),
        panel = Color.rgb(0x10, 0x0F, 0x12),
    ) else Palette(
        base = Color.rgb(0xF6, 0xF2, 0xEC),
        glows = listOf(Color.rgb(0xE8, 0xD4, 0xB8), Color.rgb(0xCF, 0xDC, 0xD4), Color.rgb(0xEC, 0xD2, 0xCB), Color.rgb(0xD4, 0xD7, 0xE6)),
        glass = white(0.58f), sheen = white(0.5f), stroke = white(0.9f), chip = white(0.45f),
        fg = Color.rgb(0x18, 0x17, 0x1C), sub = Color.rgb(0x5F, 0x5A, 0x55),
        accent = Color.rgb(0x2B, 0x29, 0x33), accent2 = Color.rgb(0x15, 0x14, 0x19),
        onAccent = Color.rgb(0xF6, 0xEF, 0xE2), link = Color.rgb(0x8A, 0x63, 0x3C),
        warn = Color.rgb(0xB0, 0x62, 0x10), ok = Color.rgb(0x2F, 0x7D, 0x50),
        panel = Color.rgb(0xFB, 0xF8, 0xF3),
    )

    private val fonts = HashMap<Int, Typeface>()
    fun font(ctx: Context, weight: Int): Typeface = fonts.getOrPut(weight) {
        try {
            Typeface.Builder(ctx.assets, "fonts/GoogleSansFlex.ttf").setFontVariationSettings("'wght' $weight").build() ?: Typeface.DEFAULT
        } catch (_: Exception) { Typeface.DEFAULT }
    }
}

/**
 * Pop-ups in Suite's own style. Same calls as AlertDialog.Builder (title, message, items,
 * multi-choice items, two buttons, dismiss listener) so call sites just swap the class.
 */
object SuiteDialog {
    class Builder(private val ctx: Context) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var customView: android.view.View? = null
        private var items: Array<out CharSequence>? = null
        private var groupedItems: List<Pair<String, List<String>>>? = null
        private var onItem: ((DialogInterface, Int) -> Unit)? = null
        private var multi: BooleanArray? = null
        private var onMulti: ((DialogInterface, Int, Boolean) -> Unit)? = null
        private var pos: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
        private var neg: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
        private var onDismiss: (() -> Unit)? = null

        fun setTitle(t: CharSequence) = apply { title = t }
        fun setMessage(m: CharSequence) = apply { message = m }
        fun setView(v: android.view.View) = apply { customView = v }
        fun setItems(list: Array<out CharSequence>, l: (DialogInterface, Int) -> Unit) = apply { items = list; groupedItems = null; onItem = l }
        fun setGroupedItems(groups: List<Pair<String, List<String>>>, l: (DialogInterface, Int) -> Unit) =
            apply { groupedItems = groups; items = null; multi = null; onItem = l }
        fun setMultiChoiceItems(list: Array<out CharSequence>, checked: BooleanArray, l: (DialogInterface, Int, Boolean) -> Unit) =
            apply { items = list; groupedItems = null; multi = checked; onMulti = l }
        fun setPositiveButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { pos = t to l }
        fun setNegativeButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { neg = t to l }
        fun setOnDismissListener(l: (DialogInterface) -> Unit) = apply { onDismiss = { l(dlg!!) } }

        private var dlg: Dialog? = null
        private val dens = ctx.resources.displayMetrics.density
        private val Int.dp get() = (this * dens).toInt()

        /**
         * Soft scroll edges for the list: within [edge] of the top/bottom the rows blur and fade
         * out. top/bot (0..1) grow with how much more there is to scroll that way, so the first
         * row isn't blurred when the list is at its start. Sharp part × (1 − w) plus blurred
         * part × w, both faded, added together (premultiplied).
         */
        // compiled once per pop-up (compiling AGSL on every scroll step made scrolling stutter);
        // a RenderEffect copies the uniforms when it's created, so the same shaders can be reused
        private val sharpShader by lazy { android.graphics.RuntimeShader(EDGE_SRC) }
        private val softShader by lazy { android.graphics.RuntimeShader(EDGE_SRC) }
        private val edgeBlur by lazy {
            android.graphics.RenderEffect.createBlurEffect(7f * dens, 7f * dens, android.graphics.Shader.TileMode.CLAMP)
        }

        private fun edgeEffect(h: Float, edge: Float, top: Float, bot: Float): android.graphics.RenderEffect {
            fun part(s: android.graphics.RuntimeShader, blurred: Float) = s.apply {
                setFloatUniform("h", h)
                setFloatUniform("e", edge)
                setFloatUniform("top", top)
                setFloatUniform("bot", bot)
                setFloatUniform("blurred", blurred)
            }
            val sharp = android.graphics.RenderEffect.createRuntimeShaderEffect(part(sharpShader, 0f), "src")
            val soft = android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createRuntimeShaderEffect(part(softShader, 1f), "src"),
                edgeBlur,
            )
            return android.graphics.RenderEffect.createBlendModeEffect(sharp, soft, android.graphics.BlendMode.PLUS)
        }

        fun show(): Dialog {
            val p = Look.palette(ctx)
            val dark = Look.isDark(ctx)
            val d = Dialog(ctx)
            dlg = d
            fun text(t: CharSequence, size: Float, color: Int, weight: Int) = TextView(ctx).apply {
                text = t; textSize = size; setTextColor(color); typeface = Look.font(ctx, weight)
            }
            fun glass(radius: Int, tint: Int, level: Int = 1, shine: Float = 1f, shadow: Boolean = false) =
                LiquidGlass(p, radius * dens, dark, dens, level = level, tint = tint, shine = shine, shadow = shadow)
            fun press(radius: Int) = RippleDrawable(
                ColorStateList.valueOf(Color.argb(30, Color.red(p.fg), Color.green(p.fg), Color.blue(p.fg))),
                null,
                GradientDrawable().apply { cornerRadius = radius * dens; setColor(Color.WHITE) },
            )
            fun hairline() = View(ctx).apply {
                setBackgroundColor(Color.argb(if (dark) 22 else 28, Color.red(p.fg), Color.green(p.fg), Color.blue(p.fg)))
            }
            fun cardHost() = object : LinearLayout(ctx) {
                private val sRect = RectF()
                private val amb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (dark) Color.argb(56, 0, 0, 0) else Color.argb(14, 40, 30, 20)
                    maskFilter = BlurMaskFilter(14f * dens, BlurMaskFilter.Blur.NORMAL)
                }
                override fun dispatchDraw(canvas: Canvas) {
                    val cr = 20f * dens
                    for (i in 0 until childCount) {
                        val c = getChildAt(i)
                        if (c.visibility != VISIBLE || c.alpha <= 0.05f) continue
                        sRect.set(c.left.toFloat(), c.top + 3f * dens, c.right.toFloat(), c.bottom + 3f * dens)
                        canvas.drawRoundRect(sRect, cr, cr, amb)
                    }
                    super.dispatchDraw(canvas)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            }
            fun listCard() = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 4.dp, 0, 4.dp)
                background = glass(20, if (dark) Color.argb(26, 255, 255, 255) else Color.argb(150, 255, 255, 255), level = 1)
            }

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(20.dp, 22.dp, 20.dp, 18.dp)
                background = glass(
                    30,
                    Color.argb(if (dark) 172 else 188, Color.red(p.panel), Color.green(p.panel), Color.blue(p.panel)),
                    level = 0,
                    shine = if (dark) 0.9f else 0.75f,
                )
            }
            title?.let {
                root.addView(
                    text(it, 21f, p.fg, 680).apply { letterSpacing = -0.01f },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10.dp },
                )
            }

            val body = cardHost()
            message?.let {
                body.addView(text(
                    it,
                    14.5f,
                    Color.argb(if (dark) 212 else 204, Color.red(p.fg), Color.green(p.fg), Color.blue(p.fg)),
                    400,
                ).apply { setLineSpacing(4.dp.toFloat(), 1f) })
            }
            customView?.let { v ->
                body.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (message != null) topMargin = 12.dp
                })
            }
            fun itemRow(t: CharSequence, idx: Int, parent: LinearLayout, compact: Boolean = false) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(14.dp, if (compact) 11.dp else 13.dp, 14.dp, if (compact) 11.dp else 13.dp)
                    background = press(14)
                }
                row.addView(text(t, 15.5f, p.fg, 480), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val m = multi
                if (m != null) {
                    val box = text("", 13f, p.onAccent, 700).apply { gravity = Gravity.CENTER }
                    val fillAccent = Color.rgb(
                        (Color.red(p.accent) + Color.red(p.accent2)) / 2,
                        (Color.green(p.accent) + Color.green(p.accent2)) / 2,
                        (Color.blue(p.accent) + Color.blue(p.accent2)) / 2,
                    )
                    fun paint() {
                        box.text = if (m[idx]) "✓" else ""
                        box.background = GradientDrawable().apply {
                            cornerRadius = 9.dp.toFloat()
                            if (m[idx]) setColor(fillAccent)
                            else {
                                setColor(Color.TRANSPARENT)
                                setStroke((1.5f * dens).toInt().coerceAtLeast(1), Color.argb(120, Color.red(p.sub), Color.green(p.sub), Color.blue(p.sub)))
                            }
                        }
                    }
                    paint()
                    row.addView(box, LinearLayout.LayoutParams(24.dp, 24.dp).apply { leftMargin = 10.dp })
                    row.setOnClickListener { m[idx] = !m[idx]; paint(); onMulti?.invoke(d, idx, m[idx]) }
                } else {
                    row.setOnClickListener { d.dismiss(); onItem?.invoke(d, idx) }
                }
                parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            groupedItems?.let { groups ->
                var flat = 0
                val topGap = if (message != null || customView != null) 12.dp else 0
                var first = true
                for ((hdr, entries) in groups) {
                    if (entries.isEmpty()) continue
                    val secCard = listCard()
                    secCard.addView(text(hdr.uppercase(), 11.5f, p.link, 660).apply {
                        letterSpacing = 0.08f
                        setPadding(14.dp, 11.dp, 14.dp, 5.dp)
                    })
                    entries.forEachIndexed { idx, entry ->
                        if (idx > 0) secCard.addView(hairline(), LinearLayout.LayoutParams(MATCH_PARENT, 1.dp.coerceAtLeast(1)).apply {
                            leftMargin = 14.dp; rightMargin = 14.dp
                        })
                        itemRow(entry, flat++, secCard, compact = true)
                    }
                    body.addView(secCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        if (first && topGap > 0) topMargin = topGap
                        bottomMargin = 10.dp
                    })
                    first = false
                }
            }
            items?.takeIf { it.isNotEmpty() }?.let { list ->
                val listBox = listCard()
                list.forEachIndexed { i, t ->
                    if (i > 0) listBox.addView(hairline(), LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
                        leftMargin = 14.dp; rightMargin = 14.dp
                    })
                    itemRow(t, i, listBox)
                }
                body.addView(listBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (message != null || customView != null) topMargin = 12.dp
                })
            }
            // The root doesn't clip (button shadows spill out), so the list gets its own clipping
            // box; without it rows drew over the title and below the buttons.
            val scroll = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(body)
            }
            val clipBox = android.widget.FrameLayout(ctx).apply {
                clipChildren = true
                addView(scroll, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val edge = 30f * dens
            var lastKey = ""
            fun edges() {
                val h = clipBox.height.toFloat()
                if (h <= 0f) return
                val range = (body.height - scroll.height).coerceAtLeast(0)
                val top = (scroll.scrollY / edge).coerceIn(0f, 1f)
                val bot = ((range - scroll.scrollY) / edge).coerceIn(0f, 1f)
                // in the middle of a long list both are 1: nothing changes, so don't rebuild the effect
                val key = "$h $top $bot"
                if (key == lastKey) return
                lastKey = key
                clipBox.setRenderEffect(if (top <= 0f && bot <= 0f) null else edgeEffect(h, edge, top, bot))
            }
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> edges() }
            clipBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> edges() }
            root.addView(clipBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

            if (pos != null || neg != null) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                fun button(t: CharSequence, filled: Boolean, l: ((DialogInterface, Int) -> Unit)?, which: Int) =
                    text(t, 14f, if (filled) p.onAccent else p.fg, 640).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(14.dp, 0, 14.dp, 0)
                        val fill = if (filled) glass(
                            22,
                            Color.argb(242, (Color.red(p.accent) + Color.red(p.accent2)) / 2,
                                (Color.green(p.accent) + Color.green(p.accent2)) / 2,
                                (Color.blue(p.accent) + Color.blue(p.accent2)) / 2),
                            shine = if (dark) 0.78f else 0.37f,
                            shadow = true,
                        ) else glass(
                            22,
                            if (dark) Color.argb(26, 255, 255, 255) else Color.argb(145, 255, 255, 255),
                            level = 1,
                        )
                        background = LayerDrawable(arrayOf(fill, press(22)))
                        setOnClickListener { d.dismiss(); l?.invoke(d, which) }
                    }
                val n = neg
                val pBtn = pos
                when {
                    n != null && pBtn != null -> {
                        val posW = if (pBtn.first.length > n.first.length + 4) 1.45f else 1f
                        row.addView(button(n.first, false, n.second, DialogInterface.BUTTON_NEGATIVE),
                            LinearLayout.LayoutParams(0, 44.dp, 1f))
                        row.addView(button(pBtn.first, true, pBtn.second, DialogInterface.BUTTON_POSITIVE),
                            LinearLayout.LayoutParams(0, 44.dp, posW).apply { leftMargin = 10.dp })
                    }
                    n != null -> row.addView(button(n.first, false, n.second, DialogInterface.BUTTON_NEGATIVE),
                        LinearLayout.LayoutParams(MATCH_PARENT, 44.dp))
                    pBtn != null -> row.addView(button(pBtn.first, true, pBtn.second, DialogInterface.BUTTON_POSITIVE),
                        LinearLayout.LayoutParams(MATCH_PARENT, 44.dp))
                }
                root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16.dp })
            }

            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            d.setContentView(root)
            d.setOnDismissListener { onDismiss?.invoke() }
            d.window?.apply {
                setBackgroundDrawable(GradientDrawable().apply {
                    cornerRadius = 30.dp.toFloat()
                    setColor(Color.TRANSPARENT)
                })
                val w = minOf(ctx.resources.displayMetrics.widthPixels - 32.dp, 420.dp)
                setLayout(w, WRAP_CONTENT)
                setBackgroundBlurRadius((32 * dens).toInt())
                setDimAmount(if (dark) 0.48f else 0.22f)
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply { blurBehindRadius = (18 * dens).toInt() }
            }
            d.show()
            // long lists: cap the height so the buttons stay on screen
            d.window?.let { win ->
                val max = (ctx.resources.displayMetrics.heightPixels * 0.8f).toInt()
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(win.attributes.width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.UNSPECIFIED)
                if (root.measuredHeight > max) win.setLayout(win.attributes.width, max)
            }
            root.alpha = 0f
            root.scaleX = 0.94f
            root.scaleY = 0.94f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
            return d
        }
    }
}
