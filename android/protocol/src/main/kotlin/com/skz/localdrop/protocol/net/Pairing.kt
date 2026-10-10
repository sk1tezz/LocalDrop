package com.skz.localdrop.protocol.net

import com.skz.localdrop.protocol.crypto.Crypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * Pairing, a port of desktop/localdrop/net/pairing.py:
 * * from the list / by IP: `pair_request`, both screens show the 6-digit SAS, the receiver accepts;
 * * by code: the other side proves it knows our rotating code (HMAC bound to the session);
 * * by QR: ip, port, device id and the current code.
 */
const val CODE_TTL_MS = 60_000L
const val PAIR_TIMEOUT_MS = 90_000L
const val MAX_CODE_TRIES = 5
const val QR_PREFIX = "localdrop:1:"

fun fmtCode(code: String) = code.take(3) + " " + code.drop(3)

fun qrPayload(ip: String, port: Int, id: String, code: String) = "$QR_PREFIX$ip:$port:$id:$code"

data class QrInfo(val ip: String, val port: Int, val id: String, val code: String)

fun parseQr(text: String): QrInfo? {
    if (!text.startsWith(QR_PREFIX)) return null
    val p = text.removePrefix(QR_PREFIX).split(":")
    if (p.size != 4 || p[1].toIntOrNull() == null || p[3].length != 6 || p[3].any { !it.isDigit() }) return null
    return QrInfo(p[0], p[1].toInt(), p[2], p[3])
}

/** The open pairing dialog. */
data class PairState(val id: String, val dir: String, val name: String, val os: String, val icon: String, val ip: String, val code: String, val trust: Boolean = true)

/** What pairing tells the app; [key] names the notice, [args] fill it in. */
interface PairingHost {
    fun pairChanged(p: PairState?)
    fun codeChanged(code: String)
    fun notice(kind: String, key: String, vararg args: String)
    fun ip(): String
}

class Pairing(private val host: PairingHost, private val peers: PeerManager, private val scope: CoroutineScope) {
    @Volatile var code = ""
        private set
    private var prevCode = "" to 0L
    private var codeAt = 0L
    @Volatile var pair: PairState? = null
        private set
    private var pairJob: Job? = null
    private var pairFuture: CompletableDeferred<Pair<Boolean, Boolean>>? = null
    private val tries = HashMap<String, MutableList<Long>>()
    private var rotateJob: Job? = null

    init {
        peers.on("pair_request", ::onRequest)
        peers.on("pair_cancel", ::onCancel)
        peers.on("pair_code", ::onCode)
    }

    fun start() {
        newCode()
        rotateJob = scope.launch {
            while (isActive) {
                delay(5000)
                if (System.currentTimeMillis() - codeAt >= CODE_TTL_MS) newCode()
            }
        }
    }

    fun stop() { rotateJob?.cancel() }

    // ---- my code ----------------------------------------------------------
    @Synchronized
    fun newCode() {
        if (code.isNotEmpty()) prevCode = code to System.currentTimeMillis()
        code = "%06d".format(Crypto.random.nextInt(1_000_000))
        codeAt = System.currentTimeMillis()
        host.codeChanged(code)
    }

    fun qr(): String = qrPayload(host.ip(), peers.hello().int("port") ?: 53317, peers.ident.id, code)

    private fun codeOk(conn: Conn, proof: String): Boolean {
        val candidates = mutableListOf(code)
        val (prev, at) = prevCode
        if (prev.isNotEmpty() && System.currentTimeMillis() - at < 15_000) candidates += prev
        return candidates.any { MessageDigest.isEqual(conn.proof(it).toByteArray(), proof.toByteArray()) }
    }

    // ---- dialog state -----------------------------------------------------
    private fun open(d: Device, conn: Conn, dir: String) {
        pair = PairState(d.id, dir, d.name, d.os, d.icon, d.ip, fmtCode(conn.sas))
        host.pairChanged(pair)
    }

    private fun close() {
        pair = null
        host.pairChanged(null)
    }

    // ---- 1/2: request with SAS confirmation --------------------------------
    suspend fun request(id: String) {
        val d = peers.get(id) ?: return
        startOutgoing(d.ip, d.port, id)
    }

    suspend fun connectIp(ip: String, port: Int) = startOutgoing(ip, if (port == 0) 53317 else port, null)

    private suspend fun startOutgoing(ip: String, port: Int, expectedId: String?) {
        if (pair != null) return
        val conn = try {
            peers.connect(ip, port, expectedId, "control")
        } catch (e: Exception) {
            host.notice("bad", "conn_failed", ip, port.toString())
            return
        }
        scope.launch { peers.runControl(conn) }
        val d = peers.get(conn.peerId) ?: return
        if (d.paired) {
            host.notice("ok", "already", d.name)
            return
        }
        open(d, conn, "out")
        pairJob = scope.launch { outgoing(d, conn) }
    }

    private suspend fun outgoing(d: Device, conn: Conn) {
        var cancelled = false
        val reply = try {
            peers.request(d.id, obj("type" to "pair_request"), timeoutMs = PAIR_TIMEOUT_MS + 5000, conn = conn).header
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            obj("ok" to false, "error" to "timeout")
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
            null
        } catch (e: Exception) {
            obj("ok" to false, "error" to "timeout")
        }
        if (cancelled) {
            scope.launch { peers.send(d.id, obj("type" to "pair_cancel")) }
            close()
            return
        }
        val trust = pair?.trust ?: true
        close()
        when {
            reply?.bool("ok") == true -> {
                peers.setPaired(d.id, trust)
                host.notice("ok", "connected", d.name)
            }
            reply?.str("error") == "busy" -> host.notice("bad", "busy", d.name)
            else -> host.notice("bad", "declined", d.name)
        }
    }

    private suspend fun onRequest(d: Device, conn: Conn, msg: Msg): Reply {
        if (d.paired) return ok()
        if (pair != null) return fail("busy")
        open(d, conn, "in")
        val fut = CompletableDeferred<Pair<Boolean, Boolean>>()
        pairFuture = fut
        host.notice("acc", "request_in", d.name)
        val (accepted, trust) = try {
            withTimeoutOrNull(PAIR_TIMEOUT_MS) { fut.await() } ?: (false to false)
        } finally {
            pairFuture = null
            close()
        }
        if (accepted) {
            peers.setPaired(d.id, trust)
            host.notice("ok", "connected", d.name)
        }
        return Reply(obj("ok" to accepted))
    }

    private suspend fun onCancel(d: Device, conn: Conn, msg: Msg): Reply? {
        val f = pairFuture
        if (pair?.id == d.id && f != null && !f.isCompleted) {
            f.complete(false to false)
            host.notice("acc", "cancelled", d.name)
        }
        return null
    }

    fun respond(id: String, accept: Boolean, trust: Boolean) {
        val p = pair ?: return
        if (p.id != id) return
        if (p.dir == "in") pairFuture?.let { if (!it.isCompleted) it.complete(accept to trust) }
        else pairJob?.cancel()
    }

    fun outTrust(id: String, trust: Boolean) {
        val p = pair ?: return
        if (p.id == id) {
            pair = p.copy(trust = trust)
            host.pairChanged(pair)
        }
    }

    // ---- 3/4: by code -----------------------------------------------------
    suspend fun enterCode(raw: String) {
        val c = raw.filter { it.isDigit() }
        if (c.length != 6) return
        if (c == code) {
            host.notice("bad", "own_code")
            return
        }
        val candidates = peers.devices.values.filter { !it.paired && peers.isOnline(it) && it.ip.isNotEmpty() }
        val results = candidates.map { d -> scope.async { tryCode(d.ip, d.port, d.id, c) } }.awaitAll()
        if (results.none { it }) host.notice("bad", "code_bad")
    }

    suspend fun tryCode(ip: String, port: Int, id: String?, c: String): Boolean {
        val conn = try {
            peers.connect(ip, port, id, "control")
        } catch (e: Exception) {
            return false
        }
        scope.launch { peers.runControl(conn) }
        val reply = try {
            peers.request(conn.peerId, obj("type" to "pair_code", "proof" to conn.proof(c)), timeoutMs = 10_000, conn = conn).header
        } catch (e: Exception) {
            return false
        }
        val d = peers.get(conn.peerId) ?: return false
        if (reply.bool("ok") == true) {
            peers.setPaired(d.id, true)
            host.notice("ok", "connected", d.name)
            return true
        }
        if (!d.paired) conn.close()
        return false
    }

    private suspend fun onCode(d: Device, conn: Conn, msg: Msg): Reply {
        val now = System.currentTimeMillis()
        val list = synchronized(tries) {
            val l = tries.getOrPut(conn.peerIp) { mutableListOf() }
            l.removeAll { now - it >= 60_000 }
            if (l.size >= MAX_CODE_TRIES) return fail("too many attempts")
            l += now
            l
        }
        check(list.isNotEmpty())
        if (!codeOk(conn, msg.header.str("proof") ?: "")) return fail()
        peers.setPaired(d.id, true)
        newCode()
        host.notice("ok", "connected_code", d.name)
        return ok()
    }

    // ---- QR ---------------------------------------------------------------
    /** A scanned QR; false when it is not a LocalDrop code. */
    fun scanned(text: String): Boolean {
        val info = parseQr(text) ?: return false
        scope.launch {
            when {
                info.id == peers.ident.id -> host.notice("bad", "qr_self")
                peers.get(info.id)?.paired == true -> host.notice("ok", "already", peers.get(info.id)!!.name)
                !tryCode(info.ip, info.port, info.id, info.code) -> host.notice("bad", "qr_failed")
            }
        }
        return true
    }
}
