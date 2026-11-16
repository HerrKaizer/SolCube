package dev.suite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * BlueLM-style ripple over a screenshot of the screen:
 *  - glowing orbs under the two fingertips and a lens swell between them,
 *  - one glassy wave (like the charging ring) that spreads out, refracting what it crosses,
 *  - a soft colour wash left in its wake: a few big blurry blobs, different every time.
 */
class RippleView(context: Context) : View(context) {

    // Timescale of the wave (user's choice). The animation now ends early, as soon as the
    // wave clears the farthest corner, so it actually runs ~700-800 ms.
    var durationMs = 1050L

    /**
     * Circle to Search is opened as soon as our first, untouched frame (an exact copy of the
     * screen) is on display, so its screenshot is clean. We hold that still frame this long
     * while it takes the screenshot, then animate on top of its UI as it loads underneath.
     */
    var ctsHoldMs = 250L   // measured: 120 ms let the ripple into CTS's screenshot, 250+ ms stays clean

    private val dp = resources.displayMetrics.density

    private var cx = 0f; private var cy = 0f
    private var f1x = 0f; private var f1y = 0f; private var f2x = 0f; private var f2y = 0f
    private var offX = 0f; private var offY = 0f
    private var t = 0f
    private var running = false
    @Volatile private var waveDone = false   // set in onDraw once the crest is past the farthest corner
    private var fadeA = 1f
    private val FADE_MS = 100f

    private val shader = RuntimeShader(AGSL)
    private val paint = Paint().apply { shader = this@RippleView.shader }

    // Soft, friendly colours for the wash (no muddy olive/gold-brown).
    private val palette = arrayOf(
        floatArrayOf(0.42f, 0.62f, 1.00f),   // sky blue
        floatArrayOf(0.56f, 0.52f, 1.00f),   // periwinkle
        floatArrayOf(0.76f, 0.54f, 1.00f),   // lavender
        floatArrayOf(1.00f, 0.54f, 0.78f),   // rose
        floatArrayOf(1.00f, 0.70f, 0.58f),   // peach
        floatArrayOf(0.42f, 0.88f, 0.90f),   // aqua
        floatArrayOf(0.60f, 0.92f, 0.72f),   // mint
        floatArrayOf(1.00f, 0.84f, 0.56f),   // soft gold
    )

    /** All points are in this view's coordinates; window offset maps them to screen space. */
    fun play(
        x: Float, y: Float, fingers: FloatArray?, screenshot: Bitmap?,
        windowX: Float, windowY: Float, onCts: () -> Unit, onStart: () -> Unit, onDone: () -> Unit
    ) {
        cx = x; cy = y; offX = windowX; offY = windowY
        if (fingers != null && fingers.size >= 4) {
            f1x = fingers[0]; f1y = fingers[1]; f2x = fingers[2]; f2y = fingers[3]
        } else { f1x = x; f1y = y; f2x = x; f2y = y }
        val img = screenshot ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        shader.setInputShader("img", BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("hasImg", if (screenshot != null) 1f else 0f)
        randomizeWash(screenshot?.width?.toFloat() ?: width.toFloat(), screenshot?.height?.toFloat() ?: height.toFloat())
        running = true; t = 0f
        waveDone = false; fadeA = 1f
        var start = 0L
        var started = false
        var doneAt = 0L
        val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (!started && now >= start) { started = true; onStart() }   // ripple begins moving
                t = ((now - start).toFloat() / durationMs).coerceIn(0f, 1f)
                // the wave has cleared the farthest corner: short fade, then finish
                // (instead of waiting out the rest of durationMs)
                if ((waveDone || t >= 1f) && doneAt == 0L) doneAt = now
                val f = if (doneAt == 0L) 0f else ((now - doneAt).toFloat() / FADE_MS).coerceIn(0f, 1f)
                fadeA = 1f - f * f * (3f - 2f * f)
                invalidate()
                if (f < 1f) postOnAnimation(this)
                else postOnAnimation { postOnAnimation { running = false; onDone() } }
            }
        }
        invalidate()   // t = 0: plain screenshot, identical to the real screen
        // two frames later that still frame is on display -> open CTS, hold, then animate
        postOnAnimation {
            postOnAnimation {
                onCts()
                start = SystemClock.uptimeMillis() + ctsHoldMs
                postOnAnimation(tick)
            }
        }
    }

    /** New blob layout and colours for every trigger. */
    private fun randomizeWash(w: Float, h: Float) {
        val rnd = Random(SystemClock.uptimeMillis())
        val picks = palette.indices.shuffled(rnd).take(4)
        for (i in 0 until 4) {
            // spread blobs over the screen: one per quadrant-ish, jittered
            val qx = if (i % 2 == 0) 0.25f else 0.75f
            val qy = if (i < 2) 0.25f else 0.75f
            val bx = (qx + rnd.nextFloat() * 0.5f - 0.25f) * w
            val by = (qy + rnd.nextFloat() * 0.5f - 0.25f) * h
            val br = (0.45f + rnd.nextFloat() * 0.35f) * w
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val speed = (0.06f + rnd.nextFloat() * 0.08f) * w   // px over the animation
            shader.setFloatUniform("b$i", bx, by, br)
            shader.setFloatUniform("v$i", kotlin.math.cos(ang) * speed, sin(ang) * speed)
            val c = palette[picks[i]]
            shader.setFloatUniform("c$i", c[0], c[1], c[2])
        }
        shader.setFloatUniform("hueShift", rnd.nextFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val w = width.toFloat(); val h = height.toFloat()
        val far = max(
            max(hypot(cx, cy), hypot(w - cx, cy)),
            max(hypot(cx, h - cy), hypot(w - cx, h - cy))
        )
        val maxR = far + 160f * dp

        // wave: starts after the orbs have bloomed a little
        val wt = ((t - 0.06f) / 0.80f).coerceIn(0f, 1f)
        val e = 0.30f * wt + 0.70f * (1f - (1f - wt).pow(2.2f))
        val radius = 10f * dp + e * maxR
        val band = (100f + 110f * e) * dp
        val waveIn = (wt / 0.08f).coerceIn(0f, 1f)
        // Snap: once the crest reaches the farthest corner the distortion dies within half a
        // band (it used to linger there ~0.3 s while the slowing wave crawled on), then the
        // overlay fades and the animation ends.
        val pass = ((radius - (far - 0.2f * band)) / (0.7f * band)).coerceIn(0f, 1f)
        val snap = 1f - pass * pass * (3f - 2f * pass)
        if (pass >= 1f) waveDone = true
        val waveOut = (1f - wt).pow(0.6f) * snap
        // refraction (px): strong; kept below ~0.8x the band width so the image never folds
        val amp = minOf(80f * dp, 0.8f * band) * waveIn * waveOut
        val blur = 2.2f * dp * waveIn * waveOut
        val glow = 0.55f * waveIn * waveOut

        // lens swell between the fingers: bulges up, then relaxes as the wave leaves
        val st = (t / 0.45f).coerceIn(0f, 1f)
        val swell = 0.32f * sin(PI.toFloat() * st)
        val swellR = 150f * dp

        // wash left behind: rises with the wave, holds, fades in the last ~30%
        val washA = 0.50f * (wt / 0.25f).coerceIn(0f, 1f) * (1f - ((t - 0.70f) / 0.30f).coerceIn(0f, 1f))
        // fingertip orbs: quick bloom, then shrink away as the wave leaves
        // (fixed timing: our window appears after the fingers are down, so it never sees them lift)
        val orbIn = (t / 0.06f).coerceIn(0f, 1f)               // ~60 ms bloom
        val orbOut = ((t - 0.07f) / 0.13f).coerceIn(0f, 1f)    // gone ~0.2 s into the ripple
        val orbA = orbIn * (1f - orbOut * orbOut * (3f - 2f * orbOut))
        val orbR = (130f + 40f * orbIn) * dp * (1f - 0.35f * orbOut)

        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("off", offX, offY)
        shader.setFloatUniform("c", cx + offX, cy + offY)
        shader.setFloatUniform("f1", f1x + offX, f1y + offY)
        shader.setFloatUniform("f2", f2x + offX, f2y + offY)
        shader.setFloatUniform("r", radius)
        shader.setFloatUniform("w", band)
        shader.setFloatUniform("amp", amp)
        shader.setFloatUniform("blur", blur)
        shader.setFloatUniform("glowA", glow)
        shader.setFloatUniform("swell", swell)
        shader.setFloatUniform("swellR", swellR)
        shader.setFloatUniform("washA", washA)
        shader.setFloatUniform("orbA", orbA)
        shader.setFloatUniform("orbR", orbR)
        shader.setFloatUniform("t", t)
        // fades out (FADE_MS) as soon as the wave has cleared the farthest corner; Circle to
        // Search has been open underneath since the start
        shader.setFloatUniform("fadeA", fadeA)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    companion object {
        private const val AGSL = """
uniform shader img;
uniform float2 size;   // view size
uniform float2 off;    // window offset on screen
uniform float2 c;      // wave centre (screen px)
uniform float2 f1;     // fingertips (screen px)
uniform float2 f2;
uniform float r;       // wave-front radius
uniform float w;       // wave half-width
uniform float amp;     // refraction (px)
uniform float blur;    // blur tap spacing at the front (px)
uniform float glowA;   // rim highlight strength
uniform float swell;   // centre lens strength
uniform float swellR;  // centre lens radius
uniform float washA;   // colour wash behind the wave
uniform float orbA;    // fingertip orbs
uniform float orbR;
uniform float hasImg;
uniform float fadeA;   // whole-overlay opacity: 1, then quickly 0 once the wave is done
uniform float t;
uniform float hueShift;
// wash blobs: position+radius, drift (px over the animation), colour
uniform float3 b0; uniform float3 b1; uniform float3 b2; uniform float3 b3;
uniform float2 v0; uniform float2 v1; uniform float2 v2; uniform float2 v3;
uniform float3 c0; uniform float3 c1; uniform float3 c2; uniform float3 c3;

// cool, light spectrum for the thin fringes (no muddy tones)
half3 spec(float h) {
    h = fract(h) * 4.0;
    half3 a = half3(0.40, 0.62, 1.00);
    half3 b = half3(0.70, 0.48, 1.00);
    half3 cc = half3(1.00, 0.52, 0.76);
    half3 d = half3(0.38, 0.88, 0.90);
    float f = smoothstep(0.0, 1.0, fract(h));
    if (h < 1.0) return mix(a, b, f);
    if (h < 2.0) return mix(b, cc, f);
    if (h < 3.0) return mix(cc, d, f);
    return mix(d, a, f);
}

float blob(float2 p, float3 b, float2 v) {
    float2 q = p - (b.xy + v * t);
    return exp(-dot(q, q) / (b.z * b.z));
}

// soft colour field: weighted mix of a few big gaussian blobs
half4 wash(float2 p) {
    float w0 = blob(p, b0, v0), w1 = blob(p, b1, v1), w2 = blob(p, b2, v2), w3 = blob(p, b3, v3);
    float s = w0 + w1 + w2 + w3;
    half3 col = (half3(c0) * w0 + half3(c1) * w1 + half3(c2) * w2 + half3(c3) * w3) / half(max(s, 1e-4));
    return half4(col, half(clamp(s, 0.0, 1.0)));
}

half4 main(float2 q) {
    float2 p = q + off;
    float2 d = p - c;
    float dist = length(d);
    float2 dir = dist > 0.001 ? d / dist : float2(0.0, 1.0);
    float x = (dist - r) / w;
    float env = exp(-x * x * 1.4);

    // wave refraction: magnify toward the crest with a trough behind it
    float o = amp * env * (x * 1.3 - 0.45 * sin(x * 3.14159));
    float2 sp = p - dir * o;
    // lens swell at the centre: samples pulled toward the centre = magnified bulge
    float sw = swell * exp(-dist * dist / (swellR * swellR));
    sp -= d * sw;

    half3 col = half3(0.0);
    float alpha = 1.0;
    if (hasImg > 0.5) {
        // blur along the wave + prism split (thin rainbow fringes like a glass ring)
        float b = blur * env;
        float k = env * 0.22 * (amp > 0.0 ? 1.0 : 0.0) + sw * 0.25;
        float2 dd = (dir * o + d * sw) * k;
        half3 acc = half3(0.0);
        for (int i = -2; i <= 2; i++) {
            float2 s = sp + dir * (float(i) * b);
            acc.r += img.eval(s - dd).r;
            acc.g += img.eval(s).g;
            acc.b += img.eval(s + dd).b;
        }
        col = acc / 5.0;
    } else {
        alpha = 0.0;
    }

    // colour wash in the wake (inside the wave front)
    float inside = smoothstep(r + w * 0.6, r - w * 1.4, dist);
    half4 film = wash(p);
    float wa = washA * inside * float(film.a);
    if (hasImg > 0.5) {
        // dark pixels: lighten with the colour (screen); light pixels: tint toward it (multiply)
        half lum = dot(col, half3(0.30, 0.59, 0.11));
        half3 lifted = 1.0 - (1.0 - col) * (1.0 - film.rgb * 0.75);
        half3 tinted = col * mix(half3(1.0), film.rgb, 0.85);
        half3 blended = mix(lifted, tinted, smoothstep(0.30, 0.75, lum));
        col = mix(col, blended, half(wa));
    } else {
        col += film.rgb * half(wa * 0.6); alpha = max(alpha, wa * 0.6);
    }

    // glassy ring: inner shadow, broad tinted rim (visible on light and dark screens)
    float shade = exp(-(x + 0.6) * (x + 0.6) * 3.5);
    col *= half(1.0 - 0.26 * glowA * shade);
    float rim = exp(-(x - 0.15) * (x - 0.15) * 7.0);
    float ang = atan(d.y, d.x) / 6.2831853;
    half3 tint = mix(half3(1.0), spec(ang + hueShift + t * 0.3), 0.55);
    float g = glowA * (0.8 * rim + 0.2 * env);
    half lumR = dot(col, half3(0.30, 0.59, 0.11));
    // on dark: glow (add); on light: colour the rim (multiply toward the tint)
    half3 glowC = col + tint * half(g * 0.55);
    half3 tintC = mix(col, col * tint, half(clamp(g * 1.4, 0.0, 1.0)));
    col = mix(glowC, tintC, smoothstep(0.35, 0.8, lumR));
    col = mix(col, tint, half(clamp(g, 0.0, 1.0)) * 0.25);
    alpha = max(alpha, g);

    // fingertip orbs: white core, soft tinted fringe
    for (int i = 0; i < 2; i++) {
        float2 fp = i == 0 ? f1 : f2;
        float2 od = p - fp;
        float od2 = length(od) / max(orbR, 1.0);
        float core = exp(-od2 * od2 * 2.2);
        float ring = exp(-(od2 - 0.9) * (od2 - 0.9) * 6.0);
        // coloured glow that tints whatever is underneath (shows on white too), small bright centre
        half3 hue = spec(atan(od.y, od.x) / 6.2831853 + t * 1.2 + hueShift + float(i) * 0.37);
        half3 body = spec(hueShift + float(i) * 0.5 + 0.25);
        float glowBody = exp(-od2 * od2 * 1.2);
        half3 orbCol = mix(body, hue, half(clamp(ring * 1.5, 0.0, 1.0)));
        half lumO = dot(col, half3(0.30, 0.59, 0.11));
        half3 onDark = col + orbCol * half(glowBody * 0.9);
        half3 onLight = mix(col, col * orbCol, half(clamp(glowBody * 1.3, 0.0, 1.0)));
        half3 withOrb = mix(onDark, onLight, smoothstep(0.35, 0.8, lumO));
        withOrb += half3(1.0) * half(exp(-od2 * od2 * 9.0) * 0.35);   // small hot centre
        col = mix(col, withOrb, half(orbA));
        alpha = max(alpha, orbA * max(core, ring * 0.7));
    }

    col = min(col, half3(1.0));
    if (hasImg < 0.5) return half4(col * half(alpha), half(alpha));

    // Solid while the wave plays (no see-through: Circle to Search's UI under a moving copy of
    // the screen looked doubled); the whole overlay fades out quickly once the wave has left.
    return half4(col * half(fadeA), half(fadeA));
}
"""
    }
}
