package com.skz.localdrop.core

import android.content.Context
import com.skz.localdrop.protocol.net.arr
import com.skz.localdrop.protocol.net.bool
import com.skz.localdrop.protocol.net.int
import com.skz.localdrop.protocol.net.json
import com.skz.localdrop.protocol.net.obj
import com.skz.localdrop.protocol.net.str
import com.skz.localdrop.protocol.net.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * settings.json in the app's files dir. Keys follow desktop/localdrop/config.py
 * (device_name, features{files,clip,audio}, clip_devices, audio{...}) plus Android-only ones.
 */
class SettingsStore(ctx: Context, defaultName: String, defaultLang: String) {
    private val file = File(ctx.filesDir, "settings.json")
    private val _flow = MutableStateFlow(load(defaultName, defaultLang))
    val flow: StateFlow<AppSettings> = _flow
    val value: AppSettings get() = _flow.value
    private val listeners = mutableListOf<(old: AppSettings, new: AppSettings) -> Unit>()

    fun onChange(fn: (AppSettings, AppSettings) -> Unit) { listeners += fn }

    @Synchronized
    fun update(f: (AppSettings) -> AppSettings) {
        val old = _flow.value
        val new = f(old)
        if (new == old) return
        _flow.value = new
        save(new)
        for (fn in listeners) runCatching { fn(old, new) }
    }

    private fun load(defaultName: String, defaultLang: String): AppSettings {
        val o = runCatching { json.parseToJsonElement(file.readText()) as JsonObject }.getOrNull()
            ?: return AppSettings(deviceName = defaultName, lang = defaultLang).also { save(it) }
        val d = AppSettings()
        val feat = o.obj("features") ?: JsonObject(emptyMap())
        val ctypes = o.obj("clip_types") ?: JsonObject(emptyMap())
        val a = o.obj("audio") ?: JsonObject(emptyMap())
        return AppSettings(
            deviceName = o.str("device_name")?.takeIf { it.isNotBlank() } ?: defaultName,
            port = o.int("port") ?: d.port,
            lang = o.str("lang") ?: defaultLang,
            theme = o.str("theme") ?: d.theme,
            featureColors = o.bool("feature_colors") ?: d.featureColors,
            onboarded = o.bool("onboarded") ?: d.onboarded,
            featFiles = feat.bool("files") ?: true,
            featClip = feat.bool("clip") ?: true,
            featAudio = feat.bool("audio") ?: true,
            visible = o.bool("visible") ?: d.visible,
            wifiOnly = o.bool("wifi_only") ?: d.wifiOnly,
            autoAccept = o.bool("auto_accept") ?: d.autoAccept,
            keepAwake = o.bool("keep_awake") ?: d.keepAwake,
            notify = o.bool("notify") ?: d.notify,
            sounds = o.bool("sounds") ?: d.sounds,
            saveDir = o.str("save_dir") ?: "",
            clipAuto = o.bool("clip_auto") ?: d.clipAuto,
            clipDevices = o.arr("clip_devices")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            clipText = ctypes.bool("text") ?: true,
            clipImage = ctypes.bool("image") ?: true,
            audioSource = a.str("source") ?: d.audioSource,
            audioQuality = a.str("quality") ?: d.audioQuality,
            audioBuffer = a.int("buffer") ?: d.audioBuffer,
            audioMuteLocal = a.bool("mute_local") ?: d.audioMuteLocal,
            audioTargets = a.arr("targets")?.mapNotNull { e ->
                (e as? JsonObject)?.let { t -> AudioTarget(t.str("dev") ?: return@mapNotNull null, t.bool("on") ?: true, t.int("vol") ?: 60, t.str("out") ?: "") }
            } ?: emptyList(),
            sendTarget = o.str("send_target") ?: "",
        )
    }

    private fun save(s: AppSettings) {
        val o = toJson(
            mapOf(
                "device_name" to s.deviceName, "port" to s.port, "lang" to s.lang, "theme" to s.theme,
                "feature_colors" to s.featureColors, "onboarded" to s.onboarded,
                "features" to mapOf("files" to s.featFiles, "clip" to s.featClip, "audio" to s.featAudio),
                "visible" to s.visible, "wifi_only" to s.wifiOnly, "auto_accept" to s.autoAccept, "keep_awake" to s.keepAwake,
                "notify" to s.notify, "sounds" to s.sounds, "save_dir" to s.saveDir, "clip_auto" to s.clipAuto,
                "clip_devices" to s.clipDevices, "clip_types" to mapOf("text" to s.clipText, "image" to s.clipImage),
                "audio" to mapOf(
                    "source" to s.audioSource, "quality" to s.audioQuality, "buffer" to s.audioBuffer, "mute_local" to s.audioMuteLocal,
                    "targets" to s.audioTargets.map { mapOf("dev" to it.dev, "on" to it.on, "vol" to it.vol, "out" to it.out) },
                ),
                "send_target" to s.sendTarget,
            )
        )
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}
