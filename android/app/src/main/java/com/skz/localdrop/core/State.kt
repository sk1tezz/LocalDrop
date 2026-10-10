package com.skz.localdrop.core

/**
 * Everything the UI shows, one immutable snapshot. The same slices as the desktop app
 * (desktop/localdrop/app.py: me, settings, devices, transfers, clips, history, audio, pair).
 */
data class AppState(
    val ready: Boolean = false,
    val me: Me = Me(),
    val settings: AppSettings = AppSettings(),
    val devices: List<Device> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val audio: AudioState = AudioState(),
    val pair: PairDialog? = null,
    val scanning: Boolean = false,
    val perms: Perms = Perms(),
) {
    fun device(id: String): Device? = devices.firstOrNull { it.id == id }
}

data class Me(
    val id: String = "",
    val name: String = "",
    val ip: String = "",
    val port: Int = 53317,
    val code: String = "",          // "482 913"
    val qr: String = "",            // localdrop:1:ip:port:id:code
    val network: String = "",       // Wi-Fi name when known
    val version: String = "",
)

data class Device(
    val id: String,
    val name: String,
    val os: String = "",
    val kind: String = "",          // windows | macos | android | linux
    val icon: String = "computer",  // Material Symbols name sent by the device
    val ip: String = "",
    val port: Int = 53317,
    val online: Boolean = false,
    val paired: Boolean = false,
    val trusted: Boolean = false,
)

/** status: ask | waiting | queued | active | paused | offline | done | error | declined | cancelled */
data class Transfer(
    val id: String,
    val dir: String,                // in | out
    val dev: String,
    val name: String,
    val kind: String = "file",      // file | folder | image | video | audio | archive | doc
    val files: Int = 1,
    val size: Long = 0,
    val done: Long = 0,
    val speed: Double = 0.0,        // bytes per second
    val status: String = "queued",
    val `when`: Double = 0.0,       // unix seconds when it finished
    val error: String = "",
    val path: String = "",          // uri of the received file / folder
) {
    val final get() = status in FINAL
    val running get() = status == "active" || status == "queued" || status == "offline"

    companion object {
        val FINAL = setOf("done", "error", "declined", "cancelled")
    }
}

data class Clip(
    val id: Long,
    val type: String,               // text | image
    val text: String,
    val meta: String = "",          // image: "1920×1080 · 412 KB" (already formatted)
    val dev: String = "me",
    val ts: Double = 0.0,
    val image: String = "",         // local file path of the image
)

data class HistoryItem(
    val id: Long,
    val ts: Double,
    val type: String,               // files | clip | audio
    val dir: String,                // in | out
    val title: String,
    val sub: String = "",           // formatted line under the title
    val dev: String = "",
    val err: Boolean = false,
    val ref: String = "",
)

data class RemoteAudio(val outputs: List<String> = emptyList(), val connected: Boolean = false, val error: String = "")

/** Audio playing here from another device. */
data class IncomingAudio(val dev: String, val vol: Int, val out: String, val connected: Boolean, val bitrate: String = "")

data class AudioState(
    val on: Boolean = false,                    // this phone is streaming
    val error: String = "",
    val latency: Int = 0,
    val remote: Map<String, RemoteAudio> = emptyMap(),
    val incoming: IncomingAudio? = null,
    val listening: String = "",                 // device we asked to stream to us (audio_request)
    val outputs: List<AudioOutput> = emptyList(),
)

data class AudioOutput(val id: String, val name: String, val type: String)   // type: speaker | bt | wired | usb

data class PairDialog(
    val id: String,
    val dir: String,                // in | out
    val name: String,
    val os: String,
    val icon: String,
    val ip: String,
    val code: String,
    val trust: Boolean = true,
)

data class Perms(
    val lan: Boolean = true,
    val notify: Boolean = false,
    val battery: Boolean = false,
    val mic: Boolean = false,
    val camera: Boolean = false,
    val tile: Boolean = false,
)

data class AudioTarget(val dev: String, val on: Boolean = true, val vol: Int = 60, val out: String = "")

/** Settings; the JSON keys match desktop/localdrop/config.py DEFAULTS where the feature exists on both. */
data class AppSettings(
    val deviceName: String = "",
    val port: Int = 53317,
    val lang: String = "ru",
    val theme: String = "dark",               // dark | light | system
    val featureColors: Boolean = true,
    val onboarded: Boolean = false,
    val featFiles: Boolean = true,
    val featClip: Boolean = true,
    val featAudio: Boolean = true,
    val visible: Boolean = true,
    val wifiOnly: Boolean = true,
    val autoAccept: Boolean = true,
    val keepAwake: Boolean = true,
    val notify: Boolean = true,
    val sounds: Boolean = false,
    val saveDir: String = "",                 // "" = Download/LocalDrop, else a SAF tree uri
    val clipAuto: Boolean = true,
    val clipDevices: List<String> = emptyList(),
    val clipText: Boolean = true,
    val clipImage: Boolean = true,
    val audioSource: String = "system",       // system | mic
    val audioQuality: String = "balance",     // low | balance | hq
    val audioBuffer: Int = 60,
    val audioMuteLocal: Boolean = false,
    val audioTargets: List<AudioTarget> = emptyList(),
    val sendTarget: String = "",              // device chosen on Home / Files
)
