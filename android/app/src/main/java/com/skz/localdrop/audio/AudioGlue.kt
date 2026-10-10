package com.skz.localdrop.audio

import android.util.Log
import com.skz.localdrop.R
import com.skz.localdrop.core.AudioOutput
import com.skz.localdrop.core.AudioState
import com.skz.localdrop.core.Core
import com.skz.localdrop.core.IncomingAudio
import com.skz.localdrop.core.TAG
import com.skz.localdrop.protocol.audio.AudioKeys
import com.skz.localdrop.protocol.audio.CODEC_OPUS
import com.skz.localdrop.protocol.audio.CODEC_PCM
import com.skz.localdrop.protocol.audio.Pcm
import com.skz.localdrop.protocol.audio.Receiver
import com.skz.localdrop.protocol.net.Conn
import com.skz.localdrop.protocol.net.Device as PeerDevice
import com.skz.localdrop.protocol.net.Msg
import com.skz.localdrop.protocol.net.Reply
import com.skz.localdrop.protocol.net.bool
import com.skz.localdrop.protocol.net.fail
import com.skz.localdrop.protocol.net.int
import com.skz.localdrop.protocol.net.obj
import com.skz.localdrop.protocol.net.ok
import com.skz.localdrop.protocol.net.str
import com.skz.localdrop.protocol.net.toJson
import com.skz.localdrop.service.LocalDropService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

/**
 * Audio on the phone (desktop/localdrop/audio/service.py): playing a stream from a computer
 * and streaming the phone's sound to computers. Control messages:
 *     audio_start {session, keys, port, delay, vol, out} -> {ok, port, outputs}
 *     audio_update {session, delay?, vol?, out?} | audio_stop {session} | audio_outputs {} -> {outputs}
 *     audio_stopped {session} (receiver -> sender) | audio_request / audio_release (receiver -> sender)
 */
class AudioGlue(private val core: Core) {
    private val ctx = core.ctx

    private class Incoming(val dev: String, val session: String, val rec: Receiver, var player: Player, var vol: Int, var out: String, val started: Long)

    @Volatile private var incoming: Incoming? = null
    @Volatile private var listening = ""                 // device we asked to stream to us
    @Volatile private var localOut = ""                  // output picked on the phone
    @Volatile private var error = ""

    init {
        core.peers.on("audio_start", ::onStart)
        core.peers.on("audio_update", ::onUpdate)
        core.peers.on("audio_stop", ::onStop)
        core.peers.on("audio_outputs") { _, _, _ -> ok("outputs" to outputNames()) }
    }

    fun start() {
        core.scope.launch {
            while (isActive) {
                delay(2000)
                val inc = incoming
                val now = System.currentTimeMillis()
                if (inc != null && now - inc.rec.lastPacket > 15_000 && now - inc.started > 15_000) {
                    Log.i(TAG, "incoming audio timed out")
                    stopIncoming(notify = false)
                }
                if (inc != null) {
                    core.publish()                       // bitrate and connection state
                    Log.d(TAG, "audio in: packets=${inc.rec.packets} ready=${inc.rec.ready} rtt=${inc.rec.clock.rtt / 1000} us codec=${inc.rec.codec}")
                }
            }
        }
    }

    private fun outputNames() = Outputs.list(ctx).map { it.first }

    fun view(): AudioState {
        val inc = incoming
        val kbps = inc?.let { i ->
            val secs = (System.currentTimeMillis() - i.started) / 1000.0
            if (secs > 1) (i.rec.bytes * 8 / secs / 1000).toInt() else 0
        } ?: 0
        val bitrate = when (inc?.rec?.codec) {
            CODEC_PCM -> "PCM · $kbps " + core.str(R.string.kbps)
            CODEC_OPUS -> "Opus · $kbps " + core.str(R.string.kbps)
            else -> ""
        }
        return AudioState(
            error = error,
            latency = inc?.let { (it.rec.delayNs / 1_000_000).toInt() + 30 } ?: 0,
            incoming = inc?.let { IncomingAudio(it.dev, it.vol, it.out.ifEmpty { localOut }, it.rec.ready, bitrate) },
            listening = listening,
            outputs = Outputs.list(ctx).map { (name, d) -> AudioOutput(name, name, Outputs.type(d)) },
        )
    }

    // ---- receiving --------------------------------------------------------
    private suspend fun onStart(d: PeerDevice, conn: Conn, msg: Msg): Reply {
        if (!core.settings.value.featAudio) return fail("disabled")
        if (!d.trusted) return fail("not trusted")
        val k = msg.header["keys"] as? kotlinx.serialization.json.JsonObject ?: return fail("bad request")
        val keys = AudioKeys(k.str("k_audio") ?: return fail("bad request"), k.str("s_audio") ?: "", k.str("k_sync") ?: "", k.str("s_sync") ?: "")
        val port = msg.header.int("port") ?: return fail("bad request")
        stopIncoming(notify = false)
        val ip = d.ip.ifEmpty { conn.peerIp }
        val vol = msg.header.int("vol") ?: 60
        val out = msg.header.str("out") ?: ""
        return withContext(Dispatchers.IO) {
            // debug builds listen on a fixed port so the emulator can get UDP through `adb emu redir`
            val local = if (com.skz.localdrop.BuildConfig.DEBUG) 53390 else 0
            val rec = Receiver(keys, InetSocketAddress(ip, port), msg.header.int("delay") ?: 60, vol, local) { c ->
                when (c) {
                    CODEC_PCM -> Pcm
                    CODEC_OPUS -> runCatching { OpusDecoder() }.getOrNull()
                    else -> null
                }
            }
            val player = try {
                Player(rec, ctx, out.ifEmpty { localOut }).also { it.start() }
            } catch (e: Exception) {
                rec.stop()
                Log.e(TAG, "audio output failed", e)
                return@withContext fail(e.message ?: "output")
            }
            rec.start()
            incoming = Incoming(d.id, msg.header.str("session") ?: "", rec, player, vol, out, System.currentTimeMillis())
            LocalDropService.playing(ctx, true)
            core.publish()
            core.toast("audio", R.string.t_audio_in, core.str(R.string.t_audio_in_text, d.name))
            ok("port" to rec.port, "outputs" to outputNames())
        }
    }

    private suspend fun onUpdate(d: PeerDevice, conn: Conn, msg: Msg): Reply? {
        val inc = incoming ?: return null
        if (inc.dev != d.id || inc.session != msg.header.str("session")) return null
        msg.header.int("vol")?.let { inc.vol = it; inc.rec.volume = it }
        msg.header.int("delay")?.let { inc.rec.delayNs = it * 1_000_000L }
        val out = msg.header.str("out") ?: ""
        if (out != inc.out) switchOutput(inc, out)
        core.publish()
        return null
    }

    private fun switchOutput(inc: Incoming, out: String) {
        inc.player.stop()
        inc.player = Player(inc.rec, ctx, out.ifEmpty { localOut }).also { it.start() }
        inc.out = out
    }

    private suspend fun onStop(d: PeerDevice, conn: Conn, msg: Msg): Reply? {
        val inc = incoming ?: return null
        if (inc.dev == d.id && inc.session == msg.header.str("session")) {
            stopIncoming(notify = false)
            if (listening == d.id) listening = ""
        }
        return null
    }

    private fun stopIncoming(notify: Boolean) {
        val inc = incoming ?: return
        incoming = null
        runCatching { inc.player.stop() }
        inc.rec.stop()
        LocalDropService.playing(ctx, false)
        history("in", inc.dev, (System.currentTimeMillis() - inc.started) / 1000, null)
        if (notify) core.scope.launch { core.peers.send(inc.dev, obj("type" to "audio_stopped", "session" to inc.session)) }
        core.publish()
    }

    fun history(dir: String, dev: String, seconds: Long, quality: String?) {
        if (seconds < 5) return
        val args = mutableListOf<Any>(mapOf("dur" to seconds))
        if (quality != null) args += "q_$quality"
        val sub = mapOf("k" to if (quality != null) "h_audio_q" else "h_audio", "a" to args)
        core.storage.addHistory("audio", dir, core.str(R.string.h_audio_title), toJson(sub).toString(), dev)
        core.historyChanged()
    }

    // ---- UI: listening ----------------------------------------------------
    /** "Listen": ask the computer to stream to this phone. */
    fun listen(dev: String) {
        core.scope.launch {
            val prev = listening
            if (prev.isNotEmpty() && prev != dev) release(prev)
            listening = dev
            error = ""
            core.publish()
            val r = runCatching { core.peers.request(dev, obj("type" to "audio_request", "vol" to (incoming?.vol ?: 70)), timeoutMs = 10_000).header }.getOrNull()
            if (r?.bool("ok") != true) {
                listening = ""
                val e = r?.str("error")
                val text = core.str(when (e) {
                    "not trusted" -> R.string.e_audio_untrusted
                    "disabled" -> R.string.e_audio_disabled
                    null -> R.string.e_audio_no_answer
                    else -> R.string.e_audio_refused
                })
                core.toast("bad", R.string.t_audio_failed, if (e != null && e !in setOf("not trusted", "disabled")) "$text: $e" else text)
                core.publish()
            }
        }
    }

    private suspend fun release(dev: String) {
        core.peers.send(dev, obj("type" to "audio_release"))
    }

    fun listenStop() {
        val dev = listening.ifEmpty { incoming?.dev ?: "" }
        listening = ""
        val inc = incoming
        core.scope.launch {
            if (dev.isNotEmpty()) release(dev)
            stopIncoming(notify = inc != null && inc.dev != dev)
        }
        core.publish()
    }

    fun incomingStop() {
        listening = ""
        stopIncoming(notify = true)
    }

    fun setOutput(name: String) {
        localOut = name
        incoming?.let { switchOutput(it, name) }
        core.publish()
    }

    fun setVolume(vol: Int) {
        incoming?.let { it.vol = vol; it.rec.volume = vol }
        core.publish()
    }

    fun stop() = stopIncoming(notify = false)
}
