package com.skz.localdrop.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import com.skz.localdrop.protocol.audio.CODEC_OPUS
import com.skz.localdrop.protocol.audio.Decoder
import com.skz.localdrop.protocol.audio.Encoder
import com.skz.localdrop.protocol.audio.FrameSize
import com.skz.localdrop.protocol.audio.RATE
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opus through MediaCodec (Android 10+ ships c2.android.opus.decoder / encoder). */
private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

private fun usOf(index: Long) = index * 1_000_000L / RATE

/** OpusHead (RFC 7845) plus the two timing buffers MediaCodec wants. */
private fun opusFormat(): MediaFormat = MediaFormat.createAudioFormat(MIME, RATE, 2).apply {
    val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray()).put(1).put(2).putShort(0).putInt(RATE).putShort(0).put(0)
    head.flip()
    setByteBuffer("csd-0", head)
    setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).flip() as ByteBuffer)
    setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).flip() as ByteBuffer)
}

/** Packets go in with their sample index as the timestamp; output keeps it, so late output still lands in the right place. */
private class Indexes {
    private val map = LinkedHashMap<Long, Long>()
    fun put(us: Long, index: Long) {
        map[us] = index
        while (map.size > 64) map.remove(map.keys.first())
    }
    fun take(us: Long): Long = map.remove(us) ?: (us * RATE / 1_000_000L)
}

class OpusDecoder : Decoder {
    private val codec = MediaCodec.createDecoderByType(MIME).apply {
        configure(opusFormat(), null, null, 0)
        start()
    }
    private val idx = Indexes()
    private val info = MediaCodec.BufferInfo()

    @Synchronized
    override fun decode(index: Long, payload: ByteArray): List<Pair<Long, FloatArray>> {
        val i = codec.dequeueInputBuffer(5_000)
        if (i >= 0) {
            codec.getInputBuffer(i)!!.apply { clear(); put(payload) }
            val us = usOf(index)
            idx.put(us, index)
            codec.queueInputBuffer(i, 0, payload.size, us, 0)
        }
        return drain()
    }

    private fun drain(): List<Pair<Long, FloatArray>> {
        val out = ArrayList<Pair<Long, FloatArray>>()
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 2_000)
            if (o < 0) {
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                break
            }
            val buf = codec.getOutputBuffer(o)!!
            buf.position(info.offset).limit(info.offset + info.size)
            val float = codec.outputFormat.let { f -> f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT }
            val pcm = if (float) {
                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                FloatArray(fb.remaining()).also { fb.get(it) }
            } else {
                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                FloatArray(sb.remaining()) { sb.get() / 32768f }
            }
            codec.releaseOutputBuffer(o, false)
            if (pcm.isNotEmpty()) out += idx.take(info.presentationTimeUs) to pcm
        }
        return out
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/** 20 ms Opus packets; [frameSamples] tells the sender to hand over 960 samples at a time. */
class OpusEncoder(bitrate: Int) : Encoder, FrameSize {
    override val codec = CODEC_OPUS
    override val frameSamples = 960
    private val mc = MediaCodec.createEncoderByType(MIME).apply {
        configure(MediaFormat.createAudioFormat(MIME, RATE, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 960 * 4 * 2)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val idx = Indexes()
    private val info = MediaCodec.BufferInfo()

    @Synchronized
    override fun encode(frame: FloatArray, off: Int, frames: Int, index: Long): List<Pair<Long, ByteArray>> {
        val i = mc.dequeueInputBuffer(5_000)
        if (i >= 0) {
            val b = mc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
            b.clear()
            for (k in 0 until frames * 2) b.putShort((frame[off * 2 + k] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
            val us = usOf(index)
            idx.put(us, index)
            mc.queueInputBuffer(i, 0, frames * 4, us, 0)
        }
        val out = ArrayList<Pair<Long, ByteArray>>()
        while (true) {
            val o = mc.dequeueOutputBuffer(info, 1_000)
            if (o < 0) {
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                break
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val buf = mc.getOutputBuffer(o)!!
                buf.position(info.offset).limit(info.offset + info.size)
                out += idx.take(info.presentationTimeUs) to ByteArray(info.size).also { buf.get(it) }
            }
            mc.releaseOutputBuffer(o, false)
        }
        return out
    }

    override fun close() {
        runCatching { mc.stop() }
        runCatching { mc.release() }
    }
}
