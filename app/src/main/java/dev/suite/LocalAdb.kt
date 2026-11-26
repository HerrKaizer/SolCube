package dev.suite

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds THIS phone's Wireless debugging services over mDNS (as Shizuku's AdbMdns does): a service
 * only counts if its address belongs to one of our own network interfaces and its port is really
 * bound here. Other phones/TVs on the same Wi-Fi advertise the same service types and must be
 * ignored. Pairing and connecting then always go to 127.0.0.1:<port>, which keeps working when the
 * Wi-Fi address changes. Standard Android NsdManager only, so it's the same on every vivo build.
 */
object LocalAdb {
    private const val TAG = "SolCube.adb"
    const val TLS_CONNECT = "_adb-tls-connect._tcp"
    const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    const val HOST = "127.0.0.1"

    fun isLocalAddress(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { nif ->
                nif.inetAddresses.asSequence().any { it.hostAddress?.substringBefore('%') == host.substringBefore('%') }
            }
        }.getOrDefault(false)
    }

    /** True when something (adbd) already listens on 127.0.0.1:[port]. */
    fun isPortInUse(port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(HOST, port), 1) }
        false
    } catch (_: IOException) {
        true
    }

    fun isOwn(info: NsdServiceInfo): Boolean {
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress
        return info.port > 0 && isLocalAddress(host) && isPortInUse(info.port)
    }

    /** Blocks until this phone advertises [type] (port) or [timeoutMs] passes (-1). Off the main thread. */
    fun findPort(ctx: Context, type: String, timeoutMs: Long): Int {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return -1
        val result = AtomicInteger(-1)
        val done = CountDownLatch(1)
        val found = LinkedBlockingQueue<NsdServiceInfo>()

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.offer(serviceInfo) }
        }
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS discovery failed", t)
            return -1
        }
        try {
            val until = System.currentTimeMillis() + timeoutMs
            while (done.count > 0 && System.currentTimeMillis() < until) {
                val info = found.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val resolved = resolve(nsd, info, until) ?: continue
                if (isOwn(resolved)) { result.set(resolved.port); done.countDown() }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
        return result.get()
    }

    /** One resolve at a time (NsdManager rejects parallel ones with FAILURE_ALREADY_ACTIVE). */
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, until: Long): NsdServiceInfo? {
        while (System.currentTimeMillis() < until) {
            val latch = CountDownLatch(1)
            var out: NsdServiceInfo? = null
            var busy = false
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        busy = errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
                        latch.countDown()
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) { out = si; latch.countDown() }
                })
            }.onFailure { return null }
            latch.await((until - System.currentTimeMillis()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            if (out != null || !busy) return out
            Thread.sleep(150)
        }
        return null
    }
}
