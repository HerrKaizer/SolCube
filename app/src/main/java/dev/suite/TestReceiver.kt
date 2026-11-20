package dev.suite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Developer hook (only shell/system can send it, see the manifest permission):
 *   am broadcast -n dev.suite/.TestReceiver -a dev.suite.TEST_CTS --ei freeze 150
 * runs the full screenshot -> ripple -> Circle to Search sequence with that freeze, and
 *   am broadcast -n dev.suite/.TestReceiver -a dev.suite.SET_FREEZE --ei freeze 150
 * stores a new default freeze.
 */
/** Switches for measuring rendering cost (set by TestReceiver, read by the screens). */
object RenderTest {
    /** Glass panes at this nesting level or deeper are drawn flat (0 = all, 3 = none). Test only. */
    @Volatile var flatGlassFrom = 3
    @Volatile var rebuild: (() -> Unit)? = null
}

class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ms = intent.getIntExtra("freeze", Components.DEFAULT_FREEZE)
        val ctx = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    "dev.suite.TEST_CTS" -> Shell.service(Shell.Kind.CTS)?.command("test $ms")
                    // am broadcast -n dev.suite/.TestReceiver -a dev.suite.RT_STATUS  → logcat tag Suite
                    // am broadcast -n dev.suite/.TestReceiver -a dev.suite.RT_RUN --es id work
                    "dev.suite.RT_CLOSE" -> Shell.service(Shell.Kind.ROUTINES)?.command("close_apps")
                    // --es apps pkg1,pkg2
                    "dev.suite.RT_REVIVE" -> Shell.service(Shell.Kind.ROUTINES)?.command("revive " + intent.getStringExtra("apps"))
                    "dev.suite.RT_RUN" -> Shell.command(ctx, Shell.Kind.ROUTINES, "run " + intent.getStringExtra("id"))
                    "dev.suite.RT_STATUS" -> android.util.Log.i("Suite", "routines: " + Shell.service(Shell.Kind.ROUTINES)?.status())
                    "dev.suite.SET_FEATURE" -> {
                        val on = intent.getBooleanExtra("on", false)
                        when (intent.getStringExtra("feature")) {
                            "mix" -> Components.setMix(ctx, on)
                            "sr" -> Components.setSr(ctx, on)
                            "fi" -> Components.setGame(ctx, on)
                            "temp" -> Components.setTemp(ctx, on)
                        }
                    }
                    // the laptop helper hands over its per-run token (it can only reach us via adb)
                    "dev.suite.HELPER_TOKEN" -> intent.getStringExtra("token")?.takeIf { it.matches(Regex("[0-9a-f]{32,64}")) }?.let { t ->
                        Ipc.setHelperToken(ctx, t)
                        Shell.service(Shell.Kind.STREAM)?.configure(Components.streamConfig(ctx))
                    }
                    // measuring the rendering cost of each effect:
                    // --ei header 0|1|3 (header blur off / one pass / the old three passes)
                    // --ei motion 0|1|2 (wallpaper still / sways on open / always). Rebuilds the screen.
                    "dev.suite.RENDER_TEST" -> {
                        if (intent.hasExtra("header")) Components.prefs(ctx).edit().putInt("header_passes", intent.getIntExtra("header", 3)).commit()
                        if (intent.hasExtra("motion")) Components.prefs(ctx).edit()
                            .putInt(GlassBackdrop.KEY_MOTION, intent.getIntExtra("motion", 1)).commit()
                        if (intent.hasExtra("glass")) RenderTest.flatGlassFrom = intent.getIntExtra("glass", 3)
                        if (intent.hasExtra("half")) Components.prefs(ctx).edit()
                            .putBoolean(GlassBackdrop.KEY_HALF, intent.getBooleanExtra("half", false)).commit()
                        RenderTest.rebuild?.invoke()
                    }
                    // logs what the temperature pill shows, once a second for --ei sec N (tag SuiteLoad):
                    // "cpuTemp gpuTemp cpuLoad% gpuLoad%". Reads the pill's last values when it's running
                    // (a second reader would reset the GPU busy counter under it).
                    "dev.suite.LOAD_LOG" -> {
                        val n = intent.getIntExtra("sec", 10).coerceIn(1, 120)
                        Thread {
                            if (Shell.service(Shell.Kind.TEMP) == null) Shell.start(ctx, Shell.Kind.TEMP)
                            repeat(n) {
                                Thread.sleep(1000)
                                val s = Shell.service(Shell.Kind.TEMP)
                                val line = if (TempOverlay.running) runCatching {
                                    org.json.JSONObject(s?.status() ?: "{}").let { o ->
                                        "${o.optInt("cpuTemp")} ${o.optInt("gpuTemp")} ${o.optInt("cpu")} ${o.optInt("gpu")}"
                                    }
                                }.getOrNull() else runCatching { s?.command("all") }.getOrNull()
                                // battery current in mA (the public BatteryManager API, like AnTuTu uses; the raw file is
                                // closed to adb). Sign varies by phone: negative = draining on most.
                                val ua = runCatching {
                                    ctx.getSystemService(android.os.BatteryManager::class.java)
                                        .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                                }.getOrDefault(Int.MIN_VALUE)
                                val ma = if (ua == Int.MIN_VALUE) "?" else (if (kotlin.math.abs(ua) > 20000) ua / 1000 else ua).toString()
                                android.util.Log.i("SuiteLoad", (line ?: "n/a") + " " + ma)
                            }
                        }.start()
                    }
                    "dev.suite.SET_FREEZE" -> {
                        Components.prefs(ctx).edit().putInt(Components.KEY_FREEZE, ms.coerceIn(0, 600)).commit()
                        Shell.service(Shell.Kind.CTS)?.configure(Components.ctsConfig(ctx))
                    }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
