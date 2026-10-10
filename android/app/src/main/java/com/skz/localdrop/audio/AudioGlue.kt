package com.skz.localdrop.audio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.skz.localdrop.core.AudioTarget
import com.skz.localdrop.core.RemoteAudio
import com.skz.localdrop.core.UiEvent
import com.skz.localdrop.protocol.audio.Sender
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import com.skz.localdrop.R
import com.skz.localdrop.core.AudioOutput
import com.skz.localdrop.core.AudioState
import com.skz.localdrop.core.Core
import com.skz.localdrop.core.IncomingAudio
import com.skz.localdrop.core.TAG
import com.skz.localdrop.protocol.audio.AudioKeys
import com.skz.localdrop.protocol.audio.CODEC_OPUS
import com.skz.localdrop.protocol.audio.CODEC_PCM
import com.skz.localdrop.protocol.audio.OpusDecoder
import com.skz.localdrop.protocol.audio.OpusEncoder
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

    // ---- sending state ----------------------------------------------------
    private class Session(val id: String, var vol: Int, var out: String, var delay: Int, val started: Long, val quality: String)

    @Volatile var on = false
        private set
    private var sender: Sender? = null
    private var capture: Capture? = null
    private var captureKey = ""                          // source + projection the capture was made for
    private val sessions = ConcurrentHashMap<String, Session>()
    private val remote = ConcurrentHashMap<String, RemoteAudio>()
    private val lock = Mutex()
    @Volatile private var projection: MediaProjection? = null

    init {
        core.peers.on("audio_stopped", ::onStopped)
        core.peers.onlineListeners.add { _, _ -> core.scope.launch { reconcile() } }
        core.settings.onChange { old, new ->
            if (old.audioTargets != new.audioTargets || old.audioQuality != new.audioQuality || old.audioSource != new.audioSource ||
                old.audioBuffer != new.audioBuffer) core.scope.launch { reconcile() }
        }
        core.peers.on("audio_start", ::onStart)
        core.peers.on("audio_update", ::onUpdate)
        core.peers.on("audio_stop", ::onStop)
        core.peers.on("audio_outputs") { _, _, _ -> ok("outputs" to outputNames()) }
    }

    fun start() {
        core.scope.launch {
            var n = 0
            while (isActive) {
                delay(2000)
                n++
                if (n % 5 == 1) refreshOutputs()
                if (on) reconcile()
                val inc = incoming
                val now = System.currentTimeMillis()
                if (inc != null && now - inc.rec.lastPacket > 15_000 && now - inc.started > 15_000) {
                    Log.i(TAG, "incoming audio timed out")
                    stopIncoming(notify = false)
                }
                if (inc != null) {
                    core.publish()                       // bitrate and connection state
                    Log.d(TAG, "audio in: packets=${inc.rec.packets} syncReplies=${inc.rec.syncReplies} ready=${inc.rec.ready} estimated=${inc.rec.estimatedClock} rtt=${inc.rec.clock.rtt / 1000} us codec=${inc.rec.codec} missing=${inc.rec.missing} relocks=${inc.rec.relocks} lag=${"%.0f".format(inc.rec.lagMs)} ms outLat=${inc.rec.outputLatencyNs / 1_000_000} ms delay=${inc.rec.effectiveDelayNs / 1_000_000} ms underruns=${inc.player.underruns}")
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
        val rem = remote.mapValues { (dev, r) -> r.copy(connected = sessions.containsKey(dev)) }
        return AudioState(
            on = on,
            error = error,
            remote = rem,
            latency = if (on) core.settings.value.audioBuffer + EXTRA_LATENCY_MS else inc?.let { (it.rec.effectiveDelayNs / 1_000_000).toInt() } ?: 0,
            incoming = inc?.let { IncomingAudio(it.dev, it.vol, it.out.ifEmpty { localOut }, it.rec.ready, bitrate, note(it)) },
            listening = listening,
            outputs = Outputs.list(ctx).map { (name, d) -> AudioOutput(name, name, Outputs.type(d)) },
        )
    }

    /** Why nothing is heard, when we can tell. */
    private fun note(inc: Incoming): String {
        val secs = (System.currentTimeMillis() - inc.started) / 1000
        return when {
            inc.rec.packets == 0L && secs >= 4 -> core.str(R.string.a_no_packets)
            inc.rec.estimatedClock && secs >= 4 -> core.str(R.string.a_no_sync)
            else -> ""
        }
    }

    // ---- receiving --------------------------------------------------------
    private suspend fun onStart(d: PeerDevice, conn: Conn, msg: Msg): Reply {
        val asked = listening == d.id
        if (!core.settings.value.featAudio && !asked) return fail("disabled")
        if (!d.trusted && !asked) return fail("not trusted")
        val k = msg.header["keys"] as? kotlinx.serialization.json.JsonObject ?: return fail("bad request")
        val keys = AudioKeys(k.str("k_audio") ?: return fail("bad request"), k.str("s_audio") ?: "", k.str("k_sync") ?: "", k.str("s_sync") ?: "")
        val port = msg.header.int("port") ?: return fail("bad request")
        stopIncoming(notify = false)
        val ip = d.ip.ifEmpty { conn.peerIp }
        val vol = msg.header.int("vol") ?: 60
        val out = msg.header.str("out") ?: ""
        return withContext(Dispatchers.IO) {
            val local = 0
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
                return@launch
            }
            delay(8000)
            if (listening == dev && incoming == null) {
                listening = ""
                core.toast("bad", R.string.t_audio_failed, core.str(R.string.e_audio_not_started))
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

    fun stop() {
        stopIncoming(notify = false)
        on = false
        core.scope.launch { lock.withLock { stopSending() } }
    }

    // ---- sending ----------------------------------------------------------
    /** The UI switch. Needs the microphone permission (Android requires it for playback capture) and the capture consent. */
    fun toggle() {
        if (on) {
            on = false
            core.scope.launch { reconcile() }
            core.toast("audio", R.string.t_stream_off, core.str(R.string.t_stream_off_text))
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            core.events.tryEmit(UiEvent.AskPermission("mic"))
            return
        }
        if (!core.settings.value.featAudio) core.settings.update { it.copy(featAudio = true) }
        if (projection == null) {
            core.events.tryEmit(UiEvent.CaptureConsent)
            return
        }
        begin()
    }

    private fun begin() {
        on = true
        error = ""
        if (incoming != null || listening.isNotEmpty()) listenStop()    // one direction at a time
        core.scope.launch {
            reconcile()
            if (on && error.isEmpty()) {
                val names = core.settings.value.audioTargets.filter { it.on }.mapNotNull { core.peers.get(it.dev)?.name }
                core.toast("audio", R.string.t_stream_on, names.joinToString(", ").ifEmpty { core.str(R.string.t_pick_devices) })
            }
        }
    }

    /** The answer to the capture consent dialog (called on the main thread). */
    fun onConsent(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        // Android 14+: the mediaProjection service type must be on before getMediaProjection()
        LocalDropService.projecting = true
        LocalDropService.applyNow()
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (p == null) {
            LocalDropService.projecting = false
            LocalDropService.update()
            core.toast("bad", R.string.t_capture_failed, core.str(R.string.e_projection))
            return
        }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projection = null
                if (on) {
                    on = false
                    core.scope.launch { reconcile() }
                }
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        projection = p
        begin()
    }

    private fun encoderFor(quality: String) = when (quality) {
        "hq" -> Pcm
        else -> runCatching { OpusEncoder(if (quality == "low") 96_000 else 192_000) }.getOrElse {
            Log.w(TAG, "no Opus encoder, sending PCM", it)
            Pcm
        }
    }

    suspend fun reconcile() {
        lock.withLock {
            try {
                reconcileLocked()
            } catch (e: Exception) {
                Log.e(TAG, "audio reconcile failed", e)
            }
        }
        core.publish()
    }

    private suspend fun reconcileLocked() {
        val s = core.settings.value
        if (!on) {
            stopSending()
            return
        }
        if (!ensureCapture(s.audioSource, s.audioQuality)) return
        val snd = sender ?: return
        if (currentQuality != s.audioQuality) {
            val old = snd.encoder
            snd.encoder = encoderFor(s.audioQuality)
            currentQuality = s.audioQuality
            if (old !== Pcm) old.close()
        }
        val desired = s.audioTargets.filter { it.on && core.peers.connected(it.dev) }.associateBy { it.dev }
        for (dev in sessions.keys.toList()) if (dev !in desired) endSession(dev, notify = true)
        for ((dev, t) in desired) {
            val ses = sessions[dev]
            if (ses == null) startSession(dev, t.vol, t.out, s.audioBuffer, s.audioQuality)
            else if (ses.vol != t.vol || ses.out != t.out || ses.delay != s.audioBuffer) {
                val sent = core.peers.send(dev, obj("type" to "audio_update", "session" to ses.id, "vol" to t.vol, "out" to t.out, "delay" to s.audioBuffer))
                if (sent) {
                    ses.vol = t.vol; ses.out = t.out; ses.delay = s.audioBuffer
                }
            }
        }
    }

    private var currentQuality = ""

    private fun ensureCapture(source: String, quality: String): Boolean {
        val key = source + "/" + System.identityHashCode(projection)
        if (sender != null && capture != null && captureKey == key) return true
        capture?.stop()
        capture = null
        val snd = sender ?: Sender(encoderFor(quality)).also { it.start(); sender = it; currentQuality = quality }
        try {
            val p = projection ?: throw IllegalStateException(core.str(R.string.e_projection))
            val cap = Capture.system(p) { snd.push(it) }
            cap.start()
            capture = cap
            captureKey = key
            error = ""
            return true
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            error = core.str(R.string.t_capture_failed) + ": " + (e.message ?: e.toString())
            on = false
            core.toast("bad", R.string.t_capture_failed, e.message ?: "")
            return false
        }
    }

    private suspend fun startSession(dev: String, vol: Int, out: String, delay: Int, quality: String) {
        val d = core.peers.get(dev) ?: return
        val snd = sender ?: return
        val sid = java.util.UUID.randomUUID().toString().replace("-", "")
        val keys = AudioKeys.random()
        val msg = obj("type" to "audio_start", "session" to sid, "keys" to keys.toMap(), "port" to snd.port, "delay" to delay, "vol" to vol, "out" to out)
        val reply = try {
            core.peers.request(dev, msg, timeoutMs = 10_000).header
        } catch (e: Exception) {
            remote[dev] = (remote[dev] ?: RemoteAudio()).copy(error = core.str(R.string.e_audio_no_answer))
            return
        }
        if (reply.bool("ok") != true) {
            val e = reply.str("error")
            remote[dev] = (remote[dev] ?: RemoteAudio()).copy(error = core.str(when (e) {
                "not trusted" -> R.string.e_audio_untrusted_pc
                "disabled" -> R.string.e_audio_disabled_pc
                else -> R.string.e_audio_refused
            }))
            return
        }
        val outs = (reply["outputs"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        remote[dev] = (remote[dev] ?: RemoteAudio()).copy(error = "", outputs = outs ?: remote[dev]?.outputs.orEmpty())
        val port = reply.int("port") ?: return
        snd.addTarget(dev, InetSocketAddress(d.ip, port), keys)
        sessions[dev] = Session(sid, vol, out, delay, System.currentTimeMillis(), quality)
    }

    private suspend fun endSession(dev: String, notify: Boolean) {
        val s = sessions.remove(dev) ?: return
        sender?.removeTarget(dev)
        if (notify) core.peers.send(dev, obj("type" to "audio_stop", "session" to s.id))
        history("out", dev, (System.currentTimeMillis() - s.started) / 1000, s.quality)
    }

    private suspend fun stopSending() {
        for (dev in sessions.keys.toList()) endSession(dev, notify = true)
        capture?.stop()
        capture = null
        captureKey = ""
        sender?.stop()
        sender = null
        currentQuality = ""
        projection?.let { runCatching { it.stop() } }
        projection = null
        if (LocalDropService.projecting) {
            LocalDropService.projecting = false
            LocalDropService.update()
        }
    }

    private suspend fun refreshOutputs() {
        for (d in core.peers.devices.values) {
            if (!d.paired || !core.peers.connected(d.id)) continue
            runCatching {
                val r = core.peers.request(d.id, obj("type" to "audio_outputs"), timeoutMs = 5000).header
                if (r.bool("ok") == true) {
                    val outs = (r["outputs"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                    remote[d.id] = (remote[d.id] ?: RemoteAudio()).copy(outputs = outs)
                }
            }
        }
        core.publish()
    }

    private suspend fun onStopped(d: PeerDevice, conn: Conn, msg: Msg): Reply? {
        val s = sessions[d.id] ?: return null
        if (s.id != msg.header.str("session")) return null
        lock.withLock { endSession(d.id, notify = false) }
        core.settings.update { st -> st.copy(audioTargets = st.audioTargets.map { if (it.dev == d.id) it.copy(on = false) else it }) }
        core.toast("audio", R.string.t_dev_left, core.str(R.string.t_dev_left_text, d.name))
        core.publish()
        return null
    }

    fun setTarget(dev: String, on: Boolean?, vol: Int?, out: String?) {
        core.settings.update { s ->
            val cur = s.audioTargets.firstOrNull { it.dev == dev } ?: AudioTarget(dev, false, 70)
            val upd = cur.copy(on = on ?: cur.on, vol = vol ?: cur.vol, out = out ?: cur.out)
            s.copy(audioTargets = if (s.audioTargets.any { it.dev == dev }) s.audioTargets.map { if (it.dev == dev) upd else it } else s.audioTargets + upd)
        }
    }

    companion object {
        const val EXTRA_LATENCY_MS = 30
    }
}

