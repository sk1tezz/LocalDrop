package com.skz.localdrop.protocol.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusSignal

/**
 * Opus in pure Java (Concentus, a port of libopus). Decoding right on the network thread
 * costs well under a millisecond a packet; MediaCodec needed several calls into the media
 * process per packet and fell behind 100 packets a second.
 */
class OpusDecoder : Decoder {
    private val dec = io.github.jaredmdobson.concentus.OpusDecoder(RATE, 2)
    private val pcm = ShortArray(5760 * 2)          // 120 ms, the longest Opus packet

    @Synchronized
    override fun decode(index: Long, payload: ByteArray): List<Pair<Long, FloatArray>> {
        val n = try {
            dec.decode(payload, 0, payload.size, pcm, 0, 5760, false)
        } catch (_: Exception) {
            return emptyList()
        }
        if (n <= 0) return emptyList()
        return listOf(index to FloatArray(n * 2) { pcm[it] / 32768f })
    }
}

/** 10 ms packets like the desktop: [frameSamples] tells the sender to hand over 480 samples at a time. */
class OpusEncoder(bitrate: Int) : Encoder, FrameSize {
    override val codec = CODEC_OPUS
    override val frameSamples = 480
    private val enc = io.github.jaredmdobson.concentus.OpusEncoder(RATE, 2, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
        setBitrate(bitrate)
        setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC)
        setComplexity(5)                            // phones: half the CPU of 10, barely audible
    }
    private val pcm = ShortArray(480 * 2)
    private val out = ByteArray(1500)

    @Synchronized
    override fun encode(frame: FloatArray, off: Int, frames: Int, index: Long): List<Pair<Long, ByteArray>> {
        for (k in 0 until frames * 2) pcm[k] = (frame[off * 2 + k] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        val n = try {
            enc.encode(pcm, 0, frames, out, 0, out.size)
        } catch (_: Exception) {
            return emptyList()
        }
        return if (n > 0) listOf(index to out.copyOf(n)) else emptyList()
    }
}
