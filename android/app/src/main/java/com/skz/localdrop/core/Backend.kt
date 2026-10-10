package com.skz.localdrop.core

import android.net.Uri
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** In-app notice; also shown as a system notification when the app is in the background. */
data class Toast(val kind: String, val title: String, val text: String = "")   // kind: ok | bad | acc | files | clip | audio

sealed interface UiEvent {
    data class ShowToast(val toast: Toast) : UiEvent
    data class Navigate(val screen: String) : UiEvent
}

/**
 * What the UI can ask for. Names follow the desktop bridge calls (ui/app.js `api(...)`)
 * so both apps stay easy to compare.
 */
interface Backend {
    val state: StateFlow<AppState>
    val events: SharedFlow<UiEvent>

    fun settings(change: (AppSettings) -> AppSettings)

    // devices and pairing
    fun rescan()
    fun pairRequest(dev: String)
    fun pairRespond(dev: String, accept: Boolean, trust: Boolean)
    fun pairOutTrust(dev: String, trust: Boolean)
    fun pairCode(code: String)
    fun connectIp(ip: String, port: Int)
    fun newCode()
    fun qrScanned(text: String): Boolean
    fun setTrust(dev: String, trusted: Boolean)
    fun forget(dev: String)

    // files
    fun sendUris(dev: String, uris: List<Uri>, folder: Boolean = false)
    fun offerRespond(tid: String, accept: Boolean)
    fun transferToggle(tid: String)
    fun transferCancel(tid: String)
    fun transferRetry(tid: String)
    fun pauseAll()
    fun resumeAll()
    fun clearDone()
    fun openTransfer(tid: String)
    fun setSaveDir(uri: Uri?)

    // clipboard
    fun clipSendCurrent(dev: String? = null)
    fun clipCopy(id: Long)
    fun clipSend(id: Long)
    fun clipDelete(id: Long)
    fun clipClear()

    // audio
    fun audioToggle()
    fun audioTarget(dev: String, on: Boolean? = null, vol: Int? = null, out: String? = null)
    fun audioListen(dev: String)
    fun audioListenStop()
    fun audioIncomingStop()
    fun audioOutput(id: String)
    fun audioVolume(vol: Int)

    // history
    fun historyClear()
    fun historyAction(id: Long)

    fun refreshPerms()
}
