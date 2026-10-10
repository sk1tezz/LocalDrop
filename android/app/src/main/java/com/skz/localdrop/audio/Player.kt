package com.skz.localdrop.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.skz.localdrop.protocol.audio.NS
import com.skz.localdrop.protocol.audio.RATE
import com.skz.localdrop.protocol.audio.Receiver
import com.skz.localdrop.protocol.audio.nowNs

/** Output devices worth offering: speaker, Bluetooth, wired and USB headphones. */
object Outputs {
    private val TYPES = mapOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "speaker",
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "bt", AudioDeviceInfo.TYPE_BLE_HEADSET to "bt", AudioDeviceInfo.TYPE_BLE_SPEAKER to "bt",
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "wired", AudioDeviceInfo.TYPE_WIRED_HEADSET to "wired",
        AudioDeviceInfo.TYPE_USB_HEADSET to "usb", AudioDeviceInfo.TYPE_USB_DEVICE to "usb",
    )

    fun list(ctx: Context): List<Pair<String, AudioDeviceInfo>> {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in TYPES }
            .map { name(it) to it }.distinctBy { it.first }
    }

    fun type(d: AudioDeviceInfo) = TYPES[d.type] ?: "speaker"

    /** A stable, readable name; the speaker gets a fixed one. */
    fun name(d: AudioDeviceInfo): String =
        if (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "Speaker" else d.productName?.toString()?.takeIf { it.isNotBlank() } ?: type(d)

    fun find(ctx: Context, name: String?): AudioDeviceInfo? = if (name.isNullOrEmpty()) null else list(ctx).firstOrNull { it.first == name }?.second
}

/**
 * Plays a [Receiver] through AudioTrack. Each block is rendered for the moment it will
 * actually reach the speaker (AudioTrack timestamps), which keeps the phone in step with
 * the other rooms.
 */
class Player(private val rec: Receiver, private val ctx: Context, output: String?) {
    private val track: AudioTrack
    @Volatile private var stopped = false
    private val thread = Thread(::run, "audio-play").apply { isDaemon = true }

    init {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, 960 * 8))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        Outputs.find(ctx, output)?.let { track.preferredDevice = it }
    }

    val underruns get() = runCatching { track.underrunCount }.getOrDefault(-1)

    fun start() {
        track.play()
        thread.start()
    }

    private fun run() {
        val chunk = 240
        val buf = FloatArray(chunk * 2)
        val ts = AudioTimestamp()
        var written = 0L
        val bufferNs = track.bufferSizeInFrames.toLong() * NS / RATE
        while (!stopped) {
            val playAt = if (track.getTimestamp(ts) && ts.framePosition > 0) ts.nanoTime + (written - ts.framePosition) * NS / RATE
            else nowNs() + bufferNs
            rec.render(chunk, playAt, buf)
            val n = track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            if (n < 0) break
            written += chunk
        }
    }

    fun stop() {
        stopped = true
        runCatching { track.pause(); track.flush() }
        thread.join(500)
        runCatching { track.release() }
    }
}
