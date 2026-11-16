package dev.suite

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The app's look ("champagne & ink"), shared by the main screen and the pop-ups. */
object Look {
    data class Palette(
        val base: Int, val glows: List<Int>,
        val glass: Int, val sheen: Int, val stroke: Int, val chip: Int,
        val fg: Int, val sub: Int, val accent: Int, val accent2: Int, val onAccent: Int, val link: Int, val warn: Int, val ok: Int,
        val panel: Int,   // solid surface for pop-ups
    )

    fun isDark(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun white(a: Float) = Color.argb((a * 255).toInt(), 255, 255, 255)

    // "Champagne & ink": near-black with muted plum / deep teal / bronze glows and champagne-gold
    // buttons at night; warm porcelain with sand / sage / blush glows and ink buttons by day.
    fun palette(ctx: Context) = if (isDark(ctx)) Palette(
        // almost pitch black; the glows are barely-there tints
        base = Color.rgb(0x03, 0x03, 0x04),
        glows = listOf(Color.rgb(0x17, 0x0E, 0x1A), Color.rgb(0x08, 0x15, 0x18), Color.rgb(0x1B, 0x12, 0x0A), Color.rgb(0x0A, 0x0D, 0x1A)),
        glass = white(0.045f), sheen = white(0.035f), stroke = white(0.09f), chip = white(0.045f),
        fg = Color.rgb(0xF3, 0xEE, 0xE6), sub = Color.rgb(0x9A, 0x94, 0x8C),
        accent = Color.rgb(0xEC, 0xD3, 0xA6), accent2 = Color.rgb(0xBF, 0x93, 0x63),
        onAccent = Color.rgb(0x1C, 0x16, 0x10), link = Color.rgb(0xE2, 0xC2, 0x8E),
        warn = Color.rgb(0xF0, 0xB0, 0x5A), ok = Color.rgb(0x9C, 0xD4, 0xAE),
        panel = Color.rgb(0x10, 0x0F, 0x12),
    ) else Palette(
        base = Color.rgb(0xF6, 0xF2, 0xEC),
        glows = listOf(Color.rgb(0xE8, 0xD4, 0xB8), Color.rgb(0xCF, 0xDC, 0xD4), Color.rgb(0xEC, 0xD2, 0xCB), Color.rgb(0xD4, 0xD7, 0xE6)),
        glass = white(0.58f), sheen = white(0.5f), stroke = white(0.9f), chip = white(0.45f),
        fg = Color.rgb(0x18, 0x17, 0x1C), sub = Color.rgb(0x6E, 0x69, 0x64),
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
        private var items: Array<out CharSequence>? = null
        private var onItem: ((DialogInterface, Int) -> Unit)? = null
        private var multi: BooleanArray? = null
        private var onMulti: ((DialogInterface, Int, Boolean) -> Unit)? = null
        private var pos: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
        private var neg: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
        private var onDismiss: (() -> Unit)? = null

        fun setTitle(t: CharSequence) = apply { title = t }
        fun setMessage(m: CharSequence) = apply { message = m }
        fun setItems(list: Array<out CharSequence>, l: (DialogInterface, Int) -> Unit) = apply { items = list; onItem = l }
        fun setMultiChoiceItems(list: Array<out CharSequence>, checked: BooleanArray, l: (DialogInterface, Int, Boolean) -> Unit) =
            apply { items = list; multi = checked; onMulti = l }
        fun setPositiveButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { pos = t to l }
        fun setNegativeButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { neg = t to l }
        fun setOnDismissListener(l: (DialogInterface) -> Unit) = apply { onDismiss = { l(dlg!!) } }

        private var dlg: Dialog? = null
        private val dens = ctx.resources.displayMetrics.density
        private val Int.dp get() = (this * dens).toInt()

        fun show(): Dialog {
            val p = Look.palette(ctx)
            val d = Dialog(ctx)
            dlg = d
            fun text(t: CharSequence, size: Float, color: Int, weight: Int) = TextView(ctx).apply {
                text = t; textSize = size; setTextColor(color); typeface = Look.font(ctx, weight)
            }
            fun press(radius: Int) = RippleDrawable(ColorStateList.valueOf(Color.argb(30, Color.red(p.fg), Color.green(p.fg), Color.blue(p.fg))),
                null, GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(Color.WHITE) })

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(22.dp, 22.dp, 22.dp, 16.dp)
                background = GradientDrawable().apply {
                    cornerRadius = 28.dp.toFloat(); setColor(p.panel); setStroke(1.dp, if (Look.isDark(ctx)) p.stroke else Color.argb(26, 0, 0, 0))
                }
            }
            title?.let { root.addView(text(it, 20f, p.fg, 640), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10.dp }) }

            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            message?.let { body.addView(text(it, 14.5f, p.sub, 400).apply { setLineSpacing(3.dp.toFloat(), 1f) }) }
            items?.forEachIndexed { i, t ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12.dp, 13.dp, 12.dp, 13.dp)
                    background = press(14.dp)
                }
                row.addView(text(t, 15.5f, p.fg, 460), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val m = multi
                if (m != null) {
                    val box = text("", 13f, p.onAccent, 700).apply { gravity = Gravity.CENTER }
                    fun paint() {
                        box.text = if (m[i]) "✓" else ""
                        box.background = GradientDrawable().apply {
                            cornerRadius = 8.dp.toFloat()
                            if (m[i]) setColor(p.accent) else { setColor(Color.TRANSPARENT); setStroke(2.dp, p.sub) }
                        }
                    }
                    paint()
                    row.addView(box, LinearLayout.LayoutParams(22.dp, 22.dp).apply { leftMargin = 10.dp })
                    row.setOnClickListener { m[i] = !m[i]; paint(); onMulti?.invoke(d, i, m[i]) }
                } else {
                    row.setOnClickListener { d.dismiss(); onItem?.invoke(d, i) }
                }
                body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            root.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(body) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

            if (pos != null || neg != null) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                fun button(t: CharSequence, filled: Boolean, l: ((DialogInterface, Int) -> Unit)?, which: Int) =
                    text(t, 14.5f, if (filled) p.onAccent else p.fg, 600).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setPadding(18.dp, 0, 18.dp, 0)
                        val fill = if (filled) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.accent, p.accent2))
                            else GradientDrawable().apply { setColor(p.chip); setStroke(1.dp, if (Look.isDark(ctx)) p.stroke else Color.argb(26, 0, 0, 0)) }
                        fill.cornerRadius = 22.dp.toFloat()
                        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), fill, null)
                        setOnClickListener { d.dismiss(); l?.invoke(d, which) }
                    }
                neg?.let { (t, l) -> row.addView(button(t, false, l, DialogInterface.BUTTON_NEGATIVE), LinearLayout.LayoutParams(WRAP_CONTENT, 44.dp)) }
                pos?.let { (t, l) ->
                    row.addView(button(t, true, l, DialogInterface.BUTTON_POSITIVE), LinearLayout.LayoutParams(WRAP_CONTENT, 44.dp).apply { leftMargin = 8.dp })
                }
                root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18.dp })
            }

            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            d.setContentView(root)
            d.setOnDismissListener { onDismiss?.invoke() }
            d.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val w = minOf(ctx.resources.displayMetrics.widthPixels - 32.dp, 420.dp)
                setLayout(w, WRAP_CONTENT)
                setDimAmount(if (Look.isDark(ctx)) 0.7f else 0.4f)
            }
            d.show()
            // long lists: cap the height so the buttons stay on screen
            d.window?.let { win ->
                val max = (ctx.resources.displayMetrics.heightPixels * 0.8f).toInt()
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(win.attributes.width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.UNSPECIFIED)
                if (root.measuredHeight > max) win.setLayout(win.attributes.width, max)
            }
            return d
        }
    }
}
