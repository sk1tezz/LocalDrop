package com.skz.localdrop.protocol.audio

import com.skz.localdrop.protocol.crypto.Aead
import com.skz.localdrop.protocol.crypto.Crypto
import com.skz.localdrop.protocol.crypto.hex
import com.skz.localdrop.protocol.crypto.unhex
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Real-time audio over UDP with synchronized playback; a port of desktop/localdrop/audio/stream.py.
 *
 * The sender emits one frame per 10 ms of its own monotonic clock: sample i has sender time
 * t0 + i / 48000. Receivers estimate the sender clock (NTP-like pings, min-RTT filtered) and
 * play sample i at sender time t0 + i / 48000 + delay, so every room aims at the same moment.
 *
 * Datagram: [u8 type][u64 seq][AES-GCM] (nonce = salt4 + seq8, AAD = type).
 * type 1 sender->receiver [u64 index][u64 t0][u8 codec][payload]; type 2 receiver->sender
 * [u64 receiver time]; type 3 sender->receiver [u64 receiver time][u64 sender time].
 */
const val RATE = 48000
const val FRAME = 480
const val T_AUDIO = 1
const val T_SYNC_REQ = 2
const val T_SYNC_RESP = 3
const val CODEC_OPUS = 1
const val CODEC_PCM = 2
const val NS = 1_000_000_000L

fun nowNs(): Long = System.nanoTime()

/** One encrypted direction of a datagram flow. */
class Channel(key: ByteArray, private val salt: ByteArray) {
    private val aead = Aead(key)
    private val seq = AtomicLong(0)

    private fun nonce(s: Long) = ByteBuffer.allocate(12).put(salt).putLong(s).array()

    fun seal(type: Int, plain: ByteArray): ByteArray {
        val s = seq.incrementAndGet()
        val ct = aead.seal(nonce(s), plain, byteArrayOf(type.toByte()))
        return ByteBuffer.allocate(9 + ct.size).put(type.toByte()).putLong(s).put(ct).array()
    }

    /** (type, plaintext), or null for anything that does not authenticate. */
    fun open(data: ByteArray, len: Int = data.size): Pair<Int, ByteArray>? {
        if (len < 9 + 16) return null
        val b = ByteBuffer.wrap(data, 0, len)
        val type = b.get().toInt() and 0xff
        val s = b.long
        return try {
            type to aead.open(nonce(s), data, byteArrayOf(type.toByte()), 9, len - 9)
        } catch (_: Exception) {
            null
        }
    }
}

/** Keys of one session, as in desktop new_keys(): hex strings in audio_start. */
data class AudioKeys(val kAudio: String, val sAudio: String, val kSync: String, val sSync: String) {
    fun toMap() = mapOf("k_audio" to kAudio, "s_audio" to sAudio, "k_sync" to kSync, "s_sync" to sSync)

    companion object {
        fun random() = AudioKeys(Crypto.randomBytes(32).hex(), Crypto.randomBytes(4).hex(), Crypto.randomBytes(32).hex(), Crypto.randomBytes(4).hex())
    }
}

fun audioPacket(index: Long, t0: Long, codec: Int, payload: ByteArray): ByteArray =
    ByteBuffer.allocate(17 + payload.size).putLong(index).putLong(t0).put(codec.toByte()).put(payload).array()

// ---- codecs -----------------------------------------------------------------

/** Encodes 10 ms of interleaved stereo float [-1, 1] (or a part of it). */
interface Encoder {
    val codec: Int
    fun encode(frame: FloatArray, off: Int, frames: Int): ByteArray?
    fun close() {}
}

/** Decodes a payload to interleaved stereo floats, or null. */
interface Decoder {
    fun decode(payload: ByteArray): FloatArray?
    fun close() {}
}

object Pcm : Encoder, Decoder {
    override val codec = CODEC_PCM

    override fun close() {}

    override fun encode(frame: FloatArray, off: Int, frames: Int): ByteArray {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * 2) b.putShort((frame[off * 2 + i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        return b.array()
    }

    override fun decode(payload: ByteArray): FloatArray? {
        if (payload.size % 4 != 0) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(payload.size / 2) { b.short / 32768f }
    }
}

// ---- receiver side ------------------------------------------------------------

/** Estimate of sender_time - local_time from ping replies. */
class ClockSync {
    private val samples = ArrayDeque<Pair<Long, Long>>()     // (rtt, offset)
    @Volatile var offset: Long? = null
        private set
    @Volatile var rtt = 0L
        private set

    @Synchronized
    fun add(t1: Long, t2: Long, t3: Long) {
        val r = t3 - t1
        if (r < 0 || r > NS) return
        samples.addLast(r to (t2 - (t1 + t3) / 2))
        while (samples.size > 24) samples.removeFirst()
        val best = samples.sortedBy { it.first }.take(max(1, samples.size / 4))
        offset = best.sumOf { it.second } / best.size
        rtt = best[0].first
    }
}

/** Samples by absolute index in a ring; missing samples read as silence. */
class JitterBuffer(seconds: Double = 4.0) {
    private val n = (RATE * seconds).toInt()
    private val data = FloatArray(n * 2)
    private val stamp = LongArray(n) { -1 }

    @Synchronized
    fun write(idx: Long, pcm: FloatArray) {
        val k = pcm.size / 2
        for (j in 0 until k) {
            val i = idx + j
            val p = Math.floorMod(i, n.toLong()).toInt()
            data[p * 2] = pcm[j * 2]
            data[p * 2 + 1] = pcm[j * 2 + 1]
            stamp[p] = i
        }
    }

    /** [count] stereo samples starting at fractional index [start], stepping by [ratio] (linear interpolation). */
    @Synchronized
    fun read(start: Double, count: Int, ratio: Double, out: FloatArray, gain: Float) {
        for (j in 0 until count) {
            val x = start + j * ratio
            val i0 = floor(x).toLong()
            val frac = (x - i0).toFloat()
            val p0 = Math.floorMod(i0, n.toLong()).toInt()
            val p1 = Math.floorMod(i0 + 1, n.toLong()).toInt()
            val aOk = stamp[p0] == i0
            val bOk = stamp[p1] == i0 + 1
            val aL = if (aOk) data[p0 * 2] else 0f
            val aR = if (aOk) data[p0 * 2 + 1] else 0f
            val bL = if (bOk) data[p1 * 2] else aL
            val bR = if (bOk) data[p1 * 2 + 1] else aR
            out[j * 2] = (aL * (1 - frac) + bL * frac) * gain
            out[j * 2 + 1] = (aR * (1 - frac) + bR * frac) * gain
        }
    }
}

/**
 * Receives, decrypts and decodes one incoming stream (desktop Receiver). The audio output calls
 * [render] with the local time its first sample will reach the speaker.
 */
class Receiver(keys: AudioKeys, private val sender: InetSocketAddress, delayMs: Int, volume: Int, private val decoders: (Int) -> Decoder?) {
    private val rx = Channel(keys.kAudio.unhex(), keys.sAudio.unhex())
    private val tx = Channel(keys.kSync.unhex(), keys.sSync.unhex())
    val sock = DatagramSocket(0).apply { receiveBufferSize = 1 shl 20 }
    val port: Int = sock.localPort
    val clock = ClockSync()
    val buf = JitterBuffer()
    @Volatile var delayNs = delayMs * 1_000_000L
    @Volatile var volume = volume
    private val decs = ConcurrentHashMap<Int, Decoder>()
    @Volatile var t0: Long? = null
    private var pos: Double? = null
    private var ratio = 1.0
    @Volatile var lastPacket = 0L
    @Volatile var packets = 0L
    @Volatile var bytes = 0L
    @Volatile var codec = 0
    @Volatile private var stopped = false
    private val threads = listOf(Thread(::recvLoop, "audio-recv"), Thread(::pingLoop, "audio-ping"))

    fun start() = threads.forEach { it.isDaemon = true; it.start() }

    fun stop() {
        stopped = true
        sock.close()
        decs.values.forEach { runCatching { it.close() } }
    }

    private fun pingLoop() {
        var n = 0
        while (!stopped) {
            runCatching {
                val p = tx.seal(T_SYNC_REQ, ByteBuffer.allocate(8).putLong(nowNs()).array())
                sock.send(DatagramPacket(p, p.size, sender))
            }
            n++
            Thread.sleep(if (n < 20) 50 else 500)
        }
    }

    private fun recvLoop() {
        val data = ByteArray(4096)
        while (!stopped) {
            val pk = DatagramPacket(data, data.size)
            try {
                sock.receive(pk)
            } catch (_: Exception) {
                if (stopped) return else continue
            }
            val (type, plain) = rx.open(pk.data, pk.length) ?: continue
            if (type == T_SYNC_RESP && plain.size == 16) {
                val b = ByteBuffer.wrap(plain)
                clock.add(b.long, b.long, nowNs())
            } else if (type == T_AUDIO && plain.size > 17) {
                val b = ByteBuffer.wrap(plain)
                val idx = b.long
                val st0 = b.long
                val c = b.get().toInt()
                val dec = decs[c] ?: decoders(c)?.also { decs[c] = it } ?: continue
                val pcm = dec.decode(plain.copyOfRange(17, plain.size)) ?: continue
                synchronized(this) {
                    if (t0 != st0) {          // the sender (re)started its timeline
                        t0 = st0
                        pos = null
                    }
                }
                buf.write(idx, pcm)
                packets++
                bytes += pk.length
                codec = c
                lastPacket = System.currentTimeMillis()
            }
        }
    }

    val ready get() = t0 != null && clock.offset != null

    /** Sample index that should be heard at local time [playLocalNs]. */
    private fun targetIndex(playLocalNs: Long): Double {
        val senderTime = playLocalNs + clock.offset!! - delayNs
        return (senderTime - t0!!).toDouble() * RATE / NS
    }

    /** [count] stereo samples into [out] that start playing at [playLocalNs]. */
    @Synchronized
    fun render(count: Int, playLocalNs: Long, out: FloatArray) {
        if (!ready) {
            out.fill(0f, 0, count * 2)
            return
        }
        val target = targetIndex(playLocalNs)
        val p = pos
        if (p == null || abs(target - p) > RATE * 0.08) {
            pos = target                          // (re)lock: jump
            ratio = 1.0
        } else {
            val err = target - p                  // positive: we are late
            ratio = 1.0 + max(-0.003, min(0.003, err / (RATE * 0.5)))
        }
        val g = (volume.coerceIn(0, 100) / 100.0).pow(1.6).toFloat()
        buf.read(pos!!, count, ratio, out, g)
        pos = pos!! + count * ratio
    }
}

// ---- sender side ----------------------------------------------------------------

class Target(val dev: String, val addr: InetSocketAddress, keys: AudioKeys) {
    val tx = Channel(keys.kAudio.unhex(), keys.sAudio.unhex())
    val rx = Channel(keys.kSync.unhex(), keys.sSync.unhex())
}

/**
 * Sends captured audio to every target on a fixed clock and answers clock pings (desktop Sender).
 * Capture pushes interleaved stereo 48 kHz floats with [push].
 */
class Sender(@Volatile var encoder: Encoder) {
    val sock = DatagramSocket(0).apply { sendBufferSize = 1 shl 20 }
    val port: Int = sock.localPort
    val targets = ConcurrentHashMap<String, Target>()
    private val queue = ArrayDeque<FloatArray>()
    private var pending = FloatArray(0)
    @Volatile private var stopped = false
    @Volatile var t0 = 0L
    @Volatile var index = 0L
    @Volatile var level = 0f
    private val threads = listOf(Thread(::run, "audio-send"), Thread(::serveSync, "audio-sync"))

    companion object {
        const val PRELOAD = 2
        const val MAX_BACKLOG = 6
    }

    fun start() = threads.forEach { it.isDaemon = true; it.start() }

    fun stop() {
        stopped = true
        sock.close()
        runCatching { encoder.close() }
    }

    fun addTarget(dev: String, addr: InetSocketAddress, keys: AudioKeys) { targets[dev] = Target(dev, addr, keys) }
    fun removeTarget(dev: String) = targets.remove(dev)

    /** Capture callback (any thread): interleaved stereo at 48 kHz, any length. */
    fun push(pcm: FloatArray, len: Int = pcm.size) {
        synchronized(queue) {
            val all = FloatArray(pending.size + len)
            System.arraycopy(pending, 0, all, 0, pending.size)
            System.arraycopy(pcm, 0, all, pending.size, len)
            var o = 0
            while (all.size - o >= FRAME * 2) {
                queue.addLast(all.copyOfRange(o, o + FRAME * 2))
                o += FRAME * 2
            }
            pending = all.copyOfRange(o, all.size)
        }
    }

    private fun nextFrame(): FloatArray = synchronized(queue) {
        if (queue.size > MAX_BACKLOG) repeat(queue.size - PRELOAD) { queue.removeFirst() }
        queue.pollFirst() ?: FloatArray(FRAME * 2)
    }

    private fun run() {
        val period = FRAME * NS / RATE
        val deadline = System.currentTimeMillis() + 200
        while (synchronized(queue) { queue.size } < PRELOAD && System.currentTimeMillis() < deadline && !stopped) Thread.sleep(2)
        t0 = nowNs()
        index = 0
        val frames = ArrayList<FloatArray>()
        while (!stopped) {
            val due = t0 + index * NS / RATE
            val wait = due - nowNs()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            val f = nextFrame()
            var peak = 0f
            for (v in f) peak = max(peak, abs(v))
            level = peak
            frames += f
            // codecs with 20 ms frames (Android's Opus) send every second tick
            val per = (encoderFrames() / FRAME).coerceAtLeast(1)
            if (frames.size >= per) {
                val joined = if (per == 1) frames[0] else FloatArray(per * FRAME * 2).also { j -> frames.forEachIndexed { i, x -> System.arraycopy(x, 0, j, i * FRAME * 2, FRAME * 2) } }
                send(joined, index - (per - 1) * FRAME, per * FRAME)
                frames.clear()
            }
            index += FRAME
            val behind = nowNs() - (t0 + index * NS / RATE)
            if (behind > 20 * period) {           // stalled (sleep, debugger): skip ahead
                index += (behind / period) * FRAME
                frames.clear()
            }
        }
    }

    /** Samples per packet the encoder wants: 480 (10 ms) for desktop-compatible PCM / Opus, 960 for 20 ms Opus. */
    private fun encoderFrames(): Int = (encoder as? FrameSize)?.frameSamples ?: FRAME

    private fun send(frame: FloatArray, startIndex: Long, frames: Int) {
        val enc = encoder
        val parts = if (enc.codec == CODEC_PCM) {
            // 5 ms packets: no IP fragmentation
            (0 until frames step FRAME / 2).map { o -> (startIndex + o) to enc.encode(frame, o, min(FRAME / 2, frames - o)) }
        } else listOf(startIndex to enc.encode(frame, 0, frames))
        for ((idx, payload) in parts) {
            if (payload == null || payload.isEmpty()) continue
            val plain = audioPacket(idx, t0, enc.codec, payload)
            for (t in targets.values) runCatching {
                val p = t.tx.seal(T_AUDIO, plain)
                sock.send(DatagramPacket(p, p.size, t.addr))
            }
        }
    }

    private fun serveSync() {
        val data = ByteArray(2048)
        while (!stopped) {
            val pk = DatagramPacket(data, data.size)
            try {
                sock.receive(pk)
            } catch (_: Exception) {
                if (stopped) return else continue
            }
            val t2 = nowNs()
            val from = pk.address.hostAddress
            for (t in targets.values) {
                if (t.addr.address.hostAddress != from) continue
                val got = t.rx.open(pk.data, pk.length) ?: continue
                if (got.first == T_SYNC_REQ && got.second.size == 8) {
                    val t1 = ByteBuffer.wrap(got.second).long
                    val reply = t.tx.seal(T_SYNC_RESP, ByteBuffer.allocate(16).putLong(t1).putLong(t2).array())
                    runCatching { sock.send(DatagramPacket(reply, reply.size, pk.socketAddress)) }
                    break
                }
            }
        }
    }
}

/** Encoders with frames other than 10 ms say how many samples per packet they take. */
interface FrameSize {
    val frameSamples: Int
}
