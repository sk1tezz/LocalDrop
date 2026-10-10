package com.skz.localdrop.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.skz.localdrop.R
import com.skz.localdrop.protocol.net.Announcement
import com.skz.localdrop.protocol.net.BEACON_EVERY_MS
import com.skz.localdrop.protocol.net.BEACON_GROUP
import com.skz.localdrop.protocol.net.BEACON_PORT
import com.skz.localdrop.protocol.net.SERVICE_TYPE
import com.skz.localdrop.protocol.net.announce
import com.skz.localdrop.protocol.net.parseBeacon
import com.skz.localdrop.protocol.net.txtRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Finding LocalDrop devices on the LAN (desktop/localdrop/net/discovery.py): mDNS through
 * NsdManager plus the UDP beacon, which covers routers that filter mDNS.
 */
class Discovery(private val core: Core) {
    private val ctx: Context = core.ctx
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    private var sock: MulticastSocket? = null
    private var ifaces: List<Net.Iface> = emptyList()
    private var jobs = mutableListOf<Job>()
    private var registered: NsdManager.RegistrationListener? = null
    private var browsing: NsdManager.DiscoveryListener? = null
    private val names = ConcurrentHashMap<String, String>()      // mdns service name -> device id
    private val resolver = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    private val visible get() = core.settings.value.visible

    private fun announcement() = Announcement(
        core.ident.id, core.settings.value.deviceName, core.osLabel(), "android", "smartphone", core.port,
    )

    /** LAN interfaces we may use (Wi-Fi, Ethernet, hotspot); empty when "Wi-Fi only" leaves nothing. */
    private fun usable(): List<Net.Iface> = Net.lan().filter { !core.settings.value.wifiOnly || !it.name.startsWith("rmnet") }

    fun start() {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { core.scope.launch { delay(1500); restartIfChanged() } }
            override fun onLost(network: Network) { core.scope.launch { delay(500); restartIfChanged() } }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { core.scope.launch { delay(1500); restartIfChanged() } }
            // addresses arrive here, often after onAvailable
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) { core.scope.launch { delay(500); restartIfChanged() } }
        })
        // and a slow safety net for networks that change without telling the default callback
        core.scope.launch {
            while (true) {
                delay(15_000)
                restartIfChanged()
            }
        }
        core.settings.onChange { old, new ->
            if (old.visible != new.visible || old.deviceName != new.deviceName) core.scope.launch { reannounce() }
            if (old.wifiOnly != new.wifiOnly) core.scope.launch { restartIfChanged(force = true) }
        }
        open()
    }

    @Synchronized
    private fun open() {
        ifaces = usable()
        core.ip = ifaces.firstOrNull()?.ip ?: ""
        core.publish()
        if (ifaces.isEmpty()) {
            Log.i(TAG, "no LAN interface, discovery paused")
            return
        }
        running = true
        lock = wifi?.createMulticastLock("localdrop")?.apply { setReferenceCounted(false); acquire() }
        openBeacon()
        startMdns()
        jobs += core.scope.launch(Dispatchers.IO) { beaconLoop() }
    }

    @Synchronized
    private fun close() {
        running = false
        for (j in jobs) j.cancel()
        jobs.clear()
        runCatching { sock?.let { s -> sendBeacon(s, hidden = true) } }
        runCatching { sock?.close() }
        sock = null
        stopMdns()
        runCatching { lock?.release() }
        lock = null
    }

    private val restartLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun restartIfChanged(force: Boolean = false) = restartLock.withLock {
        val now = usable()
        if (!force && now.map { it.ip } == ifaces.map { it.ip }) return@withLock
        Log.i(TAG, "network changed: ${now.map { it.name + "=" + it.ip }}")
        close()
        open()
    }

    // ---- UDP beacon -------------------------------------------------------
    private fun openBeacon() {
        val s = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(BEACON_PORT))
                broadcast = true
                timeToLive = 1
                loopbackMode = true      // true = loopback disabled
            }
        } catch (e: Exception) {
            Log.w(TAG, "beacon socket failed: $e")
            return
        }
        val group = InetSocketAddress(InetAddress.getByName(BEACON_GROUP), BEACON_PORT)
        for (x in ifaces) runCatching {
            s.joinGroup(group, NetworkInterface.getByName(x.name))
        }.onFailure { Log.d(TAG, "join multicast on ${x.name} failed: $it") }
        sock = s
        jobs += core.scope.launch(Dispatchers.IO) { receive(s) }
    }

    private fun sendBeacon(s: MulticastSocket, query: Boolean = false, hidden: Boolean = false, to: InetAddress? = null) {
        val data = announce(announcement(), query, hidden || !visible)
        if (to != null) {
            runCatching { s.send(DatagramPacket(data, data.size, to, BEACON_PORT)) }
            return
        }
        val group = InetAddress.getByName(BEACON_GROUP)
        for (x in ifaces) {
            runCatching {
                s.networkInterface = NetworkInterface.getByName(x.name)
                s.send(DatagramPacket(data, data.size, group, BEACON_PORT))
            }.onFailure { Log.d(TAG, "beacon on ${x.name} failed: $it") }
            Net.broadcast(x.ip, x.prefix)?.let { b -> runCatching { s.send(DatagramPacket(data, data.size, InetAddress.getByName(b), BEACON_PORT)) } }
        }
    }

    private fun receive(s: MulticastSocket) {
        val buf = ByteArray(4096)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: Exception) {
                if (s.isClosed) return else continue
            }
            val from = p.address?.hostAddress ?: continue
            if (ifaces.any { it.ip == from }) continue
            val h = parseBeacon(p.data, p.length, from, core.ident.id) ?: continue
            core.peers.seen(h.id, h.info)
            if (h.query && visible) sendBeacon(s, to = p.address)
        }
    }

    private suspend fun beaconLoop() {
        sock?.let { sendBeacon(it, query = true) }
        while (core.scope.isActive) {
            delay(BEACON_EVERY_MS)
            if (visible) sock?.let { sendBeacon(it) }
        }
    }

    // ---- mDNS -------------------------------------------------------------
    private fun startMdns() {
        if (visible) register()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "mDNS discovery failed: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { resolve(info) }
            override fun onServiceLost(info: NsdServiceInfo) {
                names.remove(info.serviceName)?.let { core.peers.lost(it) }
            }
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { browsing = l }
    }

    private fun stopMdns() {
        browsing?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        browsing = null
        unregister()
    }

    private fun register() {
        val a = announcement()
        val info = NsdServiceInfo().apply {
            serviceName = a.id
            serviceType = SERVICE_TYPE
            port = a.port
            for ((k, v) in txtRecord(a)) setAttribute(k, v)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "mDNS register failed: $errorCode") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { registered = l }
    }

    private fun unregister() {
        registered?.let { runCatching { nsd.unregisterService(it) } }
        registered = null
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        if (info.serviceName == core.ident.id) return
        val done = { r: NsdServiceInfo ->
            val props = r.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
            val id = props["id"]
            val addrs = if (Build.VERSION.SDK_INT >= 34) r.hostAddresses else listOfNotNull(r.host)
            val ip = bestAddr(addrs.mapNotNull { it.hostAddress?.takeIf { a -> !a.contains(':') } })
            if (id != null && id != core.ident.id && ip.isNotEmpty()) {
                names[info.serviceName] = id
                core.peers.seen(id, com.skz.localdrop.protocol.net.toJson(props + mapOf("ip" to ip, "port" to r.port)) as kotlinx.serialization.json.JsonObject)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(r: NsdServiceInfo) {
                    done(r)
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            runCatching { nsd.registerServiceInfoCallback(info, resolver, cb) }
        } else {
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(r: NsdServiceInfo) = done(r)
                })
            }
        }
    }

    private fun bestAddr(addrs: List<String>): String =
        addrs.firstOrNull { a -> ifaces.any { Net.sameSubnet(a, it.ip, it.prefix) } } ?: addrs.firstOrNull() ?: ""

    private suspend fun reannounce() {
        if (!running) return
        unregister()
        if (visible) register()
        sock?.let { sendBeacon(it) }
    }

    /** Sockets opened before the local network permission was granted stay blocked: reopen them. */
    suspend fun restart() = restartIfChanged(force = true)

    /** "Refresh": ask everyone to answer now. */
    suspend fun rescan() {
        core.scanning = true
        core.publish()
        restartIfChanged()
        sock?.let { sendBeacon(it, query = true) }
        delay(1500)
        core.scanning = false
        core.publish()
        val n = core.peers.view().count { it.online }
        core.toast("acc", R.string.t_scan_done, core.str(R.string.t_scan_n, n))
    }
}
