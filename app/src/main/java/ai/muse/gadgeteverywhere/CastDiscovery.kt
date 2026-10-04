package ai.muse.gadgeteverywhere

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded mDNS browse, with serial resolution for older Android stacks. */
@Suppress("DEPRECATION")
object CastDiscovery {
    fun discover(context: Context): JSONObject {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicast = wifi?.createMulticastLock("muse-discovery")?.apply { setReferenceCounted(false); acquire() }
        val main = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val done = CountDownLatch(1)
        val found = linkedMapOf<String, JSONObject>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var failure: String? = null
        fun resolveNext() {
            if (!active.get() || resolving || queue.isEmpty()) return
            resolving = true
            val service = queue.removeFirst()
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                    resolveNext()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (active.get()) {
                        val host = info.host?.hostAddress
                        if (host != null && !host.contains(':')) {
                            val id = info.attributes["id"]?.toString(Charsets.UTF_8) ?: info.serviceName
                            val name = info.attributes["fn"]?.toString(Charsets.UTF_8) ?: info.serviceName
                            synchronized(found) { found[id] = JSONObject().put("id", id).put("name", name).put("host", host).put("port", info.port) }
                        }
                    }
                    resolving = false
                    resolveNext()
                }
            })
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) { done.countDown() }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (active.get() && queue.size < 50) { queue.add(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { failure = "mDNS discovery failed ($code)"; done.countDown() }
            override fun onStopDiscoveryFailed(type: String, code: Int) { done.countDown() }
        }
        try {
            manager.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(7, TimeUnit.SECONDS)
            failure?.let { error(it) }
            return synchronized(found) { JSONObject().put("devices", JSONArray(found.values.toList())).put("scan_seconds", 7) }
        } finally {
            active.set(false)
            main.post { try { manager.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) {} }
            multicast?.let { if (it.isHeld) it.release() }
        }
    }
}
