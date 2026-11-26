package dev.suite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Temporary foreground service used ONLY while the user is pairing or reconnecting Wireless
 * debugging. Watches mDNS (`_adb-tls-pairing._tcp` & `_adb-tls-connect._tcp`) and drops an
 * auto-popping Heads-Up notification with an inline 6-digit code box so the user never has to
 * leave Settings.
 */
class PairingService : Service() {

    enum class Phase { IDLE, WATCHING, PAIRING_READY, PAIRING, CONNECTING, READY, ERROR }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val pairHost: String = "",
        val pairPort: Int = 0,
        val connectHost: String = "",
        val connectPort: Int = 0,
        val error: String = "",
    )

    companion object {
        private const val TAG = "SolCube.pair"
        private const val CHANNEL_SILENT = "solcube_pair_bg"
        private const val CHANNEL_PROMPT = "solcube_pair_prompt_v1"
        private const val NOTIF_ID = 4701
        private const val NOTIF_PROMPT_ID = 4702
        private const val NOTIF_READY_ID = 4703
        private const val CODE_KEY = "pairing_code"
        private const val TIMEOUT_MS = 600_000L   // first-timers read the steps; restarted when the pairing dialog shows up

        const val ACTION_START_PAIR = "dev.suite.action.START_PAIR"
        const val ACTION_START_RECONNECT = "dev.suite.action.START_RECONNECT"
        const val ACTION_SUBMIT_CODE = "dev.suite.action.SUBMIT_CODE"
        const val ACTION_STOP = "dev.suite.action.STOP_PAIR"
        const val EXTRA_CODE = "code"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var snapshot: Snapshot = Snapshot()
            private set

        private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        fun addListener(l: (Snapshot) -> Unit) {
            listeners.add(l)
            l(snapshot)
        }

        fun removeListener(l: (Snapshot) -> Unit) {
            listeners.remove(l)
        }

        private var logCtx: Context? = null

        private fun publish(s: Snapshot) {
            val prev = snapshot
            snapshot = s
            logCtx?.let { c ->
                if (s.phase == Phase.ERROR && (prev.phase != Phase.ERROR || prev.error != s.error)) AppLog.add(c, "Pairing error: ${s.error}")
                if (s.phase == Phase.READY && prev.phase != Phase.READY) AppLog.add(c, "Pairing done")
            }
            main.post { listeners.forEach { runCatching { it(s) } } }
        }

        fun startPairing(ctx: Context) {
            logCtx = ctx.applicationContext
            val i = Intent(ctx, PairingService::class.java).setAction(ACTION_START_PAIR)
            runCatching { ctx.startForegroundService(i) }
        }

        fun startReconnect(ctx: Context) {
            logCtx = ctx.applicationContext
            val i = Intent(ctx, PairingService::class.java).setAction(ACTION_START_RECONNECT)
            runCatching { ctx.startForegroundService(i) }
        }

        fun submitCode(ctx: Context, code: String) {
            val i = Intent(ctx, PairingService::class.java)
                .setAction(ACTION_SUBMIT_CODE)
                .putExtra(EXTRA_CODE, code)
            runCatching { ctx.startService(i) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, PairingService::class.java)) }
            publish(Snapshot(Phase.IDLE))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var nsd: NsdManager? = null
    private var pairDisc: NsdManager.DiscoveryListener? = null
    private var connDisc: NsdManager.DiscoveryListener? = null
    private val infoCallbacks = mutableListOf<Any>()
    private var reconnectMode = false
    private var connectingNow = false
    private var promptShownForKey = ""
    private val timeoutRunnable = Runnable {
        if (snapshot.phase != Phase.READY) stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Island.hide()
        ensureChannels()
        nsd = getSystemService(NsdManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SUBMIT_CODE -> {
                val code = intent.getStringExtra(EXTRA_CODE) ?: ""
                handleSubmittedCode(code)
                return START_NOT_STICKY
            }
            ACTION_START_RECONNECT -> {
                reconnectMode = true
                promptShownForKey = ""
                startForegroundCompat(buildSilentNotification(
                    I18n.t("Waiting for Wireless debugging…"),
                    I18n.t("Turn Wireless debugging on in Settings to reconnect SolCube."),
                ))
                publish(Snapshot(Phase.WATCHING))
                startDiscovery(watchPairing = false)
                resetTimeout()
                // In case Wireless debugging is already on right now:
                worker.execute { tryReconnectNow() }
            }
            else -> {
                reconnectMode = false
                promptShownForKey = ""
                startForegroundCompat(buildSilentNotification(
                    I18n.t("Ready to pair SolCube"),
                    I18n.t("Tap “Pair device with pairing code” in Wireless debugging."),
                ))
                publish(Snapshot(Phase.WATCHING))
                startDiscovery(watchPairing = true)
                resetTimeout()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacks(timeoutRunnable)
        stopDiscovery()
        worker.shutdownNow()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_PROMPT_ID)
        if (snapshot.phase != Phase.READY) publish(Snapshot(Phase.IDLE))
        super.onDestroy()
    }

    private fun resetTimeout() {
        main.removeCallbacks(timeoutRunnable)
        main.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_SILENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, "SolCube setup status", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_PROMPT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROMPT, "SolCube pairing code", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setShowBadge(true)
                },
            )
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildSilentNotification(title: String, body: String): Notification =
        Notification.Builder(this, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_notif_pair)
            .setContentTitle(title)
            .setContentText(body)
            .setGroup("solcube_pair_bg")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun showHeadsUpCodeNotification(title: String, body: String) {
        val replyIntent = Intent(this, PairingReplyReceiver::class.java).setAction(ACTION_SUBMIT_CODE)
        val replyPi = PendingIntent.getBroadcast(
            this, 11, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(CODE_KEY)
            .setLabel(I18n.t("6-digit pairing code"))
            .build()
        val actionIcon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif_pair)
        val action = Notification.Action.Builder(actionIcon, I18n.t("Enter code"), replyPi)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
        val extras = android.os.Bundle().apply {
            // Keeps action buttons visible in OriginOS notification center without tapping expand.
            putBoolean("custom_always_expand", true)
        }
        val n = Notification.Builder(this, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_notif_pair)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setGroup("solcube_pair_prompt")
            .setSortKey("0")
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .addExtras(extras)
            .addAction(action)
            .build()
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_PROMPT_ID)
        nm.notify(NOTIF_PROMPT_ID, n)
    }

    private fun showReadyNotification() {
        val mainIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 12, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_notif_pair)
            .setContentTitle(I18n.t("SolCube is connected"))
            .setContentText(I18n.t("All features unlocked — tap to return to SolCube."))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIF_PROMPT_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm?.notify(NOTIF_READY_ID, n)
    }

    // ------------------------------------------------------------------ mDNS discovery

    private fun startDiscovery(watchPairing: Boolean) {
        stopDiscovery()
        val mgr = nsd ?: return
        connDisc = makeDiscoveryListener(isPairing = false)
        runCatching { mgr.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connDisc) }
        if (watchPairing) {
            pairDisc = makeDiscoveryListener(isPairing = true)
            runCatching { mgr.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairDisc) }
        }
    }

    private fun stopDiscovery() {
        val mgr = nsd ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            for (cb in infoCallbacks) {
                runCatching { mgr.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }
        infoCallbacks.clear()
        pairDisc?.let { runCatching { mgr.stopServiceDiscovery(it) } }
        connDisc?.let { runCatching { mgr.stopServiceDiscovery(it) } }
        pairDisc = null
        connDisc = null
    }

    private fun makeDiscoveryListener(isPairing: Boolean) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "discover $serviceType start failed: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (isPairing && snapshot.phase == Phase.PAIRING_READY) {
                publish(snapshot.copy(phase = Phase.WATCHING, pairHost = "", pairPort = 0))
            }
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            resolveEndpoint(serviceInfo, isPairing)
        }
    }

    private fun resolveEndpoint(info: NsdServiceInfo, isPairing: Boolean) {
        val mgr = nsd ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    // only this phone's own service (other devices on the Wi-Fi advertise the same types)
                    val host = updated.hostAddresses.firstOrNull { LocalAdb.isLocalAddress(it.hostAddress) }?.hostAddress ?: return
                    val port = updated.port
                    if (port > 0 && LocalAdb.isPortInUse(port)) onEndpointResolved(isPairing, host, port)
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            infoCallbacks.add(cb)
            runCatching { mgr.registerServiceInfoCallback(info, worker, cb) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                mgr.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        if (!LocalAdb.isOwn(si)) return
                        onEndpointResolved(isPairing, si.host?.hostAddress ?: LocalAdb.HOST, si.port)
                    }
                })
            }
        }
    }

    private fun onEndpointResolved(isPairing: Boolean, host: String, port: Int) {
        if (isPairing) {
            val key = "$host:$port"
            val prev = snapshot
            if (prev.phase != Phase.PAIRING && prev.phase != Phase.CONNECTING && prev.phase != Phase.READY) {
                publish(prev.copy(phase = Phase.PAIRING_READY, pairHost = host, pairPort = port, error = ""))
            } else {
                publish(prev.copy(pairHost = host, pairPort = port))
            }
            if (promptShownForKey != key) {
                promptShownForKey = key
                resetTimeout()
                showHeadsUpCodeNotification(
                    I18n.t("Pairing code ready"),
                    I18n.t("Tap Enter code below to type the 6 digits."),
                )
            }
        } else {
            publish(snapshot.copy(connectHost = host, connectPort = port))
            if (reconnectMode && !connectingNow && snapshot.phase != Phase.READY) {
                worker.execute { tryReconnectWith(host, port) }
            }
        }
    }

    // ------------------------------------------------------------------ pairing + connect execution

    private fun handleSubmittedCode(rawCode: String) {
        val code = rawCode.filter { it.isDigit() }
        if (code.length != 6) {
            publish(snapshot.copy(phase = Phase.ERROR, error = "Enter the 6-digit pairing code"))
            showHeadsUpCodeNotification(
                I18n.t("Enter all 6 digits"),
                I18n.t("Tap “Enter code” and type the 6-digit code shown on screen."),
            )
            return
        }
        val cur = snapshot
        if (cur.pairHost.isEmpty() || cur.pairPort <= 0) {
            publish(cur.copy(phase = Phase.ERROR, error = "Open “Pair device with pairing code” first"))
            showHeadsUpCodeNotification(
                I18n.t("Pairing dialog not open"),
                I18n.t("Tap “Pair device with pairing code” first, then enter the 6-digit code."),
            )
            return
        }

        worker.execute {
            publish(snapshot.copy(phase = Phase.PAIRING, error = ""))
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildSilentNotification(I18n.t("Pairing SolCube…"), I18n.t("Connecting to Wireless debugging.")))

            val pairRes = AdbEngine.pair(applicationContext, cur.pairHost, cur.pairPort, code)
            if (pairRes.isFailure) {
                val msg = known(pairRes.exceptionOrNull(), "Code didn't match")
                publish(snapshot.copy(phase = Phase.ERROR, error = msg))
                showHeadsUpCodeNotification(
                    I18n.t("Code didn't match"),
                    I18n.t("Tap “Enter code” to try the 6-digit code again."),
                )
                return@execute
            }

            publish(snapshot.copy(phase = Phase.CONNECTING, error = ""))
            nm?.notify(NOTIF_ID, buildSilentNotification(I18n.t("Starting SolCube…"), I18n.t("Almost ready.")))

            // Wait briefly for _adb-tls-connect._tcp if not resolved yet.
            val waitUntil = System.currentTimeMillis() + 5000L
            while (snapshot.connectPort <= 0 && System.currentTimeMillis() < waitUntil) {
                Thread.sleep(100)
            }
            val snap = snapshot
            val connRes = AdbEngine.connectAndLaunchDaemon(
                applicationContext,
                host = snap.connectHost.takeIf { it.isNotEmpty() },
                port = snap.connectPort,
                timeoutMs = 10_000L,
            )
            if (connRes.isSuccess) {
                onEngineReady()
            } else {
                val msg = known(connRes.exceptionOrNull(), "Could not start SolEngine")
                publish(snapshot.copy(phase = Phase.ERROR, error = msg))
                nm?.notify(NOTIF_ID, buildSilentNotification(I18n.t("Connection needed"), I18n.t(msg)))
            }
        }
    }

    private fun tryReconnectNow() {
        if (connectingNow || snapshot.phase == Phase.READY) return
        if (Shell.hasBuiltInDaemon() || AdbEngine.pingDaemonCommand(applicationContext, "ATTACH")) {
            onEngineReady()
            return
        }
        val cur = snapshot
        if (cur.connectHost.isNotEmpty() && cur.connectPort > 0) {
            tryReconnectWith(cur.connectHost, cur.connectPort)
        } else if (AdbEngine.isWirelessAdbEnabled(applicationContext)) {
            connectingNow = true
            try {
                publish(snapshot.copy(phase = Phase.CONNECTING, error = ""))
                if (AdbEngine.connectAndLaunchDaemon(applicationContext, timeoutMs = 6000L).isSuccess) {
                    onEngineReady()
                } else {
                    publish(snapshot.copy(phase = Phase.WATCHING))
                }
            } finally {
                connectingNow = false
            }
        }
    }

    private fun tryReconnectWith(host: String, port: Int) {
        if (connectingNow || snapshot.phase == Phase.READY) return
        connectingNow = true
        try {
            publish(snapshot.copy(phase = Phase.CONNECTING, error = ""))
            val res = AdbEngine.connectAndLaunchDaemon(applicationContext, host, port, timeoutMs = 8000L)
            if (res.isSuccess) {
                onEngineReady()
            } else {
                publish(snapshot.copy(phase = Phase.ERROR, error = known(res.exceptionOrNull(), "Reconnect failed")))
            }
        } finally {
            connectingNow = false
        }
    }

    /** Our own messages (translated when shown); raw library / socket errors become [fallback]. */
    private fun known(t: Throwable?, fallback: String): String {
        val m = t?.message ?: return fallback
        Log.w(TAG, "pair/connect failed", t)
        return if (m in AdbEngine.MESSAGES) m else fallback
    }

    private fun onEngineReady() {
        SetupPrefs.markConnected(applicationContext)
        publish(snapshot.copy(phase = Phase.READY, error = ""))
        showReadyNotification()
        main.post {
            Components.reconcile(applicationContext)
            SolCubeService.sync(applicationContext)
            stopSelf()
        }
    }
}

/** Receives the 6-digit pairing code typed straight into the Heads-Up notification. */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = results.getCharSequence("pairing_code")?.toString() ?: return
        PairingService.submitCode(context.applicationContext, code)
    }
}
