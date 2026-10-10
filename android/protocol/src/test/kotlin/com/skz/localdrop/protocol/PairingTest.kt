package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.net.parseQr
import com.skz.localdrop.protocol.net.qrPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun str(e: kotlinx.serialization.json.JsonElement) = (e as JsonPrimitive).content

    private suspend fun until(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "condition not met" }
            delay(30)
        }
    }

    @Test
    fun phoneRequestsDesktopAccepts() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                k.meet(py, pyPort, pyId)
                val job = k.scope.launch { k.pairing.request(pyId) }
                py.waitFor("k.pair")
                val pyPair = py.cmd("k.pair") as JsonObject
                until { k.dialog != null }
                assertEquals(k.dialog!!.code, str(pyPair["code"]!!))      // same SAS on both screens
                assertEquals("out", k.dialog!!.dir)
                assertEquals("in", str(pyPair["dir"]!!))
                k.pairing.outTrust(pyId, false)
                py.cmd("k.api_respond('${k.ident.id}', True, True)")
                job.join()
                until { k.peers.get(pyId)!!.paired }
                assertFalse(k.peers.get(pyId)!!.trusted)                    // our "trusted" switch was off
                py.waitFor("p.get('${k.ident.id}').paired")
                assertTrue("connected" in k.notices)
            }
        }
    }

    @Test
    fun desktopRequestsPhoneDeclines() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                k.meet(py, pyPort, pyId)
                py.cmd("asyncio.ensure_future(k.api_request('${k.ident.id}')) and None")
                until { k.dialog != null }
                assertEquals("in", k.dialog!!.dir)
                assertEquals(k.dialog!!.code, str((py.waitFor("k.pair") as JsonObject)["code"]!!))
                k.pairing.respond(pyId, accept = false, trust = false)
                py.waitFor("k.pair is None")
                assertFalse(k.peers.get(pyId)!!.paired)
                assertTrue(py.toasts.any { it[1] == "Подключение отклонено" })
            }
        }
    }

    @Test
    fun desktopRequestsPhoneAcceptsTrusted() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                k.meet(py, pyPort, pyId)
                py.cmd("asyncio.ensure_future(k.api_request('${k.ident.id}')) and None")
                until { k.dialog != null }
                k.pairing.respond(pyId, accept = true, trust = true)
                py.waitFor("p.get('${k.ident.id}').paired")
                assertTrue(k.peers.get(pyId)!!.trusted)
            }
        }
    }

    @Test
    fun phoneEntersDesktopCode() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                k.meet(py, pyPort, pyId)
                val code = str(py.cmd("k.code"))
                k.pairing.enterCode("111111".takeIf { it != code } ?: "222222")
                assertTrue("code_bad" in k.notices)
                assertFalse(k.peers.get(pyId)!!.paired)
                k.pairing.enterCode(code.take(3) + " " + code.drop(3))
                assertTrue(k.peers.get(pyId)!!.paired)
                py.waitFor("p.get('${k.ident.id}').paired")
            }
        }
    }

    @Test
    fun desktopEntersPhoneCode() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                k.meet(py, pyPort, pyId)
                val before = k.pairing.code
                py.cmd("k.api_code('${k.pairing.code}')")
                py.waitFor("p.get('${k.ident.id}').paired")
                until { k.peers.get(pyId)?.paired == true }
                assertTrue(k.pairing.code != before)                         // a used code is replaced
            }
        }
    }

    @Test
    fun phoneScansDesktopQr() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                // the desktop QR as desktop pairing.qr_payload builds it; no discovery needed
                val qr = str(py.cmd("__import__('localdrop.net.pairing', fromlist=['x']).qr_payload('127.0.0.1', app.port, app.ident.id, k.code)"))
                val info = parseQr(qr)!!
                assertEquals(pyId, info.id)
                assertEquals(qr, qrPayload(info.ip, info.port, info.id, info.code))
                assertTrue(k.pairing.scanned(qr))
                until { k.peers.get(pyId)?.paired == true }
                py.waitFor("p.get('${k.ident.id}').paired")
                assertFalse(k.pairing.scanned("https://example.com"))
                assertNull(parseQr("localdrop:1:1.2.3.4:x:id:123456"))
            }
        }
    }
}
