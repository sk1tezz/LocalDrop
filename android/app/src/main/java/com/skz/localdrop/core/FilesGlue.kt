package com.skz.localdrop.core

import android.app.DownloadManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import com.skz.localdrop.R
import com.skz.localdrop.protocol.files.FileHost
import com.skz.localdrop.protocol.files.FileService
import com.skz.localdrop.protocol.files.Inbox
import com.skz.localdrop.protocol.files.Source
import com.skz.localdrop.protocol.files.Transfer
import com.skz.localdrop.protocol.net.toJson
import com.skz.localdrop.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.skz.localdrop.core.Transfer as TransferView

/** Files on Android: the protocol's FileService plus storage, history, notices and "open". */
class FilesGlue(private val core: Core) {
    private val ctx = core.ctx
    private val source = UriSource(ctx)

    val service = FileService(object : FileHost {
        override fun enabled() = core.settings.value.featFiles
        override fun autoAccept() = core.settings.value.autoAccept
        override fun inbox(): Inbox = this@FilesGlue.inbox()
        override fun source(): Source = source
        override fun save(t: Transfer) = core.storage.saveTransfer(Storage.TransferRow(t.id, t.dir, t.dev, t.name, t.kind, t.status, t.created, t.data()))
        override fun delete(id: String) = core.storage.deleteTransfer(id)
        override fun changed(now: Boolean) = core.publish()
        override fun history(t: Transfer, err: Boolean) = this@FilesGlue.addHistory(t, err)
        override fun notice(kind: String, key: String, t: Transfer, vararg args: String) = this@FilesGlue.notice(kind, key, t, args)
        override fun incomingOffer(t: Transfer, auto: Boolean) = this@FilesGlue.offer(t, auto)
        override fun received(t: Transfer) = this@FilesGlue.received(t)
        override fun log(msg: String) { Log.i(TAG, msg) }
    }, core.peers, core.scope, core.storage.transfers().map { Transfer.restore(it.id, it.dir, it.dev, it.name, it.kind, it.status, it.created, it.data) })

    fun inbox(): Inbox {
        val dir = core.settings.value.saveDir
        return if (dir.isEmpty()) MediaStoreInbox(ctx) else SafInbox(ctx, Uri.parse(dir))
    }

    private fun fmt() = Fmt(core.res())
    private fun devName(id: String) = core.peers.get(id)?.name ?: "?"

    fun view(): List<TransferView> = service.list().map {
        TransferView(it.id, it.dir, it.dev, it.name, it.kind, it.files, it.size, it.done, if (it.status == "active") it.speed else 0.0, it.status, it.`when`, it.error, location(it))
    }

    /** Where "open" goes: the received file itself, or the folder. */
    private fun location(t: Transfer): String =
        if (t.dir == "in" && t.items.size == 1 && !t.items[0].dir && t.items[0].dest.isNotEmpty()) t.items[0].dest else t.path

    private fun addHistory(t: Transfer, err: Boolean) {
        val verb = if (t.dir == "out") "sent" else "recv"
        val sub = when {
            err -> mapOf("k" to "h_err", "a" to listOf(mapOf("size" to t.size), t.error.ifEmpty { "error" }))
            t.kind == "folder" -> mapOf("k" to "h_folder_$verb", "a" to listOf(mapOf("files" to t.files), mapOf("size" to t.size)))
            else -> mapOf("k" to "h_file_$verb", "a" to listOf(mapOf("size" to t.size)))
        }
        core.storage.addHistory("files", t.dir, t.name, toJson(sub).toString(), t.dev, err, if (err) t.id else location(t))
        core.historyChanged()
    }

    private fun notice(kind: String, key: String, t: Transfer, args: Array<out String>) {
        val f = fmt()
        when (key) {
            "sent" -> core.toast(kind, R.string.t_done, core.str(R.string.t_arrow, t.name, devName(t.dev)))
            "failed" -> core.toast(kind, R.string.t_failed, t.name + ": " + f.err(args.firstOrNull() ?: ""))
            "declined" -> core.toast(kind, R.string.t_declined, t.name + " · " + devName(t.dev))
            "cancelled" -> core.toast(kind, R.string.t_cancelled, t.name + " · " + devName(t.dev))
        }
    }

    private fun offer(t: Transfer, auto: Boolean) {
        val text = core.str(R.string.t_offer_text, t.name, fmt().size(t.size), devName(t.dev))
        if (auto) core.toast("files", R.string.t_incoming, text, system = false)
        else {
            core.toast("files", R.string.t_ask, text, system = false)
            core.navigate("files")
        }
        core.notify.offer(t, auto, text)
    }

    private fun received(t: Transfer) {
        core.toast("files", R.string.t_received, core.str(R.string.t_from, t.name, devName(t.dev)), system = false)
        core.notify.received(t, location(t))
        if (core.settings.value.sounds) runCatching {
            RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }

    // ---- UI calls ---------------------------------------------------------
    fun send(dev: String, uris: List<Uri>, folder: Boolean) {
        core.scope.launch(Dispatchers.IO) {
            var n = 0
            for (u in uris) {
                Picker.persist(ctx, u)
                val p = runCatching { if (folder) Picker.tree(ctx, u) else Picker.file(ctx, u) }.getOrNull() ?: continue
                if (p.items.isEmpty()) continue
                n++
                launch { service.send(dev, p.name, p.items, p.path, p.isDir) }
            }
            if (n > 0) core.toast("files", R.string.t_queued, core.str(R.string.t_arrow, core.plural(if (folder) R.plurals.n_items else R.plurals.n_files, n), devName(dev)))
        }
    }

    fun open(tid: String) {
        val t = service.transfers[tid] ?: return
        openLocation(location(t))
    }

    fun historyAction(h: HistoryItem) {
        if (h.err) core.scope.launch {
            if (!service.retry(h.ref)) core.toast("bad", R.string.t_no_retry, core.str(R.string.t_no_retry_text))
        } else if (!openLocation(h.ref)) core.toast("bad", R.string.t_no_file, core.str(R.string.t_no_file_text))
    }

    /** Opens a received file, or the folder it is in. False when there is nothing to open. */
    fun openLocation(loc: String): Boolean {
        if (loc.isEmpty()) return false
        val intent = if (loc.startsWith("content://")) {
            val uri = Uri.parse(loc)
            val isDir = runCatching { DocumentsContract.isDocumentUri(ctx, uri) && ctx.contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR }.getOrDefault(false)
            if (!isDir && runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.close(); true }.getOrDefault(false).not()) return false
            val type = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR
            else ctx.contentResolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(loc.substringAfterLast('.', "")) ?: "*/*"
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        return runCatching {
            ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun setSaveDir(uri: Uri?) {
        if (uri != null) runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        core.settings.update { it.copy(saveDir = uri?.toString() ?: "") }
    }
}
