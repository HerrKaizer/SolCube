package dev.suite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-Android Wireless Debugging (ADB-over-TLS) client for SolCube.
 * Pairs once with Android's built-in Wireless Debugging and launches `SolCubeDaemon` (`uid=2000`)
 * straight from SolCube's own APK — no external Shizuku app required.
 */
object AdbEngine {
    private const val TAG = "SolCube.adb"
    private const val PREF = "ipc"
    private const val KEY_PAIRED = "adb_paired"
    const val DAEMON_PORT = 47323

    /** User-facing errors thrown below (all have translations). */
    val MESSAGES = setOf(
        "Pairing rejected — check the 6-digit code",
        "Could not connect to Wireless debugging",
        "SolEngine did not attach in time",
    )

    private val reconnecting = AtomicBoolean(false)
    private val lock = Any()

    @Volatile
    private var manager: SolCubeAdbManager? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isPaired(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PAIRED, false)

    fun setPaired(ctx: Context, paired: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PAIRED, paired).commit()
    }

    /** Clears SolCube's saved pairing state and RSA/X.509 identity so setup starts fresh. */
    fun resetIdentity(ctx: Context) = synchronized(lock) {
        runCatching { manager?.disconnect() }
        manager = null
        val dir = ctx.applicationContext.noBackupFilesDir
        runCatching { File(dir, "adb_key.der").delete() }
        runCatching { File(dir, "adb_cert.der").delete() }
        setPaired(ctx.applicationContext, false)
    }

    fun isOnWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    fun isDevOptionsEnabled(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrDefault(false)

    /** vivo keeps "unhidden" (7 taps) apart from the Developer options switch. */
    fun isDevOptionsUnhidden(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, "vivo_development_show", 0) == 1
    }.getOrDefault(false)

    fun isWirelessAdbEnabled(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)

    /** Like Shizuku: stop Android from revoking our pairing after 7 days without a connection
     *  (adb_allowed_connection_time, default 7 days). Needs WRITE_SECURE_SETTINGS (granted by SolEngine). */
    fun keepPairing(ctx: Context) {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { Settings.Global.putLong(ctx.contentResolver, "adb_allowed_connection_time", 0L) }
    }

    private fun getManager(ctx: Context): SolCubeAdbManager {
        synchronized(lock) {
            manager?.let { return it }
            return SolCubeAdbManager(ctx.applicationContext).also { manager = it }
        }
    }

    /** Pairs with this phone's Wireless debugging pairing endpoint on 127.0.0.1:[port] (port from
     *  [LocalAdb]; [host] is ignored, other devices on the network are never paired with). */
    fun pair(ctx: Context, @Suppress("UNUSED_PARAMETER") host: String, port: Int, code: String): Result<Unit> = synchronized(lock) {
        runCatching {
            val m = getManager(ctx)
            m.setTimeout(12, TimeUnit.SECONDS)
            val ok = m.pair(LocalAdb.HOST, port, code.trim())
            if (!ok) error("Pairing rejected — check the 6-digit code")
            setPaired(ctx, true)
            Log.i(TAG, "paired on port $port")
            keepPairing(ctx)
            Unit
        }
    }

    /** Connects to this phone's Wireless debugging on 127.0.0.1 ([port], or found via [LocalAdb]) and
     *  starts SolEngine. [host] is ignored: only our own adbd is ever used. */
    fun connectAndLaunchDaemon(
        ctx: Context,
        host: String? = null,
        port: Int = -1,
        timeoutMs: Long = 10_000L,
    ): Result<Unit> = synchronized(lock) {
        val app = ctx.applicationContext
        runCatching {
            if (Shell.hasBuiltInDaemon()) return@runCatching
            if (pingDaemonCommand(app, "ATTACH")) {
                val waitUntil = System.currentTimeMillis() + 2000L
                while (!Shell.hasBuiltInDaemon() && System.currentTimeMillis() < waitUntil) {
                    Thread.sleep(60)
                }
                if (Shell.hasBuiltInDaemon()) return@runCatching
            }

            val m = getManager(app)
            m.setTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            if (m.isConnected) runCatching { m.disconnect() }

            val p = if (port > 0 && LocalAdb.isPortInUse(port)) port
                else LocalAdb.findPort(app, LocalAdb.TLS_CONNECT, timeoutMs)
            if (p <= 0) error("Could not connect to Wireless debugging")
            val connected = m.connect(LocalAdb.HOST, p)
            if (!connected || !m.isConnected) {
                error("Could not connect to Wireless debugging")
            }
            setPaired(app, true)

            try {
                val secret = Ipc.secret(app)
                val ver = Shell.engineVersion(app)
                val cmd = "APK=\$(pm path ${app.packageName} | head -n1 | cut -d: -f2); " +
                    "CLASSPATH=\"\$APK\" setsid app_process /system/bin --nice-name=solcube_engine " +
                    "dev.suite.shell.SolCubeDaemon $secret $ver </dev/null >/dev/null 2>&1 &\nexit\n"

                m.openStream("shell:").use { stream ->
                    stream.write(cmd.toByteArray(StandardCharsets.UTF_8), 0, cmd.length)
                    stream.flush()
                    val buf = ByteArray(512)
                    val drainUntil = System.currentTimeMillis() + 1200L
                    while (!stream.isClosed && System.currentTimeMillis() < drainUntil) {
                        if (stream.available() > 0) {
                            if (stream.read(buf, 0, buf.size) < 0) break
                        } else if (Shell.hasBuiltInDaemon()) {
                            break
                        } else {
                            Thread.sleep(50)
                        }
                    }
                }
            } finally {
                runCatching { m.disconnect() }
            }

            val attachUntil = System.currentTimeMillis() + 6000L
            while (!Shell.hasBuiltInDaemon() && System.currentTimeMillis() < attachUntil) {
                if (pingDaemonCommand(app, "ATTACH")) break
                Thread.sleep(80)
            }
            if (!Shell.hasBuiltInDaemon()) {
                val fallbackUntil = System.currentTimeMillis() + 2000L
                while (!Shell.hasBuiltInDaemon() && System.currentTimeMillis() < fallbackUntil) {
                    Thread.sleep(80)
                }
            }
            if (!Shell.hasBuiltInDaemon()) error("SolEngine did not attach in time")
            keepPairing(app)
            SetupPrefs.markConnected(app)
            Log.i(TAG, "SolCubeDaemon attached via Wireless ADB")
            Unit
        }
    }

    /**
     * Pings an already-running `solcube_engine` daemon on 127.0.0.1:47323 and asks it to push
     * its binders back to `EngineProvider`. Safe and fast; returns true if daemon responded `ok`.
     */
    fun pingDaemonCommand(ctx: Context, cmd: String): Boolean {
        val secret = Ipc.secret(ctx.applicationContext)
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 350)
                s.soTimeout = 1500
                val reader = s.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val out = s.getOutputStream()
                val nonce = reader.readLine() ?: return false
                if (nonce.isEmpty()) return false
                val mac = Ipc.mac(secret, "$nonce $cmd")
                out.write("$cmd $mac\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
                val reply = reader.readLine() ?: return false
                reply.startsWith("ok")
            }
        }.getOrDefault(false)
    }

    /** Why a one-tap SolEngine start didn't work (shown to the user). */
    enum class Start { OK, NOT_PAIRED, NO_WIFI, DEV_OFF, NO_PERMISSION, FAILED }

    /** One-tap start, like Shizuku's "Start" button (blocking; call off the main thread). */
    fun tryAutoReconnect(ctx: Context): Boolean = start(ctx) == Start.OK

    /**
     * Starts SolEngine without the user touching Settings:
     *  1. re-attach to an engine that is still running (survives SolCube being killed);
     *  2. if Wireless debugging is on, connect and launch the engine;
     *  3. if it is off, switch it on through WRITE_SECURE_SETTINGS (granted by the engine on first
     *     connect), connect, and leave it on (Shizuku does the same).
     * Always this phone's own adbd on 127.0.0.1 (see [LocalAdb]).
     * A rejected key means the pairing was removed on the phone: only pairing again helps.
     * Uses only standard Android settings, so it behaves the same on every vivo build.
     */
    fun start(ctx: Context, why: String = "app"): Start {
        if (Shell.hasBuiltInDaemon()) return Start.OK
        lastError = null
        val t0 = System.currentTimeMillis()
        val r = startInner(ctx.applicationContext)
        val wd = if (isWirelessAdbEnabled(ctx)) "WD on" else "WD off"
        AppLog.add(ctx, "SolEngine start ($why): $r in ${System.currentTimeMillis() - t0} ms · $wd" +
            (lastError?.let { " · $it" } ?: ""))
        return r
    }

    @Volatile private var lastError: String? = null

    private fun startInner(app: Context): Start {
        if (!reconnecting.compareAndSet(false, true)) {
            // another start is running: wait for its outcome instead of racing it
            val until = System.currentTimeMillis() + 25_000L
            while (reconnecting.get() && System.currentTimeMillis() < until) Thread.sleep(100)
            return if (Shell.hasBuiltInDaemon()) Start.OK else Start.FAILED
        }
        try {
            if (pingDaemonCommand(app, "ATTACH")) {
                val until = System.currentTimeMillis() + 1500L
                while (!Shell.hasBuiltInDaemon() && System.currentTimeMillis() < until) Thread.sleep(50)
                if (Shell.hasBuiltInDaemon()) return Start.OK
            }
            if (!isPaired(app)) return Start.NOT_PAIRED
            if (!isOnWifi(app)) return Start.NO_WIFI
            if (!isDevOptionsEnabled(app)) return Start.DEV_OFF

            // The phone no longer trusts our key (pairing removed under Paired devices, or adb keys revoked):
            // no switch can fix that, only pairing again.
            fun connect(timeout: Long): Start {
                val r = connectAndLaunchDaemon(app, timeoutMs = timeout)
                if (r.isSuccess) return Start.OK
                lastError = r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}".take(140) }
                if (isAuthFailure(r.exceptionOrNull())) { setPaired(app, false); return Start.NOT_PAIRED }
                return Start.FAILED
            }

            val wasOn = isWirelessAdbEnabled(app)
            if (wasOn) {
                keepPairing(app)
                // vivo keeps Wireless debugging on across reboots: then it's just a connect with our saved key,
                // like Shizuku's Start. Never toggle it here: that would only reset the user's session.
                var r = connect(8_000L)
                if (r == Start.FAILED) { Thread.sleep(1_500); r = connect(8_000L) }   // mDNS can lag after boot
                return r
            }

            val hasWss = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasWss) return Start.NO_PERMISSION

            fun setWifiAdb(on: Boolean) = runCatching {
                Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", if (on) 1 else 0)
            }.getOrDefault(false)

            // Like Shizuku's boot start: switch Wireless debugging (and USB debugging, which it depends
            // on) on, connect, and leave it on so the next start is just a connect.
            if (!setWifiAdb(true)) return Start.NO_PERMISSION
            runCatching { Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1) }
            keepPairing(app)
            Thread.sleep(1_000)
            var r = connect(10_000L)
            if (r == Start.FAILED) { Thread.sleep(1_500); r = connect(8_000L) }   // adbd can be slow to advertise
            return r
        } catch (t: Throwable) {
            Log.w(TAG, "SolEngine start failed", t)
            lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(140)
            return Start.FAILED
        } finally {
            reconnecting.set(false)
        }
    }

    private fun isAuthFailure(t: Throwable?): Boolean {
        var e = t
        while (e != null) {
            if (e is javax.net.ssl.SSLHandshakeException) return true
            val m = e.message ?: ""
            if (m.contains("CERTIFICATE", ignoreCase = true) || m.contains("not authorized", ignoreCase = true)) return true
            e = e.cause
        }
        return false
    }

    /** User-facing text for a failed [start] (translated by the caller). */
    fun startMessage(r: Start): String = when (r) {
        Start.OK -> "SolEngine started"
        Start.NOT_PAIRED -> "SolEngine needs pairing again"
        Start.NO_WIFI -> "Connect to Wi-Fi first"
        Start.DEV_OFF -> "Developer options are off"
        Start.NO_PERMISSION, Start.FAILED -> "Couldn't start SolEngine — turn on Wireless debugging"
    }

    // ------------------------------------------------------------------ RSA key & X.509 cert

    private class SolCubeAdbManager(ctx: Context) : AbsAdbConnectionManager() {
        private val privKey: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val pair = loadOrCreateIdentity(ctx)
            privKey = pair.first
            cert = pair.second
        }

        override fun getPrivateKey(): PrivateKey = privKey
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "SolCube"
    }

    private fun loadOrCreateIdentity(ctx: Context): Pair<PrivateKey, Certificate> {
        val dir = ctx.noBackupFilesDir
        val keyFile = File(dir, "adb_key.der")
        val certFile = File(dir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            val loaded = runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cf = CertificateFactory.getInstance("X.509")
                val c = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                priv to c
            }.getOrNull()
            if (loaded != null) return loaded
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        val certDer = buildSelfSignedX509(kp.public.encoded, kp.private)
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certDer))

        runCatching {
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(certDer)
        }
        return kp.private to cert
    }

    /** Minimal pure-Kotlin DER encoder for a self-signed SHA256withRSA X.509 v3 certificate. */
    private fun buildSelfSignedX509(spkiDer: ByteArray, priv: PrivateKey): ByteArray {
        val serial = ByteArray(8).also { SecureRandom().nextBytes(it) }
        serial[0] = (serial[0].toInt() and 0x7F).toByte()
        if (serial[0].toInt() == 0) serial[0] = 1

        // 1.2.840.113549.1.1.11 (sha256WithRSAEncryption) + NULL
        val sha256RsaOid = byteArrayOf(
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
        )
        val algId = tlv(0x30, tlv(0x06, sha256RsaOid) + byteArrayOf(0x05, 0x00))

        // CN=SolCube -> SEQUENCE { SET { SEQUENCE { OID 2.5.4.3, UTF8String "SolCube" } } }
        val cnOid = byteArrayOf(0x55, 0x04, 0x03)
        val name = tlv(0x30, tlv(0x31, tlv(0x30, tlv(0x06, cnOid) + tlv(0x0C, "SolCube".toByteArray(StandardCharsets.UTF_8)))))

        val now = System.currentTimeMillis() - 86_400_000L
        val exp = now + 25L * 365L * 86_400_000L
        val validity = tlv(0x30, derTime(now) + derTime(exp))

        val version = tlv(0xA0, tlv(0x02, byteArrayOf(0x02)))
        val serialTlv = tlv(0x02, serial)

        val tbs = tlv(0x30, version + serialTlv + algId + name + validity + name + spkiDer)

        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(priv)
            update(tbs)
            sign()
        }
        val sigBits = tlv(0x03, byteArrayOf(0x00) + sig)
        return tlv(0x30, tbs + algId + sigBits)
    }

    private fun derTime(ms: Long): ByteArray {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = ms }
        val yr = cal.get(java.util.Calendar.YEAR)
        return if (yr in 1950..2049) {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            tlv(0x17, fmt.format(Date(ms)).toByteArray(StandardCharsets.US_ASCII))
        } else {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            tlv(0x18, fmt.format(Date(ms)).toByteArray(StandardCharsets.US_ASCII))
        }
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xFF -> { out.write(0x81); out.write(len) }
            len <= 0xFFFF -> { out.write(0x82); out.write(len ushr 8); out.write(len and 0xFF) }
            else -> {
                out.write(0x83)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }
}
