package com.skz.localdrop.core

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.FileProvider
import com.skz.localdrop.R
import com.skz.localdrop.protocol.clip.MAX_IMAGE
import com.skz.localdrop.protocol.clip.MAX_PIXELS
import com.skz.localdrop.protocol.clip.MAX_TEXT
import com.skz.localdrop.protocol.clip.argbToRgb
import com.skz.localdrop.protocol.clip.imageHash
import com.skz.localdrop.protocol.clip.textHash
import com.skz.localdrop.protocol.net.Device as PeerDevice
import com.skz.localdrop.protocol.net.Conn
import com.skz.localdrop.protocol.net.Msg
import com.skz.localdrop.protocol.net.Reply
import com.skz.localdrop.protocol.net.bool
import com.skz.localdrop.protocol.net.fail
import com.skz.localdrop.protocol.net.obj
import com.skz.localdrop.protocol.net.ok
import com.skz.localdrop.protocol.net.str
import com.skz.localdrop.protocol.net.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Clipboard sync (desktop/localdrop/clip/sync.py). Android lets an app read the clipboard only
 * while it has focus, so the phone's clipboard is picked up when the app is opened, by the
 * button, the Quick Settings tile and Share. Clips from trusted devices go straight into the
 * clipboard (when "receive automatically" is on); others only land in the history.
 */
class ClipGlue(private val core: Core) {
    private val ctx = core.ctx
    private val cm = ctx.getSystemService(ClipboardManager::class.java)
    private val dir = File(ctx.filesDir, "clips").apply { mkdirs() }
    @Volatile private var lastHash = ""        // last content seen on the local clipboard
    @Volatile private var appliedHash = ""     // last content we wrote ourselves

    /** A clip in memory: text, or an image with its PNG. */
    class Local(val ctype: String, val hash: String, val text: String = "", val png: ByteArray? = null, val w: Int = 0, val h: Int = 0)

    init {
        core.peers.on("clip", ::onClip)
        core.peers.pairedListeners.add { dev ->
            core.settings.update { s -> if (dev in s.clipDevices) s else s.copy(clipDevices = s.clipDevices + dev) }
        }
    }

    private val enabled get() = core.settings.value.featClip
    private fun typeOn(ctype: String) = if (ctype == "text") core.settings.value.clipText else core.settings.value.clipImage

    fun view(): List<Clip> = core.storage.clips().map {
        Clip(it.id, it.type, it.text, it.meta, it.dev, it.ts, if (it.image.isEmpty()) "" else File(dir, it.image).path)
    }

    private fun targets(manual: Boolean): List<String> {
        var chosen = core.settings.value.clipDevices.filter { core.peers.get(it)?.paired == true }
        if (chosen.isEmpty() && manual) chosen = core.peers.devices.values.filter { it.paired }.map { it.id }
        return chosen.filter { core.peers.connected(it) }
    }

    // ---- the local clipboard ----------------------------------------------
    /** Read the clipboard; works only while the app (or one of its activities) has focus. */
    fun read(): Local? {
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val desc = clip.description
        val uri = item.uri
        if (uri != null && (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }) {
            return runCatching { fromImage(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }.getOrNull()
        }
        val text = item.coerceToText(ctx)?.toString() ?: return null
        if (text.isBlank() || text.length > MAX_TEXT) return null
        return Local("text", textHash(text), text)
    }

    /** Decode any image, keep its pixels as a PNG, hash them like PIL does. */
    private fun fromImage(bytes: ByteArray): Local? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return Local("image", imageHash(bmp.width, bmp.height, argbToRgb(px)), png = png, w = bmp.width, h = bmp.height)
    }

    /** The app got focus: pick up a clipboard change made in another app. */
    fun onFocus() {
        if (!enabled) return
        core.scope.launch(Dispatchers.IO) {
            val c = read() ?: return@launch
            if (c.hash == lastHash || c.hash == appliedHash) {
                lastHash = c.hash
                return@launch
            }
            lastHash = c.hash
            if (!typeOn(c.ctype)) return@launch
            store(c, "me")
            if (core.settings.value.clipAuto) broadcast(c, targets(false), manual = false)
        }
    }

    private fun store(c: Local, dev: String): Long {
        val (id, evicted) = if (c.ctype == "text") core.storage.addClip("text", text = c.text, dev = dev, hash = c.hash)
        else {
            val name = c.hash.substring(1, 17) + ".png"
            val f = File(dir, name)
            if (!f.exists()) f.writeBytes(c.png!!)
            val meta = toJson(mapOf("k" to "clip_meta", "a" to listOf(c.w, c.h, mapOf("size" to f.length())))).toString()
            core.storage.addClip("image", text = "${c.w}×${c.h}", image = name, meta = meta, dev = dev, hash = c.hash)
        }
        cleanup(evicted)
        core.publish()
        return id
    }

    private fun cleanup(images: List<String>) {
        val used = core.storage.clips().map { it.image }.toSet()
        for (n in images) if (n.isNotEmpty() && n !in used) File(dir, n).delete()
    }

    // ---- network ----------------------------------------------------------
    private suspend fun broadcast(c: Local, targets: List<String>, manual: Boolean): Int {
        if (targets.isEmpty()) return 0
        val msg = mutableMapOf<String, Any?>("type" to "clip", "ctype" to c.ctype, "hash" to c.hash, "manual" to manual)
        var body = ByteArray(0)
        if (c.ctype == "text") msg["text"] = c.text else {
            body = c.png ?: return 0
            if (body.size > MAX_IMAGE) return 0
            msg["w"] = c.w
            msg["h"] = c.h
        }
        val header = toJson(msg) as kotlinx.serialization.json.JsonObject
        val results = withContext(Dispatchers.IO) { targets.map { d -> core.scope.async { core.peers.send(d, header, body) } }.awaitAll() }
        if (manual) for ((d, okay) in targets.zip(results)) if (okay) history(c, "out", d)
        return results.count { it }
    }

    private fun history(c: Local, dir: String, dev: String) {
        val verb = if (dir == "out") "sent" else "recv"
        val (title, key) = if (c.ctype == "text") c.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(200) to "h_text_$verb"
        else "${c.w}×${c.h}" to "h_image_$verb"
        core.storage.addHistory("clip", dir, title, toJson(mapOf("k" to key, "a" to emptyList<Any>())).toString(), dev, ref = c.hash)
        core.historyChanged()
    }

    private suspend fun onClip(d: PeerDevice, conn: Conn, msg: Msg): Reply {
        if (!enabled) return fail("disabled")
        val ctype = msg.header.str("ctype")
        val c = when (ctype) {
            "text" -> {
                val text = msg.header.str("text") ?: return fail()
                if (text.length > MAX_TEXT) return fail()
                Local("text", textHash(text), text)
            }
            "image" -> {
                if (msg.body.size > MAX_IMAGE) return fail()
                withContext(Dispatchers.IO) { runCatching { fromImage(msg.body) }.getOrNull() } ?: return fail()
            }
            else -> return fail()
        }
        if (!typeOn(c.ctype)) return fail("type disabled")
        store(c, d.id)
        history(c, "in", d.id)
        val kindText = core.str(if (c.ctype == "text") R.string.f_text else R.string.clip_image)
        if (d.trusted && core.settings.value.clipAuto) {
            apply(c)
            if (msg.header.bool("manual") == true) core.toast("clip", R.string.t_clip_updated, core.str(R.string.t_from, kindText, d.name), system = false)
        } else {
            core.toast("clip", R.string.t_clip_got, core.str(R.string.t_clip_got_text, d.name), system = false)
        }
        core.notify.clip(d.name, if (c.ctype == "text") c.text else "${c.w}×${c.h}", core.storage.clips().firstOrNull()?.id ?: 0)
        return ok()
    }

    /** Put a clip into the system clipboard. */
    private fun apply(c: Local) {
        appliedHash = c.hash
        lastHash = c.hash
        val data = if (c.ctype == "text") ClipData.newPlainText("LocalDrop", c.text) else {
            val share = File(ctx.cacheDir, "clipshare").apply { mkdirs() }
            share.listFiles()?.forEach { it.delete() }
            val f = File(share, c.hash.substring(1, 13) + ".png").apply { writeBytes(c.png!!) }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ClipData.newUri(ctx.contentResolver, "LocalDrop", uri)
        }
        runCatching { cm.setPrimaryClip(data) }.onFailure { Log.w(TAG, "writing the clipboard failed", it) }
    }

    private fun fromRow(r: Storage.ClipRow): Local? =
        if (r.type == "text") Local("text", r.hash.ifEmpty { textHash(r.text) }, r.text)
        else runCatching { fromImage(File(dir, r.image).readBytes()) }.getOrNull()

    // ---- UI calls ---------------------------------------------------------
    fun sendCurrent(dev: String? = null) {
        core.scope.launch(Dispatchers.IO) {
            val c = read()
            if (c == null) {
                core.toast("bad", R.string.t_clip_empty, core.str(R.string.t_clip_empty_text))
                return@launch
            }
            if (c.hash != lastHash) {
                lastHash = c.hash
                store(c, "me")
            }
            sendManual(c, dev)
        }
    }

    private suspend fun sendManual(c: Local, dev: String?) {
        val targets = if (dev != null) listOf(dev).filter { core.peers.connected(it) } else targets(true)
        val n = broadcast(c, targets, manual = true)
        if (n > 0) core.toast("clip", R.string.t_clip_sent, core.str(R.string.t_to, core.plural(R.plurals.n_devices, n)))
        else core.toast("bad", R.string.t_nobody, core.str(R.string.t_no_conn))
    }

    fun copy(id: Long) {
        core.scope.launch(Dispatchers.IO) {
            val c = core.storage.clip(id)?.let { fromRow(it) } ?: return@launch
            apply(c)
            core.toast("clip", R.string.t_copied, core.str(R.string.t_paste))
        }
    }

    fun send(id: Long) {
        core.scope.launch(Dispatchers.IO) {
            val c = core.storage.clip(id)?.let { fromRow(it) } ?: return@launch
            sendManual(c, null)
        }
    }

    fun delete(id: Long) {
        val img = core.storage.deleteClip(id)
        cleanup(listOf(img))
        core.publish()
    }

    fun clear() {
        cleanup(core.storage.clearClips())
        core.publish()
    }

    fun historyAction(h: HistoryItem) {
        val c = core.storage.clips().firstOrNull { it.hash == h.ref }
        if (c != null) copy(c.id) else core.toast("bad", R.string.t_clip_gone)
    }
}
