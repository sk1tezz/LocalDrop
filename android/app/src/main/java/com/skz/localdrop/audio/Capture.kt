package com.skz.localdrop.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.skz.localdrop.protocol.audio.RATE

/**
 * Captures the phone's sound (AudioPlaybackCapture: apps that allow it, never calls) at 48 kHz
 * stereo and hands interleaved floats to [onAudio] in 10 ms chunks.
 */
class Capture private constructor(private val record: AudioRecord, private val projection: MediaProjection?, private val onAudio: (FloatArray) -> Unit) {
    @Volatile private var stopped = false
    private val thread = Thread(::run, "audio-capture").apply { isDaemon = true }

    fun start() {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("capture did not start")
        thread.start()
    }

    private fun run() {
        val shorts = ShortArray(480 * 2)
        while (!stopped) {
            val n = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (stopped) break
                Thread.sleep(5)
                continue
            }
            onAudio(FloatArray(n) { shorts[it] / 32768f })
        }
    }

    fun stop() {
        stopped = true
        runCatching { record.stop() }
        thread.join(500)
        record.release()
        runCatching { projection?.stop() }
    }

    companion object {
        private val FORMAT = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()

        private fun bufSize() = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT), 480 * 4 * 8)

        @SuppressLint("MissingPermission")
        fun system(projection: MediaProjection, onAudio: (FloatArray) -> Unit): Capture {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val rec = AudioRecord.Builder().setAudioFormat(FORMAT).setBufferSizeInBytes(bufSize()).setAudioPlaybackCaptureConfig(cfg).build()
            return Capture(rec, projection, onAudio)
        }
    }
}
