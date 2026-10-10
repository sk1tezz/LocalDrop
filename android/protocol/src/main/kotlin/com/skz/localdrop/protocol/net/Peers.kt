package com.skz.localdrop.protocol.net

import com.skz.localdrop.protocol.crypto.Identity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Known devices and connections to them; a port of desktop/localdrop/net/peers.py.
 *
 * Every pair of online, paired devices keeps a *control* connection open: small messages
 * travel over it as request/reply pairs. Bulk data uses separate *stream* connections.
 * Unpaired devices may only exchange pairing messages.
 */
const val ONLINE_TTL_MS = 35_000L
const val PING_EVERY_MS = 12_000L
const val DEAD_AFTER_MS = 40_000L
val PAIRING_TYPES = setOf("pair_request", "pair_cancel", "pair_code", "unpair", "ping", "pong", "reply")

class Device(
    val id: String,
    @Volatile var name: String = "?",
    @Volatile var os: String = "",
    @Volatile var kind: String = "",
    @Volatile var icon: String = "computer",
    @Volatile var ip: String = "",
    @Volatile var port: Int = 0,
    @Volatile var paired: Boolean = false,
    @Volatile var trusted: Boolean = false,
    @Volatile var lastSeen: Long = 0,
    @Volatile var hidden: Boolean = false,
) {
    fun info() = listOf(name, os, kind, icon, ip, port)
}

/** A device as the UI shows it. */
data class DeviceView(
    val id: String, val name: String, val os: String, val kind: String, val icon: String, val ip: String, val port: Int,
    val online: Boolean, val paired: Boolean, val trusted: Boolean,
)

class PeerError(msg: String) : IOException(msg)

/** A handler's answer: the reply header (without type/re) and an optional body. */
class Reply(val header: JsonObject, val body: ByteArray = Msg.EMPTY)

fun ok(vararg extra: Pair<String, Any?>) = Reply(obj("ok" to true, *extra))
fun fail(error: String? = null) = Reply(if (error == null) obj("ok" to false) else obj("ok" to false, "error" to error))

typealias Handler = suspend (Device, Conn, Msg) -> Reply?
typealias StreamHandler = suspend (Device, Conn, JsonObject) -> Unit

/** What the peer manager needs from the app. */
interface PeerHost {
    val port: Int
    /** name, os, kind, icon of this device. */
    fun meInfo(): Map<String, Any>
    fun saveDevice(d: Device)
    fun forgetDevice(id: String)
    fun devicesChanged()
    fun unpaired(d: Device)
    fun log(msg: String) {}
}

class PeerManager(private val host: PeerHost, val ident: Identity, private val scope: CoroutineScope, saved: List<Device> = emptyList()) {
    val devices = ConcurrentHashMap<String, Device>()
    private val control = ConcurrentHashMap<String, CopyOnWriteArrayList<Conn>>()
    private val handlers = ConcurrentHashMap<String, Handler>()
    private val streamHandlers = ConcurrentHashMap<String, StreamHandler>()
    val onlineListeners = CopyOnWriteArrayList<(String, Boolean) -> Unit>()
    val pairedListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Msg>>()
    private val rid = AtomicLong(1)
    private val dialing = ConcurrentHashMap<String, Deferred<Conn?>>()
    private val backoff = ConcurrentHashMap<String, Long>()
    private val lastRx = ConcurrentHashMap<Conn, Long>()
    private val wasOnline = ConcurrentHashMap<String, Boolean>()
    private var lastView: List<DeviceView> = emptyList()
    private var server: Server? = null
    private var maintainJob: Job? = null

    init {
        for (d in saved) devices[d.id] = d
        on("ping") { _, _, _ -> ok() }
        on("unpair", ::onUnpair)
    }

    private fun now() = System.currentTimeMillis()

    // ---- lifecycle --------------------------------------------------------
    fun start() {
        server = Server(host.port, ident, scope, ::onConn).start()
        host.log("listening on tcp/${host.port} as ${ident.id}")
        maintainJob = scope.launch { maintain() }
    }

    fun stop() {
        maintainJob?.cancel()
        server?.close()
        for (l in control.values) for (c in l) c.close()
    }

    // ---- registry ---------------------------------------------------------
    fun on(type: String, fn: Handler) { handlers[type] = fn }
    fun onStream(purpose: String, fn: StreamHandler) { streamHandlers[purpose] = fn }

    fun hello(purpose: String = "control", extra: Map<String, Any?> = emptyMap()): JsonObject {
        val me = host.meInfo()
        return obj("type" to "hello", "purpose" to purpose, "name" to me["name"], "os" to me["os"], "kind" to me["kind"],
            "icon" to me["icon"], "port" to host.port) + extra
    }

    fun get(id: String): Device? = devices[id]

    fun isOnline(d: Device): Boolean {
        if (control[d.id]?.isNotEmpty() == true) return true
        return !d.hidden && now() - d.lastSeen < ONLINE_TTL_MS
    }

    fun connected(id: String) = control[id]?.isNotEmpty() == true

    fun view(): List<DeviceView> = devices.values.mapNotNull { d ->
        val online = isOnline(d)
        if (!d.paired && !online) null
        else DeviceView(d.id, d.name, d.os, d.kind, d.icon, d.ip, d.port, online, d.paired, d.trusted)
    }.sortedWith(compareBy({ !it.online }, { !it.paired }, { it.name.lowercase() }))

    @Synchronized
    fun changed() {
        val v = view()
        if (v != lastView) {
            lastView = v
            host.devicesChanged()
        }
        for (d in devices.values) {
            val on = isOnline(d)
            if (wasOnline[d.id] != on) {
                wasOnline[d.id] = on
                for (fn in onlineListeners) runCatching { fn(d.id, on) }
            }
        }
    }

    private fun save(d: Device) { if (d.paired) host.saveDevice(d) }

    /** Updates a device from a hello / announcement. */
    fun update(id: String, info: JsonObject, seen: Boolean = true): Device? {
        if (id == ident.id) return null
        val d = devices.getOrPut(id) { Device(id) }
        val before = d.info()
        info.str("name")?.takeIf { it.isNotEmpty() }?.let { d.name = it.take(64) }
        info.str("os")?.takeIf { it.isNotEmpty() }?.let { d.os = it.take(64) }
        info.str("kind")?.takeIf { it.isNotEmpty() }?.let { d.kind = it.take(64) }
        info.str("icon")?.takeIf { it.isNotEmpty() }?.let { d.icon = it.take(64) }
        info.str("ip")?.takeIf { it.isNotEmpty() }?.let { d.ip = it.take(64) }
        info.int("port")?.let { if (info["port"].let { p -> p is kotlinx.serialization.json.JsonPrimitive && !p.isString }) d.port = it }
        if (info.containsKey("hidden")) d.hidden = info.truthy("hidden")
        if (seen) d.lastSeen = now()
        if (d.info() != before) save(d)
        return d
    }

    /** Called by discovery when a device announces itself. */
    fun seen(id: String, info: JsonObject) {
        val d = update(id, info) ?: return
        changed()
        if (d.paired && !connected(d.id)) ensureControl(d.id)
    }

    fun lost(id: String) {
        val d = devices[id] ?: return
        if (!connected(id)) {
            d.lastSeen = 0
            changed()
        }
    }

    fun setPaired(id: String, trusted: Boolean) {
        val d = devices[id] ?: return
        val was = d.paired
        d.paired = true
        d.trusted = trusted
        save(d)
        changed()
        if (!was) for (fn in pairedListeners) runCatching { fn(id) }
    }

    fun setTrust(id: String, trusted: Boolean) {
        val d = devices[id] ?: return
        if (d.paired) {
            d.trusted = trusted
            save(d)
            changed()
        }
    }

    /** Unpair on both sides: tell the other device first, then forget it here. */
    suspend fun forget(id: String) {
        val d = devices[id] ?: return
        if (d.paired && connected(id)) withTimeoutOrNull(3000) { send(id, obj("type" to "unpair")) }
        forgetLocal(id)
    }

    private fun forgetLocal(id: String) {
        val d = devices[id] ?: return
        d.paired = false
        d.trusted = false
        host.forgetDevice(id)
        backoff.remove(id)
        control.remove(id)?.forEach { it.close() }
        changed()
    }

    private suspend fun onUnpair(d: Device, conn: Conn, msg: Msg): Reply? {
        if (d.paired) {
            host.log("${d.name} removed the pairing")
            forgetLocal(d.id)
            host.unpaired(d)
        }
        return null
    }

    /** The peer says it does not know us (it forgot us while we were offline): forget it too. */
    private fun syncPairing(id: String, hello: JsonObject) {
        val d = devices[id] ?: return
        if (d.paired && hello["paired"].let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "false" }) {
            host.log("${d.name} no longer has this device paired")
            forgetLocal(id)
            host.unpaired(d)
        }
    }

    private fun pairedFlag(id: String?): Map<String, Any?> =
        if (id == null) emptyMap() else mapOf("paired" to (devices[id]?.paired == true))

    // ---- connections ------------------------------------------------------
    fun ensureControl(id: String): Deferred<Conn?>? {
        if (connected(id)) return null
        dialing[id]?.let { if (it.isActive) return it }
        if (now() < (backoff[id] ?: 0)) return null
        val t = scope.async { dialControl(id) }
        dialing[id] = t
        return t
    }

    private suspend fun dialControl(id: String): Conn? {
        val d = devices[id] ?: return null
        if (d.ip.isEmpty() || d.port == 0) return null
        val conn = try {
            connect(d.ip, d.port, id, "control")
        } catch (e: Exception) {
            host.log("dial ${d.name} (${d.ip}:${d.port}) failed: $e")
            backoff[id] = now() + 5000
            return null
        }
        backoff.remove(id)
        scope.launch { runControl(conn) }
        return conn
    }

    /** Dial, authenticate and exchange hello. Returns an open connection. */
    suspend fun connect(ip: String, port: Int, expectedId: String? = null, purpose: String = "control", extra: Map<String, Any?> = emptyMap()): Conn {
        val conn = dial(ip, port, ident, expectedId)
        try {
            if (conn.peerId == ident.id) throw PeerError("connected to myself")
            conn.send(hello(purpose, pairedFlag(conn.peerId) + extra))
            val msg = conn.recv(8000)
            if (msg.type != "hello") {
                if (msg.header.str("error") == "not paired") syncPairing(conn.peerId, obj("paired" to false))
                throw PeerError(msg.header.str("error") ?: "no hello")
            }
            conn.hello = msg.header
            update(conn.peerId, msg.header + mapOf("ip" to ip))
            syncPairing(conn.peerId, msg.header)
            changed()
            if (purpose != "control" && devices[conn.peerId]?.paired != true) throw PeerError("not paired")
            return conn
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }

    private suspend fun onConn(conn: Conn) {
        val msg = conn.recv(8000)
        if (msg.type != "hello") return
        conn.hello = msg.header
        val purpose = msg.header.str("purpose") ?: "control"
        val d = update(conn.peerId, msg.header + mapOf("ip" to conn.peerIp)) ?: return
        syncPairing(d.id, msg.header)
        if (purpose != "control" && !d.paired) {
            conn.send(obj("type" to "error", "error" to "not paired"))
            return
        }
        conn.send(hello(purpose, pairedFlag(d.id)))
        if (purpose == "control") {
            runControl(conn)
            return
        }
        streamHandlers[purpose]?.invoke(d, conn, msg.header)
    }

    suspend fun runControl(conn: Conn) {
        val id = conn.peerId
        control.getOrPut(id) { CopyOnWriteArrayList() }.add(conn)
        lastRx[conn] = now()
        changed()
        host.log("control connection with ${conn.hello.str("name")} (${id.take(8)})")
        try {
            while (true) {
                val msg = conn.recv()
                lastRx[conn] = now()
                devices[id]?.lastSeen = now()
                scope.launch { dispatch(conn, msg) }
            }
        } catch (_: Exception) {
        } finally {
            conn.close()
            lastRx.remove(conn)
            control[id]?.let { l ->
                l.remove(conn)
                if (l.isEmpty()) control.remove(id, l)
            }
            changed()
            host.log("control connection with ${id.take(8)} closed")
        }
    }

    private suspend fun dispatch(conn: Conn, msg: Msg) {
        val type = msg.type
        if (type == "reply") {
            pending.remove(msg.header.str("re") ?: "")?.complete(msg)
            return
        }
        val d = devices[conn.peerId]
        val r = msg.header.str("rid")
        if (d == null || (!d.paired && type !in PAIRING_TYPES)) {
            if (r != null) reply(conn, r, fail("not paired"))
            return
        }
        val fn = handlers[type]
        val result: Reply? = if (fn == null) fail("unknown message") else try {
            fn(d, conn, msg)
        } catch (e: Exception) {
            host.log("handler $type failed: $e")
            fail(e.message ?: e.toString())
        }
        if (r != null) reply(conn, r, result ?: ok())
    }

    private suspend fun reply(conn: Conn, r: String, rep: Reply) {
        runCatching { conn.send(rep.header + mapOf("type" to "reply", "re" to r), rep.body) }
    }

    // ---- messaging --------------------------------------------------------
    private suspend fun controlConn(id: String, dialTimeoutMs: Long = 6000): Conn {
        control[id]?.lastOrNull()?.let { return it }
        var t = ensureControl(id) ?: dialing[id]?.takeIf { it.isActive }
        if (t == null && backoff.containsKey(id)) {
            backoff.remove(id)
            t = ensureControl(id)
        }
        if (t != null) withTimeoutOrNull(dialTimeoutMs) { t.await() }
        return control[id]?.lastOrNull() ?: throw PeerError("device is offline")
    }

    suspend fun request(id: String, msg: JsonObject, body: ByteArray = Msg.EMPTY, timeoutMs: Long = 15_000, conn: Conn? = null): Msg {
        val c = conn ?: controlConn(id)
        val r = "%s-%d".format(ident.id.take(6), rid.getAndIncrement())
        val fut = CompletableDeferred<Msg>()
        pending[r] = fut
        try {
            c.send(msg + mapOf("rid" to r), body)
            return withTimeout(timeoutMs) { fut.await() }
        } finally {
            pending.remove(r)
        }
    }

    suspend fun send(id: String, msg: JsonObject, body: ByteArray = Msg.EMPTY): Boolean = try {
        controlConn(id).send(msg, body)
        true
    } catch (_: IOException) {
        false
    }

    suspend fun openStream(id: String, purpose: String, extra: Map<String, Any?> = emptyMap()): Conn {
        val d = devices[id] ?: throw PeerError("unknown device")
        if (d.ip.isEmpty()) throw PeerError("unknown device")
        return connect(d.ip, d.port, id, purpose, extra)
    }

    // ---- upkeep -----------------------------------------------------------
    private suspend fun maintain() {
        while (scope.isActive) {
            delay(4000)
            val t = now()
            for ((id, conns) in control) for (c in conns) {
                val idle = t - (lastRx[c] ?: t)
                if (idle > DEAD_AFTER_MS) {
                    host.log("control connection with ${id.take(8)} timed out")
                    c.close()
                } else if (idle > PING_EVERY_MS) {
                    try {
                        c.send(obj("type" to "ping"))
                    } catch (_: IOException) {
                        c.close()
                    }
                }
            }
            for (d in devices.values) if (d.paired && !connected(d.id) && t - d.lastSeen < ONLINE_TTL_MS) ensureControl(d.id)
            changed()
        }
    }
}
