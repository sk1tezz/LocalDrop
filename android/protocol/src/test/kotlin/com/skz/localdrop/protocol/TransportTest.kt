package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.crypto.Identity
import com.skz.localdrop.protocol.net.Conn
import com.skz.localdrop.protocol.net.HandshakeError
import com.skz.localdrop.protocol.net.Server
import com.skz.localdrop.protocol.net.dial
import com.skz.localdrop.protocol.net.int
import com.skz.localdrop.protocol.net.obj
import com.skz.localdrop.protocol.net.str
import com.skz.localdrop.protocol.net.type
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun kotlinToKotlin() = runBlocking {
        val a = Identity.generate()
        val b = Identity.generate()
        val got = CompletableDeferred<Pair<Conn, String>>()
        val server = Server(0, b, scope) { conn ->
            val m = conn.recv()
            conn.send(obj("type" to "echo", "x" to m.header.str("x")), m.body.reversedArray())
            got.complete(conn to conn.sas)
            conn.recv()          // until the client closes
        }.start()
        val c = dial("127.0.0.1", server.localPort, a, expectedId = b.id)
        c.send(obj("type" to "hi", "x" to "яблоко"), byteArrayOf(1, 2, 3))
        val r = c.recv(5000)
        assertEquals("echo", r.type)
        assertEquals("яблоко", r.header.str("x"))
        assertArrayEquals(byteArrayOf(3, 2, 1), r.body)
        val (sconn, ssas) = withTimeout(5000) { got.await() }
        assertEquals(c.sas, ssas)
        assertEquals(a.id, sconn.peerId)
        assertEquals(b.id, c.peerId)
        assertEquals(c.proof("123456"), sconn.proof("123456"))
        c.close()
        server.close()
    }

    @Test
    fun wrongIdentityIsRejected() = runBlocking {
        val server = Server(0, Identity.generate(), scope) { it.recv() }.start()
        try {
            dial("127.0.0.1", server.localPort, Identity.generate(), expectedId = "00".repeat(16))
            fail("expected HandshakeError")
        } catch (_: HandshakeError) {
        }
        server.close()
    }

    @Test
    fun kotlinDialsDesktop() = runBlocking {
        PythonPeer("serve").use { py ->
            val ready = py.line().split(" ")
            assertEquals("READY", ready[0])
            val me = Identity.generate()
            val c = dial("127.0.0.1", ready[1].toInt(), me, expectedId = ready[2])
            val big = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            c.send(obj("type" to "hi", "text" to "привет", "list" to listOf(1, 2)), big)
            val r = c.recv(10_000)
            assertEquals("echo", r.type)
            assertEquals(c.sas, r.header.str("sas"))
            assertEquals(me.id, r.header.str("peer"))
            assertEquals(c.proof("123456"), r.header.str("proof"))
            assertEquals("привет", r.header.obj("got")!!.str("text"))
            assertEquals(big.size, r.header.int("n"))
            assertArrayEquals(big.reversedArray(), r.body)
            c.send(obj("type" to "bye"))
            c.close()
        }
    }

    @Test
    fun desktopDialsKotlin() = runBlocking {
        val me = Identity.generate()
        val seen = CompletableDeferred<Conn>()
        val server = Server(0, me, scope) { conn ->
            seen.complete(conn)
            while (true) {
                val m = conn.recv()
                if (m.type == "bye") break
                conn.send(obj("type" to "echo", "text" to m.header.str("text")), m.body)
            }
        }.start()
        PythonPeer("dial", server.localPort.toString()).use { py ->
            val hs = py.obj()
            val conn = withTimeout(10_000) { seen.await() }
            assertEquals(conn.sas, hs.str("sas"))
            assertEquals(me.id, hs.str("peer"))
            assertEquals(conn.peerId, hs.str("me"))
            assertEquals(conn.proof("123456"), hs.str("proof"))
            val reply = py.obj()
            assertEquals("привет", (reply["reply"] as kotlinx.serialization.json.JsonObject).str("text"))
            assertEquals("abc", reply.str("body"))
            assertTrue(py.waitExit() == 0)
        }
        server.close()
    }
}
