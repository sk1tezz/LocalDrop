package com.skz.localdrop.protocol.net

import com.skz.localdrop.protocol.crypto.Aead
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer

/**
 * Message framing and record encryption, as desktop/localdrop/net/framing.py:
 * message = [u32 header length][header json][body]; on the wire every message is one
 * record [u32 ciphertext length][AES-256-GCM], nonce = 4 zero bytes + u64 counter.
 */
const val MAX_RECORD = 16 * 1024 * 1024

class FrameError(msg: String) : Exception(msg)

class Msg(val header: JsonObject, val body: ByteArray = EMPTY) {
    val type get() = header.type
    override fun toString() = "Msg($header, ${body.size} bytes)"

    companion object {
        val EMPTY = ByteArray(0)
    }
}

fun packMsg(header: JsonObject, body: ByteArray): ByteArray {
    val h = header.toString().toByteArray(Charsets.UTF_8)
    val out = ByteArray(4 + h.size + body.size)
    ByteBuffer.wrap(out).putInt(h.size)
    System.arraycopy(h, 0, out, 4, h.size)
    System.arraycopy(body, 0, out, 4 + h.size, body.size)
    return out
}

fun unpackMsg(data: ByteArray): Msg {
    if (data.size < 4) throw FrameError("short message")
    val n = ByteBuffer.wrap(data).int
    if (n < 0 || n > data.size - 4) throw FrameError("bad header length")
    val header = try {
        json.parseToJsonElement(String(data, 4, n, Charsets.UTF_8)) as? JsonObject
    } catch (e: Exception) {
        throw FrameError("bad header")
    } ?: throw FrameError("header is not an object")
    return Msg(header, data.copyOfRange(4 + n, data.size))
}

/** One direction of an encrypted channel. */
class RecordCipher(key: ByteArray) {
    private val aead = Aead(key)
    private var n = 0L

    private fun nonce(): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(n++).array()

    fun seal(plain: ByteArray): ByteArray = aead.seal(nonce(), plain)

    fun open(ct: ByteArray): ByteArray = try {
        aead.open(nonce(), ct)
    } catch (e: Exception) {
        throw FrameError("decryption failed")
    }
}
