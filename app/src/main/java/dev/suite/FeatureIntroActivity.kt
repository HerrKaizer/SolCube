package dev.suite

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Post-setup interactive Feature Tour showcasing SolCube 5.4's capabilities with live device
 * screenshots framed in LiquidGlass, glowing gold buttons, swipe/tap navigation, and a tiny Skip button.
 */
class FeatureIntroActivity : Activity() {

    companion object {
        private val openCount = java.util.concurrent.atomic.AtomicInteger(0)
        val isOpen: Boolean get() = openCount.get() > 0
    }

    private data class SlideSpec(
        val badge: String,
        val title1: String,
        val title2: String,
        val demo: TourDemo.Kind,
        val chips: List<Pair<String, Boolean>>, // label to isOk
        val summary: String,
    )

    private lateinit var p: Look.Palette
    private val fontRegular: Typeface by lazy { Fonts.get(this, 400) }
    private val fontMedium: Typeface by lazy { Fonts.get(this, 560) }
    private val fontBold: Typeface by lazy { Fonts.get(this, 680) }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private val slideInterp = PathInterpolator(0.2f, 0f, 0f, 1f)

    private lateinit var slideHost: FrameLayout
    private lateinit var counterView: TextView
    private lateinit var backBtn: TextView
    private lateinit var nextBtn: TextView
    private val dots = mutableListOf<View>()
    private var activeSlideView: View? = null
    private var slideIndex = 0
    private var done = false

    private val slides = listOf(
        SlideSpec(
            badge = "SHORTCUTS",
            title1 = "Circle to Search,",
            title2 = "and buttons that obey.",
            demo = TourDemo.Kind.SHORTCUTS,
            chips = listOf("Two-finger · Nav bar" to false, "Power hold & double-press" to true),
            summary = "Launch ***Circle to Search*** or ***Gemini*** with a custom ripple anywhere, and remap power-button double-press & hold separately.",
        ),
        SlideSpec(
            badge = "GESTURES",
            title1 = "Draw any shape.",
            title2 = "Run any action.",
            demo = TourDemo.Kind.GESTURES,
            chips = listOf("1 or 2 fingers" to true, "Test Pad" to false, "Custom shapes" to false),
            summary = "Draw shapes over any app — even while scrolling — to open apps, toggle settings, or fire ***full routines***.",
        ),
        SlideSpec(
            badge = "GAMING",
            title1 = "120 FPS frame-gen,",
            title2 = "live HUD & laptop stream.",
            demo = TourDemo.Kind.GAMING,
            chips = listOf("FI 60→120" to true, "FPS & Temp HUD" to false, "Super Res" to false),
            summary = "Per-game ***45→90 & 60→120*** frame interpolation with heat auto-pause, floating FPS/°C/load pills, and 16:10 ***laptop streaming***.",
        ),
        SlideSpec(
            badge = "AUTOMATION",
            title1 = "Smart routines &",
            title2 = "auto-revert profiles.",
            demo = TourDemo.Kind.AUTOMATION,
            chips = listOf("45+ triggers" to true, "40+ actions" to false, "Per-app profiles" to false),
            summary = "Automate Wi-Fi, NFC, Game Cube Boost, and DND with ***45+ triggers*** — profiles automatically ***revert cleanly*** when you leave an app.",
        ),
        SlideSpec(
            badge = "AUDIO",
            title1 = "Play two apps at once.",
            title2 = "Dial volume in 1% steps.",
            demo = TourDemo.Kind.AUDIO,
            chips = listOf("Spotify + Game" to true, "1% Knob & Slider" to false),
            summary = "Keep music playing over games or videos without muting, and dial media volume in ***exact 1% steps*** with a 3D rotary knob.",
        ),
        SlideSpec(
            badge = "SETTINGS & MORE",
            title1 = "Haptics, lock screen,",
            title2 = "9 languages & SolEngine.",
            demo = TourDemo.Kind.SETTINGS,
            chips = listOf("Custom haptics" to true, "Lock-screen bar" to false, "9 languages" to false),
            summary = "Tailor ***per-action vibration*** feedback, hide the lock-screen top bar, and enjoy ***LiquidGlass*** UI with hands-free reconnect.",
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        openCount.incrementAndGet()
        I18n.init(this)
        setTheme(if (Look.isDark(this)) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        p = Look.palette(this)
        actionBar?.hide()
        styleSystemBars()
        setContentView(buildRoot())
        showSlide(0, dir = 1, animate = false)
    }

    override fun onDestroy() {
        openCount.decrementAndGet()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (slideIndex > 0) {
            showSlide(slideIndex - 1, dir = -1)
        } else {
            finishTour()
        }
    }

    private fun finishTour() {
        if (done || isFinishing || isDestroyed) return
        done = true
        SetupPrefs.setSeenIntro(this, true)
        finish()
    }

    // ------------------------------------------------------------------ Layout

    private fun buildRoot(): View {
        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        // True GlassBackdrop wallpaper so every LiquidGlass card & glowing button refracts live.
        root.addView(GlassBackdrop(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // 1. Header: icon + SolCube + slide counter badge + tiny Skip button
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val iconView = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, out: android.graphics.Outline) {
                    out.setRoundRect(0, 0, v.width, v.height, 8.dp.toFloat())
                }
            }
        }
        topBar.addView(iconView, LinearLayout.LayoutParams(28.dp, 28.dp).apply { rightMargin = 9.dp })
        topBar.addView(label("SolCube", 20f, p.fg, bold = true).apply {
            typeface = fontBold
            letterSpacing = -0.02f
        })
        counterView = TextView(this).apply {
            textSize = 11.5f
            typeface = fontMedium
            setTextColor(p.sub)
            setPadding(9.dp, 3.dp, 9.dp, 3.dp)
            background = glass(11.dp, level = 1)
        }
        topBar.addView(counterView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp })
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val skipBtn = label("Skip", 12f, alpha(p.sub, 0.82f), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(11.dp, 5.dp, 11.dp, 5.dp)
            background = ripple(glass(14.dp, level = 1), 14.dp)
            setOnClickListener { finishTour() }
        }
        topBar.addView(skipBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10.dp })

        // 2. Swipeable Showcase Stage
        slideHost = FitStage(this, minScale = 0.6f).apply {
            clipChildren = false
            clipToPadding = false
        }
        attachSwipe(slideHost)
        col.addView(slideHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 3. Bottom Dots + Navigation Controls (unclipped for full gold glow)
        val bottomBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dots.clear()
        for (i in slides.indices) {
            val dot = View(this).apply {
                background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat() }
                setOnClickListener {
                    if (i != slideIndex) showSlide(i, if (i > slideIndex) 1 else -1)
                }
            }
            dots += dot
            dotsRow.addView(dot, LinearLayout.LayoutParams(if (i == 0) 20.dp else 7.dp, 6.dp).apply {
                if (i > 0) leftMargin = 6.dp
            })
        }
        bottomBox.addView(dotsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = 12.dp
            bottomMargin = 12.dp
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        backBtn = label("←", 18f, p.fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = ripple(glass(26.dp, level = 2), 26.dp)
            visibility = View.GONE
            setOnClickListener {
                if (slideIndex > 0) showSlide(slideIndex - 1, dir = -1)
            }
        }
        btnRow.addView(backBtn, LinearLayout.LayoutParams(54.dp, 52.dp).apply { rightMargin = 10.dp })

        nextBtn = pillButton("Next  →", filled = true, size = 16f) {
            if (slideIndex < slides.lastIndex) {
                showSlide(slideIndex + 1, dir = 1)
            } else {
                finishTour()
            }
        }
        btnRow.addView(nextBtn, LinearLayout.LayoutParams(0, 52.dp, 1f))
        bottomBox.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(bottomBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(18.dp, sys.top + 12.dp, 18.dp, sys.bottom + 18.dp)
            insets
        }
        return root
    }

    private fun buildSlideView(idx: Int): View {
        val spec = slides[idx]
        val dark = Look.isDark(this)
        val dens = resources.displayMetrics.density

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false

            addView(pillTag("•  ${I18n.t(spec.badge)}", accent = true),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 6.dp })

            addView(label(spec.title1, 25f, p.fg, bold = true).apply {
                typeface = fontBold
                letterSpacing = -0.02f
            })
            addView(label(spec.title2, 25f, if (dark) p.accent else p.link, bold = true).apply {
                typeface = fontBold
                letterSpacing = -0.02f
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })

            // Top LiquidGlass feature description card
            val infoCard = LinearLayout(this@FeatureIntroActivity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                background = LiquidGlass(p, 22.dp.toFloat(), dark, dens, level = 0)
            }
            val chips = FlowRow(this@FeatureIntroActivity, hGap = 7.dp, vGap = 6.dp)
            spec.chips.forEach { (txt, isOk) ->
                chips.addView(pillTag(txt, ok = isOk))
            }
            infoCard.addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 8.dp })
            infoCard.addView(TextView(this@FeatureIntroActivity).apply {
                text = richText(spec.summary)
                textSize = 14.5f
                setTextColor(p.sub)
                typeface = fontRegular
                setLineSpacing(4.dp.toFloat(), 1f)
            })
            addView(infoCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })

            // LiquidGlass frame holding the feature's live demo (drawn in code, see TourDemo)
            val artFrame = AspectFrame(this@FeatureIntroActivity, 0.75f).apply {
                background = LiquidGlass(p, 24.dp.toFloat(), dark, dens, level = 0)
                setPadding(7.dp, 7.dp, 7.dp, 7.dp)
            }
            val img = TourDemo(this@FeatureIntroActivity, spec.demo, p).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, out: android.graphics.Outline) {
                        out.setRoundRect(0, 0, v.width, v.height, 18.dp.toFloat())
                    }
                }
            }
            artFrame.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(artFrame, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    // ------------------------------------------------------------------ Slide Transitions & Swipe

    private fun attachSwipe(stage: View) {
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        stage.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!dragging && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        dragging = true
                        stage.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        val cur = activeSlideView
                        if (cur != null) {
                            cur.animate().cancel()
                            cur.translationX = dx * 0.35f
                            cur.alpha = (1f - kotlin.math.abs(dx) / (stage.width.coerceAtLeast(1) * 1.4f)).coerceIn(0.45f, 1f)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stage.parent?.requestDisallowInterceptTouchEvent(false)
                    val dx = ev.x - downX
                    val threshold = 48.dp.toFloat()
                    when {
                        dragging && dx < -threshold && slideIndex < slides.lastIndex -> showSlide(slideIndex + 1, dir = 1)
                        dragging && dx > threshold && slideIndex > 0 -> showSlide(slideIndex - 1, dir = -1)
                        else -> activeSlideView?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(180L)?.start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stage.parent?.requestDisallowInterceptTouchEvent(false)
                    activeSlideView?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(180L)?.start()
                    true
                }
                else -> false
            }
        }
    }

    private fun showSlide(targetIdx: Int, dir: Int, animate: Boolean = true) {
        val idx = targetIdx.coerceIn(0, slides.lastIndex)
        slideIndex = idx
        updateControls(idx)

        val outgoing = activeSlideView
        for (i in slideHost.childCount - 1 downTo 0) {
            val child = slideHost.getChildAt(i)
            child.animate().cancel()
            if (child !== outgoing) slideHost.removeViewAt(i)
        }

        val incoming = buildSlideView(idx)
        activeSlideView = incoming
        slideHost.addView(incoming, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (!animate || outgoing == null) {
            if (outgoing != null) slideHost.removeView(outgoing)
            return
        }

        incoming.alpha = 0f
        incoming.translationX = (dir * 46.dp).toFloat()
        incoming.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(slideInterp)
            .start()

        if (outgoing.parent === slideHost) {
            var removed = false
            fun removeOut() {
                if (!removed) {
                    removed = true
                    slideHost.removeView(outgoing)
                }
            }
            outgoing.animate()
                .alpha(0f)
                .translationX((-dir * 46.dp).toFloat())
                .setDuration(190L)
                .setInterpolator(slideInterp)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { removeOut() }
                    override fun onAnimationCancel(animation: Animator) { removeOut() }
                })
                .start()
        }
    }

    private fun updateControls(idx: Int) {
        counterView.text = "${idx + 1} / ${slides.size}"
        backBtn.visibility = if (idx > 0) View.VISIBLE else View.GONE
        nextBtn.text = I18n.t(if (idx == slides.lastIndex) "Start using SolCube  →" else "Next  →")
        for (i in dots.indices) {
            val d = dots[i]
            val act = i == idx
            (d.background as GradientDrawable).setColor(if (act) p.fill else alpha(p.sub, 0.34f))
            val lp = d.layoutParams as LinearLayout.LayoutParams
            lp.width = if (act) 20.dp else 7.dp
            d.layoutParams = lp
        }
    }

    // ------------------------------------------------------------------ Rich text & LiquidGlass helpers

    private fun pillTag(text: String, accent: Boolean = false, ok: Boolean = false): TextView {
        val dark = Look.isDark(this)
        val fgColor = when {
            ok -> p.ok
            accent -> p.accent
            else -> p.fg
        }
        val bgColor = when {
            ok -> alpha(p.ok, if (dark) 0.16f else 0.18f)
            accent -> alpha(p.accent, if (dark) 0.16f else 0.16f)
            else -> null
        }
        val border = when {
            ok -> alpha(p.ok, 0.55f)
            accent -> alpha(p.accent, 0.55f)
            else -> 0
        }
        return label(text, 11.5f, fgColor, bold = true).apply {
            maxLines = 1
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            background = LiquidGlass(
                p, 12.dp.toFloat(), dark, resources.displayMetrics.density,
                level = 1, tint = bgColor, stroke = border,
            )
        }
    }

    private fun richText(raw: String): CharSequence {
        val text = I18n.t(raw)
        val sb = SpannableStringBuilder()
        var i = 0
        val hiColor = p.link
        while (i < text.length) {
            when {
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end > i + 3) {
                        val s = sb.length
                        sb.append(text.substring(i + 3, end))
                        sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(hiColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 3
                        continue
                    }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        val s = sb.length
                        sb.append(text.substring(i + 2, end))
                        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(p.fg), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                        continue
                    }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        val s = sb.length
                        sb.append(text.substring(i + 1, end))
                        sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(p.fg), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                        continue
                    }
                }
            }
            sb.append(text[i])
            i++
        }
        return sb
    }

    private fun glass(radius: Int, level: Int = 2, tint: Int? = null, stroke: Int = 0): Drawable =
        LiquidGlass(p, radius.toFloat(), Look.isDark(this), resources.displayMetrics.density, level = level, tint = tint, stroke = stroke)

    private fun accentGradient(radius: Int): Drawable =
        LiquidGlass(
            p, radius.toFloat(), Look.isDark(this), resources.displayMetrics.density,
            level = 2, tint = p.fill, shine = p.fillShine, shadow = p.halo == 0,
            halo = p.halo, stroke = p.fillStroke,
        )

    private fun ripple(content: Drawable?, radius: Int): Drawable {
        val mask = GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(Color.WHITE) }
        val r = RippleDrawable(ColorStateList.valueOf(alpha(p.fg, 0.14f)), null, mask)
        return if (content == null) r else LayerDrawable(arrayOf(content, r))
    }

    /** [autoFit]: shrink the text (down to 10sp) instead of cutting it off, for longer translations.
     *  Needs a fixed width + height; pass false for WRAP_CONTENT buttons. */
    private fun pillButton(t: String, filled: Boolean, size: Float = 15f, autoFit: Boolean = true, onClick: () -> Unit) =
        label(t, size, if (filled) p.onAccent else p.fg, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (autoFit) {
                val sp = resources.displayMetrics.scaledDensity
                val minPx = (10f * sp).toInt().coerceAtLeast(1)
                val maxPx = (size * sp).toInt().coerceAtLeast(minPx + 1)
                setAutoSizeTextTypeUniformWithConfiguration(minPx, maxPx, 1, android.util.TypedValue.COMPLEX_UNIT_PX)
            }
            setPadding(14.dp, 0, 14.dp, 0)
            background = ripple(if (filled) accentGradient(27.dp) else glass(27.dp), 27.dp)
            setOnClickListener { onClick() }
        }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = I18n.t(t)
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontMedium else fontRegular
    }

    private fun alpha(color: Int, a: Float) =
        Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.decorView.setBackgroundColor(p.base)
        window.setDecorFitsSystemWindows(false)
        val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (Look.isDark(this)) 0 else f, f)
    }
}
