package com.skz.localdrop.ui.demo

import android.content.Context
import android.net.Uri
import com.skz.localdrop.R
import com.skz.localdrop.core.AppSettings
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.AudioOutput
import com.skz.localdrop.core.AudioState
import com.skz.localdrop.core.AudioTarget
import com.skz.localdrop.core.Backend
import com.skz.localdrop.core.Clip
import com.skz.localdrop.core.Device
import com.skz.localdrop.core.HistoryItem
import com.skz.localdrop.core.IncomingAudio
import com.skz.localdrop.core.Me
import com.skz.localdrop.core.PairDialog
import com.skz.localdrop.core.Perms
import com.skz.localdrop.core.Toast
import com.skz.localdrop.core.Transfer
import com.skz.localdrop.core.UiEvent
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Fake data from the design mockup, for checking the interface without a network. */
class DemoBackend(private val ctx: Context) : Backend {
    private val scope = MainScope()
    private val now get() = System.currentTimeMillis() / 1000.0
    private val _state = MutableStateFlow(initial())
    override val state: StateFlow<AppState> = _state
    override val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)

    init {
        scope.launch {
            while (true) {
                delay(500)
                tick()
            }
        }
    }

    private fun initial(): AppState {
        val t = System.currentTimeMillis() / 1000.0
        val devices = listOf(
            Device("d1", "MacBook Pro Ани", "macOS 15.1", "macos", "laptop_mac", "192.168.1.31", online = true, paired = true, trusted = true),
            Device("d2", "Рабочий ПК", "Windows 11", "windows", "desktop_windows", "192.168.1.12", online = true, paired = true, trusted = true),
            Device("d3", "Медиацентр", "Windows 10", "windows", "tv", "192.168.1.40", online = true, paired = true, trusted = true),
            Device("d4", "iMac в гостиной", "macOS 14.6", "macos", "desktop_mac", "192.168.1.27", online = true, paired = true, trusted = true),
            Device("d5", "Ноутбук Lenovo", "Windows 11", "windows", "laptop_windows", "192.168.1.55", online = true),
            Device("d9", "MacBook Air", "macOS 15.0", "macos", "laptop_mac", "192.168.1.44", online = true),
            Device("d6", "Mac mini — студия", "macOS 15.1", "macos", "desktop_mac", "192.168.1.18", paired = true, trusted = true),
            Device("d8", "ПК Дмитрия", "Windows 11", "windows", "desktop_windows", "192.168.1.71", paired = true, trusted = true),
        )
        val mb = 1048576L
        return AppState(
            ready = true,
            me = Me("me", "Pixel 8", "192.168.1.57", 53317, "482 913", "localdrop:1:192.168.1.57:53317:me:482913", "Home_5G", "1.0.0"),
            settings = AppSettings(
                deviceName = "Pixel 8", onboarded = true, clipDevices = listOf("d1", "d2"), sendTarget = "d1",
                audioTargets = listOf(AudioTarget("d3", true, 70), AudioTarget("d4", false, 55)), keepAwake = false,
            ),
            devices = devices,
            transfers = listOf(
                Transfer("t1", "out", "d1", "VID_20261010_1432.mp4", "video", 1, 1240 * mb, 471 * mb, 48.0 * mb, "active"),
                Transfer("t2", "out", "d4", "Фото_Отпуск_2026", "folder", 248, 1126 * mb, 0, 0.0, "queued"),
                Transfer("t4", "in", "d2", "Презентация_Q3.pdf", "doc", 1, 18 * mb, 18 * mb, 0.0, "done", t - 7200),
                Transfer("t5", "in", "d1", "Скан_договора.pdf", "doc", 1, 3355443, 3355443, 0.0, "done", t - 13000),
            ),
            clips = listOf(
                Clip(1, "text", "https://localdrop.app/docs/pairing", dev = "d1", ts = t - 120),
                Clip(2, "image", "1920×1080", "1920×1080 · 412 КБ", dev = "d4", ts = t - 840),
                Clip(3, "text", "Встреча перенесена на 16:30 — ссылка в календаре, не забудь презентацию", dev = "me", ts = t - 1920),
                Clip(5, "text", "pip install localdrop-core==2.1", dev = "d2", ts = t - 7200),
            ),
            history = listOf(
                HistoryItem(1, t - 60, "files", "out", "IMG_2041.heic", "4,8 МБ · отправлено", "d1"),
                HistoryItem(2, t - 360, "clip", "in", "https://localdrop.app/docs/pairing", "Текст · получено", "d1"),
                HistoryItem(3, t - 5600, "audio", "in", "Звук с компьютера", "1 ч 12 мин · Баланс", "d2"),
                HistoryItem(4, t - 8000, "files", "in", "Презентация_Q3.pdf", "18 МБ · получено", "d2"),
                HistoryItem(5, t - 13000, "files", "in", "Скан_договора.pdf", "3 МБ · получено", "d1"),
                HistoryItem(6, t - 86400 - 3000, "files", "out", "Фото_Семья", "Папка · 86 файлов · 420 МБ · отправлено", "d4"),
                HistoryItem(7, t - 86400 - 12000, "audio", "out", "Трансляция звука", "48 мин · Высокое качество", "d3"),
                HistoryItem(8, t - 86400 - 30000, "files", "out", "Видео_концерт.mp4", "1,1 ГБ · ошибка: Wi-Fi отключился", "d8", err = true),
            ),
            audio = AudioState(
                incoming = IncomingAudio("d2", 65, "bt", true, "192 kbit/s"), listening = "d2", latency = 90,
                outputs = listOf(AudioOutput("speaker", "Динамик телефона", "speaker"), AudioOutput("bt", "Pixel Buds Pro", "bt")),
            ),
            perms = com.skz.localdrop.ui.readPerms(ctx),
        )
    }

    private fun set(f: (AppState) -> AppState) = _state.update(f)
    private fun toast(kind: String, title: Int, text: String = "") {
        events.tryEmit(UiEvent.ShowToast(Toast(kind, ctx.getString(title), text)))
    }

    private fun tick() = set { s ->
        var changed = false
        val list = s.transfers.map { t ->
            if (t.status != "active") t else {
                changed = true
                val done = minOf(t.size, t.done + (t.speed / 2).toLong())
                if (done >= t.size) t.copy(done = t.size, status = "done", `when` = now, speed = 0.0) else t.copy(done = done)
            }
        }
        // start the next queued transfer when nothing is running
        val next = if (list.none { it.status == "active" }) list.indexOfFirst { it.status == "queued" } else -1
        val out = if (next >= 0) list.toMutableList().also { it[next] = it[next].copy(status = "active", speed = 52.0 * 1048576) } else list
        if (changed || next >= 0) s.copy(transfers = out) else s
    }

    override fun settings(change: (AppSettings) -> AppSettings) = set { it.copy(settings = change(it.settings)) }

    override fun rescan() {
        scope.launch {
            set { it.copy(scanning = true) }
            delay(1500)
            set { it.copy(scanning = false) }
            toast("acc", R.string.t_scan_done, ctx.getString(R.string.t_scan_n, state.value.devices.count { it.online }))
        }
    }

    override fun pairRequest(dev: String) {
        val d = state.value.device(dev) ?: return
        set { it.copy(pair = PairDialog(d.id, "out", d.name, d.os, d.icon, d.ip, "731 064")) }
    }

    override fun pairRespond(dev: String, accept: Boolean, trust: Boolean) {
        set { s -> s.copy(pair = null, devices = if (accept) s.devices.map { if (it.id == dev) it.copy(paired = true, trusted = trust) else it } else s.devices) }
        if (accept) toast("ok", R.string.t_connected, state.value.device(dev)?.name ?: "") else toast("bad", R.string.t_conn_declined)
    }

    override fun pairOutTrust(dev: String, trust: Boolean) = set { s -> s.copy(pair = s.pair?.copy(trust = trust)) }

    override fun pairCode(code: String) {
        if (code.filter { it.isDigit() } == state.value.me.code.filter { it.isDigit() }) {
            toast("bad", R.string.t_own_code, ctx.getString(R.string.t_own_code_text)); return
        }
        val d = state.value.devices.firstOrNull { !it.paired && it.online } ?: run { toast("bad", R.string.t_code_bad, ctx.getString(R.string.t_code_bad_text)); return }
        set { s -> s.copy(devices = s.devices.map { if (it.id == d.id) it.copy(paired = true, trusted = true) else it }) }
        toast("ok", R.string.t_connected, d.name)
    }

    override fun connectIp(ip: String, port: Int) = toast("bad", R.string.t_conn_failed, ctx.getString(R.string.t_no_answer, ip, port))
    override fun newCode() = set { it.copy(me = it.me.copy(code = "%03d %03d".format((100..999).random(), (0..999).random()))) }
    override fun qrScanned(text: String): Boolean { pairCode("000000"); return true }

    override fun setTrust(dev: String, trusted: Boolean) {
        set { s -> s.copy(devices = s.devices.map { if (it.id == dev) it.copy(trusted = trusted) else it }) }
        if (!trusted) toast("acc", R.string.t_untrusted, state.value.device(dev)?.name ?: "")
    }

    override fun forget(dev: String) {
        set { s -> s.copy(devices = s.devices.map { if (it.id == dev) it.copy(paired = false, trusted = false) else it }) }
        toast("acc", R.string.t_unpaired, state.value.device(dev)?.name ?: "")
    }

    override fun sendUris(dev: String, uris: List<Uri>, folder: Boolean) {
        if (uris.isEmpty()) return
        val items = uris.mapIndexed { i, u ->
            val name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
            Transfer("n${System.nanoTime()}$i", "out", dev, name, if (folder) "folder" else "file", 1, (5..300).random() * 1048576L, 0, 0.0, "queued")
        }
        set { it.copy(transfers = it.transfers + items) }
        toast("files", R.string.t_queued, ctx.getString(R.string.t_arrow, ctx.resources.getQuantityString(R.plurals.n_files, items.size, items.size), state.value.device(dev)?.name ?: ""))
    }

    override fun offerRespond(tid: String, accept: Boolean) = updT(tid) { it.copy(status = if (accept) "queued" else "declined") }

    override fun transferToggle(tid: String) = updT(tid) {
        when (it.status) {
            "paused" -> it.copy(status = "queued")
            "active", "queued", "offline" -> it.copy(status = "paused", speed = 0.0)
            else -> it
        }
    }

    override fun transferCancel(tid: String) = set { s -> s.copy(transfers = s.transfers.filter { it.id != tid }) }
    override fun transferRetry(tid: String) = updT(tid) { it.copy(status = "queued", done = 0, error = "") }
    override fun pauseAll() = set { s -> s.copy(transfers = s.transfers.map { if (it.running) it.copy(status = "paused", speed = 0.0) else it }) }
    override fun resumeAll() = set { s -> s.copy(transfers = s.transfers.map { if (it.status == "paused") it.copy(status = "queued") else it }) }
    override fun clearDone() = set { s -> s.copy(transfers = s.transfers.filter { !it.final }) }
    override fun openTransfer(tid: String) {}
    override fun setSaveDir(uri: Uri?) = settings { it.copy(saveDir = uri?.toString() ?: "") }

    private fun updT(tid: String, f: (Transfer) -> Transfer) = set { s -> s.copy(transfers = s.transfers.map { if (it.id == tid) f(it) else it }) }

    override fun clipSendCurrent(dev: String?) {
        val n = if (dev != null) 1 else state.value.settings.clipDevices.size
        toast("clip", R.string.t_clip_sent, ctx.getString(R.string.t_to, ctx.resources.getQuantityString(R.plurals.n_devices, n, n)))
    }

    override fun clipCopy(id: Long) = toast("clip", R.string.t_copied, ctx.getString(R.string.t_paste))
    override fun clipSend(id: Long) = clipSendCurrent()
    override fun clipDelete(id: Long) = set { s -> s.copy(clips = s.clips.filter { it.id != id }) }
    override fun clipClear() = set { it.copy(clips = emptyList()) }

    override fun audioToggle() {
        val on = !state.value.audio.on
        set { s -> s.copy(audio = s.audio.copy(on = on, incoming = if (on) null else s.audio.incoming, listening = if (on) "" else s.audio.listening, latency = 90)) }
        if (on) {
            val names = state.value.settings.audioTargets.filter { it.on }.mapNotNull { state.value.device(it.dev)?.name }
            toast("audio", R.string.t_stream_on, names.joinToString(", ").ifEmpty { ctx.getString(R.string.t_pick_devices) })
        } else toast("audio", R.string.t_stream_off, ctx.getString(R.string.t_stream_off_text))
    }

    override fun audioTarget(dev: String, on: Boolean?, vol: Int?, out: String?) = settings { s ->
        val cur = s.audioTargets.firstOrNull { it.dev == dev } ?: AudioTarget(dev, false)
        val upd = cur.copy(on = on ?: cur.on, vol = vol ?: cur.vol, out = out ?: cur.out)
        s.copy(audioTargets = s.audioTargets.filter { it.dev != dev } + upd)
    }

    override fun audioListen(dev: String) = set { s ->
        s.copy(audio = s.audio.copy(on = false, listening = dev, incoming = IncomingAudio(dev, s.audio.incoming?.vol ?: 65, s.audio.incoming?.out ?: "speaker", true, "192 kbit/s")))
    }

    override fun audioListenStop() = set { s -> s.copy(audio = s.audio.copy(listening = "", incoming = null)) }
    override fun audioIncomingStop() = audioListenStop()
    override fun audioOutput(id: String) = set { s -> s.copy(audio = s.audio.copy(incoming = s.audio.incoming?.copy(out = id))) }
    override fun audioVolume(vol: Int) = set { s -> s.copy(audio = s.audio.copy(incoming = s.audio.incoming?.copy(vol = vol))) }

    override fun historyClear() {
        set { it.copy(history = emptyList()) }
        toast("acc", R.string.t_cleared)
    }

    override fun historyAction(id: Long) {}
    /** Debug entry points for checking screens: adb shell am start ... -e demo onboarding|pair_in|pair_out|ask */
    fun demo(what: String) {
        when (what) {
            "onboarding" -> settings { it.copy(onboarded = false) }
            "pair_in" -> set { it.copy(pair = PairDialog("d9", "in", "MacBook Air", "macOS 15.0", "laptop_mac", "192.168.1.44", "731 064")) }
            "pair_out" -> pairRequest("d5")
            "ask" -> set { it.copy(transfers = listOf(Transfer("ask1", "in", "d2", "Отчёт_октябрь.pdf", "doc", 1, 2202009, 0, 0.0, "ask")) + it.transfers) }
        }
    }

    override fun refreshPerms() = set { it.copy(perms = com.skz.localdrop.ui.readPerms(ctx, it.perms.tile)) }
}
