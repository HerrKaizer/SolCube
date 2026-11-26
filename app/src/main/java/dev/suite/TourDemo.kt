package dev.suite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Feature-tour illustrations: short looping animations drawn in code with SolCube's own visual
 * language (gold accent, island pill, ripple, glass). They show the feature *happening* instead of
 * a stock picture, stay sharp at any density, cost no APK space, and take their few words from
 * the app's translations.
 */
class TourDemo(ctx: Context, private val kind: Kind, private val p: Look.Palette) : View(ctx) {

    enum class Kind { SHORTCUTS, GESTURES, GAMING, AUTOMATION, AUDIO, SETTINGS }

    private val d = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val gold get() = if (light) p.link else p.accent   // light palette: accent is the dark ink, gold is the link colour
    private val ok = p.ok
    private val warn = p.warn
    private val light = !Look.isDark(ctx)
    // ink = lines/text on the backdrop (follows the theme); screenInk = on a phone's dark screen
    private val screenInk = Color.rgb(0xF4, 0xEE, 0xE4)
    private val ink = if (light) Color.rgb(0x2A, 0x24, 0x1C) else screenInk
    // faint fills/outlines on the backdrop: white on dark, black on light
    private val surface = if (light) Color.BLACK else Color.WHITE
    private val dim = Color.argb(90, 0xF4, 0xEE, 0xE4)
    private val bold = Fonts.get(ctx, 680)
    private val medium = Fonts.get(ctx, 560)

    private val period: Float = when (kind) {
        Kind.SHORTCUTS -> 4.8f
        Kind.GESTURES -> 4.6f
        Kind.GAMING -> 5.6f
        Kind.AUTOMATION -> 5.4f
        Kind.AUDIO -> 4.8f
        Kind.SETTINGS -> 5.6f
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r1 = RectF()
    private val r2 = RectF()
    private val path = Path()
    private val pm = PathMeasure()
    private val segPath = Path()

    // ------------------------------------------------------------------ loop

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = ((SystemClock.uptimeMillis() - start) / 1000f) % period

        // backdrop: deep warm black with a soft gold glow, like the app in dark mode
        r1.set(0f, 0f, w, h)
        fill.color = Color.BLACK   // a shader is tinted by the paint's alpha: reset it
        fill.shader = LinearGradient(0f, 0f, 0f, h, if (light) Color.rgb(0xFB, 0xF7, 0xF0) else Color.rgb(0x16, 0x13, 0x0F),
            if (light) Color.rgb(0xEC, 0xE3, 0xD5) else Color.rgb(0x08, 0x07, 0x06), Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, 18 * d, 18 * d, fill)
        fill.color = Color.BLACK   // a shader is tinted by the paint's alpha: reset it
        fill.shader = RadialGradient(w * 0.5f, h * 0.55f, max(w, h) * 0.6f, intArrayOf(alpha(gold, 0.16f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, 18 * d, 18 * d, fill)
        fill.shader = null

        // whole scene fades in/out at the loop seam
        val a = smooth(seg(t, 0f, 0.3f)) * (1f - smooth(seg(t, period - 0.4f, period)))
        c.saveLayerAlpha(0f, 0f, w, h, (a * 255).toInt())
        when (kind) {
            Kind.SHORTCUTS -> shortcuts(c, w, h, t)
            Kind.GESTURES -> gestures(c, w, h, t)
            Kind.GAMING -> gaming(c, w, h, t)
            Kind.AUTOMATION -> automation(c, w, h, t)
            Kind.AUDIO -> audio(c, w, h, t)
            Kind.SETTINGS -> settings(c, w, h, t)
        }
        c.restore()
        if (isAttachedToWindow && isShown) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ 1. shortcuts

    private fun shortcuts(c: Canvas, w: Float, h: Float, t: Float) {
        val shake = if (t in 1.1f..1.3f) sin((t - 1.1f) * 90f) * 1.6f * d else 0f
        val screen = phone(c, w * 0.5f + shake, h * 0.5f, h * 0.86f)
        contentRows(c, screen)

        // power button on the right edge, pressed while the thumb holds it
        val btnY = screen.top + screen.height() * 0.26f
        val press = smooth(seg(t, 0.45f, 0.6f)) * (1f - smooth(seg(t, 1.6f, 1.8f)))
        fill.color = Color.rgb(0x3A, 0x34, 0x2C)
        r2.set(screen.right + 4 * d - press * 1.5f * d, btnY, screen.right + 7 * d - press * 1.5f * d, btnY + screen.height() * 0.13f)
        c.drawRoundRect(r2, 2 * d, 2 * d, fill)

        // thumb + hold progress ring
        val thumbA = smooth(seg(t, 0.2f, 0.5f)) * (1f - smooth(seg(t, 1.5f, 1.9f)))
        if (thumbA > 0f) {
            val tx = screen.right + 22 * d
            val ty = r2.centerY()
            fill.color = alpha(ink, 0.22f * thumbA)
            c.drawCircle(tx, ty, 13 * d, fill)
            stroke.strokeWidth = 2.5f * d
            stroke.color = alpha(gold, thumbA)
            r1.set(tx - 17 * d, ty - 17 * d, tx + 17 * d, ty + 17 * d)
            c.drawArc(r1, -90f, 360f * smooth(seg(t, 0.55f, 1.1f)), false, stroke)
        }

        // the ripple, from the power button across the screen
        val rp = seg(t, 1.1f, 2.2f)
        if (rp > 0f && rp < 1f) {
            c.save()
            c.clipRect(screen)
            val ox = screen.right
            val oy = r2.centerY()
            val reach = hypot(screen.width(), screen.height()) * 1.1f
            val rad = easeOut(rp) * reach
            fill.color = alpha(gold, 0.10f * (1f - rp))
            c.drawCircle(ox, oy, rad, fill)
            for (i in 0..2) {
                val rr = rad - i * 12 * d
                if (rr <= 0f) continue
                stroke.strokeWidth = (3f - i) * d
                stroke.color = alpha(gold, (0.9f - i * 0.28f) * (1f - rp))
                c.drawCircle(ox, oy, rr, stroke)
            }
            c.restore()
        }

        // Circle to Search: a glowing loop drawn around the picture, search bar slides up
        val img = RectF(screen.left + screen.width() * 0.14f, screen.top + screen.height() * 0.30f,
            screen.right - screen.width() * 0.14f, screen.top + screen.height() * 0.52f)
        val sp = seg(t, 2.2f, 3.1f)
        if (sp > 0f) {
            path.reset()
            val cx = img.centerX(); val cy = img.centerY()
            val rx = img.width() * 0.62f; val ry = img.height() * 0.78f
            var first = true
            var k = 0f
            while (k <= 1.08f) {
                val ang = -PI / 2 + k * 2 * PI
                val wob = 1f + 0.05f * sin(k * 9f)
                val x = cx + (cos(ang) * rx * wob).toFloat()
                val y = cy + (sin(ang) * ry * wob).toFloat()
                if (first) path.moveTo(x, y) else path.lineTo(x, y)
                first = false
                k += 0.02f
            }
            drawProgress(c, path, easeOut(sp), Color.WHITE, 3f * d, glow = alpha(gold, 0.55f))
            val bar = smooth(seg(t, 2.5f, 2.9f))
            fill.color = alpha(Color.rgb(0x26, 0x22, 0x1D), bar)
            val bh = 24 * d
            r1.set(screen.left + 8 * d, screen.bottom - 10 * d - bh * bar, screen.right - 8 * d, screen.bottom - 10 * d - bh * bar + bh)
            c.drawRoundRect(r1, bh / 2, bh / 2, fill)
            stroke.strokeWidth = 1.6f * d
            stroke.color = alpha(screenInk, 0.8f * bar)
            c.drawCircle(r1.left + 14 * d, r1.centerY(), 4.5f * d, stroke)
            c.drawLine(r1.left + 17.5f * d, r1.centerY() + 3.5f * d, r1.left + 20 * d, r1.centerY() + 6 * d, stroke)
        }

        island(c, screen, I18n.t("Circle to Search"), seg(t, 1.2f, 1.6f), seg(t, 3.6f, 4.0f), ok)
    }

    // ------------------------------------------------------------------ 2. gestures

    private fun gestures(c: Canvas, w: Float, h: Float, t: Float) {
        val screen = phone(c, w * 0.5f, h * 0.5f, h * 0.86f)
        contentRows(c, screen, faint = true)

        // a caret drawn with two fingers anywhere over the app
        path.reset()
        path.moveTo(screen.left + screen.width() * 0.2f, screen.top + screen.height() * 0.70f)
        path.lineTo(screen.centerX(), screen.top + screen.height() * 0.36f)
        path.lineTo(screen.right - screen.width() * 0.2f, screen.top + screen.height() * 0.70f)
        val dp = seg(t, 0.4f, 1.5f)
        val fade = 1f - smooth(seg(t, 1.7f, 2.2f))
        if (dp > 0f && fade > 0f) {
            drawProgress(c, path, easeInOut(dp), alpha(gold, fade), 4f * d, glow = alpha(gold, 0.4f * fade))
            if (dp < 1f) {   // the two fingertips at the head of the stroke
                pm.setPath(path, false)
                val pos = FloatArray(2)
                pm.getPosTan(pm.length * easeInOut(dp), pos, null)
                fill.color = alpha(screenInk, 0.35f)
                c.drawCircle(pos[0] - 9 * d, pos[1] + 2 * d, 7 * d, fill)
                c.drawCircle(pos[0] + 9 * d, pos[1] - 2 * d, 7 * d, fill)
            }
        }

        // recognised: the flashlight comes on
        val on = smooth(seg(t, 1.8f, 2.3f)) * (1f - smooth(seg(t, 3.7f, 4.1f)))
        if (on > 0f) {
            val cx = screen.centerX()
            val cy = screen.top + screen.height() * 0.5f
            fill.color = Color.BLACK   // a shader is tinted by the paint's alpha: reset it
            fill.shader = RadialGradient(cx, cy, screen.width() * 0.75f, intArrayOf(alpha(Color.rgb(0xFF, 0xE3, 0xA6), 0.55f * on), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.save(); c.clipRect(screen); c.drawCircle(cx, cy, screen.width(), fill); c.restore()
            fill.shader = null
            torch(c, cx, cy, 15 * d, on)
        }
        island(c, screen, I18n.t("Done"), seg(t, 1.8f, 2.2f), seg(t, 3.6f, 4.0f), ok)
    }

    // ------------------------------------------------------------------ 3. gaming

    private fun gaming(c: Canvas, w: Float, h: Float, t: Float) {
        // landscape game screen
        val scr = RectF(w * 0.08f, h * 0.10f, w * 0.92f, h * 0.60f)
        fill.color = if (light) Color.rgb(0xF4, 0xEE, 0xE4) else Color.rgb(0x1B, 0x18, 0x14)
        c.drawRoundRect(scr, 12 * d, 12 * d, fill)
        stroke.strokeWidth = 1 * d
        stroke.color = alpha(ink, 0.12f)
        c.drawRoundRect(scr, 12 * d, 12 * d, stroke)

        val fiOn = smooth(seg(t, 0.8f, 1.3f)) * (1f - smooth(seg(t, 3.9f, 4.3f)))
        val hot = smooth(seg(t, 3.9f, 4.3f)) * (1f - smooth(seg(t, period - 0.6f, period - 0.3f)))

        // film strip of frames streaming past; FI slots gold frames in between the real ones
        c.save()
        c.clipRect(scr)
        val gap = 26 * d
        val off = (t * 60 * d) % gap
        var x = scr.left - gap + (gap - off)
        val fh = scr.height() * 0.56f
        val fy = scr.centerY() - fh / 2
        while (x < scr.right + gap) {
            fill.color = alpha(ink, 0.55f)
            r1.set(x, fy, x + 9 * d, fy + fh)
            c.drawRoundRect(r1, 3 * d, 3 * d, fill)
            if (fiOn > 0f) {
                fill.color = alpha(gold, 0.85f * fiOn)
                r1.set(x + gap / 2, fy + fh * 0.08f, x + gap / 2 + 7 * d, fy + fh * 0.92f)
                c.drawRoundRect(r1, 3 * d, 3 * d, fill)
            }
            x += gap
        }
        c.restore()

        // FPS pill (top-left of the game), 60 → 120
        val fps = (60 + 60 * easeOut(seg(t, 0.9f, 1.9f)) * (1f - smooth(seg(t, 3.9f, 4.3f)))).toInt()
        hudPill(c, scr.left + 10 * d, scr.top + 10 * d, "$fps FPS", if (fps >= 118) gold else Color.WHITE)

        // temperature bar under the game: warms up until FI pauses
        val temp = 38f + 7f * smooth(seg(t, 2.0f, 3.9f)) - 4f * smooth(seg(t, 4.5f, period - 0.4f))
        val track = RectF(w * 0.16f, h * 0.76f, w * 0.84f, h * 0.76f + 8 * d)
        fill.color = alpha(ink, 0.10f)
        c.drawRoundRect(track, 4 * d, 4 * d, fill)
        val frac = ((temp - 30f) / 20f).coerceIn(0f, 1f)
        fill.color = blend(ok, warn, smooth(seg(temp, 40f, 45f)))
        r1.set(track.left, track.top, track.left + track.width() * frac, track.bottom)
        c.drawRoundRect(r1, 4 * d, 4 * d, fill)
        text.typeface = bold
        text.textSize = 13 * d
        text.color = ink
        text.textAlign = Paint.Align.LEFT
        c.drawText(String.format("%.0f°", temp), track.left, track.top - 7 * d, text)
        text.textAlign = Paint.Align.CENTER

        if (hot > 0f) {   // FI paused: amber pause pill over the game
            pill(c, scr.centerX(), scr.top + 12 * d, "45°", warn, hot, icon = ICON_PAUSE)
        }
    }

    // ------------------------------------------------------------------ 4. automation

    private fun automation(c: Canvas, w: Float, h: Float, t: Float) {
        val card = RectF(w * 0.12f, h * 0.20f, w * 0.88f, h * 0.90f)
        fill.color = alpha(surface, 0.05f)
        c.drawRoundRect(card, 16 * d, 16 * d, fill)
        stroke.strokeWidth = 1 * d
        stroke.color = alpha(surface, 0.12f)
        c.drawRoundRect(card, 16 * d, 16 * d, stroke)

        val revert = smooth(seg(t, 3.9f, 4.5f))
        val r = min(card.width() / 9.5f, card.height() / 5.2f)
        val row1 = card.top + card.height() * 0.27f
        val row2 = card.top + card.height() * 0.73f
        // When: Wi-Fi + time window
        val whenX = floatArrayOf(card.centerX() - r * 1.4f, card.centerX() + r * 1.4f)
        val whenAt = floatArrayOf(0.5f, 0.9f)
        for (i in 0..1) {
            val lit = smooth(seg(t, whenAt[i], whenAt[i] + 0.3f)) * (1f - revert)
            iconBubble(c, whenX[i], row1, r, lit, if (i == 0) ICON_WIFI else ICON_CLOCK)
        }
        // flowing arrow
        val ap = seg(t, 1.2f, 1.7f)
        stroke.strokeWidth = 2 * d
        stroke.color = alpha(gold, 0.25f + 0.6f * ap * (1f - revert))
        c.drawLine(card.centerX(), row1 + r + 6 * d, card.centerX(), row2 - r - 6 * d, stroke)
        c.drawLine(card.centerX() - 5 * d, row2 - r - 11 * d, card.centerX(), row2 - r - 6 * d, stroke)
        c.drawLine(card.centerX() + 5 * d, row2 - r - 11 * d, card.centerX(), row2 - r - 6 * d, stroke)
        // Do: silent, do not disturb, NFC
        val doX = floatArrayOf(card.centerX() - r * 2.6f, card.centerX(), card.centerX() + r * 2.6f)
        val doAt = floatArrayOf(1.7f, 2.1f, 2.5f)
        val icons = intArrayOf(ICON_BELL_OFF, ICON_MOON, ICON_NFC)
        for (i in 0..2) {
            val lit = smooth(seg(t, doAt[i], doAt[i] + 0.3f)) * (1f - revert)
            iconBubble(c, doX[i], row2, r, lit, icons[i])
        }
        // leaving: everything goes back; a return arrow sweeps around the card
        if (revert > 0f && revert < 1f) {
            path.reset()
            r1.set(card.left - 6 * d, card.top - 6 * d, card.right + 6 * d, card.bottom + 6 * d)
            path.addRoundRect(r1, 20 * d, 20 * d, Path.Direction.CCW)
            drawProgress(c, path, revert, alpha(gold, 0.8f * (1f - revert * 0.5f)), 2.5f * d, glow = alpha(gold, 0.3f))
        }
        val top = RectF(w * 0.2f, h * 0.02f, w * 0.8f, h * 0.18f)
        pillAt(c, top.centerX(), top.top + 4 * d, I18n.t("Do Not Disturb"), ok, seg(t, 2.6f, 3.0f), seg(t, 3.8f, 4.2f))
    }

    // ------------------------------------------------------------------ 5. audio

    private fun audio(c: Canvas, w: Float, h: Float, t: Float) {
        // two apps, both playing (bars move together)
        val tile = min(w, h) * 0.20f
        val lx = w * 0.12f
        val ys = floatArrayOf(h * 0.30f, h * 0.70f)
        for (i in 0..1) {
            val cy = ys[i]
            r1.set(lx, cy - tile / 2, lx + tile, cy + tile / 2)
            fill.color = alpha(surface, 0.07f)
            c.drawRoundRect(r1, tile * 0.28f, tile * 0.28f, fill)
            stroke.strokeWidth = 1 * d
            stroke.color = alpha(gold, 0.5f)
            c.drawRoundRect(r1, tile * 0.28f, tile * 0.28f, stroke)
            drawIcon(c, if (i == 0) ICON_NOTE else ICON_PAD, r1.centerX(), r1.centerY(), tile * 0.26f, ink)
            for (b in 0..5) {
                val amp = 0.35f + 0.65f * abs(sin(t * (5.5f + b * 0.9f + i * 1.7f) + b))
                val bx = lx + tile + 12 * d + b * 9 * d
                val bh = tile * 0.8f * amp
                fill.color = alpha(if (i == 0) gold else ok, 0.85f)
                r2.set(bx, cy - bh / 2, bx + 5 * d, cy + bh / 2)
                c.drawRoundRect(r2, 2.5f * d, 2.5f * d, fill)
            }
        }
        // knob turning in 1 % clicks
        val kr = min(w * 0.17f, h * 0.30f)
        val kx = w * 0.74f
        val ky = h * 0.5f
        val pct = 37 + (seg(t, 0.6f, 3.6f) * 16).toInt()
        fill.color = Color.BLACK   // a shader is tinted by the paint's alpha: reset it
        fill.shader = RadialGradient(kx - kr * 0.3f, ky - kr * 0.4f, kr * 1.4f, intArrayOf(Color.rgb(0x3A, 0x34, 0x2C), Color.rgb(0x12, 0x10, 0x0E)), null, Shader.TileMode.CLAMP)
        c.drawCircle(kx, ky, kr, fill)
        fill.shader = null
        stroke.strokeWidth = 1 * d
        stroke.color = alpha(gold, 0.5f)
        c.drawCircle(kx, ky, kr, stroke)
        // tick ring + arc
        for (k in 0..40) {
            val a = (135 + k * 270f / 40) * PI / 180
            val on = k <= pct * 40 / 100
            stroke.strokeWidth = (if (k % 5 == 0) 2f else 1.2f) * d
            stroke.color = if (on) gold else alpha(ink, 0.18f)
            val r0 = kr + 6 * d
            val r9 = kr + (if (k % 5 == 0) 12 else 9) * d
            c.drawLine(kx + (cos(a) * r0).toFloat(), ky + (sin(a) * r0).toFloat(), kx + (cos(a) * r9).toFloat(), ky + (sin(a) * r9).toFloat(), stroke)
        }
        val pa = (135 + pct * 2.7f) * PI / 180
        stroke.strokeWidth = 3 * d
        stroke.color = gold
        c.drawLine(kx + (cos(pa) * kr * 0.45f).toFloat(), ky + (sin(pa) * kr * 0.45f).toFloat(), kx + (cos(pa) * kr * 0.82f).toFloat(), ky + (sin(pa) * kr * 0.82f).toFloat(), stroke)
        text.typeface = bold
        text.textSize = kr * 0.38f
        text.color = screenInk
        c.drawText("$pct%", kx, ky + text.textSize * 0.35f, text)
        // both streams flow into the knob
        stroke.strokeWidth = 1.5f * d
        for (i in 0..1) {
            path.reset()
            val sx = lx + tile + 70 * d
            path.moveTo(sx, ys[i])
            path.cubicTo(sx + 30 * d, ys[i], kx - kr - 40 * d, ky, kx - kr - 14 * d, ky)
            stroke.color = alpha(if (i == 0) gold else ok, 0.35f)
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 6 * d), -t * 40 * d)
            c.drawPath(path, stroke)
            stroke.pathEffect = null
        }
    }

    // ------------------------------------------------------------------ 6. settings

    private fun settings(c: Canvas, w: Float, h: Float, t: Float) {
        val shake = if (t in 2.4f..2.9f) sin((t - 2.4f) * 80f) * 1.8f * d * (1f - seg(t, 2.4f, 2.9f)) else 0f
        val screen = phone(c, w * 0.30f + shake, h * 0.5f, h * 0.86f)
        // lock screen: big clock
        text.typeface = bold
        text.textSize = screen.width() * 0.26f
        text.color = screenInk
        c.drawText("9:41", screen.centerX(), screen.top + screen.height() * 0.38f, text)
        // status bar icons slide away (lock-screen status bar hidden)
        val hide = smooth(seg(t, 0.9f, 1.4f)) * (1f - smooth(seg(t, period - 0.8f, period - 0.4f)))
        val sy = screen.top + 9 * d - hide * 10 * d
        val ia = 1f - hide
        fill.color = alpha(screenInk, 0.75f * ia)
        for (i in 0..2) {
            r1.set(screen.left + 8 * d + i * 7 * d, sy - (2 + i * 1.5f) * d, screen.left + 12 * d + i * 7 * d, sy + 2 * d)
            c.drawRoundRect(r1, 1 * d, 1 * d, fill)
        }
        r1.set(screen.right - 22 * d, sy - 3 * d, screen.right - 9 * d, sy + 3 * d)
        stroke.strokeWidth = 1.2f * d
        stroke.color = alpha(screenInk, 0.75f * ia)
        c.drawRoundRect(r1, 1.5f * d, 1.5f * d, stroke)

        // haptics: a buzz you can see
        val col = w * 0.70f
        text.typeface = medium
        text.textSize = 12 * d
        text.color = alpha(ink, 0.7f)
        c.drawText(I18n.t("Haptics"), col, h * 0.22f, text)
        val env = smooth(seg(t, 2.2f, 2.5f)) * (1f - smooth(seg(t, 2.8f, 3.4f)))
        path.reset()
        val ww = w * 0.40f
        var k = 0f
        while (k <= 1f) {
            val x = col - ww / 2 + ww * k
            val y = h * 0.36f + sin(k * 30f - t * 18f) * (2 * d + 14 * d * env) * sin(k * PI.toFloat())
            if (k == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            k += 0.01f
        }
        stroke.strokeWidth = 2.5f * d
        stroke.color = gold
        c.drawPath(path, stroke)

        // languages: native names cross-fade
        c.drawText(I18n.t("Language"), col, h * 0.62f, text)
        val names = listOf("English", "Русский", "Deutsch", "Español", "中文", "Français")
        val per = period / names.size
        val idx = (t / per).toInt() % names.size
        val f = seg(t % per, per - 0.25f, per)
        text.typeface = bold
        text.textSize = 20 * d
        text.color = alpha(ink, 1f - f)
        c.drawText(names[idx], col, h * 0.76f - f * 6 * d, text)
        text.color = alpha(gold, f)
        c.drawText(names[(idx + 1) % names.size], col, h * 0.76f + (1 - f) * 6 * d, text)
    }

    // ------------------------------------------------------------------ pieces

    /** Phone body centred at (cx, cy) with height [ph]; returns the screen rect. */
    private fun phone(c: Canvas, cx: Float, cy: Float, ph: Float): RectF {
        val pw = ph * 0.49f
        r1.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
        fill.color = Color.BLACK   // a shader is tinted by the paint's alpha: reset it
        fill.shader = LinearGradient(r1.left, r1.top, r1.right, r1.bottom, Color.rgb(0x3B, 0x34, 0x2B), Color.rgb(0x16, 0x13, 0x10), Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, pw * 0.16f, pw * 0.16f, fill)
        fill.shader = null
        stroke.strokeWidth = 1.2f * d
        stroke.color = alpha(gold, 0.45f)
        c.drawRoundRect(r1, pw * 0.16f, pw * 0.16f, stroke)
        val s = RectF(r1.left + 3.5f * d, r1.top + 3.5f * d, r1.right - 3.5f * d, r1.bottom - 3.5f * d)
        fill.color = Color.rgb(0x0B, 0x0A, 0x09)
        c.drawRoundRect(s, pw * 0.13f, pw * 0.13f, fill)
        fill.color = Color.BLACK
        c.drawCircle(s.centerX(), s.top + 8 * d, 3 * d, fill)
        return s
    }

    private fun contentRows(c: Canvas, s: RectF, faint: Boolean = false) {
        val a = if (faint) 0.07f else 0.11f
        fill.color = alpha(Color.WHITE, a)
        val x0 = s.left + s.width() * 0.12f
        val x1 = s.right - s.width() * 0.12f
        r1.set(x0, s.top + s.height() * 0.13f, x0 + (x1 - x0) * 0.6f, s.top + s.height() * 0.13f + 6 * d)
        c.drawRoundRect(r1, 3 * d, 3 * d, fill)
        r1.set(x0, s.top + s.height() * 0.30f, x1, s.top + s.height() * 0.52f)
        fill.color = alpha(gold, if (faint) 0.10f else 0.18f)
        c.drawRoundRect(r1, 8 * d, 8 * d, fill)
        fill.color = alpha(Color.WHITE, a)
        for (i in 0..2) {
            val y = s.top + s.height() * (0.60f + i * 0.08f)
            r1.set(x0, y, x1 - (if (i == 2) (x1 - x0) * 0.35f else 0f), y + 5 * d)
            c.drawRoundRect(r1, 3 * d, 3 * d, fill)
        }
    }

    /** SolCube's island pill dropping out of the punch hole. */
    private fun island(c: Canvas, s: RectF, label: String, inP: Float, outP: Float, color: Int) {
        pillAt(c, s.centerX(), s.top + 4 * d, label, color, inP, outP)
    }

    private fun pillAt(c: Canvas, cx: Float, top: Float, label: String, color: Int, inP: Float, outP: Float) {
        val show = easeBack(inP) * (1f - smooth(outP))
        if (show <= 0f) return
        pill(c, cx, top, label, color, show, ICON_CHECK)
    }

    private fun pill(c: Canvas, cx: Float, top: Float, label: String, color: Int, show: Float, icon: Int) {
        text.typeface = medium
        text.textSize = 11 * d
        val tw = text.measureText(label)
        val ph = 22 * d
        val pw = tw + ph + 14 * d
        val s = show.coerceIn(0f, 1.1f)
        c.save()
        c.translate(cx, top + ph / 2)
        c.scale(0.6f + 0.4f * s, 0.6f + 0.4f * s)
        val a = show.coerceIn(0f, 1f)
        r1.set(-pw / 2, -ph / 2, pw / 2, ph / 2)
        fill.color = alpha(Color.BLACK, 0.95f * a)
        c.drawRoundRect(r1, ph / 2, ph / 2, fill)
        stroke.strokeWidth = 0.8f * d
        stroke.color = alpha(Color.WHITE, 0.2f * a)
        c.drawRoundRect(r1, ph / 2, ph / 2, stroke)
        val ix = -pw / 2 + ph * 0.55f
        fill.color = alpha(color, a)
        c.drawCircle(ix, 0f, 6.5f * d, fill)
        drawIcon(c, icon, ix, 0f, 3.6f * d, alpha(Color.BLACK, a))
        text.color = alpha(Color.WHITE, a)
        text.textAlign = Paint.Align.LEFT
        c.drawText(label, ix + 11 * d, text.textSize * 0.36f, text)
        text.textAlign = Paint.Align.CENTER
        c.restore()
    }

    private fun hudPill(c: Canvas, x: Float, y: Float, label: String, color: Int) {
        text.typeface = bold
        text.textSize = 11 * d
        val tw = text.measureText(label)
        r1.set(x, y, x + tw + 14 * d, y + 20 * d)
        fill.color = alpha(Color.BLACK, 0.7f)
        c.drawRoundRect(r1, 10 * d, 10 * d, fill)
        stroke.strokeWidth = 0.8f * d
        stroke.color = alpha(color, 0.6f)
        c.drawRoundRect(r1, 10 * d, 10 * d, stroke)
        text.color = color
        c.drawText(label, r1.centerX(), r1.centerY() + text.textSize * 0.36f, text)
    }

    private fun iconBubble(c: Canvas, cx: Float, cy: Float, r: Float, lit: Float, icon: Int) {
        fill.color = blend(alpha(surface, 0.06f), alpha(gold, 0.22f), lit)
        c.drawCircle(cx, cy, r, fill)
        stroke.strokeWidth = 1.2f * d
        stroke.color = blend(alpha(surface, 0.14f), gold, lit)
        c.drawCircle(cx, cy, r, stroke)
        drawIcon(c, icon, cx, cy, r * 0.5f, blend(alpha(ink, 0.45f), ink, lit))
        if (lit > 0.6f) {   // small check badge
            val a = (lit - 0.6f) / 0.4f
            fill.color = alpha(ok, a)
            c.drawCircle(cx + r * 0.72f, cy - r * 0.72f, r * 0.28f, fill)
            drawIcon(c, ICON_CHECK, cx + r * 0.72f, cy - r * 0.72f, r * 0.16f, alpha(Color.BLACK, a))
        }
    }

    private fun torch(c: Canvas, cx: Float, cy: Float, s: Float, a: Float) {
        fill.color = alpha(screenInk, a)
        r1.set(cx - s * 0.32f, cy - s * 0.1f, cx + s * 0.32f, cy + s * 1.1f)
        c.drawRoundRect(r1, s * 0.12f, s * 0.12f, fill)
        path.reset()
        path.moveTo(cx - s * 0.6f, cy - s * 0.7f)
        path.lineTo(cx + s * 0.6f, cy - s * 0.7f)
        path.lineTo(cx + s * 0.34f, cy - s * 0.12f)
        path.lineTo(cx - s * 0.34f, cy - s * 0.12f)
        path.close()
        c.drawPath(path, fill)
        stroke.strokeWidth = 2 * d
        stroke.color = alpha(gold, a)
        for (i in -1..1) {
            val ang = (-90 + i * 30) * PI / 180
            c.drawLine(cx + (cos(ang) * s * 0.95f).toFloat(), cy - s * 0.4f + (sin(ang) * s * 0.95f).toFloat(),
                cx + (cos(ang) * s * 1.45f).toFloat(), cy - s * 0.4f + (sin(ang) * s * 1.45f).toFloat(), stroke)
        }
    }

    private fun drawProgress(c: Canvas, src: Path, f: Float, color: Int, width: Float, glow: Int) {
        pm.setPath(src, false)
        segPath.reset()
        pm.getSegment(0f, pm.length * f.coerceIn(0f, 1f), segPath, true)
        stroke.strokeWidth = width * 3.2f
        stroke.color = glow
        stroke.maskFilter = android.graphics.BlurMaskFilter(width * 2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawPath(segPath, stroke)
        stroke.maskFilter = null
        stroke.strokeWidth = width
        stroke.color = color
        c.drawPath(segPath, stroke)
    }

    private fun drawIcon(c: Canvas, icon: Int, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = max(1.4f * d, s * 0.22f)
        fill.color = color
        when (icon) {
            ICON_CHECK -> {
                path.reset(); path.moveTo(cx - s, cy); path.lineTo(cx - s * 0.25f, cy + s * 0.7f); path.lineTo(cx + s, cy - s * 0.7f)
                c.drawPath(path, stroke)
            }
            ICON_PAUSE -> {
                r2.set(cx - s * 0.7f, cy - s, cx - s * 0.2f, cy + s); c.drawRect(r2, fill)
                r2.set(cx + s * 0.2f, cy - s, cx + s * 0.7f, cy + s); c.drawRect(r2, fill)
            }
            ICON_WIFI -> {
                for (i in 1..3) {
                    val rr = s * 0.45f * i
                    r2.set(cx - rr, cy + s * 0.6f - rr, cx + rr, cy + s * 0.6f + rr)
                    c.drawArc(r2, 225f, 90f, false, stroke)
                }
                c.drawCircle(cx, cy + s * 0.6f, stroke.strokeWidth * 0.7f, fill)
            }
            ICON_CLOCK -> {
                c.drawCircle(cx, cy, s, stroke)
                c.drawLine(cx, cy, cx, cy - s * 0.6f, stroke)
                c.drawLine(cx, cy, cx + s * 0.45f, cy + s * 0.2f, stroke)
            }
            ICON_BELL_OFF -> {
                path.reset()
                path.moveTo(cx - s * 0.75f, cy + s * 0.5f)
                path.lineTo(cx - s * 0.6f, cy - s * 0.1f)
                path.quadTo(cx - s * 0.6f, cy - s * 0.9f, cx, cy - s * 0.9f)
                path.quadTo(cx + s * 0.6f, cy - s * 0.9f, cx + s * 0.6f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.75f, cy + s * 0.5f)
                path.close()
                c.drawPath(path, stroke)
                c.drawLine(cx - s, cy + s, cx + s, cy - s, stroke)
            }
            ICON_MOON -> {
                path.reset()
                r2.set(cx - s, cy - s, cx + s, cy + s)
                path.addArc(r2, 60f, 240f)
                path.quadTo(cx - s * 0.1f, cy - s * 0.2f, cx + s * 0.5f, cy + s * 0.87f)
                c.drawPath(path, stroke)
            }
            ICON_NFC -> {
                for (i in 0..2) {
                    val rr = s * (0.4f + i * 0.35f)
                    r2.set(cx - s * 0.6f - rr, cy - rr, cx - s * 0.6f + rr, cy + rr)
                    c.drawArc(r2, -45f, 90f, false, stroke)
                }
            }
            ICON_NOTE -> {
                c.drawLine(cx + s * 0.3f, cy - s, cx + s * 0.3f, cy + s * 0.5f, stroke)
                c.drawLine(cx + s * 0.3f, cy - s, cx + s * 0.9f, cy - s * 0.7f, stroke)
                c.drawCircle(cx - s * 0.05f, cy + s * 0.6f, s * 0.38f, fill)
            }
            ICON_PAD -> {
                r2.set(cx - s, cy - s * 0.55f, cx + s, cy + s * 0.55f)
                c.drawRoundRect(r2, s * 0.5f, s * 0.5f, stroke)
                c.drawLine(cx - s * 0.6f, cy, cx - s * 0.2f, cy, stroke)
                c.drawLine(cx - s * 0.4f, cy - s * 0.2f, cx - s * 0.4f, cy + s * 0.2f, stroke)
                c.drawCircle(cx + s * 0.45f, cy, s * 0.12f, fill)
            }
        }
    }

    // ------------------------------------------------------------------ math

    private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun smooth(x: Float) = x * x * (3 - 2 * x)
    private fun easeOut(x: Float) = 1 - (1 - x) * (1 - x) * (1 - x)
    private fun easeInOut(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    private fun easeBack(x: Float): Float {
        if (x <= 0f) return 0f
        val c1 = 1.70158f
        val c3 = c1 + 1
        return 1 + c3 * (x - 1) * (x - 1) * (x - 1) + c1 * (x - 1) * (x - 1)
    }
    private fun alpha(color: Int, a: Float) =
        Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    private fun blend(a: Int, b: Int, f: Float): Int {
        val x = f.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * x).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * x).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * x).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * x).toInt(),
        )
    }

    private companion object {
        const val ICON_CHECK = 0
        const val ICON_PAUSE = 1
        const val ICON_WIFI = 2
        const val ICON_CLOCK = 3
        const val ICON_BELL_OFF = 4
        const val ICON_MOON = 5
        const val ICON_NFC = 6
        const val ICON_NOTE = 7
        const val ICON_PAD = 8
    }
}
