package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.clip.argbToRgb
import com.skz.localdrop.protocol.clip.imageHash
import com.skz.localdrop.protocol.clip.textHash
import com.skz.localdrop.protocol.net.obj
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun str(e: kotlinx.serialization.json.JsonElement) = (e as JsonPrimitive).content

    @Test
    fun hashesMatchTheDesktop() = runBlocking<Unit> {
        val (py, _, _) = pyNode(tmp.newFolder("desk"))
        py.use {
            val text = "Встреча в 16:30 — ссылка 🙂\nвторая строка"
            assertEquals(str(py.cmd("__import__('localdrop.clip.sync', fromlist=['x']).text_hash(${pyStr(text)})")), textHash(text))
            // a 3x2 RGBA image with transparency: the hash only sees RGB, like PIL's convert("RGB")
            val px = intArrayOf(0x80FF0000.toInt(), 0xFF00FF00.toInt(), 0x000000FF, 0xFF123456.toInt(), 0x7F7F7F7F, 0xFFFFFFFF.toInt())
            val rgba = px.joinToString(",") { p -> "(${(p shr 16) and 255},${(p shr 8) and 255},${p and 255},${(p ushr 24) and 255})" }
            val expr = "(lambda Image, s: (lambda im: (im.putdata([$rgba]), s.image_hash(im))[1])(Image.new('RGBA', (3, 2))))" +
                "(__import__('PIL.Image', fromlist=['x']), __import__('localdrop.clip.sync', fromlist=['x']))"
            assertEquals(str(py.cmd(expr)), imageHash(3, 2, argbToRgb(px)))
        }
    }

    @Test
    fun clipMessagesBothWays() = runBlocking<Unit> {
        val (py, pyPort, pyId) = pyNode(tmp.newFolder("desk"))
        py.use {
            KNode().use { k ->
                k.pairWith(py, pyPort, pyId)
                // phone -> desktop: stored in the desktop clipboard history with the same hash
                val text = "pip install localdrop"
                val r = k.peers.request(pyId, obj("type" to "clip", "ctype" to "text", "text" to text, "hash" to textHash(text), "manual" to true))
                assertEquals(true, (r.header["ok"] as JsonPrimitive).content.toBoolean())
                val clips = py.cmd("[(x['type'], x['text'], x['hash']) for x in app.storage.clips()]") as JsonArray
                assertEquals(textHash(text), str((clips[0] as JsonArray)[2]))
                // desktop -> phone: the message the phone will get
                val got = kotlinx.coroutines.CompletableDeferred<JsonObject>()
                k.peers.on("clip") { _, _, m -> got.complete(m.header); null }
                py.cmd("c._broadcast({'ctype': 'text', 'text': 'с ПК', 'hash': c.__class__ and __import__('localdrop.clip.sync', fromlist=['x']).text_hash('с ПК')}, ['${k.ident.id}'], manual=True)")
                val m = kotlinx.coroutines.withTimeout(5000) { got.await() }
                assertEquals("text", str(m["ctype"]!!))
                assertEquals("с ПК", str(m["text"]!!))
                assertEquals(textHash("с ПК"), str(m["hash"]!!))
            }
        }
    }

    private fun pyStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
