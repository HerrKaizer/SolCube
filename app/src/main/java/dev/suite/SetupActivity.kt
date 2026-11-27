package dev.suite

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsetsController
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Ad-style animated Feature Showcase + floating sliding pop-up onboarding wizard.
 * Guides the user calmly through Developer options and Wireless debugging without scary jargon,
 * highlights key tap targets with bold + italic spans, and keeps a subtle "Use basic features only"
 * option at the bottom.
 */
class SetupActivity : Activity() {

    companion object {
        private val activeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val isActive: Boolean get() = activeCount.get() > 0
    }

    private enum class Step { INTRO, UNHIDE_DEV, ENABLE_DEV, CONNECT }

    private lateinit var p: Look.Palette
    private val fontRegular: Typeface by lazy { Fonts.get(this, 400) }
    private val fontMedium: Typeface by lazy { Fonts.get(this, 560) }
    private val fontBold: Typeface by lazy { Fonts.get(this, 680) }
    private val main = Handler(Looper.getMainLooper())
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private val slideInterp = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var currentStep = Step.INTRO
    private var forcePair = false
    private var pendingOpenWireless = false
    private var finishingDone = false
    private var inForeground = false
    // Fixed when the screen opens: pairing succeeding mid-flow must not flip the wizard into "reconnect" wording.
    private var pairedAtStart = false
    private val previewMode: Boolean get() = intent.getBooleanExtra("preview", false)

    private lateinit var showcaseStage: FrameLayout
    private lateinit var dotsRow: LinearLayout
    private lateinit var langBtn: View
    private lateinit var taglineView: TextView
    private lateinit var topSpacer: View
    private lateinit var bottomSpacer: View
    private var imeOpen = false
    private var activeSlideView: View? = null
    private val slideDots = mutableListOf<View>()
    private var slideIndex = 0
    private var slideTimer: Runnable? = null

    private lateinit var stepHost: FrameLayout
    private var currentStepView: View? = null

    // Dynamic views inside Step.CONNECT (updated live as mDNS / Wi-Fi / pairing state changes)
    private var wifiBanner: TextView? = null
    private var statusDot: View? = null
    private var statusLabel: TextView? = null
    private var devStatusLabel: TextView? = null

    private val shellListener: () -> Unit = {
        if (!previewMode && Shell.state() == Shell.State.READY) onConnectedFinish() else refreshCurrentStep()
    }

    private val pairingListener: (PairingService.Snapshot) -> Unit = { snap ->
        if (!previewMode && snap.phase == PairingService.Phase.READY) {
            onConnectedFinish()
        } else {
            renderPairingSnapshot(snap)
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!previewMode && Shell.state() == Shell.State.READY) {
                onConnectedFinish()
                return
            }
            if (!autoAdvance()) refreshCurrentStep()
            main.postDelayed(this, 750L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        activeCount.incrementAndGet()
        Island.hide()
        I18n.init(this)
        setTheme(if (Look.isDark(this)) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        p = Look.palette(this)
        actionBar?.hide()
        styleSystemBars()

        pairedAtStart = AdbEngine.isPaired(this)
        currentStep = initialStep()
        setContentView(buildRoot())
        showStepImmediate(currentStep)

        Shell.addStateListener(shellListener)
        PairingService.addListener(pairingListener)
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        Island.hide()
        if (!previewMode) {
            Shell.tryAttachExisting(applicationContext)
            if (Shell.state() == Shell.State.READY) {
                onConnectedFinish()
                return
            }
        }
        if (!autoAdvance()) {
            refreshCurrentStep()
            syncStageVisibility()
        }
        main.removeCallbacks(pollTask)
        main.postDelayed(pollTask, 750L)
    }

    override fun onPause() {
        inForeground = false
        main.removeCallbacks(pollTask)
        stopCarousel()
        super.onPause()
    }

    override fun onDestroy() {
        activeCount.decrementAndGet()
        main.removeCallbacks(pollTask)
        stopCarousel()
        Shell.removeStateListener(shellListener)
        PairingService.removeListener(pairingListener)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            currentStep == Step.CONNECT && !isReconnectMode() -> {
                val backStep = if (AdbEngine.isDevOptionsEnabled(this)) Step.INTRO else Step.ENABLE_DEV
                transitionTo(backStep, forward = false)
            }
            currentStep == Step.ENABLE_DEV -> transitionTo(Step.UNHIDE_DEV, forward = false)
            currentStep == Step.UNHIDE_DEV -> transitionTo(Step.INTRO, forward = false)
            intent.getBooleanExtra("from_unlock", false) ||
                previewMode ||
                SetupPrefs.skippedBasic(this) ||
                SetupPrefs.hasEverConnected(this) -> {
                PairingService.stop(this)
                super.onBackPressed()
            }
            else -> confirmBasicMode()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201 && pendingOpenWireless) {
            pendingOpenWireless = false
            launchWirelessSettings(reconnect = isReconnectMode())
        }
    }

    /** Where the Developer-options state says the user is now (vivo hides and enables them separately). */
    private fun devStep(): Step = when {
        AdbEngine.isDevOptionsEnabled(this) -> Step.CONNECT
        AdbEngine.isDevOptionsUnhidden(this) -> Step.ENABLE_DEV
        else -> Step.UNHIDE_DEV
    }

    /** Steps 1–2 move on by themselves as soon as Settings reflects the change. */
    private fun autoAdvance(): Boolean {
        if (previewMode) return false
        if (currentStep != Step.UNHIDE_DEV && currentStep != Step.ENABLE_DEV) return false
        val target = devStep()
        if (target.ordinal <= currentStep.ordinal) return false
        transitionTo(target, forward = true)
        return true
    }

    private fun initialStep(): Step = when {
        previewMode -> Step.INTRO
        isReconnectMode() -> if (AdbEngine.isDevOptionsEnabled(this)) Step.CONNECT else devStep()
        else -> Step.INTRO
    }

    private fun isReconnectMode(): Boolean = !previewMode && pairedAtStart && !forcePair

    private fun onConnectedFinish() {
        if (finishingDone || isFinishing || isDestroyed) return
        finishingDone = true
        SetupPrefs.markConnected(this)
        AppLog.add(this, "Setup finished: SolEngine connected")
        PairingService.stop(this)
        Thread {
            runCatching { Components.reconcile(applicationContext) }
            runCatching { SolCubeService.sync(applicationContext) }
        }.start()
        main.post {
            Toast.makeText(this, I18n.t("SolCube is ready"), Toast.LENGTH_SHORT).show()
            // Only from the foreground: Android blocks activity starts while the user is still in Settings.
            // Otherwise MainActivity opens the tour the next time SolCube comes to the front.
            if (inForeground && !SetupPrefs.hasSeenIntro(this) && !FeatureIntroActivity.isOpen) {
                startActivity(Intent(this, FeatureIntroActivity::class.java))
            }
            finish()
        }
    }

    // ------------------------------------------------------------------ root layout & showcase stage

    private fun buildRoot(): View {
        val dark = Look.isDark(this)
        val dens = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // True GlassBackdrop wallpaper so every LiquidGlass card & button refracts live.
        root.addView(GlassBackdrop(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // 1. Compact Brand Header + Language pill + carousel progress dots
        val brandBar = LinearLayout(this).apply {
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
                    out.setRoundRect(0, 0, v.width, v.height, 9.dp.toFloat())
                }
            }
        }
        brandBar.addView(iconView, LinearLayout.LayoutParams(32.dp, 32.dp).apply { rightMargin = 10.dp })
        brandBar.addView(label("SolCube", 22f, p.fg, bold = true).apply {
            typeface = fontBold
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        langBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 0, 10.dp, 0)
            background = ripple(glass(15.dp, level = 1), 15.dp)
            setOnClickListener { showLanguagePicker() }

            val globe = object : View(this@SetupActivity) {
                private val d = resources.displayMetrics.density
                private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.4f * d
                    color = p.fg
                }
                private val oval = RectF()
                override fun onDraw(canvas: Canvas) {
                    val cx = width * 0.5f
                    val cy = height * 0.5f
                    val r = minOf(width, height) * 0.42f
                    canvas.drawCircle(cx, cy, r, gp)
                    oval.set(cx - r * 0.46f, cy - r, cx + r * 0.46f, cy + r)
                    canvas.drawOval(oval, gp)
                    canvas.drawLine(cx - r, cy, cx + r, cy, gp)
                }
            }
            addView(globe, LinearLayout.LayoutParams(14.dp, 14.dp).apply { rightMargin = 5.dp })
            val cur = I18n.current()
            val codeText = if (cur == I18n.LANG_ZH) "中文" else cur.uppercase()
            addView(TextView(this@SetupActivity).apply {
                text = codeText
                textSize = 11.5f
                typeface = fontMedium
                setTextColor(p.fg)
            })
        }
        brandBar.addView(langBtn, LinearLayout.LayoutParams(WRAP_CONTENT, 30.dp).apply { rightMargin = 10.dp })

        dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        slideDots.clear()
        for (i in 0 until 5) {
            val dot = View(this).apply {
                background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat() }
                setOnClickListener {
                    if (i != slideIndex) showSlide(i, if (i > slideIndex) 1 else -1)
                }
            }
            slideDots += dot
            dotsRow.addView(dot, LinearLayout.LayoutParams(if (i == 0) 18.dp else 6.dp, 6.dp).apply {
                if (i > 0) leftMargin = 5.dp
            })
        }
        updateSlideDots(0)
        brandBar.addView(dotsRow)
        col.addView(brandBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        taglineView = TextView(this).apply {
            text = richText("The all-in-one power toolkit for ***vivo***.")
            textSize = 13.5f
            setTextColor(p.sub)
            typeface = fontRegular
        }
        col.addView(taglineView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp; bottomMargin = 10.dp })

        // 2. Animated Feature Showcase Stage (first screen only) with live manual swipe + tap support
        showcaseStage = FitStage(this, minScale = 0.55f).apply {
            clipChildren = false
            clipToPadding = false
        }
        val firstSlide = buildSlide(0)
        activeSlideView = firstSlide
        showcaseStage.addView(firstSlide, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        attachCarouselTouch(showcaseStage)
        col.addView(showcaseStage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        topSpacer = View(this).apply { visibility = View.GONE }
        col.addView(topSpacer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 3. Floating LiquidGlass Pop-up Wizard Card (lower half on Intro, centered on Steps 1-3)
        val popupCard = FrameLayout(this).apply {
            background = LiquidGlass(p, 30.dp.toFloat(), dark, dens, level = 0)
            minimumHeight = 300.dp
            clipChildren = false
            clipToPadding = false
            setPadding(10.dp, 14.dp, 10.dp, 10.dp)
        }
        stepHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(14.dp, 12.dp, 14.dp, 14.dp)
        }
        val stepScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // a tall step scrolls inside the card: clip to it and fade the edges so nothing slides over the logo
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(28.dp)
            addView(stepHost, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        popupCard.addView(stepScroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(popupCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })

        bottomSpacer = View(this).apply { visibility = View.GONE }
        col.addView(bottomSpacer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 4. Teeny-weeny skip link below the pop-up card
        val basicLink = label("Use basic features only", 12.5f, alpha(p.fg, 0.78f), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(14.dp, 7.dp, 14.dp, 7.dp)
            setShadowLayer(4f, 0f, 1f, if (dark) Color.argb(170, 0, 0, 0) else Color.argb(140, 255, 255, 255))
            background = ripple(glass(16.dp, level = 1), 16.dp)
            setOnClickListener { confirmBasicMode() }
        }
        // only for first-time setup; a returning user just needs Start
        if (SetupPrefs.hasEverConnected(this) || isReconnectMode()) basicLink.visibility = View.GONE
        col.addView(basicLink, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 6.dp
        })

        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            imeOpen = ime.bottom > sys.bottom + 80.dp
            syncStageVisibility()
            col.setPadding(18.dp, sys.top + 14.dp, 18.dp, maxOf(sys.bottom, ime.bottom) + 14.dp)
            insets
        }
        return root
    }

    private fun syncStageVisibility() {
        if (!::showcaseStage.isInitialized) return
        val showCarousel = currentStep == Step.INTRO
        langBtn.visibility = if (showCarousel) View.VISIBLE else View.GONE
        dotsRow.visibility = if (showCarousel) View.VISIBLE else View.GONE
        taglineView.visibility = if (showCarousel) View.VISIBLE else View.GONE
        showcaseStage.visibility = if (showCarousel && !imeOpen) View.VISIBLE else View.GONE
        topSpacer.visibility = if (!showCarousel && !imeOpen) View.VISIBLE else View.GONE
        bottomSpacer.visibility = if (!showCarousel && !imeOpen) View.VISIBLE else View.GONE
        if (showCarousel && !imeOpen) startCarousel() else stopCarousel()
    }

    // ------------------------------------------------------------------ Showcase Carousel

    private fun startCarousel() {
        stopCarousel()
        val r = object : Runnable {
            override fun run() {
                if (showcaseStage.visibility == View.VISIBLE) {
                    showSlide((slideIndex + 1) % 5, dir = 1)
                }
                main.postDelayed(this, 3800L)
            }
        }
        slideTimer = r
        main.postDelayed(r, 3800L)
    }

    private fun stopCarousel() {
        slideTimer?.let { main.removeCallbacks(it) }
        slideTimer = null
    }

    private fun attachCarouselTouch(stage: View) {
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        stage.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    dragging = false
                    stopCarousel()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
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
                android.view.MotionEvent.ACTION_UP -> {
                    stage.parent?.requestDisallowInterceptTouchEvent(false)
                    val dx = ev.x - downX
                    val threshold = 44.dp.toFloat()
                    when {
                        dragging && dx < -threshold -> showSlide((slideIndex + 1) % 5, dir = 1)
                        dragging && dx > threshold -> showSlide((slideIndex + 4) % 5, dir = -1)
                        !dragging && kotlin.math.abs(dx) < slop -> showSlide((slideIndex + 1) % 5, dir = 1)
                        else -> {
                            activeSlideView?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(180L)?.start()
                            startCarousel()
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    stage.parent?.requestDisallowInterceptTouchEvent(false)
                    activeSlideView?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(180L)?.start()
                    startCarousel()
                    true
                }
                else -> false
            }
        }
    }

    private fun showSlide(targetIdx: Int, dir: Int) {
        if (!::showcaseStage.isInitialized || showcaseStage.visibility != View.VISIBLE) return
        val idx = ((targetIdx % 5) + 5) % 5
        slideIndex = idx
        updateSlideDots(idx)
        startCarousel()

        // Remove any stale/mid-exit children immediately so rapid swipes never pile up.
        val outgoing = activeSlideView
        for (i in showcaseStage.childCount - 1 downTo 0) {
            val child = showcaseStage.getChildAt(i)
            child.animate().cancel()
            if (child !== outgoing) {
                showcaseStage.removeViewAt(i)
            }
        }

        val newView = buildSlide(idx).apply {
            alpha = 0f
            translationX = (dir * 42.dp).toFloat()
        }
        activeSlideView = newView
        showcaseStage.addView(newView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        newView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(280L)
            .setInterpolator(slideInterp)
            .start()

        if (outgoing != null && outgoing.parent === showcaseStage) {
            var removed = false
            fun removeOutgoing() {
                if (!removed) {
                    removed = true
                    showcaseStage.removeView(outgoing)
                }
            }
            outgoing.animate()
                .alpha(0f)
                .translationX((-dir * 42.dp).toFloat())
                .setDuration(200L)
                .setInterpolator(slideInterp)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { removeOutgoing() }
                    override fun onAnimationCancel(animation: Animator) { removeOutgoing() }
                })
                .start()
        }
    }

    private fun updateSlideDots(active: Int) {
        for (i in slideDots.indices) {
            val d = slideDots[i]
            val isAct = i == active
            (d.background as GradientDrawable).setColor(if (isAct) p.fill else alpha(p.sub, 0.32f))
            val lp = d.layoutParams as LinearLayout.LayoutParams
            lp.width = if (isAct) 18.dp else 6.dp
            d.layoutParams = lp
        }
    }

    private fun showcaseCardBox() = LinearLayout(this).apply {
        val dark = Look.isDark(this@SetupActivity)
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(16.dp, 14.dp, 16.dp, 16.dp)
        background = LiquidGlass(p, 22.dp.toFloat(), dark, resources.displayMetrics.density, level = 0)
    }

    private fun showcasePill(text: String, accent: Boolean = false, ok: Boolean = false): TextView {
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

    private fun buildSlide(index: Int): View {
        val dark = Look.isDark(this)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val (badge, line1, line2) = when (index) {
            0 -> Triple("GAMING", "Frame-gen any game.", "120 FPS & live HUD.")
            1 -> Triple("SHORTCUTS", "Circle to Search,", "and buttons that obey.")
            2 -> Triple("GESTURES", "Draw any shape.", "Run any action.")
            3 -> Triple("AUTOMATION", "Smart routines &", "auto-revert profiles.")
            else -> Triple("AUDIO", "Play two apps at once.", "1% precision volume.")
        }

        wrap.addView(showcasePill("•  ${I18n.t(badge)}", accent = true).apply {
            textSize = 10.5f
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 8.dp })

        wrap.addView(label(line1, 24f, p.fg, bold = true).apply {
            typeface = fontBold
            letterSpacing = -0.02f
        })
        wrap.addView(label(line2, 24f, if (dark) p.accent else p.link, bold = true).apply {
            typeface = fontBold
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10.dp })

        val card = showcaseCardBox()
        when (index) {
            0 -> {
                // Gaming HUD preview (matching Showcase/ready/04_gaming.png)
                // wraps onto a second line when translations are longer
                val hudRow = FlowRow(this, hGap = 8.dp, vGap = 6.dp)
                hudRow.addView(showcasePill("✓  FI 60→120", ok = true))
                hudRow.addView(showcasePill("118 FPS", accent = true))
                hudRow.addView(showcasePill("75° · 79° · 37°"))
                card.addView(hudRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10.dp })
                card.addView(label("Per-game 45→90 & 60→120 frame interpolation with heat auto-pause and persistent Super Resolution.", 12.5f, p.sub).apply {
                    tag = FitStage.OPTIONAL
                    setLineSpacing(2.dp.toFloat(), 1f)
                })
            }
            1 -> {
                // Circle to Search + Power button remap preview (matching Showcase/ready/05_shortcuts.png)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val rippleRing = object : View(this) {
                    private val d = resources.displayMetrics.density
                    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 3.2f * d
                        strokeCap = Paint.Cap.ROUND
                    }
                    override fun onDraw(canvas: Canvas) {
                        val cx = width * 0.5f
                        val cy = height * 0.5f
                        val r = minOf(width, height) * 0.40f
                        ringPaint.shader = SweepGradient(
                            cx, cy,
                            intArrayOf(p.accent, p.ok, Color.rgb(0x6C, 0xB8, 0xFF), p.accent),
                            null,
                        )
                        canvas.drawCircle(cx, cy, r, ringPaint)
                    }
                }
                row.addView(rippleRing, LinearLayout.LayoutParams(52.dp, 52.dp).apply { rightMargin = 12.dp })
                val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val chips = FlowRow(this, hGap = 6.dp, vGap = 6.dp)
                chips.addView(showcasePill("Two-finger hold", accent = true))
                chips.addView(showcasePill("Power hold"))
                chips.addView(showcasePill("Nav bar"))
                rightCol.addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 6.dp })
                rightCol.addView(label("Launch Circle to Search, flashlight, silent mode, or any app anywhere.", 12f, p.sub).apply { tag = FitStage.OPTIONAL })
                row.addView(rightCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                card.addView(row)
            }
            2 -> {
                // Gestures stroke preview (matching Showcase/ready/03_gestures.png)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val pad = object : View(this) {
                    private val d = resources.displayMetrics.density
                    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 3.5f * d
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        color = if (dark) p.accent else p.link
                    }
                    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ok }
                    private val path = Path()
                    override fun onDraw(canvas: Canvas) {
                        val w = width.toFloat()
                        val h = height.toFloat()
                        path.reset()
                        path.moveTo(w * 0.22f, h * 0.72f)
                        path.lineTo(w * 0.50f, h * 0.28f)
                        path.lineTo(w * 0.78f, h * 0.72f)
                        canvas.drawPath(path, strokePaint)
                        canvas.drawCircle(w * 0.22f, h * 0.72f, 4f * d, dotPaint)
                    }
                }.apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 14.dp.toFloat()
                        setColor(if (dark) Color.argb(140, 0, 0, 0) else Color.argb(18, 0, 0, 0))
                        setStroke(1.dp, alpha(p.stroke, 0.7f))
                    }
                }
                row.addView(pad, LinearLayout.LayoutParams(64.dp, 64.dp).apply { rightMargin = 12.dp })
                val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val chips = FlowRow(this, hGap = 6.dp, vGap = 6.dp)
                chips.addView(showcasePill("1 or 2 fingers", accent = true))
                chips.addView(showcasePill("Custom shapes"))
                rightCol.addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 6.dp })
                rightCol.addView(label("Sketch over any app — even while scrolling — to trigger apps or routines.", 12f, p.sub).apply { tag = FitStage.OPTIONAL })
                row.addView(rightCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                card.addView(row)
            }
            3 -> {
                // Automation & Profiles preview (matching Showcase/ready/02_automation.png)
                val pillRow = FlowRow(this, hGap = 8.dp, vGap = 6.dp)
                pillRow.addView(showcasePill("✓  Work off", ok = true))
                pillRow.addView(showcasePill("✓  Wallet off", ok = true))
                pillRow.addView(showcasePill("45+ triggers", accent = true))
                card.addView(pillRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10.dp })
                card.addView(label("Open Wallet → NFC ON · DND · Boost. Leave app → everything reverts automatically.", 12.5f, p.sub).apply {
                    tag = FitStage.OPTIONAL
                    setLineSpacing(2.dp.toFloat(), 1f)
                })
            }
            else -> {
                // Audio mixing & 1% precision knob preview (matching Showcase/ready/06_audio.png)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val dial = object : View(this) {
                    private val d = resources.displayMetrics.density
                    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 4f * d
                        strokeCap = Paint.Cap.ROUND
                        color = alpha(p.sub, 0.25f)
                    }
                    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 4f * d
                        strokeCap = Paint.Cap.ROUND
                        color = if (dark) p.accent else p.link
                    }
                    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textAlign = Paint.Align.CENTER
                        textSize = 13f * d
                        typeface = fontBold
                        color = p.fg
                    }
                    private val box = RectF()
                    override fun onDraw(canvas: Canvas) {
                        val m = 6f * d
                        box.set(m, m, width - m, height - m)
                        canvas.drawArc(box, 135f, 270f, false, track)
                        canvas.drawArc(box, 135f, 270f * 0.37f, false, arc)
                        val fm = txt.fontMetrics
                        val ty = height * 0.5f - (fm.ascent + fm.descent) * 0.5f
                        canvas.drawText("37%", width * 0.5f, ty, txt)
                    }
                }
                row.addView(dial, LinearLayout.LayoutParams(58.dp, 58.dp).apply { rightMargin = 12.dp })
                val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val chips = FlowRow(this, hGap = 6.dp, vGap = 6.dp)
                chips.addView(showcasePill("1% steps", accent = true))
                chips.addView(showcasePill("Spotify + Game", ok = true))
                rightCol.addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 6.dp })
                rightCol.addView(label("Two apps play sound together, plus exact 1% media volume control.", 12f, p.sub).apply { tag = FitStage.OPTIONAL })
                row.addView(rightCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                card.addView(row)
            }
        }
        wrap.addView(card)
        return wrap
    }

    // ------------------------------------------------------------------ Sliding Wizard Pop-up

    private fun showStepImmediate(step: Step) {
        currentStep = step
        stepHost.removeAllViews()
        val v = buildStepView(step)
        currentStepView = v
        stepHost.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        refreshCurrentStep()
        syncStageVisibility()
    }

    private fun transitionTo(next: Step, forward: Boolean) {
        if (next == currentStep) {
            refreshCurrentStep()
            syncStageVisibility()
            return
        }
        currentStep = next
        syncStageVisibility()
        val oldView = currentStepView
        for (i in stepHost.childCount - 1 downTo 0) {
            val child = stepHost.getChildAt(i)
            child.animate().cancel()
            if (child !== oldView) stepHost.removeViewAt(i)
        }
        val newView = buildStepView(next).apply {
            alpha = 0f
            translationX = (if (forward) 48 else -48).dp.toFloat()
        }
        currentStepView = newView
        stepHost.addView(newView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        refreshCurrentStep()

        newView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(280L)
            .setInterpolator(slideInterp)
            .start()

        if (oldView != null && oldView.parent === stepHost) {
            var removed = false
            fun removeOld() {
                if (!removed) {
                    removed = true
                    stepHost.removeView(oldView)
                }
            }
            oldView.animate()
                .alpha(0f)
                .translationX((if (forward) -48 else 48).dp.toFloat())
                .setDuration(200L)
                .setInterpolator(slideInterp)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { removeOld() }
                    override fun onAnimationCancel(animation: Animator) { removeOld() }
                })
                .start()
        }
    }

    private fun buildStepView(step: Step): View {
        wifiBanner = null
        statusDot = null
        statusLabel = null
        devStatusLabel = null

        return when (step) {
            Step.INTRO -> buildIntroStep()
            Step.UNHIDE_DEV -> buildUnhideDevStep()
            Step.ENABLE_DEV -> buildEnableDevStep()
            Step.CONNECT -> buildConnectStep()
        }
    }

    private fun buildIntroStep(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        val chips = FlowRow(this@SetupActivity, hGap = 8.dp, vGap = 6.dp)
        listOf("No root", "No PC", "Undo anytime").forEach {
            chips.addView(showcasePill(it).apply {
                textSize = 12.5f
                setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            })
        }
        addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 14.dp })

        addView(label("One-minute unlock", 25f, p.fg, bold = true).apply { typeface = fontBold })
        addView(
            TextView(this@SetupActivity).apply {
                text = richText(
                    "*Circle to Search*, *Frame gen*, *Gestures* and *Routines* need one permission that Android only gives through its own settings. Three short steps, about a minute, and you never do it again.",
                )
                textSize = 16f
                setTextColor(p.sub)
                typeface = fontRegular
                setLineSpacing(5.dp.toFloat(), 1f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp },
        )
        addView(
            label("Is this safe?", 14.5f, p.link, bold = true).apply {
                setPadding(0, 8.dp, 16.dp, 8.dp)
                setOnClickListener { showSafety() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 4.dp; bottomMargin = 12.dp },
        )
        addView(
            pillButton("Let's go  →", filled = true, size = 16.5f) {
                transitionTo(if (previewMode) Step.UNHIDE_DEV else devStep(), forward = true)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 54.dp),
        )
    }

    private fun stepHeaderRow(badgeText: String, onBack: (() -> Unit)?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (onBack != null) {
            addView(
                label("←", 16f, p.fg, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(12.dp, 4.dp, 12.dp, 4.dp)
                    background = ripple(glass(16.dp, level = 1), 16.dp)
                    setOnClickListener { onBack() }
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, 36.dp).apply { rightMargin = 10.dp },
            )
        }
        addView(showcasePill(badgeText, accent = true).apply {
            textSize = 12.5f
            setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            // preview (Settings → Diagnostics → Open setup): tap the step badge to see the next screen
            if (previewMode) setOnClickListener {
                when (currentStep) {
                    Step.UNHIDE_DEV -> transitionTo(Step.ENABLE_DEV, forward = true)
                    Step.ENABLE_DEV -> transitionTo(Step.CONNECT, forward = true)
                    else -> confirmBasicMode()
                }
            }
        })
    }

    private fun buildUnhideDevStep(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(stepHeaderRow("Step 1 of 3") { transitionTo(Step.INTRO, forward = false) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 14.dp })
        addView(label("Show hidden settings", 25f, p.fg, bold = true).apply { typeface = fontBold })
        addView(
            TextView(this@SetupActivity).apply {
                text = richText(
                    "Open *About phone*, tap ***Version information*** *(if shown)*, then tap ***Software version*** or ***Build number*** ***7 times***. Android then says *“You are now a developer”*. That's just the name of these settings.",
                )
                textSize = 16f
                setTextColor(p.sub)
                typeface = fontRegular
                setLineSpacing(5.dp.toFloat(), 1f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp; bottomMargin = 22.dp },
        )
        addView(
            pillButton("Open About phone  →", filled = true, size = 16.5f) { openAboutPhone() },
            LinearLayout.LayoutParams(MATCH_PARENT, 54.dp),
        )
        // No "I did it" button: SolCube sees the change and moves on by itself.
        addView(waitingRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
    }

    /** "Waiting for Developer options…" with a softly pulsing dot. */
    private fun waitingRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dot = View(this@SetupActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accent) }
        }
        ValueAnimator.ofFloat(1f, 0.25f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            dot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { start() }
                override fun onViewDetachedFromWindow(v: View) { cancel() }
            })
        }
        devStatusLabel = label("Waiting… SolCube moves on by itself", 14f, p.sub)
        addView(dot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 9.dp })
        addView(devStatusLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun buildEnableDevStep(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(stepHeaderRow("Step 2 of 3") { transitionTo(Step.UNHIDE_DEV, forward = false) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 14.dp })
        addView(label("Switch them on", 25f, p.fg, bold = true).apply { typeface = fontBold })
        addView(
            TextView(this@SetupActivity).apply {
                text = richText(
                    "Tap below and turn on the switch at the top, called ***Developer options***. Then come back here.",
                )
                textSize = 16f
                setTextColor(p.sub)
                typeface = fontRegular
                setLineSpacing(5.dp.toFloat(), 1f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp; bottomMargin = 22.dp },
        )
        addView(
            pillButton("Open Developer options  →", filled = true, size = 16.5f) { openDeveloperOptions() },
            LinearLayout.LayoutParams(MATCH_PARENT, 54.dp),
        )
        addView(waitingRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
    }

    private fun buildConnectStep(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        val reconnect = isReconnectMode()
        val devAlreadyOn = AdbEngine.isDevOptionsEnabled(this@SetupActivity)
        val badge = when {
            reconnect -> "Quick reconnect"
            devAlreadyOn -> "Final step"
            else -> "Step 3 of 3"
        }
        addView(
            stepHeaderRow(badge, onBack = if (reconnect) null else {
                { transitionTo(if (devAlreadyOn) Step.INTRO else Step.ENABLE_DEV, forward = false) }
            }),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 14.dp },
        )
        addView(label(if (reconnect) "Reconnect SolCube" else "Link SolCube", 25f, p.fg, bold = true).apply {
            typeface = fontBold
        })

        wifiBanner = label("", 13.5f, p.sub).apply {
            text = richText("**Connect to Wi-Fi** to continue.")
            setLineSpacing(3.dp.toFloat(), 1f)
            setPadding(14.dp, 10.dp, 14.dp, 10.dp)
            background = GradientDrawable().apply {
                cornerRadius = 12.dp.toFloat()
                setColor(alpha(p.accent, 0.10f))
                setStroke(1.dp, alpha(p.accent, 0.35f))
            }
            visibility = View.GONE
        }
        addView(wifiBanner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })

        if (reconnect) {
            addView(
                TextView(this@SetupActivity).apply {
                    text = richText("SolCube paused, usually after a restart without Wi-Fi. Tap ***Start*** and it reconnects by itself.")
                    textSize = 16f
                    setTextColor(p.sub)
                    typeface = fontRegular
                    setLineSpacing(5.dp.toFloat(), 1f)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp; bottomMargin = 20.dp },
            )
        } else {
            val stepsBox = LinearLayout(this@SetupActivity).apply { orientation = LinearLayout.VERTICAL }
            listOf(
                "**1.** Turn on ***Use wireless debugging***, then tap ***Allow***",
                "**2.** Tap ***Pair device with pairing code***",
                "**3.** Pull down notifications, tap ***Enter code*** and type the 6 digits",
            ).forEach { line ->
                stepsBox.addView(
                    TextView(this@SetupActivity).apply {
                        text = richText(line)
                        textSize = 16f
                        setTextColor(p.sub)
                        typeface = fontRegular
                        setLineSpacing(4.dp.toFloat(), 1f)
                    },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 9.dp },
                )
            }
            addView(stepsBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })
            addView(PairHintView(this@SetupActivity, p, fontRegular, fontBold),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 16.dp })
        }

        if (reconnect) {
            addView(
                pillButton("Start SolEngine  →", filled = true, size = 16.5f) { startEngineNow() },
                LinearLayout.LayoutParams(MATCH_PARENT, 54.dp),
            )
            // Fallback, shown only when Start couldn't do it on its own
            wdFallback = pillButton("Open Wireless debugging", filled = false, size = 14.5f) { startSetupAction(reconnect = true) }
                .apply { visibility = View.GONE }
            addView(wdFallback, LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { topMargin = 10.dp })
        } else {
            addView(
                pillButton("Open Wireless debugging  →", filled = true, size = 16.5f) { startSetupAction(reconnect = false) },
                LinearLayout.LayoutParams(MATCH_PARENT, 54.dp),
            )
        }

        // No in-app code box: leaving Settings closes its pairing dialog and the code changes, so the
        // code can only be typed into the notification while the dialog stays open.

        val statusRow = LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this@SetupActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(alpha(p.sub, 0.5f)) }
        }
        statusLabel = label("Ready when you are", 14f, p.sub)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { rightMargin = 9.dp })
        statusRow.addView(statusLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
    }

    private fun refreshCurrentStep() {
        if (currentStep == Step.CONNECT) {
            wifiBanner?.visibility = if (!isReconnectMode() && !AdbEngine.isOnWifi(this)) View.VISIBLE else View.GONE
            renderPairingSnapshot(PairingService.snapshot)
        }
    }

    private fun renderPairingSnapshot(snap: PairingService.Snapshot) {
        val lbl = statusLabel ?: return
        val dot = statusDot ?: return
        val msg = when (snap.phase) {
            PairingService.Phase.IDLE -> engineMsg ?: if (isReconnectMode()) "Ready to reconnect" else "Ready when you are"
            PairingService.Phase.WATCHING -> if (isReconnectMode()) "Waiting for Wireless debugging…" else "Waiting for you to tap Pair device…"
            PairingService.Phase.PAIRING_READY -> "Got it. Now pull down notifications and tap Enter code"
            PairingService.Phase.PAIRING -> "Checking the code…"
            PairingService.Phase.CONNECTING -> "Starting SolCube…"
            PairingService.Phase.READY -> "All set!"
            PairingService.Phase.ERROR -> snap.error.ifEmpty { "That didn't work. Tap the button to try again; nothing was changed." }
        }
        lbl.text = I18n.t(msg)
        val dotColor = when (snap.phase) {
            PairingService.Phase.READY, PairingService.Phase.PAIRING_READY -> p.ok
            PairingService.Phase.ERROR -> p.warn
            PairingService.Phase.PAIRING, PairingService.Phase.CONNECTING -> p.accent
            else -> alpha(p.sub, 0.55f)
        }
        (dot.background as GradientDrawable).setColor(dotColor)
        val warn = snap.phase == PairingService.Phase.ERROR ||
            (snap.phase == PairingService.Phase.IDLE && engineMsg != null && !engineStarting)
        lbl.setTextColor(if (warn) p.warn else p.sub)
    }

    // ------------------------------------------------------------------ Actions

    private var engineStarting = false
    private var wdFallback: View? = null
    private var engineMsg: String? = null   // last one-tap start status, kept across status refreshes

    /** One tap: Wireless debugging on if needed, connect, launch SolEngine. */
    private fun startEngineNow() {
        if (previewMode) { previewToast(); return }
        if (engineStarting) return
        engineStarting = true
        engineMsg = "Starting SolEngine…"
        refreshCurrentStep()
        Thread {
            val r = AdbEngine.start(applicationContext, "setup")
            main.post {
                engineStarting = false
                if (isFinishing || isDestroyed) return@post
                if (r == AdbEngine.Start.OK) onConnectedFinish()
                else {
                    engineMsg = AdbEngine.startMessage(r)
                    if (r == AdbEngine.Start.FAILED || r == AdbEngine.Start.NO_PERMISSION) wdFallback?.visibility = View.VISIBLE
                    refreshCurrentStep()
                    when (r) {
                        AdbEngine.Start.DEV_OFF -> transitionTo(Step.ENABLE_DEV, forward = false)
                        AdbEngine.Start.NOT_PAIRED -> {   // full pairing (rebuild: the step layout depends on reconnect mode)
                            forcePair = true
                            engineMsg = null
                            val next = devStep()
                            if (next == currentStep) showStepImmediate(next) else transitionTo(next, forward = true)
                        }
                        else -> {}
                    }
                }
            }
        }.start()
    }

    private fun startSetupAction(reconnect: Boolean) {
        engineMsg = null
        if (previewMode) { previewToast(); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingOpenWireless = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201)
            return
        }
        launchWirelessSettings(reconnect)
    }

    private fun launchWirelessSettings(reconnect: Boolean) {
        if (reconnect) PairingService.startReconnect(this) else PairingService.startPairing(this)
        // Straight to the Wireless debugging page (switch + "Pair device with pairing code"): Settings opens
        // it for a Quick Settings tile long-press. vivo routes that through a gateway activity, AOSP through
        // the tile preferences action. Fall back to Developer options with the row highlighted.
        for (i in wirelessDebuggingIntents()) {
            if (runCatching { startActivity(i); true }.getOrDefault(false)) return
        }
        val primary = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { startActivity(primary); true }.getOrDefault(false)) return
        val fallback = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!runCatching { startActivity(fallback); true }.getOrDefault(false)) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun previewToast() =
        Toast.makeText(this, I18n.t("Preview only — SolCube is already connected"), Toast.LENGTH_SHORT).show()

    private fun wirelessDebuggingIntents(): List<Intent> {
        val settings = "com.android.settings"
        fun tilePrefs(tileClass: String) = Intent(android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName(settings, tileClass))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return listOf(
            // vivo / OriginOS
            tilePrefs("$settings.development.qstile.AdbWirelessDebuggingDevelopmentTile")
                .setClassName(settings, "$settings.qstile.QSTileLongPressGatewayActivity"),
            // AOSP
            tilePrefs("$settings.development.qstile.DevelopmentTiles\$WirelessDebugging").setPackage(settings),
        )
    }

    private fun openAboutPhone() {
        val about = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!runCatching { startActivity(about); true }.getOrDefault(false)) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun openDeveloperOptions() {
        val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!runCatching { startActivity(dev); true }.getOrDefault(false)) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun showLanguagePicker() {
        val dark = Look.isDark(this)
        val dens = resources.displayMetrics.density
        val d = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
            background = LiquidGlass(
                p, 30.dp.toFloat(), dark, dens, level = 0,
                tint = if (dark) Color.argb(236, 0x11, 0x0E, 0x0A) else Color.argb(244, 0xFB, 0xF8, 0xF3),
                stroke = if (dark) Color.argb(46, 255, 232, 190) else Color.argb(45, 40, 30, 18),
            )
        }
        box.addView(label("Language", 22f, p.fg, bold = true).apply {
            typeface = fontBold
            letterSpacing = -0.01f
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 14.dp })

        val savedCode = I18n.saved(this)
        val options = listOf(I18n.LANG_SYSTEM to I18n.t("System")) + I18n.SUPPORTED.map { it to I18n.nativeName(it) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { idx, (code, title) ->
            val selected = savedCode == code
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp, 0, 16.dp, 0)
                val bg = GradientDrawable().apply {
                    cornerRadius = 18.dp.toFloat()
                    if (selected) {
                        setColor(alpha(p.accent, 0.14f))
                        setStroke(1.dp, alpha(p.accent, 0.50f))
                    } else {
                        setColor(if (dark) Color.argb(18, 255, 255, 255) else Color.argb(12, 0, 0, 0))
                        setStroke(1.dp, alpha(p.stroke, 0.55f))
                    }
                }
                background = ripple(bg, 18.dp)
                setOnClickListener {
                    d.dismiss()
                    if (I18n.saved(this@SetupActivity) != code) {
                        I18n.setLang(this@SetupActivity, code)
                        recreate()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                }
            }
            val titleTv = TextView(this).apply {
                text = title
                textSize = 15.5f
                typeface = if (selected) fontBold else fontMedium
                setTextColor(if (selected) (if (dark) p.accent else p.link) else p.fg)
            }
            row.addView(titleTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (selected) {
                row.addView(TextView(this).apply {
                    text = "✓"
                    textSize = 16f
                    typeface = fontBold
                    setTextColor(if (dark) p.accent else p.link)
                })
            }
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply {
                if (idx > 0) topMargin = 8.dp
            })
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val maxListH = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        box.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            // Bounded nicely via post if very tall
        })
        list.post {
            if (scroll.height > maxListH) {
                scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = maxListH }
            }
        }

        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = 30.dp.toFloat()
                setColor(Color.TRANSPARENT)
            })
            val w = minOf(resources.displayMetrics.widthPixels - 32.dp, 400.dp)
            setLayout(w, WRAP_CONTENT)
            setBackgroundBlurRadius(32.dp)
            setDimAmount(if (dark) 0.65f else 0.35f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            attributes = attributes.apply { blurBehindRadius = 18.dp }
        }
        d.show()
        box.alpha = 0f
        box.scaleX = 0.94f
        box.scaleY = 0.94f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()
    }

    /** "Is this safe?" on the intro: plain answers, one button. */
    private fun showSafety() {
        val dark = Look.isDark(this)
        val dens = resources.displayMetrics.density
        val d = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26.dp, 28.dp, 26.dp, 24.dp)
            background = LiquidGlass(
                p, 30.dp.toFloat(), dark, dens, level = 0,
                tint = if (dark) Color.argb(236, 0x11, 0x0E, 0x0A) else Color.argb(244, 0xFB, 0xF8, 0xF3),
                stroke = if (dark) Color.argb(46, 255, 232, 190) else Color.argb(45, 40, 30, 18),
            )
        }
        box.addView(label("Is this safe?", 23f, p.fg, bold = true).apply { typeface = fontBold; letterSpacing = -0.01f })
        listOf(
            "**Yes.** SolCube uses ***Wireless debugging***, a switch built into Android. It gives SolCube extra abilities without rooting your phone.",
            "It only connects SolCube to this phone. Nothing goes over the internet, and SolCube collects no data.",
            "It stays on, together with ***USB debugging***, so SolCube can start by itself after a restart.",
            "To undo it, turn off ***Developer options*** in Settings.",
        ).forEach { t ->
            box.addView(TextView(this).apply {
                text = richText(t)
                textSize = 15.5f
                setTextColor(p.sub)
                typeface = fontRegular
                setLineSpacing(5.dp.toFloat(), 1f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        }
        box.addView(pillButton("Got it", filled = true, size = 15f) { d.dismiss() },
            LinearLayout.LayoutParams(MATCH_PARENT, 50.dp).apply { topMargin = 24.dp })
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { cornerRadius = 30.dp.toFloat(); setColor(Color.TRANSPARENT) })
            setLayout(minOf(resources.displayMetrics.widthPixels - 32.dp, 420.dp), WRAP_CONTENT)
            setBackgroundBlurRadius(32.dp)
            setDimAmount(if (dark) 0.65f else 0.35f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            attributes = attributes.apply { blurBehindRadius = 18.dp }
        }
        d.show()
    }

    private fun confirmBasicMode() {
        val dark = Look.isDark(this)
        val dens = resources.displayMetrics.density
        val d = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(26.dp, 28.dp, 26.dp, 24.dp)
            background = LiquidGlass(
                p, 30.dp.toFloat(), dark, dens, level = 0,
                tint = if (dark) Color.argb(236, 0x11, 0x0E, 0x0A) else Color.argb(244, 0xFB, 0xF8, 0xF3),
                stroke = if (dark) Color.argb(46, 255, 232, 190) else Color.argb(45, 40, 30, 18),
            )
        }
        box.addView(label("Skip for now?", 23f, p.fg, bold = true).apply {
            typeface = fontBold
            letterSpacing = -0.01f
        })
        box.addView(
            TextView(this).apply {
                text = richText("*Circle to Search*, *Frame gen*, *Gestures*, *Play together* and *Routines* need the one-minute unlock, so they stay ***locked*** for now.")
                textSize = 15.5f
                setTextColor(p.sub)
                typeface = fontRegular
                setLineSpacing(5.dp.toFloat(), 1f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp },
        )
        box.addView(
            TextView(this).apply {
                text = richText("You can still use **Fine volume** and **Double power press**. Tap any locked card to unlock it later.")
                textSize = 15.5f
                setTextColor(p.sub)
                typeface = fontRegular
                setLineSpacing(5.dp.toFloat(), 1f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp },
        )
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        btnRow.addView(
            pillButton("Skip for now", filled = false, size = 15f) {
                d.dismiss()
                if (previewMode) { finish(); return@pillButton }
                SetupPrefs.setSkippedBasic(this, true)
                PairingService.stop(this)
                finish()
            },
            LinearLayout.LayoutParams(0, 50.dp, 1f),
        )
        btnRow.addView(
            pillButton("Set it up", filled = true, size = 15f) { d.dismiss() },
            LinearLayout.LayoutParams(0, 50.dp, 1.25f).apply { leftMargin = 12.dp },
        )
        box.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24.dp })

        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = 30.dp.toFloat()
                setColor(Color.TRANSPARENT)
            })
            val w = minOf(resources.displayMetrics.widthPixels - 32.dp, 420.dp)
            setLayout(w, WRAP_CONTENT)
            setBackgroundBlurRadius(32.dp)
            setDimAmount(if (dark) 0.65f else 0.35f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            attributes = attributes.apply { blurBehindRadius = 18.dp }
        }
        d.show()
        box.alpha = 0f
        box.scaleX = 0.94f
        box.scaleY = 0.94f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()
    }

    // ------------------------------------------------------------------ Rich text & local style helpers

    /**
     * Parses concise inline emphasis:
     *  `***text***` -> Bold + Italic + bright emphasis color
     *  `**text**`   -> Bold + foreground color
     *  `*text*`     -> Italic
     */
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
