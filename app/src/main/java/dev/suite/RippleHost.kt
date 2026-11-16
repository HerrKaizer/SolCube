package dev.suite

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Circle to Search ripple (formerly the CtsRipple app). Keeps a localhost socket open for the
 * shell-side listener (suite.cts).
 * Protocol: "ripple fx fy len f1x f1y f2x f2y [holdMs]\n" -> "ok\n", then len bytes of raw
 * screencap; we reply "cts\n" once the frame is on screen (listener opens Circle to Search),
 * "start\n" when the ripple begins moving and "done\n" at the end.
 */
class RippleHost(private val ctx: Context) {

    companion object {
        const val PORT = 47321
        private const val TAG = "Suite.ripple"
        @Volatile var running = false
        @Volatile var instance: RippleHost? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val winCtx: Context
    private val wm: WindowManager
    private var server: ServerSocket? = null
    private var keepAlive: android.view.View? = null
    @Volatile private var holdOverride: Long? = null

    init {
        // createWindowContext: Service.display crashes on Android 16
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        winCtx = ctx.createWindowContext(
            dm.getDisplay(android.view.Display.DEFAULT_DISPLAY),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
        )
        wm = winCtx.getSystemService(WindowManager::class.java)
    }

    fun start() {
        if (running) return
        running = true
        instance = this
        Thread(::serve, "ripple-server").start()
        addKeepAlive()
    }

    fun stop() {
        running = false
        if (instance === this) instance = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        keepAlive?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        keepAlive = null
    }

    /** Test button in the app. */
    fun test() = main.post { show(0.5f, 0.5f, null, null, {}, {}) {} }

    private fun serve() {
        while (running) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
                server = ss
                if (!running) { ss.close(); return }
                Log.i(TAG, "listening on $PORT")
                while (!ss.isClosed) {
                    val s = ss.accept()
                    Thread { handle(s) }.start()
                }
            } catch (e: Exception) {
                // e.g. port still held by something else: try again shortly
                if (running) { Log.w(TAG, "server: ${e.message}"); Thread.sleep(3000) }
            }
        }
    }

    private fun handle(s: Socket) {
        s.use {
            it.tcpNoDelay = true
            it.receiveBufferSize = 1 shl 22
            val input = java.io.DataInputStream(java.io.BufferedInputStream(it.getInputStream(), 1 shl 20))
            val line = readLine(input) ?: return
            val p = line.trim().split(" ")
            if (p.size < 3 || p[0] != "ripple") return
            val fx = p[1].toFloatOrNull() ?: return
            val fy = p[2].toFloatOrNull() ?: return
            val len = p.getOrNull(3)?.toIntOrNull() ?: 0
            val fingers = if (p.size >= 8) FloatArray(4) { i -> p[4 + i].toFloatOrNull() ?: 0.5f } else null
            holdOverride = p.getOrNull(8)?.toLongOrNull()
            val out = it.getOutputStream()
            out.write("ok\n".toByteArray()); out.flush()
            var frame: android.graphics.Bitmap? = null
            if (len > 16) {
                val t0 = android.os.SystemClock.uptimeMillis()
                val data = ByteArray(len)
                input.readFully(data)
                frame = decodeScreencap(data)
                Log.i(TAG, "frame ${frame?.width}x${frame?.height} in ${android.os.SystemClock.uptimeMillis() - t0}ms")
            }
            val cts = CountDownLatch(1)
            val start = CountDownLatch(1)
            val done = CountDownLatch(1)
            main.post {
                show(fx, fy, fingers, frame, { cts.countDown() }, { start.countDown() }) {
                    cts.countDown(); start.countDown(); done.countDown()
                }
            }
            cts.await(4, TimeUnit.SECONDS)
            out.write("cts\n".toByteArray()); out.flush()
            start.await(4, TimeUnit.SECONDS)
            out.write("start\n".toByteArray()); out.flush()
            done.await(4, TimeUnit.SECONDS)
            out.write("done\n".toByteArray()); out.flush()
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    /** Raw `screencap` output: 16-byte header (w, h, format, colorspace; LE) + RGBA_8888 pixels. */
    private fun decodeScreencap(data: ByteArray): android.graphics.Bitmap? {
        val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = bb.getInt(0); val h = bb.getInt(4); val fmt = bb.getInt(8); val cs = bb.getInt(12)
        if (fmt != 1 || w <= 0 || h <= 0 || data.size < 16 + w * h * 4) return null
        val space = android.graphics.ColorSpace.get(
            if (cs == 2) android.graphics.ColorSpace.Named.DISPLAY_P3 else android.graphics.ColorSpace.Named.SRGB
        )
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888, false, space)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(data, 16, w * h * 4))
        val screen = wm.maximumWindowMetrics.bounds
        return if (screen.width() > 0 && (screen.width() != w || screen.height() != h))
            android.graphics.Bitmap.createScaledBitmap(bmp, screen.width(), screen.height(), true)
        else bmp
    }

    /**
     * A permanent 1x1 px transparent, untouchable overlay. A visible overlay window makes the
     * process "perceptible", which keeps vivo's fast_freezer off it.
     */
    private fun addKeepAlive() {
        if (keepAlive != null || !Settings.canDrawOverlays(ctx)) return
        val v = android.view.View(winCtx)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            alpha = 0f
            title = "SuiteKeepAlive"
        }
        try { wm.addView(v, lp); keepAlive = v } catch (e: Exception) { Log.w(TAG, "keepAlive", e) }
    }

    /** Must run on the main thread. */
    private fun show(
        fx: Float, fy: Float, fingers: FloatArray?, frame: android.graphics.Bitmap?,
        onCts: () -> Unit, onStart: () -> Unit, onDone: () -> Unit
    ) {
        // Prefer the accessibility overlay: it is drawn above the status and navigation bars, so
        // the real status bar doesn't show on top of the screenshot's own. Fall back to the
        // normal app overlay (under the bars) if the accessibility service isn't connected.
        val a11y = SuiteA11y.instance
        val vctx: Context = a11y ?: winCtx
        val vwm: WindowManager = a11y?.getSystemService(WindowManager::class.java) ?: wm
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (a11y == null && !Settings.canDrawOverlays(ctx)) { onDone(); return }
        val view = RippleView(vctx)
        holdOverride?.let { view.ctsHoldMs = it }
        // the app-overlay fallback IS captured by Circle to Search's screenshot: keep it frozen
        if (a11y == null) view.ctsHoldMs = maxOf(view.ctsHoldMs, 250L)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Touchable on purpose: Android caps pass-through (NOT_TOUCHABLE) overlays at 0.8
            // opacity, which let the real screen show through the warped screenshot.
            // The window lives < 1 s and just swallows touches meanwhile.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "SuiteRipple"
            colorMode = 1   // ActivityInfo.COLOR_MODE_WIDE_COLOR; the screenshot is Display P3
        }
        // Map the panel's natural-orientation point to current screen coordinates.
        val disp = winCtx.display
        val mode = disp.mode
        val nw = minOf(mode.physicalWidth, mode.physicalHeight).toFloat()
        val nh = maxOf(mode.physicalWidth, mode.physicalHeight).toFloat()
        val bounds = wm.maximumWindowMetrics.bounds
        val scale = maxOf(bounds.width(), bounds.height()) / nh
        fun map(ax: Float, ay: Float): Pair<Float, Float> = when (disp.rotation) {
            Surface.ROTATION_90 -> ay * nh to (1f - ax) * nw
            Surface.ROTATION_180 -> (1f - ax) * nw to (1f - ay) * nh
            Surface.ROTATION_270 -> (1f - ay) * nh to ax * nw
            else -> ax * nw to ay * nh
        }.let { (a, b) -> a * scale to b * scale }
        val (sx, sy) = map(fx, fy)
        val fs = fingers?.let {
            val (a, b) = map(it[0], it[1]); val (c, d) = map(it[2], it[3])
            floatArrayOf(a, b, c, d)
        }
        var added = true
        try { vwm.addView(view, lp) } catch (e: Exception) { Log.w(TAG, "addView", e); added = false }
        if (!added) { onDone(); return }
        Log.i(TAG, if (a11y != null) "ripple window: accessibility overlay (above bars)" else "ripple window: app overlay")
        view.post {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val lx = loc[0].toFloat(); val ly = loc[1].toFloat()
            val vf = fs?.let { floatArrayOf(it[0] - lx, it[1] - ly, it[2] - lx, it[3] - ly) }
            view.play(sx - lx, sy - ly, vf, frame, lx, ly, onCts, onStart) {
                try { vwm.removeViewImmediate(view) } catch (_: Exception) {}
                onDone()
            }
        }
    }
}
