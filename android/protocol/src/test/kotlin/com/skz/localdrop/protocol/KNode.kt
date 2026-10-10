package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.crypto.Identity
import com.skz.localdrop.protocol.net.Device
import com.skz.localdrop.protocol.net.PeerHost
import com.skz.localdrop.protocol.net.PeerManager
import com.skz.localdrop.protocol.net.PairState
import com.skz.localdrop.protocol.net.Pairing
import com.skz.localdrop.protocol.net.PairingHost
import com.skz.localdrop.protocol.net.obj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** A Kotlin node for tests: a PeerManager on a free port. */
class KNode(name: String = "Phone") : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ident = Identity.generate()
    val unpaired = CopyOnWriteArrayList<String>()
    val host = object : PeerHost {
        override val port = freePort()
        override fun meInfo(): Map<String, Any> = mapOf("name" to name, "os" to "Android 16", "kind" to "android", "icon" to "smartphone")
        override fun saveDevice(d: Device) {}
        override fun forgetDevice(id: String) {}
        override fun devicesChanged() {}
        override fun unpaired(d: Device) { unpaired.add(d.id) }
        override fun log(msg: String) = println("[kotlin] $msg")
    }
    val peers = PeerManager(host, ident, scope).also { it.start() }
    val notices = CopyOnWriteArrayList<String>()
    @Volatile var dialog: PairState? = null
    val pairing = Pairing(object : PairingHost {
        override fun pairChanged(p: PairState?) { dialog = p }
        override fun codeChanged(code: String) {}
        override fun notice(kind: String, key: String, vararg args: String) { notices.add(key); println("[kotlin] notice $key ${args.toList()}") }
        override fun ip() = "127.0.0.1"
    }, peers, scope).also { it.start() }

    /** Make both nodes know each other's address without pairing. */
    fun meet(py: PythonPeer, pyPort: Int, pyId: String) {
        peers.seen(pyId, obj("name" to "Desktop", "ip" to "127.0.0.1", "port" to pyPort))
        py.cmd("p.seen('${ident.id}', {'name': 'Phone', 'ip': '127.0.0.1', 'port': ${host.port}})")
    }

    /** Introduce a desktop node to this one and pair both sides directly. */
    fun pairWith(py: PythonPeer, pyPort: Int, pyId: String, trusted: Boolean = true) {
        peers.seen(pyId, obj("name" to "Desktop", "ip" to "127.0.0.1", "port" to pyPort))
        peers.setPaired(pyId, trusted)
        py.cmd("p.seen('${ident.id}', {'name': 'Phone', 'ip': '127.0.0.1', 'port': ${host.port}})")
        py.cmd("p.set_paired('${ident.id}', ${if (trusted) "True" else "False"})")
    }

    override fun close() {
        pairing.stop()
        peers.stop()
        scope.cancel()
    }
}

fun pyNode(dir: java.io.File): Triple<PythonPeer, Int, String> {
    val py = PythonPeer("node", dir.path)
    val r = py.line().split(" ")
    check(r[0] == "READY") { r }
    return Triple(py, r[1].toInt(), r[2])
}
