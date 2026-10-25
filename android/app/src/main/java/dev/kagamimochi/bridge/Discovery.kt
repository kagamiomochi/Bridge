package dev.kagamimochi.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A Linux daemon found over mDNS. */
data class Found(val name: String, val host: String, val port: Int, val fpPrefix: String)

/** Finds Linux daemons over mDNS (_bridge._tcp). */
class Discovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    /**
     * Starts continuous discovery and reports every resolved device through
     * [onFound] (possibly more than once per device). Returns a function that
     * stops the discovery. [onFound] is called from a binder thread.
     */
    fun scan(onFound: (Found) -> Unit): () -> Unit {
        // NsdManager allows only one resolve at a time, so resolve sequentially
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun next() {
            synchronized(queue) {
                if (resolving) return
                val info = queue.removeFirstOrNull() ?: return
                resolving = true
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                        synchronized(queue) { resolving = false }
                        next()
                    }

                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val fp = i.attributes["fp"]?.let { String(it, Charsets.UTF_8) }
                        val host = i.host?.hostAddress
                        if (fp != null && host != null) onFound(Found(i.serviceName, host, i.port, fp))
                        synchronized(queue) { resolving = false }
                        next()
                    }
                }
                try {
                    nsd.resolveService(info, listener)
                } catch (e: Exception) {
                    resolving = false
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Status.event("mDNS discovery failed to start ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(queue) { queue.addLast(info) }
                next()
            }
        }

        nsd.discoverServices("_bridge._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        return { runCatching { nsd.stopServiceDiscovery(discoveryListener) } }
    }

    /** Waits for the paired device (identified by its certificate fingerprint prefix). */
    suspend fun find(fingerprintPrefix: String, timeoutMs: Long = 10_000): Pair<String, Int>? {
        val result = CompletableDeferred<Pair<String, Int>>()
        val stop = scan { if (it.fpPrefix == fingerprintPrefix) result.complete(it.host to it.port) }
        try {
            return withTimeoutOrNull(timeoutMs) { result.await() }
        } finally {
            stop()
        }
    }
}
