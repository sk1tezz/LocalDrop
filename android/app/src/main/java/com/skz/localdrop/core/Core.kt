package com.skz.localdrop.core

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.skz.localdrop.BuildConfig
import com.skz.localdrop.R
import com.skz.localdrop.protocol.crypto.Identity
import com.skz.localdrop.protocol.net.Device as PeerDevice
import com.skz.localdrop.protocol.net.PeerHost
import com.skz.localdrop.protocol.net.PairState
import com.skz.localdrop.protocol.net.Pairing
import com.skz.localdrop.protocol.net.PairingHost
import com.skz.localdrop.protocol.net.PeerManager
import com.skz.localdrop.protocol.net.fmtCode
import com.skz.localdrop.ui.readPerms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

const val TAG = "LocalDrop"

/**
 * The app core (desktop/localdrop/app.py): owns the identity, settings, storage and the
 * services, builds the UI state and answers the UI's calls. Lives as long as the process;
 * the foreground service keeps the process alive.
 */
class Core(val ctx: Context) : Backend {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val storage = Storage(ctx)
    val settings = SettingsStore(ctx, defaultName(), if (Locale.getDefault().language == "ru") "ru" else "en")
    val ident: Identity = loadIdentity()
    private val started = AtomicBoolean(false)
    @Volatile var appVisible = false

    private val _state = MutableStateFlow(AppState(settings = settings.value))
    override val state: StateFlow<AppState> = _state
    override val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)

    // pieces of state owned by the core itself
    @Volatile var ip: String = ""
    @Volatile var network: String = ""
    @Volatile var scanning = false
    @Volatile var perms = Perms()
    @Volatile var code: String = ""
    @Volatile var pair: PairDialog? = null
    @Volatile private var history: List<HistoryItem> = emptyList()

    val port: Int get() = settings.value.port

    val peers = PeerManager(object : PeerHost {
        override val port get() = this@Core.port
        override fun meInfo() = mapOf("name" to settings.value.deviceName, "os" to osLabel(), "kind" to "android", "icon" to "smartphone")
        override fun saveDevice(d: PeerDevice) = storage.saveDevice(d)
        override fun forgetDevice(id: String) = storage.forgetDevice(id)
        override fun devicesChanged() = publish()
        override fun unpaired(d: PeerDevice) = toast("acc", R.string.t_unpaired, str(R.string.t_unpaired_text, d.name))
        override fun log(msg: String) { Log.i(TAG, msg) }
    }, ident, scope, storage.pairedDevices())

    val discovery = Discovery(this)

    val pairing = Pairing(object : PairingHost {
        override fun pairChanged(p: PairState?) {
            pair = p?.let { PairDialog(it.id, it.dir, it.name, it.os, it.icon, it.ip, it.code, it.trust) }
            publish()
        }

        override fun codeChanged(code: String) {
            this@Core.code = fmtCode(code)
            publish()
        }

        override fun notice(kind: String, key: String, vararg args: String) = pairNotice(kind, key, args)
        override fun ip() = this@Core.ip
    }, peers, scope)

    private fun pairNotice(kind: String, key: String, a: Array<out String>) {
        val arg = a.firstOrNull() ?: ""
        when (key) {
            "conn_failed" -> toast(kind, R.string.t_conn_failed, str(R.string.t_no_answer, arg, a.getOrNull(1)?.toIntOrNull() ?: 0))
            "already" -> toast(kind, R.string.t_already, arg)
            "connected" -> toast(kind, R.string.t_connected, arg)
            "connected_code" -> toast(kind, R.string.t_connected, str(R.string.t_by_code, arg))
            "busy" -> toast(kind, R.string.t_busy, str(R.string.t_busy_text, arg))
            "declined" -> toast(kind, R.string.t_conn_declined, arg)
            "request_in" -> toast(kind, R.string.t_request_in, arg)
            "cancelled" -> toast(kind, R.string.t_request_cancelled, arg)
            "own_code" -> toast(kind, R.string.t_own_code, str(R.string.t_own_code_text))
            "code_bad" -> toast(kind, R.string.t_code_bad, str(R.string.t_code_bad_text))
            "qr_self" -> toast(kind, R.string.t_qr_self)
            "qr_failed" -> toast(kind, R.string.t_qr_failed, str(R.string.t_qr_failed_text))
        }
    }

    val notify = com.skz.localdrop.service.Notifier(this)
    val files = FilesGlue(this)

    fun navigate(screen: String) { events.tryEmit(UiEvent.Navigate(screen)) }

    /** Started once from the service (or the activity if the service could not start). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        storage.prune()
        history = storage.history()
        perms = readPerms(ctx, perms.tile)
        // the language picked in the app (or in system settings for this app) wins over the stored one
        AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language?.let { l ->
            if (l == "ru" || l == "en") settings.update { it.copy(lang = l) }
        }
        settings.onChange { old, new -> onSettings(old, new) }
        try {
            peers.start()
        } catch (e: Exception) {
            Log.e(TAG, "cannot listen on ${port}", e)
        }
        discovery.start()
        pairing.start()
        files.service.start()
        publish()
    }

    private fun onSettings(old: AppSettings, new: AppSettings) {
        publish()
    }

    // ---- state ------------------------------------------------------------
    private var publishJob: Job? = null

    /** Rebuild the UI state soon (several changes in a row become one update). */
    fun publish() {
        if (publishJob?.isActive == true) return
        publishJob = scope.launch {
            delay(50)
            _state.value = build()
        }
    }

    fun historyChanged() {
        history = storage.history()
        publish()
    }

    private fun build(): AppState = AppState(
        ready = true,
        me = Me(ident.id, settings.value.deviceName, ip, port, code, pairing.qr(), network, BuildConfig.VERSION_NAME),
        settings = settings.value,
        devices = peers.view().map { Device(it.id, it.name, it.os, it.kind, it.icon, it.ip, it.port, it.online, it.paired, it.trusted) },
        transfers = files.view(),
        history = history,
        pair = pair,
        scanning = scanning,
        perms = perms,
    )

    // ---- texts and notices ------------------------------------------------
    /** Resources in the language chosen in the app. */
    fun res(): android.content.res.Resources {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ctx.resources
        val conf = Configuration(ctx.resources.configuration)
        conf.setLocale(locales[0])
        return ctx.createConfigurationContext(conf).resources
    }

    fun str(id: Int, vararg args: Any): String = res().getString(id, *args)
    fun plural(id: Int, n: Int): String = res().getQuantityString(id, n, n)

    fun toast(kind: String, title: Int, text: String = "") {
        if (!settings.value.notify && kind != "bad") return
        events.tryEmit(UiEvent.ShowToast(Toast(kind, str(title), text)))
    }

    // ---- identity ---------------------------------------------------------
    private fun loadIdentity(): Identity {
        val f = File(ctx.noBackupFilesDir, "identity.key")
        runCatching { f.readBytes().takeIf { it.size == 32 }?.let { return Identity(it) } }
        val id = Identity.generate()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(id.seed)
        tmp.renameTo(f)
        return id
    }

    private fun defaultName(): String =
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL ?: "Android"

    fun osLabel() = "Android " + Build.VERSION.RELEASE

    // ---- backend calls ----------------------------------------------------
    override fun settings(change: (AppSettings) -> AppSettings) = settings.update(change)

    override fun rescan() { scope.launch { discovery.rescan() } }
    override fun pairRequest(dev: String) { scope.launch { pairing.request(dev) } }
    override fun pairRespond(dev: String, accept: Boolean, trust: Boolean) = pairing.respond(dev, accept, trust)
    override fun pairOutTrust(dev: String, trust: Boolean) = pairing.outTrust(dev, trust)
    override fun pairCode(code: String) { scope.launch { pairing.enterCode(code) } }
    override fun connectIp(ip: String, port: Int) { scope.launch { pairing.connectIp(ip, port) } }
    override fun newCode() = pairing.newCode()
    override fun qrScanned(text: String): Boolean = pairing.scanned(text)
    override fun setTrust(dev: String, trusted: Boolean) {
        peers.setTrust(dev, trusted)
        if (!trusted) toast("acc", R.string.t_untrusted, peers.get(dev)?.name ?: "")
    }

    override fun forget(dev: String) {
        val name = peers.get(dev)?.name ?: ""
        scope.launch {
            peers.forget(dev)
            toast("acc", R.string.t_unpaired, name)
        }
    }

    override fun sendUris(dev: String, uris: List<Uri>, folder: Boolean) = files.send(dev, uris, folder)
    override fun offerRespond(tid: String, accept: Boolean) { scope.launch { files.service.respond(tid, accept) } }
    override fun transferToggle(tid: String) { scope.launch { files.service.toggle(tid) } }
    override fun transferCancel(tid: String) { scope.launch { files.service.cancel(tid) } }
    override fun transferRetry(tid: String) { scope.launch { files.service.retry(tid) } }
    override fun pauseAll() { scope.launch { files.service.pauseAll() } }
    override fun resumeAll() { scope.launch { files.service.resumeAll() } }
    override fun clearDone() = files.service.clearDone()
    override fun openTransfer(tid: String) = files.open(tid)
    override fun setSaveDir(uri: Uri?) = files.setSaveDir(uri)
    override fun clipSendCurrent(dev: String?) {}
    override fun clipCopy(id: Long) {}
    override fun clipSend(id: Long) {}
    override fun clipDelete(id: Long) {}
    override fun clipClear() {}
    override fun audioToggle() {}
    override fun audioTarget(dev: String, on: Boolean?, vol: Int?, out: String?) {}
    override fun audioListen(dev: String) {}
    override fun audioListenStop() {}
    override fun audioIncomingStop() {}
    override fun audioOutput(id: String) {}
    override fun audioVolume(vol: Int) {}

    override fun historyClear() {
        storage.clearHistory()
        historyChanged()
    }

    override fun historyAction(id: Long) {
        val h = storage.historyItem(id) ?: return
        when (h.type) {
            "files" -> files.historyAction(h)
        }
    }

    override fun refreshPerms() {
        val before = perms
        perms = readPerms(ctx, perms.tile)
        if (perms.lan && !before.lan) scope.launch { discovery.restart() }
        publish()
    }
}
