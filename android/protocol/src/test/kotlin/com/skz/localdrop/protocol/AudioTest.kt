package com.skz.localdrop.protocol

import com.skz.localdrop.protocol.audio.AudioKeys
import com.skz.localdrop.protocol.audio.CODEC_PCM
import com.skz.localdrop.protocol.audio.Channel
import com.skz.localdrop.protocol.audio.Pcm
import com.skz.localdrop.protocol.audio.Receiver
import com.skz.localdrop.protocol.audio.Sender
import com.skz.localdrop.protocol.audio.T_AUDIO
import com.skz.localdrop.protocol.audio.nowNs
import com.skz.localdrop.protocol.crypto.hex
import com.skz.localdrop.protocol.crypto.unhex
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun str(e: kotlinx.serialization.json.JsonElement) = (e as JsonPrimitive).content
    private val keys = AudioKeys("11".repeat(32), "a1b2c3d4", "22".repeat(32), "01020304")
    private val st = "__import__('localdrop.audio.stream', fromlist=['x'])"
    // a sine "capture" that follows the clock like a real one (sleep-paced loops fall behind)
    private val FEEDER = "!exec(\"def feed():\\n  n=0; t0=time.perf_counter()\\n  while not stop.is_set():\\n    while n < (time.perf_counter()-t0)*48000+960:\\n      snd.on_audio(np.stack([0.5*np.sin(2*np.pi*440*(n+np.arange(480))/48000)]*2,1).astype(np.float32), 48000); n+=480\\n    time.sleep(0.005)\\nthreading.Thread(target=feed, daemon=True).start()\", globals())"
    private val pyKeys = "{'k_audio': '${keys.kAudio}', 's_audio': '${keys.sAudio}', 'k_sync': '${keys.kSync}', 's_sync': '${keys.sSync}'}"

    private fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "condition not met" }
            Thread.sleep(20)
        }
    }

    @Test
    fun datagramsMatchTheDesktop() {
        val (py, _, _) = pyNode(tmp.newFolder("desk"))
        py.use {
            val plain = "00000000000001e0000000000000002a02abcdef"
            // desktop seals, Kotlin opens
            val sealed = str(py.cmd("$st.Channel(bytes.fromhex('${keys.kAudio}'), bytes.fromhex('${keys.sAudio}')).seal(1, bytes.fromhex('$plain')).hex()"))
            val got = Channel(keys.kAudio.unhex(), keys.sAudio.unhex()).open(sealed.unhex())!!
            assertEquals(T_AUDIO, got.first)
            assertArrayEquals(plain.unhex(), got.second)
            // Kotlin seals, desktop opens
            val mine = Channel(keys.kSync.unhex(), keys.sSync.unhex()).seal(2, plain.unhex()).hex()
            val back = py.cmd("(lambda r: [r[0], r[1].hex()])($st.Channel(bytes.fromhex('${keys.kSync}'), bytes.fromhex('${keys.sSync}')).open(bytes.fromhex('$mine')))")
            assertEquals("[2,\"$plain\"]", back.toString())
        }
    }

    private fun rms(x: FloatArray) = sqrt(x.map { it * it }.average()).toFloat()

    @Test
    fun desktopStreamsToKotlin() {
        val (py, _, _) = pyNode(tmp.newFolder("desk"))
        py.use {
            py.cmd("!import threading, time, numpy as np")
            py.cmd("!snd = $st.Sender('hq'); snd.start()")
            py.cmd("!stop = threading.Event()")
            py.cmd(FEEDER)
            val port = str(py.cmd("snd.port")).toInt()
            val rec = Receiver(keys, InetSocketAddress("127.0.0.1", port), 60, 100) { c -> if (c == CODEC_PCM) Pcm else null }
            rec.start()
            py.cmd("snd.add_target('phone', ('127.0.0.1', ${rec.port}), $pyKeys)")
            waitFor { rec.ready && rec.packets > 100 }
            val out = FloatArray(4800 * 2)
            rec.render(4800, nowNs(), out)
            assertTrue("rms ${rms(out)}", rms(out) in 0.25f..0.45f)       // a 0.5 sine has rms 0.354
            assertTrue(rec.clock.rtt < 50_000_000)
            py.cmd("!stop.set(); snd.stop()")
            rec.stop()
        }
    }

    /** Clock pings lost (e.g. a firewall on the computer): the receiver still plays. */
    @Test
    fun playsWithoutClockReplies() {
        val (py, _, _) = pyNode(tmp.newFolder("desk"))
        py.use {
            py.cmd("!import threading, time, numpy as np")
            py.cmd("!snd = $st.Sender('hq'); snd.start()")
            py.cmd("!stop = threading.Event()")
            py.cmd(FEEDER)
            val port = str(py.cmd("snd.port")).toInt()
            // pings go to a port nobody answers on
            val rec = Receiver(keys, InetSocketAddress("127.0.0.1", port + 1), 60, 100) { c -> if (c == CODEC_PCM) Pcm else null }
            rec.start()
            py.cmd("snd.add_target('phone', ('127.0.0.1', ${rec.port}), $pyKeys)")
            waitFor { rec.ready && rec.packets > 200 }
            assertTrue(rec.estimatedClock)
            assertEquals(0L, rec.syncReplies)
            val out = FloatArray(4800 * 2)
            rec.render(4800, nowNs(), out)
            assertTrue("rms ${rms(out)}", rms(out) in 0.25f..0.45f)
            py.cmd("!stop.set(); snd.stop()")
            rec.stop()
        }
    }

    @Test
    fun kotlinStreamsToDesktop() {
        val (py, _, _) = pyNode(tmp.newFolder("desk"))
        py.use {
            val snd = Sender(Pcm)
            snd.start()
            val feeder = Thread {
                // like a real capture: as many samples as the clock says, in 10 ms chunks
                var n = 0L
                val start = System.nanoTime()
                val f = FloatArray(960)
                while (!Thread.currentThread().isInterrupted) {
                    while (n < (System.nanoTime() - start) * 48 / 1_000_000 + 960) {
                        for (i in 0 until 480) {
                            val v = (0.5 * sin(2 * PI * 440 * (n + i) / 48000)).toFloat()
                            f[i * 2] = v; f[i * 2 + 1] = v
                        }
                        n += 480
                        snd.push(f)
                    }
                    try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
            py.cmd("!rec = $st.Receiver($pyKeys, ('127.0.0.1', ${snd.port}), 60, 100); rec.start()")
            snd.addTarget("pc", InetSocketAddress("127.0.0.1", str(py.cmd("rec.port")).toInt()), keys)
            py.waitFor("rec.ready and rec.packets > 100", 10_000)
            val r = str(py.cmd("float(__import__('numpy').sqrt((rec.render(4800, $st.now_ns())**2).mean()))")).toFloat()
            assertTrue("rms $r", r in 0.25f..0.45f)
            py.cmd("!rec.stop()")
            feeder.interrupt()
            snd.stop()
        }
    }
}
