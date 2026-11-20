package dev.suite

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * A ScrollView with a rubber-band edge instead of Android's stretch.
 *
 * The stretch is drawn by the GPU on the finished page: the cards LOOK pulled, but they never move,
 * so the glass kept sampling the wallpaper where the card would be unstretched, and what showed
 * through no longer matched what was behind it. Here the page really moves (translationY on the
 * child): glass panes see their new place in the window and refract what is actually behind them.
 *
 * Pulling past an edge follows the finger with growing resistance and springs back on release;
 * a fling that hits an edge overshoots a little and settles.
 */
class BounceScrollView(ctx: Context) : ScrollView(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val dens = resources.displayMetrics.density

    private var pulling = false
    private var raw = 0f                 // finger travel past the edge (+ pulled down at the top, − up at the bottom)
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var resetY = false
    private var touching = false
    private var disallow = false
    private var anim: ValueAnimator? = null

    // fling speed, to bounce when a fling runs into an edge
    private var lastT = 0L
    private var v = 0f                   // px/ms, scroll direction
    private var prevV = 0f

    init { overScrollMode = OVER_SCROLL_NEVER }

    private val page get() = if (childCount > 0) getChildAt(0) else null

    /** Finger travel → how far the page moves: 1:1 at first, ever stiffer (iOS-style). */
    private fun band(x: Float): Float {
        val d = height.toFloat().coerceAtLeast(1f)
        val a = abs(x)
        return sign(x) * (a * d * C) / (d + C * a)
    }

    private fun unband(t: Float): Float {
        val d = height.toFloat().coerceAtLeast(1f)
        val a = abs(t).coerceAtMost(d * C * 0.98f)
        return sign(t) * (a * d) / (C * (d - a).coerceAtLeast(1f))
    }

    private fun setPull(x: Float) {
        raw = x
        page?.translationY = band(x)
    }

    override fun requestDisallowInterceptTouchEvent(d: Boolean) {
        disallow = d
        super.requestDisallowInterceptTouchEvent(d)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val y = ev.getY(0)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; disallow = false; resetY = false
                lastY = y; downX = ev.x; downY = y
                val t = page?.translationY ?: 0f
                if (anim?.isRunning == true || t != 0f) {
                    // caught while springing back: hold it where it is and keep pulling from there
                    anim?.cancel(); anim = null
                    raw = unband(t)
                    pulling = raw != 0f
                    if (pulling) return true
                }
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                resetY = true
                if (pulling) return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resetY) { resetY = false; lastY = y }
                val dy = y - lastY
                lastY = y
                if (pulling) {
                    val nr = raw + dy
                    if ((raw > 0f && nr <= 0f) || (raw < 0f && nr >= 0f)) {
                        // back at the edge: scrolling takes over again
                        setPull(0f); pulling = false
                        val d = MotionEvent.obtain(ev); d.action = MotionEvent.ACTION_DOWN
                        super.dispatchTouchEvent(d); d.recycle()
                        return true
                    }
                    setPull(nr)
                    return true
                }
                val moved = y - downY
                val vertical = abs(moved) > slop && abs(moved) > abs(ev.x - downX)
                val atEdge = (dy > 0f && !canScrollVertically(-1)) || (dy < 0f && !canScrollVertically(1))
                if (!disallow && vertical && atEdge) {
                    // past the edge: the children and the scroller let go, the page follows the finger
                    val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c); c.recycle()
                    pulling = true
                    setPull(dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                lastT = 0L
                if (pulling) { pulling = false; springBack(); return true }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun springBack() {
        val p = page ?: return
        val from = p.translationY
        if (from == 0f) { raw = 0f; return }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 480
            interpolator = PathInterpolator(0.25f, 1f, 0.35f, 1f)
            addUpdateListener { p.translationY = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (anim === a) { raw = 0f; anim = null } }
            })
            start()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val now = SystemClock.uptimeMillis()
        if (lastT != 0L && now > lastT) { prevV = v; v = (t - oldt).toFloat() / (now - lastT) }
        else { prevV = 0f; v = 0f }
        lastT = now
        if (touching || pulling) return
        val max = ((page?.height ?: 0) - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
        val hitTop = t == 0 && oldt > 0
        val hitBottom = t >= max && oldt < max && max > 0
        if (!hitTop && !hitBottom) return
        // the last step is cut short by the edge: the one before gives the real speed
        val speed = maxOf(abs(v), abs(prevV))
        if (speed < 0.6f) return
        lastT = 0L
        bounce(if (hitTop) speed else -speed)
    }

    /** A fling ran into an edge at [speed] px/ms: overshoot and settle, starting at that speed. */
    private fun bounce(speed: Float) {
        val p = page ?: return
        anim?.cancel()
        val tau = 95f
        // x(t) = A·(t/τ)·e^(1 − t/τ): starts at the fling's speed (A·e/τ), peaks at A after τ
        val amp = (speed * tau / Math.E.toFloat()).let { sign(it) * abs(it).coerceAtMost(72f * dens) }
        val total = (tau * 7).toLong()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val ms = (it.animatedValue as Float) * total
                val k = ms / tau
                val fade = if (it.animatedFraction > 0.85f) (1f - it.animatedFraction) / 0.15f else 1f
                p.translationY = amp * k * exp(1f - k) * fade
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (anim === a) { p.translationY = 0f; raw = 0f; anim = null } }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        page?.translationY = 0f
        super.onDetachedFromWindow()
    }

    private companion object { const val C = 0.55f }
}
