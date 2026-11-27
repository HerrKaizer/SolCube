package dev.suite

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.suite.shell.CtsUserService
import dev.suite.shell.FiUserService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Manager for SolCube's shell-user (`uid=2000`) services, all hosted by SolEngine (`SolCubeDaemon`,
 * process `solcube_engine`). Shizuku support was removed in 5.7.0 on purpose: one engine to support,
 * and former Shizuku users are sent through SolEngine pairing.
 */
object Shell {
    enum class State { NOT_RUNNING, READY }
    enum class Backend { NONE, BUILTIN }

    enum class Kind(val key: String, val cls: Class<*>, val suffix: String) {
        CTS(Components.KEY_CTS, CtsUserService::class.java, "cts"),
        FI(Components.KEY_GAME, FiUserService::class.java, "fi"),
        SR(Components.KEY_SR, dev.suite.shell.SrUserService::class.java, "sr"),
        STREAM(Components.KEY_STREAM, dev.suite.shell.StreamUserService::class.java, "stream"),
        MIX(Components.KEY_MIX, dev.suite.shell.MixUserService::class.java, "mix"),
        ROUTINES(Routines.KEY, dev.suite.shell.RoutinesUserService::class.java, "routines"),
        PROFILES(Profiles.KEY, dev.suite.shell.ProfilesUserService::class.java, "profiles"),
        TEMP(Components.KEY_TEMP, dev.suite.shell.TempUserService::class.java, "temp"),
        STATUSBAR(Components.KEY_SB_LOCK, dev.suite.shell.StatusBarUserService::class.java, "sb"),
        UTIL("util_never_enabled", dev.suite.shell.UtilUserService::class.java, "util"),   // one-off jobs
        NFC("nfc_never_enabled", dev.suite.shell.UtilUserService::class.java, "nfc"),     // NFC while double-press app is open; exits by itself
    }

    private const val TAG = "SolCube"
    private val main = Handler(Looper.getMainLooper())
    private val binders = ConcurrentHashMap<Kind, ISolCubeService>()
    private val bindingSince = ConcurrentHashMap<Kind, Long>()
    private val stateListeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var daemon: ISolCubeDaemon? = null

    @Volatile
    private var app: Context? = null

    fun addStateListener(l: () -> Unit) { stateListeners.add(l) }
    fun removeStateListener(l: () -> Unit) { stateListeners.remove(l) }

    private fun notifyStateChanged() {
        main.post { stateListeners.forEach { runCatching { it() } } }
    }

    fun hasBuiltInDaemon(): Boolean =
        daemon?.let { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) } == true

    fun backend(): Backend = when {
        hasBuiltInDaemon() -> Backend.BUILTIN
        else -> Backend.NONE
    }

    fun state(): State = if (hasBuiltInDaemon()) State.READY else State.NOT_RUNNING

    /** Live service, or null if it isn't running / not attached yet. */
    fun service(k: Kind): ISolCubeService? = binders[k]?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }

    fun apkVersion(ctx: Context): Int =
        runCatching {
            (ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime / 1000 % Int.MAX_VALUE).toInt()
        }.getOrDefault(1)

    /** Built-in engine version: fingerprint of the installed APK path (changes on every install).
     *  Must match SolCubeDaemon.versionOf(). */
    fun engineVersion(ctx: Context): Int = ctx.applicationInfo.sourceDir.hashCode() and Int.MAX_VALUE

    @Volatile private var upgradeAskedAt = 0L

    /** The engine is still running an older SolCube APK: ask it to restart from the new one. Engines from
     *  before 5.4.7 don't know UPGRADE; they keep running (old code) until the next reconnect or reboot.
     *  Never shut them down here: if Wireless debugging isn't listening, nothing could replace them. */
    private fun upgradeStaleEngine(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - upgradeAskedAt < 60_000L) return
        upgradeAskedAt = now
        Thread {
            if (AdbEngine.pingDaemonCommand(ctx, "UPGRADE")) Log.i(TAG, "asked the engine to upgrade")
            else Log.w(TAG, "engine is from an older build and can't upgrade itself")
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ Built-in Engine attach

    fun onDaemonAttached(ctx: Context, daemonBinder: IBinder, children: Bundle?) {
        val context = ctx.applicationContext
        app = context
        val d = ISolCubeDaemon.Stub.asInterface(daemonBinder) ?: return
        daemon = d
        val running = runCatching { d.version() }.getOrDefault(0)
        if (running != engineVersion(context)) upgradeStaleEngine(context)
        SetupPrefs.markConnected(context)
        AdbEngine.keepPairing(context)   // Android must never auto-revoke the pairing
        runCatching {
            daemonBinder.linkToDeath({
                if (daemon?.asBinder() === daemonBinder) {
                    daemon = null
                    binders.clear()
                    bindingSince.clear()
                    Log.i(TAG, "built-in daemon died")
                    notifyStateChanged()
                }
            }, 0)
        }
        if (children != null) {
            for (k in Kind.entries) {
                val cb = children.getBinder(k.suffix)
                if (cb != null && runCatching { cb.pingBinder() }.getOrDefault(false)) {
                    attachServiceBinder(context, k, cb, cacheInDaemon = false)
                }
            }
        }
        AppLog.add(context, "SolEngine connected")
        Thread {
            runCatching { SetupPrefs.grantBasicsOnce(context) }
            runCatching { DoublePress.ensure(context, "engine start") }
        }.apply { isDaemon = true }.start()
        Components.ensureCtsAssistant(context, "engine start")
        notifyStateChanged()
    }

    fun onChildAttached(ctx: Context, suffix: String, childBinder: IBinder) {
        val context = ctx.applicationContext
        app = context
        val k = Kind.entries.firstOrNull { it.suffix == suffix } ?: return
        attachServiceBinder(context, k, childBinder, cacheInDaemon = true)
    }

    private fun attachServiceBinder(ctx: Context, k: Kind, b: IBinder, cacheInDaemon: Boolean) {
        bindingSince.remove(k)
        if (!runCatching { b.pingBinder() }.getOrDefault(false)) return
        val s = ISolCubeService.Stub.asInterface(b) ?: return
        binders[k] = s
        if (cacheInDaemon) {
            runCatching { daemon?.cacheChild(k.suffix, b) }
        }
        runCatching {
            b.linkToDeath({
                if (binders[k]?.asBinder() === b) {
                    binders.remove(k)
                    Log.i(TAG, "$k disconnected")
                    scheduleRestartIfWanted(ctx, k)
                }
            }, 0)
        }
        Log.i(TAG, "$k attached")
        pushInitialConfig(ctx, k, s)
    }

    private fun pushInitialConfig(ctx: Context, k: Kind, s: ISolCubeService) {
        when (k) {
            Kind.FI -> Thread { runCatching { s.configure(Components.gameConfig(ctx)) } }.start()
            Kind.CTS -> Thread { runCatching { s.configure(Components.ctsConfig(ctx)) } }.start()
            Kind.ROUTINES -> Thread { runCatching { s.configure(Routines.config(ctx)) } }.start()
            Kind.PROFILES -> Thread { runCatching { s.configure(Profiles.config(ctx)) } }.start()
            Kind.STREAM -> Thread { runCatching { s.configure(Components.streamConfig(ctx)) } }.start()
            Kind.STATUSBAR -> Thread { runCatching { s.configure(Components.statusBarConfig(ctx)) } }.start()
            else -> {}
        }
    }

    private fun scheduleRestartIfWanted(ctx: Context, k: Kind) {
        main.postDelayed({
            val paused = k == Kind.FI && Components.enabled(ctx, Components.KEY_STREAM)   // paused while streaming
            val wanted = when (k) {
                Kind.TEMP -> Components.enabled(ctx, Components.KEY_TEMP) || Components.enabled(ctx, Components.KEY_FPS)
                Kind.CTS -> Components.ctsServiceWanted(ctx)
                else -> Components.enabled(ctx, k.key)
            }
            if (wanted && !paused) start(ctx, k)
        }, 3000)
    }

    /** Non-blocking attempt to re-attach to an already-running `solcube_engine` daemon. */
    fun tryAttachExisting(ctx: Context, done: (() -> Unit)? = null) {
        if (hasBuiltInDaemon()) { done?.let { main.post(it) }; return }
        val appCtx = ctx.applicationContext
        app = appCtx
        Thread {
            if (AdbEngine.pingDaemonCommand(appCtx, "ATTACH")) {
                val until = System.currentTimeMillis() + 1500L
                while (!hasBuiltInDaemon() && System.currentTimeMillis() < until) Thread.sleep(50)
            }
            done?.let { main.post(it) }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts (or re-attaches to) the service. Safe to call repeatedly. */
    fun start(ctx: Context, k: Kind) {
        val appCtx = ctx.applicationContext
        app = appCtx
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { start(appCtx, k) }; return }
        if (state() != State.READY || service(k) != null) return
        val since = bindingSince[k]
        if (since != null && System.currentTimeMillis() - since < 15_000) return   // already on its way
        bindingSince[k] = System.currentTimeMillis()

        when (backend()) {
            Backend.BUILTIN -> {
                Thread {
                    try {
                        val d = daemon ?: run { bindingSince.remove(k); return@Thread }
                        val existing = runCatching { d.getChild(k.suffix) }.getOrNull()
                        if (existing != null && runCatching { existing.pingBinder() }.getOrDefault(false)) {
                            attachServiceBinder(appCtx, k, existing, cacheInDaemon = false)
                        } else {
                            d.spawnService(k.cls.name, k.suffix)
                        }
                    } catch (t: Throwable) {
                        bindingSince.remove(k)
                        Log.w(TAG, "builtin spawn $k failed", t)
                    }
                }.start()
            }
            Backend.NONE -> bindingSince.remove(k)
        }
    }

    /** Stops the service (it cleans up first, e.g. FI + SR off). Call off the main thread. */
    fun stop(ctx: Context, k: Kind) {
        val appCtx = ctx.applicationContext
        app = appCtx
        val b = binders.remove(k)
        bindingSince.remove(k)
        b?.let { s -> runCatching { s.destroy() } }   // the process exits; DeadObject is expected
        val d = daemon
        if (d != null) {
            runCatching { d.stopService(k.suffix) }
        }
    }

    private val utilUsers = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var utilIdleToken = 0L

    /**
     * Runs one command in the shared one-off shell service. Off the main thread. The service stays up
     * for 30 s after the last command (each spawn is a new app_process, ~0.3 s), and it is only stopped
     * once nobody is using it, so two callers can no longer stop each other's command midway.
     */
    fun oneShot(ctx: Context, cmd: String): String? {
        val appCtx = ctx.applicationContext
        utilUsers.incrementAndGet()
        utilIdleToken = 0L
        try {
            return command(appCtx, Kind.UTIL, cmd)
        } finally {
            if (utilUsers.decrementAndGet() == 0) {
                val token = System.nanoTime().also { utilIdleToken = it }
                main.postDelayed({
                    if (utilIdleToken == token && utilUsers.get() == 0) Thread { stop(appCtx, Kind.UTIL) }.start()
                }, 30_000)
            }
        }
    }

    /** Sends a command, starting the service first if needed (waits up to 8 s). Off the main thread. */
    fun command(ctx: Context, k: Kind, cmd: String): String? {
        val appCtx = ctx.applicationContext
        if (state() != State.READY) return null
        var s = service(k)
        if (s == null) {
            start(appCtx, k)
            val until = System.currentTimeMillis() + 8000
            while (s == null && System.currentTimeMillis() < until) { Thread.sleep(100); s = service(k) }
        }
        return s?.let {
            if (k == Kind.UTIL || k == Kind.NFC) {
                runCatching { it.configure(org.json.JSONObject().put("secret", Ipc.secret(appCtx)).toString()) }
            }
            runCatching { it.command(cmd) }.getOrElse { e -> e.toString() }
        }
    }

    /** Stops the built-in uid=2000 daemon (best-effort) and clears local binder caches. */
    fun shutdownBuiltInDaemon() {
        val d = daemon
        if (d != null) {
            Thread { runCatching { d.shutdown() } }.apply { isDaemon = true }.start()
        }
        reset()
    }

    /** Server went away (e.g. reboot pending, server restarted): forget the binders. */
    fun reset() {
        if (daemon != null) app?.let { AppLog.add(it, "SolEngine disconnected") }
        daemon = null
        binders.clear()
        bindingSince.clear()
        notifyStateChanged()
    }
}
