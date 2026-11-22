package dev.suite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * One foreground service for everything that lives inside the app: the ripple overlay
 * (Circle to Search section) and the temperature pill. It also watches that the shell-side
 * programs are running while their section is enabled (they die on reboot).
 * Runs while any section is enabled.
 */
class SolCubeService : Service() {

    companion object {
        private const val CHANNEL = "suite_bg"
        private const val WATCHDOG_MS = 60_000L

        fun anyEnabled(ctx: Context): Boolean =
            listOf(Components.KEY_CTS, Components.KEY_GAME, Components.KEY_SR, Components.KEY_TEMP, Components.KEY_STREAM, Components.KEY_MIX, Routines.KEY, Profiles.KEY)
                .any { Components.enabled(ctx, it) }

        /** Starts/updates or stops the service to match the switches. */
        fun sync(ctx: Context) {
            val i = Intent(ctx, SolCubeService::class.java)
            if (anyEnabled(ctx)) ctx.startForegroundService(i) else ctx.stopService(i)
        }
    }

    private var ripple: RippleHost? = null
    private var temp: TempOverlay? = null
    private var stream: StreamOverlay? = null
    private val worker = HandlerThread("suite-bg").apply { start() }
    private val bg = Handler(worker.looper)

    private val watchdog = object : Runnable {
        override fun run() {
            Components.reconcile(this@SolCubeService)
            shizukuCheck(false)
            // daily GitHub release check (Updates.check returns at once until a day has passed);
            // a new version opens its pop-up once (allowed from the background: SolCube can draw over apps)
            runCatching {
                Updates.check(this@SolCubeService)?.let { r ->
                    if (Updates.firstShow(this@SolCubeService, r))
                        startActivity(Intent(this@SolCubeService, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            bg.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ---- Shizuku stopped warning: a pill (tap opens Shizuku) once it has been down a minute, while
    // the phone is in use; again on unlock, at most every 15 min, until it runs again.
    private var shizukuDownSince = 0L
    private var shizukuWarnedAt = 0L

    private fun shizukuCheck(unlocked: Boolean) {
        val now = System.currentTimeMillis()
        if (Shell.state() != Shell.State.NOT_RUNNING || !anyEnabled(this)) { shizukuDownSince = 0L; shizukuWarnedAt = 0L; return }
        if (shizukuDownSince == 0L) shizukuDownSince = now
        if (now - shizukuDownSince < 55_000L) return
        val inUse = getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (!inUse) return
        val again = if (unlocked) 15 * 60_000L else Long.MAX_VALUE
        if (shizukuWarnedAt != 0L && now - shizukuWarnedAt < again) return
        shizukuWarnedAt = now
        Island.show(this, "Shizuku stopped · tap to start", ok = false) {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(this, MainActivity::class.java)
            runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { bg.postDelayed({ shizukuCheck(true) }, 1200) }
    }

    // Shizuku started (e.g. after a reboot): bring the shell side up.
    private val binderListener = Shizuku.OnBinderReceivedListener { bg.post { Components.reconcile(this) } }
    private val deadListener = Shizuku.OnBinderDeadListener { Shell.reset(); bg.post { shizukuCheck(false) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // SolCube may post notifications now (island events), so the service's own channel is
        // created blocked: the foreground service keeps running, its notification never shows.
        nm.deleteNotificationChannel("suite")   // the old IMPORTANCE_MIN channel
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background service", NotificationManager.IMPORTANCE_NONE))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("SolCube is running")
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        registerReceiver(unlockReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
        bg.postDelayed({ Components.reconcile(this@SolCubeService) }, 2000)
        bg.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!anyEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        apply()
        bg.post { Components.reconcile(this) }
        return START_STICKY
    }

    /** Starts/stops the in-app parts to match the switches. */
    private fun apply() {
        if (Components.enabled(this, Components.KEY_CTS)) {
            if (ripple == null) ripple = RippleHost(this).also { it.start() }
        } else {
            ripple?.stop(); ripple = null
        }
        if (Components.enabled(this, Components.KEY_STREAM)) {
            if (stream == null) stream = StreamOverlay(this).also { it.start() }
        } else {
            stream?.stop(); stream = null
        }
        if (Components.enabled(this, Components.KEY_TEMP)) {
            if (temp == null) temp = TempOverlay(this).also { it.start() }
            if (!TempOverlay.running) { temp?.stop(); temp = TempOverlay(this).also { it.start() } }
        } else {
            temp?.stop(); temp = null
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        temp?.onConfigurationChanged()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        runCatching { unregisterReceiver(unlockReceiver) }
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        ripple?.stop(); ripple = null
        temp?.stop(); temp = null
        stream?.stop(); stream = null
        super.onDestroy()
    }
}
