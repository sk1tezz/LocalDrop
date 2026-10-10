package com.skz.localdrop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skz.localdrop.audio.OpusDecoder
import com.skz.localdrop.audio.OpusEncoder
import com.skz.localdrop.protocol.crypto.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Opus through MediaCodec against packets made by the desktop (PyAV / libopus), on a device. */
@RunWith(AndroidJUnit4::class)
class OpusTest {
    private fun rms(chunks: List<FloatArray>): Double {
        val all = chunks.flatMap { it.asList() }
        return sqrt(all.sumOf { (it * it).toDouble() } / all.size)
    }

    @Test
    fun decodesDesktopPackets() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val packets = ctx.assets.open("opus_desktop.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map { it.unhex() }
        val dec = OpusDecoder()
        val out = ArrayList<Pair<Long, FloatArray>>()
        packets.forEachIndexed { i, p -> out += dec.decode(i * 480L, p) }
        dec.close()
        assertTrue("decoded ${out.size} of ${packets.size}", out.size >= packets.size - 3)
        assertEquals(960, out[10].second.size)                   // 10 ms of stereo
        assertEquals(10 * 480L, out[10].first)                   // the sample index survives the codec
        val r = rms(out.drop(5).map { it.second })
        assertTrue("rms $r", r in 0.3..0.4)                       // a 0.5 sine
    }

    @Test
    fun encodesWhatTheDecoderReads() {
        val enc = OpusEncoder(192_000)
        val dec = OpusDecoder()
        val frame = FloatArray(960 * 2)
        val decoded = ArrayList<FloatArray>()
        var packets = 0
        for (n in 0 until 60) {
            for (i in 0 until 960) {
                val v = (0.5 * sin(2 * PI * 440 * (n * 960 + i) / 48000)).toFloat()
                frame[i * 2] = v; frame[i * 2 + 1] = v
            }
            for ((idx, p) in enc.encode(frame, 0, 960, n * 960L)) {
                packets++
                assertEquals(0L, idx % 960)
                decoded += dec.decode(idx, p).map { it.second }
            }
        }
        enc.close(); dec.close()
        assertTrue("packets $packets", packets >= 50)
        val r = rms(decoded.drop(5))
        assertTrue("rms $r", r in 0.3..0.4)
    }
}
