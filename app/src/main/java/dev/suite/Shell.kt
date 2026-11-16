package dev.suite

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.suite.shell.CtsUserService
import dev.suite.shell.FiUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap

/**
 * The shell-user halves of the sections, run by Shizuku as UserServices straight from Suite's
 * APK (nothing is copied to the phone's storage). Daemon mode: they keep running if Suite's own
 * process is killed; Suite re-attaches to them when it comes back.
 */
object Shell {
    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    enum class Kind(val key: String, val cls: Class<*>, val suffix: String) {
        CTS(Components.KEY_CTS, CtsUserService::class.java, "cts"),
        FI(Components.KEY_GAME, FiUserService::class.java, "fi"),
        SR(Components.KEY_SR, dev.suite.shell.SrUserService::class.java, "sr"),
        STREAM(Components.KEY_STREAM, dev.suite.shell.StreamUserService::class.java, "stream"),
        MIX(Components.KEY_MIX, dev.suite.shell.MixUserService::class.java, "mix"),
        ROUTINES(Routines.KEY, dev.suite.shell.RoutinesUserService::class.java, "routines"),
        UTIL("util_never_enabled", dev.suite.shell.UtilUserService::class.java, "util"),   // one-off jobs
        NFC("nfc_never_enabled", dev.suite.shell.UtilUserService::class.java, "nfc"),     // NFC while the double-press app is open; exits by itself
    }

    private const val TAG = "Suite"
    private val main = Handler(Looper.getMainLooper())
    private val binders = ConcurrentHashMap<Kind, ISuiteService>()
    private val conns = HashMap<Kind, ServiceConnection>()
    private val bindingSince = ConcurrentHashMap<Kind, Long>()
    private lateinit var app: Context

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.NOT_RUNNING
            Shizuku.isPreV11() -> State.NO_PERMISSION
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    } catch (_: Throwable) {
        State.NOT_RUNNING
    }

    /** Live service, or null if it isn't running / not attached yet. */
    fun service(k: Kind): ISuiteService? = binders[k]?.takeIf { it.asBinder().pingBinder() }

    // Changes on every install, so Shizuku replaces a service started from an older APK.
    private fun version(ctx: Context): Int =
        (ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime / 1000 % Int.MAX_VALUE).toInt()

    private fun args(ctx: Context, k: Kind) =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, k.cls.name))
            .daemon(true)
            .processNameSuffix(k.suffix)
            .tag(k.suffix)
            .debuggable(false)
            .version(version(ctx))

    private fun conn(k: Kind) = conns.getOrPut(k) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                bindingSince.remove(k)
                if (b == null || !b.pingBinder()) return
                val s = ISuiteService.Stub.asInterface(b)
                binders[k] = s
                Log.i(TAG, "$k attached")
                if (k == Kind.FI) Thread { runCatching { s.configure(Components.gameConfig(app)) } }.start()
                if (k == Kind.CTS) Thread { runCatching { s.configure(Components.ctsConfig(app)) } }.start()
                if (k == Kind.ROUTINES) Thread { runCatching { s.configure(Routines.config(app)) } }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binders.remove(k)
                Log.i(TAG, "$k disconnected")
                // crashed? bring it back if it should be running
                main.postDelayed({
                    val paused = k == Kind.FI && Components.enabled(app, Components.KEY_STREAM)   // paused while streaming
                    if (Components.enabled(app, k.key) && !paused) start(app, k)
                }, 3000)
            }
        }
    }

    /** Starts (or re-attaches to) the service. Safe to call repeatedly. */
    fun start(ctx: Context, k: Kind) {
        app = ctx.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { start(ctx, k) }; return }
        if (state() != State.READY || service(k) != null) return
        val since = bindingSince[k]
        if (since != null && System.currentTimeMillis() - since < 15_000) return   // already on its way
        bindingSince[k] = System.currentTimeMillis()
        try {
            Shizuku.bindUserService(args(app, k), conn(k))
        } catch (t: Throwable) {
            bindingSince.remove(k)
            Log.w(TAG, "bind $k failed", t)
        }
    }

    /** Stops the service (it cleans up first, e.g. FI + SR off). Call off the main thread. */
    fun stop(ctx: Context, k: Kind) {
        app = ctx.applicationContext
        binders.remove(k)?.let { s -> runCatching { s.destroy() } }   // the process exits; DeadObject is expected
        bindingSince.remove(k)
        main.post {
            if (state() == State.READY) {
                runCatching { Shizuku.unbindUserService(args(app, k), conn(k), true) }
                    .onFailure { Log.w(TAG, "unbind $k", it) }
            }
        }
    }

    /** Runs one command in the one-off shell service, then stops it. Off the main thread. */
    fun oneShot(ctx: Context, cmd: String): String? {
        val r = command(ctx, Kind.UTIL, cmd)
        stop(ctx, Kind.UTIL)
        return r
    }

    /** Sends a command, starting the service first if needed (waits up to 8 s). Off the main thread. */
    fun command(ctx: Context, k: Kind, cmd: String): String? {
        if (state() != State.READY) return null
        var s = service(k)
        if (s == null) {
            start(ctx, k)
            val until = System.currentTimeMillis() + 8000
            while (s == null && System.currentTimeMillis() < until) { Thread.sleep(100); s = service(k) }
        }
        return s?.let { runCatching { it.command(cmd) }.getOrElse { e -> e.toString() } }
    }

    /** Shizuku went away (e.g. reboot pending, server restarted): forget the binders. */
    fun reset() {
        binders.clear()
        bindingSince.clear()
    }
}
