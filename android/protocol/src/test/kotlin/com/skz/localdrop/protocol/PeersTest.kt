package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.net.bool
import com.skz.localdrop.protocol.net.obj
import com.skz.localdrop.protocol.net.str
import com.skz.localdrop.protocol.net.type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun controlConnectionAndRequestsBothWays() = runBlocking {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                k.pairWith(py, pyPort, pyId)
                // Kotlin -> desktop request over the control connection
                val r = k.peers.request(pyId, obj("type" to "ping"))
                assertEquals("reply", r.type)
                assertEquals(true, r.header.bool("ok"))
                assertTrue(k.peers.connected(pyId))
                // desktop sees our hello (name, kind, icon) and can ask us too
                py.waitFor("p.connected('${k.ident.id}')")
                assertEquals("Phone", (py.cmd("p.get('${k.ident.id}').name") as JsonPrimitive).content)
                assertEquals("smartphone", (py.cmd("p.get('${k.ident.id}').icon") as JsonPrimitive).content)
                val back = py.cmd("p.request('${k.ident.id}', {'type': 'ping'})") as JsonArray
                assertEquals(true, (back[0] as JsonObject).bool("ok"))
                // unknown messages get an error reply
                val unk = k.peers.request(pyId, obj("type" to "no_such_message"))
                assertEquals("unknown message", unk.header.str("error"))
            }
        }
    }

    @Test
    fun unpairReachesTheOtherSide() = runBlocking {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                k.pairWith(py, pyPort, pyId)
                k.peers.request(pyId, obj("type" to "ping"))
                k.peers.forget(pyId)
                py.waitFor("not p.get('${k.ident.id}').paired")
                assertFalse(k.peers.get(pyId)!!.paired)

                // and the other way round: the desktop forgets us, we follow
                k.pairWith(py, pyPort, pyId)
                k.peers.request(pyId, obj("type" to "ping"))
                py.cmd("p.forget('${k.ident.id}')")
                repeat(100) { if (k.peers.get(pyId)!!.paired) delay(50) }
                assertFalse(k.peers.get(pyId)!!.paired)
                assertTrue(pyId in k.unpaired)
            }
        }
    }

    @Test
    fun unpairedDeviceGetsOnlyPairingMessages() = runBlocking {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                // we think we are paired, the desktop does not know us
                k.peers.seen(pyId, obj("name" to "Desktop", "ip" to "127.0.0.1", "port" to pyPort))
                val conn = k.peers.connect("127.0.0.1", pyPort, pyId, "control")
                val job = k.scope.launch { k.peers.runControl(conn) }
                val r = k.peers.request(pyId, obj("type" to "clip", "ctype" to "text", "text" to "x"), conn = conn)
                assertEquals("not paired", r.header.str("error"))
                job.cancel()
            }
        }
    }
}
