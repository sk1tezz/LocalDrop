package com.skz.localdrop.protocol.net

import com.skz.localdrop.protocol.crypto.Crypto
import com.skz.localdrop.protocol.crypto.Ephemeral
import com.skz.localdrop.protocol.crypto.Identity
import com.skz.localdrop.protocol.crypto.hex
import com.skz.localdrop.protocol.crypto.idFromPub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Authenticated, encrypted connections over TCP: the SIGMA handshake of
 * desktop/localdrop/net/transport.py, byte for byte.
 *
 *     C -> S  M1 = "LDv1" | eC (X25519, 32) | nC (16)
 *     S -> C  M2 = eS (32) | nS (16) | pubS (Ed25519, 32) | sigS (64)
 *             sigS = Sign_S("LD-srv" | H(M1 | eS | nS | pubS))
 *     C -> S  M3 = pubC (32) | sigC (64)
 *             sigC = Sign_C("LD-cli" | H(M1 | M2 | pubC))
 *
 * Session keys = HKDF(X25519(eC, eS), salt = H(M1|M2|M3)); the 6-digit SAS comes from the same transcript.
 */
val MAGIC = "LDv1".toByteArray()
const val HANDSHAKE_TIMEOUT_MS = 8000

class HandshakeError(msg: String) : IOException(msg)

class Conn internal constructor(
    private val socket: Socket, sendKey: ByteArray, recvKey: ByteArray,
    val peerPub: ByteArray, val sas: String, private val binding: ByteArray, val initiator: Boolean,
) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
    private val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
    private val tx = RecordCipher(sendKey)
    private val rx = RecordCipher(recvKey)
    private val wlock = Mutex()
    val peerId: String = idFromPub(peerPub)
    val peerIp: String = socket.inetAddress?.hostAddress ?: ""
    @Volatile var hello: JsonObject = JsonObject(emptyMap())
    @Volatile var closed = false
        private set

    suspend fun send(header: JsonObject, body: ByteArray = Msg.EMPTY) = withContext(Dispatchers.IO) {
        wlock.withLock {
            val ct = tx.seal(packMsg(header, body))
            output.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            output.write(ct)
            output.flush()
        }
    }

    /** Next message; [timeoutMs] > 0 limits the wait (the connection is closed after a timeout). */
    suspend fun recv(timeoutMs: Int = 0): Msg = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = timeoutMs
            val n = input.readInt()
            if (n < 0 || n > MAX_RECORD + 64) throw FrameError("record too large")
            val ct = ByteArray(n)
            input.readFully(ct)
            unpackMsg(rx.open(ct))
        } catch (e: SocketTimeoutException) {
            close()
            throw e
        }
    }

    /** HMAC of a shared secret bound to this session (pairing by code). */
    fun proof(secret: String): String =
        Crypto.hmacSha256(secret.toByteArray(Charsets.UTF_8), "LD-proof".toByteArray() + binding).hex()

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    override fun toString() = "Conn(${peerId.take(8)}@$peerIp)"
}

private class Derived(val tx: ByteArray, val rx: ByteArray, val sas: String, val binding: ByteArray)

private fun derive(shared: ByteArray, transcript: ByteArray, initiator: Boolean): Derived {
    val keys = Crypto.hkdf(shared, transcript, "LD keys".toByteArray(), 96)
    val c2s = keys.copyOfRange(0, 32)
    val s2c = keys.copyOfRange(32, 64)
    val binding = keys.copyOfRange(64, 96)
    val raw = Crypto.hkdf(shared, transcript, "LD sas".toByteArray(), 4)
    val v = ByteBuffer.wrap(raw).int.toLong() and 0xffffffffL
    val sas = "%06d".format(v % 1_000_000)
    return if (initiator) Derived(c2s, s2c, sas, binding) else Derived(s2c, c2s, sas, binding)
}

private fun DataInputStream.exactly(n: Int) = ByteArray(n).also { readFully(it) }

private fun tune(s: Socket) {
    runCatching {
        s.tcpNoDelay = true
        s.keepAlive = true
        s.sendBufferSize = 4 * 1024 * 1024
        s.receiveBufferSize = 4 * 1024 * 1024
    }
}

private fun clientHandshake(s: Socket, ident: Identity, expectedId: String?): Conn {
    val inp = DataInputStream(s.getInputStream())
    val out = s.getOutputStream()
    val e = Ephemeral()
    val m1 = MAGIC + e.pub + Crypto.randomBytes(16)
    out.write(m1); out.flush()
    val m2 = inp.exactly(32 + 16 + 32 + 64)
    val eS = m2.copyOfRange(0, 32)
    val nS = m2.copyOfRange(32, 48)
    val pubS = m2.copyOfRange(48, 80)
    val sigS = m2.copyOfRange(80, 144)
    if (!Crypto.verify(pubS, sigS, "LD-srv".toByteArray() + Crypto.sha256(m1, eS, nS, pubS))) throw HandshakeError("bad server signature")
    if (expectedId != null && idFromPub(pubS) != expectedId) throw HandshakeError("unexpected device identity")
    val sigC = ident.sign("LD-cli".toByteArray() + Crypto.sha256(m1, m2, ident.pub))
    val m3 = ident.pub + sigC
    out.write(m3); out.flush()
    val d = derive(e.exchange(eS), Crypto.sha256(m1, m2, m3), true)
    return Conn(s, d.tx, d.rx, pubS, d.sas, d.binding, true)
}

private fun serverHandshake(s: Socket, ident: Identity): Conn {
    val inp = DataInputStream(s.getInputStream())
    val out = s.getOutputStream()
    val m1 = inp.exactly(4 + 32 + 16)
    if (!m1.copyOfRange(0, 4).contentEquals(MAGIC)) throw HandshakeError("not a LocalDrop peer")
    val e = Ephemeral()
    val nS = Crypto.randomBytes(16)
    val sigS = ident.sign("LD-srv".toByteArray() + Crypto.sha256(m1, e.pub, nS, ident.pub))
    val m2 = e.pub + nS + ident.pub + sigS
    out.write(m2); out.flush()
    val m3 = inp.exactly(32 + 64)
    val pubC = m3.copyOfRange(0, 32)
    val sigC = m3.copyOfRange(32, 96)
    if (!Crypto.verify(pubC, sigC, "LD-cli".toByteArray() + Crypto.sha256(m1, m2, pubC))) throw HandshakeError("bad client signature")
    val d = derive(e.exchange(m1.copyOfRange(4, 36)), Crypto.sha256(m1, m2, m3), false)
    return Conn(s, d.tx, d.rx, pubC, d.sas, d.binding, false)
}

/** Connect and authenticate. Throws IOException / HandshakeError. */
suspend fun dial(host: String, port: Int, ident: Identity, expectedId: String? = null, timeoutMs: Int = HANDSHAKE_TIMEOUT_MS): Conn =
    withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            tune(s)
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val c = clientHandshake(s, ident, expectedId)
            s.soTimeout = 0
            c
        } catch (e: Throwable) {
            runCatching { s.close() }
            if (e is EOFException) throw HandshakeError("connection closed during handshake") else throw e
        }
    }

/** Accepts connections; [onConn] runs for each authenticated peer and the connection is closed after it. */
class Server(port: Int, private val ident: Identity, private val scope: CoroutineScope, private val onConn: suspend (Conn) -> Unit) {
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("0.0.0.0", port))
    }
    private var job: Job? = null
    val localPort: Int get() = socket.localPort

    fun start(): Server {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val s = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (socket.isClosed) break else continue
                }
                scope.launch(Dispatchers.IO) { handle(s) }
            }
        }
        return this
    }

    private suspend fun handle(s: Socket) {
        tune(s)
        val conn = try {
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            serverHandshake(s, ident).also { s.soTimeout = 0 }
        } catch (e: Exception) {
            runCatching { s.close() }
            return
        }
        try {
            onConn(conn)
        } catch (_: IOException) {
        } catch (_: FrameError) {
        } finally {
            conn.close()
        }
    }

    fun close() {
        job?.cancel()
        runCatching { socket.close() }
    }
}
