package dev.suite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Process-wide host for SolCube's in-app runtime: the ripple server/keep-alive, temperature pill,
 * stream bubble, Shizuku reconciler, and update check. Hosted inside [SolCubeA11y] whenever the
 * accessibility service is available (zero system notifications), falling back to [SolCubeService]
 * only when neither accessibility nor Shizuku can keep the process bound.
 */
object SolCubeHost {
    private const val WATCHDOG_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var started = false
    private var ripple: RippleHost? = null
    private var temp: TempOverlay? = null
    private var stream: StreamOverlay? = null
    private var worker: HandlerThread? = null
    private var bg: Handler? = null
    private var enablingA11y = false

    private val fgsFallback = Runnable {
        val c = appCtx ?: return@Runnable
        if (SolCubeService.anyEnabled(c) && SolCubeA11y.instance == null) startFallbackFgs(c)
    }

    private val disableA11yTask = Runnable {
        val c = appCtx ?: return@Runnable
        if (!SolCubeService.anyEnabled(c) && SolCubeA11y.isEnabled(c) && Shell.state() == Shell.State.READY) {
            Thread { runCatching { Shell.oneShot(c, "disable-a11y") } }.start()
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            Components.reconcile(c)
            shizukuCheck(false)
            // daily GitHub release check (Updates.check returns at once until a day has passed);
            // a new version opens its pop-up once (allowed from the background: SolCube can draw over apps / a11y)
            runCatching {
                Updates.check(c)?.let { r ->
                    if (Updates.firstShow(c, r)) {
                        c.startActivity(Intent(c, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            bg?.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ---- Shizuku stopped warning: a pill (tap opens Shizuku) once it has been down a minute, while
    // the phone is in use; again on unlock, at most every 15 min, until it runs again.
    private var shizukuDownSince = 0L
    private var shizukuWarnedAt = 0L

    private fun shizukuCheck(unlocked: Boolean) {
        val c = appCtx ?: return
        val now = System.currentTimeMillis()
        if (Shell.state() != Shell.State.NOT_RUNNING || !SolCubeService.anyEnabled(c)) {
            shizukuDownSince = 0L; shizukuWarnedAt = 0L; return
        }
        if (shizukuDownSince == 0L) shizukuDownSince = now
        if (now - shizukuDownSince < 55_000L) return
        val inUse = c.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (!inUse) return
        val again = if (unlocked) 15 * 60_000L else Long.MAX_VALUE
        if (shizukuWarnedAt != 0L && now - shizukuWarnedAt < again) return
        shizukuWarnedAt = now
        Island.show(c, "Shizuku stopped · tap to start", ok = false) {
            val i = c.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(c, MainActivity::class.java)
            runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { bg?.postDelayed({ shizukuCheck(true) }, 1200) }
    }

    // Shizuku started (e.g. after a reboot): bring the shell side up.
    private val binderListener = Shizuku.OnBinderReceivedListener {
        val c = appCtx ?: return@OnBinderReceivedListener
        bg?.post {
            Components.reconcile(c)
            if (SolCubeService.anyEnabled(c) && SolCubeA11y.instance == null) requestA11yEnable(c)
        }
    }
    private val deadListener = Shizuku.OnBinderDeadListener { Shell.reset(); bg?.post { shizukuCheck(false) } }

    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { sync(app) }; return }
        appCtx = app
        if (!SolCubeService.anyEnabled(app)) {
            main.removeCallbacks(fgsFallback)
            stop()
            runCatching { app.stopService(Intent(app, SolCubeService::class.java)) }
            main.removeCallbacks(disableA11yTask)
            main.postDelayed(disableA11yTask, 6_000L)
            return
        }
        main.removeCallbacks(disableA11yTask)
        when {
            SolCubeA11y.instance != null -> {
                main.removeCallbacks(fgsFallback)
                ensureStarted(app)
                apply(app)
                runCatching { app.stopService(Intent(app, SolCubeService::class.java)) }
            }
            SolCubeA11y.isEnabled(app) -> {
                if (SolCubeService.running) { ensureStarted(app); apply(app) }
                main.removeCallbacks(fgsFallback)
                main.postDelayed(fgsFallback, 3_000L)
            }
            Shell.state() == Shell.State.READY -> {
                if (SolCubeService.running) { ensureStarted(app); apply(app) }
                requestA11yEnable(app)
                main.removeCallbacks(fgsFallback)
                main.postDelayed(fgsFallback, 3_000L)
            }
            else -> startFallbackFgs(app)
        }
    }

    fun onA11yConnected(a11y: SolCubeA11y) {
        val app = a11y.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onA11yConnected(a11y) }; return }
        appCtx = app
        main.removeCallbacks(fgsFallback)
        if (!SolCubeService.anyEnabled(app)) return
        main.removeCallbacks(disableA11yTask)
        ensureStarted(app)
        apply(app)
        runCatching { app.stopService(Intent(app, SolCubeService::class.java)) }
    }

    fun onA11yDisconnected(ctx: Context) {
        val app = ctx.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onA11yDisconnected(app) }; return }
        appCtx = app
        ripple?.dropA11yKeepAlive()
        if (temp?.usingA11y == true) { temp?.stop(); temp = null }
        if (stream?.usingA11y == true) { stream?.stop(); stream = null }
        if (!SolCubeService.anyEnabled(app)) {
            stop()
        } else if (!SolCubeService.running) {
            main.removeCallbacks(fgsFallback)
            main.postDelayed(fgsFallback, 1_500L)
        }
    }

    fun onFgsStarted(svc: Service) {
        val app = svc.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onFgsStarted(svc) }; return }
        appCtx = app
        main.removeCallbacks(disableA11yTask)
        ensureStarted(app)
        apply(app)
        bg?.post { Components.reconcile(app) }
    }

    fun onConfigurationChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onConfigurationChanged() }; return }
        temp?.onConfigurationChanged()
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop() }; return }
        ripple?.stop(); ripple = null
        temp?.stop(); temp = null
        stream?.stop(); stream = null
        if (!started) return
        started = false
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        appCtx?.let { runCatching { it.unregisterReceiver(unlockReceiver) } }
        bg?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        bg = null; worker = null
    }

    private fun ensureStarted(app: Context) {
        appCtx = app
        if (started) return
        started = true
        val wt = HandlerThread("suite-bg").apply { start() }
        worker = wt
        val bh = Handler(wt.looper)
        bg = bh
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        runCatching {
            app.registerReceiver(unlockReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
        }
        bh.postDelayed({ Components.reconcile(app) }, 2000)
        bh.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun requestA11yEnable(app: Context) {
        if (enablingA11y || SolCubeA11y.isEnabled(app) || Shell.state() != Shell.State.READY) return
        enablingA11y = true
        Thread {
            try {
                Shell.oneShot(app, "enable-a11y")
            } finally {
                main.post { enablingA11y = false }
            }
        }.start()
    }

    private fun startFallbackFgs(app: Context) {
        if (!SolCubeService.anyEnabled(app) || SolCubeA11y.instance != null) return
        runCatching { app.startForegroundService(Intent(app, SolCubeService::class.java)) }
    }

    /** Starts/stops or upgrades the in-app parts to match the switches. */
    private fun apply(app: Context) {
        if (Components.ctsRippleWanted(app)) {
            if (ripple == null) ripple = RippleHost(app).also { it.start() }
            else ripple?.refreshKeepAlive()
        } else {
            ripple?.stop(); ripple = null
        }
        if (Components.enabled(app, Components.KEY_STREAM)) {
            if (stream != null && !stream!!.usingA11y && SolCubeA11y.instance != null) {
                stream?.stop(); stream = null
            }
            if (stream == null) stream = StreamOverlay(app).also { it.start() }
        } else {
            stream?.stop(); stream = null
        }
        if (Components.enabled(app, Components.KEY_TEMP) || Components.enabled(app, Components.KEY_FPS)) {
            if (temp != null && !temp!!.usingA11y && SolCubeA11y.instance != null) {
                temp?.stop(); temp = null
            }
            if (temp == null) temp = TempOverlay(app).also { it.start() }
            temp?.refresh()   // which pills show (temperatures, load, FPS)
            if (!TempOverlay.running) { temp?.stop(); temp = TempOverlay(app).also { it.start() } }
        } else {
            temp?.stop(); temp = null
        }
    }
}

/**
 * Fallback foreground service when neither [SolCubeA11y] nor Shizuku is available to keep SolCube
 * bound without triggering "running in the background" notifications.
 */
class SolCubeService : Service() {

    companion object {
        private const val CHANNEL = "suite_bg"
        @Volatile var running = false

        fun anyEnabled(ctx: Context): Boolean =
            listOf(Components.KEY_CTS, Components.KEY_GAME, Components.KEY_SR, Components.KEY_TEMP, Components.KEY_FPS, Components.KEY_STREAM, Components.KEY_MIX, Routines.KEY, Profiles.KEY)
                .any { Components.enabled(ctx, it) }

        /** Starts/updates or stops the host runtime (a11y first, FGS fallback). */
        fun sync(ctx: Context) = SolCubeHost.sync(ctx)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("suite")   // the old IMPORTANCE_MIN channel
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background service", NotificationManager.IMPORTANCE_NONE))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("SolCube is running")
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        SolCubeHost.onFgsStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!anyEnabled(this) || SolCubeA11y.instance != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        SolCubeHost.onFgsStarted(this)
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        SolCubeHost.onConfigurationChanged()
    }

    override fun onDestroy() {
        running = false
        if (SolCubeA11y.instance == null) SolCubeHost.stop()
        super.onDestroy()
    }
}
