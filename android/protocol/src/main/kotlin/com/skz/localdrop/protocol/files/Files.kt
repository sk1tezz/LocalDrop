package com.skz.localdrop.protocol.files

import com.skz.localdrop.protocol.crypto.hex
import com.skz.localdrop.protocol.net.Conn
import com.skz.localdrop.protocol.net.Device
import com.skz.localdrop.protocol.net.FrameError
import com.skz.localdrop.protocol.net.Msg
import com.skz.localdrop.protocol.net.PeerManager
import com.skz.localdrop.protocol.net.Reply
import com.skz.localdrop.protocol.net.arr
import com.skz.localdrop.protocol.net.bool
import com.skz.localdrop.protocol.net.fail
import com.skz.localdrop.protocol.net.int
import com.skz.localdrop.protocol.net.json
import com.skz.localdrop.protocol.net.long
import com.skz.localdrop.protocol.net.obj
import com.skz.localdrop.protocol.net.ok
import com.skz.localdrop.protocol.net.str
import com.skz.localdrop.protocol.net.toJson
import com.skz.localdrop.protocol.net.truthy
import com.skz.localdrop.protocol.net.type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * File and folder transfers; a port of desktop/localdrop/files/transfer.py.
 *
 * Offer (control connection): file_offer {tid, name, kind, items:[{rel, size, dir?}]} -> {state: accepted | pending};
 * file_answer {tid, accept} later. Data (a "files" stream, hello carries tid): resume {have}, file {i, offset},
 * c (chunk) ..., end {i, hash}; the receiver confirms with prog {n} and ack {i, ok}; fin / fin_ok.
 * file_ctl {tid, op: pause | resume | cancel} from either side. SHA-256 per file.
 */
const val CHUNK = 1024 * 1024
const val WINDOW = 16L * 1024 * 1024
const val PROG_EVERY = 4L * 1024 * 1024
const val PROG_MS = 250L
const val STALL_MS = 30_000L
val FINAL = setOf("done", "error", "declined", "cancelled")

/** An error shown to the user: a key plus arguments, kept as {"k": key, "a": [...]} like the desktop. */
class TransferError(val key: String, vararg val args: String) : Exception(key) {
    fun text(): String = toJson(mapOf("k" to key, "a" to args.toList())).toString()
}

private val EXT = mapOf(
    "video" to setOf("mp4", "mov", "mkv", "avi", "webm", "m4v", "wmv", "3gp"),
    "image" to setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "raw", "dng"),
    "audio" to setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus"),
    "archive" to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg", "apk"),
    "doc" to setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "odt", "rtf", "md", "csv", "pages", "key"),
)

fun kindOf(name: String, isDir: Boolean = false): String {
    if (isDir) return "folder"
    val ext = name.substringAfterLast('.', "").lowercase()
    return EXT.entries.firstOrNull { ext in it.value }?.key ?: "file"
}

class Item(val rel: String, val size: Long, val dir: Boolean = false, val src: String = "", @Volatile var handle: String? = null, @Volatile var dest: String = "")

class Transfer(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val dir: String, val dev: String, val name: String, val kind: String = "file",
    val items: List<Item> = emptyList(), val size: Long = 0, val files: Int = 1,
    @Volatile var status: String = "queued", @Volatile var done: Long = 0,
    val path: String = "", val complete: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
    @Volatile var error: String = "", @Volatile var `when`: Double = 0.0,
    val created: Double = System.currentTimeMillis() / 1000.0, @Volatile var offered: Boolean = false,
) {
    @Volatile var speed = 0.0
    @Volatile var job: Job? = null
    @Volatile var conn: Conn? = null
    @Volatile var retryAt = 0L
    private var rateT = System.currentTimeMillis()
    private var rateB = done

    val final get() = status in FINAL

    fun resetRate() {
        rateT = System.currentTimeMillis(); rateB = done; speed = 0.0
    }

    fun rate() {
        val now = System.currentTimeMillis()
        val dt = (now - rateT) / 1000.0
        if (dt >= 0.5) {
            val inst = (done - rateB) / dt
            speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
            rateT = now; rateB = done
        }
    }

    /** Everything needed to continue after a restart (the "data" column of the desktop table). */
    fun data(): String = toJson(
        mapOf(
            "items" to items.map {
                mapOf("rel" to it.rel, "size" to it.size, "dir" to it.dir, "src" to it.src, "handle" to it.handle, "dest" to it.dest)
            },
            "size" to size, "files" to files, "done" to done, "path" to path, "complete" to complete.toList(),
            "error" to error, "when" to `when`, "offered" to offered,
        )
    ).toString()

    companion object {
        fun restore(id: String, dir: String, dev: String, name: String, kind: String, status: String, created: Double, data: String): Transfer {
            val o = json.parseToJsonElement(data) as JsonObject
            val items = (o.arr("items") ?: JsonArray(emptyList())).map { e ->
                val i = e as JsonObject
                Item(i.str("rel") ?: "", i.long("size") ?: 0, i.bool("dir") == true, i.str("src") ?: "", i.str("handle"), i.str("dest") ?: "")
            }
            return Transfer(
                id, dir, dev, name, kind, items, o.long("size") ?: 0, o.int("files") ?: 1, status, o.long("done") ?: 0,
                o.str("path") ?: "", ConcurrentHashMap.newKeySet<Int>().apply { o.arr("complete")?.forEach { add((it as JsonPrimitive).content.toInt()) } },
                o.str("error") ?: "", (o["when"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0, created, o.bool("offered") == true,
            )
        }
    }
}

interface FileHost {
    fun enabled(): Boolean
    fun autoAccept(): Boolean
    fun inbox(): Inbox
    fun source(): Source
    fun save(t: Transfer)
    fun delete(id: String)
    /** The list changed; [now] = publish without throttling. */
    fun changed(now: Boolean)
    fun history(t: Transfer, err: Boolean)
    fun notice(kind: String, key: String, t: Transfer, vararg args: String)
    fun incomingOffer(t: Transfer, auto: Boolean)
    fun received(t: Transfer)
    fun log(msg: String) {}
}

private fun sha256() = MessageDigest.getInstance("SHA-256")

private fun isNetError(e: Throwable) = e is IOException || e is FrameError || e is kotlinx.coroutines.TimeoutCancellationException

class FileService(private val host: FileHost, private val peers: PeerManager, private val scope: CoroutineScope, saved: List<Transfer> = emptyList()) {
    val transfers = ConcurrentHashMap<String, Transfer>()
    private var pubAt = 0L
    private var loop: Job? = null

    init {
        for (t in saved) {
            if (t.status == "active" || t.status == "queued") t.status = "offline"
            transfers[t.id] = t
        }
        peers.on("file_offer", ::onOffer)
        peers.on("file_answer", ::onAnswer)
        peers.on("file_ctl", ::onCtl)
        peers.onStream("files", ::onStream)
        peers.onlineListeners.add { _, online -> if (online) scope.launch { kick() } }
    }

    fun start() {
        loop = scope.launch {
            while (isActive) {
                delay(2000)
                kick()
                var any = false
                for (t in transfers.values) if (t.status == "active") {
                    any = true
                    t.rate()
                    host.save(t)
                }
                if (any) publish(true)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        for (t in transfers.values) {
            t.job?.cancel()
            if (t.status == "active") t.status = "offline"
            host.save(t)
        }
    }

    fun list(): List<Transfer> = transfers.values.sortedBy { it.created }

    private fun set(t: Transfer, status: String, error: String = "") {
        t.status = status
        t.error = error
        if (status in FINAL) {
            t.`when` = System.currentTimeMillis() / 1000.0
            t.speed = 0.0
        }
        host.save(t)
        publish(true)
    }

    private fun publish(now: Boolean = false) {
        val t = System.currentTimeMillis()
        if (now || t - pubAt > 400) {
            pubAt = t
            host.changed(now)
        }
    }

    // ---- sending ----------------------------------------------------------
    /** Queue one top-level file or folder (already scanned by the app) for [dev]. */
    suspend fun send(dev: String, name: String, items: List<Item>, path: String, isDir: Boolean): Transfer? {
        val d = peers.get(dev) ?: return null
        if (!d.paired) return null
        val t = Transfer(dir = "out", dev = dev, name = name, kind = kindOf(name, isDir), items = items, size = items.sumOf { it.size },
            files = items.count { !it.dir }, path = path, status = "waiting")
        transfers[t.id] = t
        host.save(t)
        publish(true)
        offer(t)
        return t
    }

    private suspend fun offer(t: Transfer) {
        val items = t.items.map { if (it.dir) mapOf("rel" to it.rel, "size" to it.size, "dir" to true) else mapOf("rel" to it.rel, "size" to it.size) }
        val msg = obj("type" to "file_offer", "tid" to t.id, "name" to t.name, "kind" to t.kind, "size" to t.size, "files" to t.files, "items" to items)
        val reply = try {
            peers.request(t.dev, msg, timeoutMs = 20_000).header
        } catch (e: Exception) {
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            t.offered = false
            set(t, "offline")
            return
        }
        t.offered = true
        if (reply.bool("ok") != true) {
            val err = reply.str("error")
            if (err == "declined") set(t, "declined") else {
                set(t, "error", TransferError(err ?: "error").text())
                host.history(t, true)
            }
            return
        }
        set(t, if (reply.str("state") == "accepted") "queued" else "waiting")
        kick()
    }

    /** Start the next outgoing transfer if none is running. */
    @Synchronized
    fun kick() {
        if (transfers.values.any { it.dir == "out" && it.status == "active" }) return
        val now = System.currentTimeMillis()
        for (t in list()) {
            if (t.dir != "out" || now < t.retryAt) continue
            if (t.status == "offline" && peers.connected(t.dev)) {
                if (!t.offered) {
                    t.retryAt = now + 3000
                    scope.launch { offer(t) }
                    continue
                }
            } else if (t.status != "queued") continue
            if (!peers.connected(t.dev)) {
                if (t.status != "offline") set(t, "offline")
                peers.ensureControl(t.dev)
                continue
            }
            t.status = "active"
            t.job = scope.launch(Dispatchers.IO) { runSend(t) }
            publish(true)
            return
        }
    }

    private suspend fun runSend(t: Transfer) {
        try {
            sendStream(t)
        } catch (e: TransferError) {
            host.log("transfer ${t.name} failed: ${e.key}")
            set(t, "error", e.text())
            host.history(t, true)
            host.notice("bad", "failed", t, e.text())
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) netFail(t, e) else if (t.status == "active") set(t, "paused")
        } catch (e: Exception) {
            if (isNetError(e)) netFail(t, e) else {
                host.log("transfer ${t.name} crashed: $e")
                set(t, "error", TransferError("error", e.toString()).text())
            }
        } finally {
            t.conn?.close()
            t.conn = null
            t.job = null
            t.speed = 0.0
            publish(true)
            scope.launch { delay(100); kick() }
        }
    }

    private fun netFail(t: Transfer, e: Throwable) {
        host.log("transfer ${t.name} interrupted: $e")
        if (t.status == "active") {
            t.retryAt = System.currentTimeMillis() + 3000
            set(t, "offline")
        }
    }

    /** Messages the receiver sends back on a data stream, read in the background. */
    private class Acks(val conn: Conn, base: Long) {
        val acked = MutableStateFlow(base)
        @Volatile var last = System.currentTimeMillis()
        val queue = Channel<JsonObject>(Channel.UNLIMITED)
        @Volatile var error: Throwable? = null
        var onProgress: ((Long) -> Unit)? = null

        suspend fun run() {
            try {
                while (true) {
                    val m = conn.recv()
                    last = System.currentTimeMillis()
                    if (m.type == "prog") {
                        val n = m.header.long("n") ?: 0
                        if (n > acked.value) acked.value = n
                        onProgress?.invoke(acked.value)
                    } else queue.send(m.header)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
                queue.close()
                acked.value = acked.value      // wake up
            }
        }

        fun check() {
            error?.let { throw IOException("stream closed: $it") }
            if (System.currentTimeMillis() - last > STALL_MS) throw IOException("the receiver stopped confirming data")
        }

        /** Wait until no more than WINDOW bytes are unconfirmed. */
        suspend fun window(sent: Long) {
            while (sent - acked.value > WINDOW) {
                check()
                withTimeoutOrNull(1000) { acked.first { sent - it <= WINDOW || error != null } }
            }
            check()
        }

        suspend fun next(timeoutMs: Long): JsonObject {
            val end = System.currentTimeMillis() + timeoutMs
            while (true) {
                check()
                val r = withTimeoutOrNull(1000) { queue.receiveCatching() }
                if (r == null) {
                    if (System.currentTimeMillis() > end) throw IOException("no answer from the receiver")
                    continue
                }
                r.getOrNull()?.let { return it }
                check()
                throw IOException("stream closed")
            }
        }
    }

    private suspend fun sendStream(t: Transfer) {
        val conn = peers.openStream(t.dev, "files", mapOf("tid" to t.id))
        t.conn = conn
        val msg = conn.recv(15_000)
        if (msg.type != "resume") throw TransferError(msg.header.str("error") ?: "error")
        val have = (msg.header.arr("have") ?: JsonArray(emptyList())).map { (it as JsonPrimitive).content.toLong() }
        if (have.size != t.items.size) throw TransferError("list mismatch")
        t.done = t.items.zip(have).sumOf { (it, h) -> if (h == -1L) it.size else minOf(h, it.size) }
        t.resetRate()
        publish(true)
        val acks = Acks(conn, t.done)
        acks.onProgress = { n ->
            t.done = minOf(n, t.size)
            t.rate()
            publish()
        }
        val reader = scope.launch(Dispatchers.IO) { acks.run() }
        try {
            sendItems(t, conn, have, acks)
        } finally {
            reader.cancel()
        }
    }

    private suspend fun sendItems(t: Transfer, conn: Conn, have: List<Long>, acks: Acks) = withContext(Dispatchers.IO) {
        val src = host.source()
        var pos = t.done
        val buf = ByteArray(CHUNK)
        for ((i, pair) in t.items.zip(have).withIndex()) {
            val (it, h) = pair
            if (h == -1L || it.dir) continue
            val offset = h.coerceIn(0, it.size)
            val hasher = sha256()
            var sent = offset
            try {
                if (offset > 0) src.open(it.src, 0).use { inp -> feed(inp, offset, hasher) }
                src.open(it.src, offset).use { inp ->
                    conn.send(obj("type" to "file", "i" to i, "offset" to offset))
                    while (sent < it.size) {
                        val n = readUpTo(inp, buf, minOf(CHUNK.toLong(), it.size - sent).toInt())
                        if (n <= 0) break
                        hasher.update(buf, 0, n)
                        acks.window(pos)
                        conn.send(obj("type" to "c"), buf.copyOf(n))
                        sent += n
                        pos += n
                    }
                }
            } catch (e: java.io.FileNotFoundException) {
                throw TransferError("read failed", it.rel)
            } catch (e: SecurityException) {
                throw TransferError("read failed", it.rel)
            }
            if (sent != it.size) throw TransferError("changed", it.rel)
            conn.send(obj("type" to "end", "i" to i, "hash" to hasher.digest().hex()))
            val ack = acks.next(120_000)
            if (ack.bool("ok") != true) throw TransferError("hash", it.rel)
        }
        conn.send(obj("type" to "fin"))
        val ack = acks.next(60_000)
        if (ack.type != "fin_ok") throw TransferError("no fin")
        t.done = t.size
        set(t, "done")
        host.history(t, false)
        host.notice("files", "sent", t)
    }

    private fun readUpTo(inp: InputStream, buf: ByteArray, n: Int): Int {
        var got = 0
        while (got < n) {
            val r = inp.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    private fun feed(inp: InputStream, n: Long, h: MessageDigest) {
        val b = ByteArray(CHUNK)
        var left = n
        while (left > 0) {
            val r = inp.read(b, 0, minOf(b.size.toLong(), left).toInt())
            if (r < 0) break
            h.update(b, 0, r)
            left -= r
        }
    }

    // ---- receiving --------------------------------------------------------
    private suspend fun onOffer(d: Device, conn: Conn, msg: Msg): Reply {
        if (!host.enabled()) return fail("disabled")
        val tid = msg.header.str("tid") ?: ""
        if (!Regex("[0-9a-f]{32}").matches(tid)) return fail("bad offer")
        transfers[tid]?.let { return ok("state" to if (it.status == "ask") "pending" else "accepted") }
        val items = try {
            val raw = msg.header.arr("items") ?: JsonArray(emptyList())
            raw.map { e ->
                val o = e as JsonObject
                Item(safeRel(o.str("rel") ?: ""), maxOf(0, o.long("size") ?: 0), o.truthy("dir"))
            }.also { if (it.isEmpty()) throw TransferError("empty offer") }
        } catch (e: Exception) {
            return fail("bad offer")
        }
        val tops = items.map { it.rel.substringBefore('/') }.toSet()
        if (tops.size != 1) return fail("bad offer")
        val size = items.sumOf { it.size }
        val inbox = host.inbox()
        if (inbox.freeSpace() < size + 50L * 1024 * 1024) return fail("space")
        val top = tops.first()
        val root = inbox.uniqueRoot(top)
        val placed = items.map { Item(root + it.rel.removePrefix(top), it.size, it.dir) }
        val kind = if (items.size > 1 || '/' in items[0].rel || items[0].dir) "folder" else kindOf(top)
        val auto = d.trusted && host.autoAccept()
        val t = Transfer(tid, "in", d.id, root, kind, placed, size, placed.count { !it.dir }, if (auto) "queued" else "ask",
            path = inbox.location(root), offered = true)
        transfers[tid] = t
        host.save(t)
        publish(true)
        host.incomingOffer(t, auto)
        return ok("state" to if (auto) "accepted" else "pending")
    }

    suspend fun respond(tid: String, accept: Boolean) {
        val t = transfers[tid] ?: return
        if (t.status != "ask") return
        set(t, if (accept) "queued" else "declined")
        if (!accept) {
            transfers.remove(tid)
            host.delete(tid)
            publish(true)
        }
        peers.send(t.dev, obj("type" to "file_answer", "tid" to tid, "accept" to accept))
    }

    private suspend fun onAnswer(d: Device, conn: Conn, msg: Msg): Reply? {
        val t = transfers[msg.header.str("tid") ?: ""] ?: return null
        if (t.dir != "out" || t.dev != d.id || t.status != "waiting") return null
        if (msg.header.bool("accept") == true) {
            set(t, "queued")
            kick()
        } else {
            set(t, "declined")
            host.notice("bad", "declined", t)
        }
        return null
    }

    private fun partHave(t: Transfer): List<Long> = t.items.mapIndexed { i, it ->
        if (i in t.complete || it.dir) -1L else host.inbox().partLength(it.handle)
    }

    private suspend fun onStream(d: Device, conn: Conn, hello: JsonObject) {
        val t = transfers[hello.str("tid") ?: ""]
        if (t == null || t.dir != "in" || t.dev != d.id) {
            conn.send(obj("type" to "error", "error" to "unknown"))
            return
        }
        if (t.status !in setOf("queued", "offline", "active")) {
            conn.send(obj("type" to "error", "error" to t.status))
            return
        }
        val inbox = host.inbox()
        withContext(Dispatchers.IO) { for (it in t.items) if (it.dir) inbox.mkdirs(it.rel) }
        val have = withContext(Dispatchers.IO) { partHave(t) }
        t.done = t.items.zip(have).sumOf { (it, h) -> if (h == -1L) it.size else minOf(h, it.size) }
        t.resetRate()
        t.conn = conn
        set(t, "active")
        conn.send(obj("type" to "resume", "have" to have))
        var part: PartFile? = null
        var hasher: MessageDigest? = null
        var cur = -1
        var confirmed = t.done
        var confirmedAt = System.currentTimeMillis()
        try {
            while (true) {
                val m = conn.recv((STALL_MS * 2).toInt())
                when (m.type) {
                    "c" -> withContext(Dispatchers.IO) {
                        val p = part ?: throw TransferError("data without file")
                        p.write(m.body, 0, m.body.size)
                        hasher!!.update(m.body)
                        t.done += m.body.size
                        if (t.done - confirmed >= PROG_EVERY || System.currentTimeMillis() - confirmedAt >= PROG_MS) {
                            confirmed = t.done
                            confirmedAt = System.currentTimeMillis()
                            conn.send(obj("type" to "prog", "n" to t.done))
                        }
                        t.rate()
                        publish()
                    }
                    "file" -> withContext(Dispatchers.IO) {
                        cur = m.header.int("i") ?: throw TransferError("bad message")
                        val it = t.items[cur]
                        val offset = m.header.long("offset") ?: 0
                        if (offset > 0 && inbox.partLength(it.handle) < offset) throw TransferError("bad resume offset")
                        val h = sha256()
                        if (offset > 0) inbox.readPart(it.handle!!).use { inp -> feed(inp, offset, h) }
                        part?.close()
                        val p = inbox.openPart(it.rel, it.handle, offset)
                        part = p
                        hasher = h
                        if (it.handle != p.handle) {
                            it.handle = p.handle
                            host.save(t)
                        }
                    }
                    "end" -> withContext(Dispatchers.IO) {
                        val it = t.items[cur]
                        part?.close()
                        part = null
                        if (hasher!!.digest().hex() != m.header.str("hash")) {
                            it.handle?.let { h -> inbox.deletePart(h) }
                            it.handle = null
                            t.done -= it.size
                            conn.send(obj("type" to "ack", "i" to cur, "ok" to false))
                            throw TransferError("hash", it.rel)
                        }
                        it.dest = inbox.finish(it.rel, it.handle!!)
                        t.complete.add(cur)
                        host.save(t)
                        conn.send(obj("type" to "ack", "i" to cur, "ok" to true))
                    }
                    "fin" -> {
                        t.done = t.size
                        set(t, "done")
                        conn.send(obj("type" to "fin_ok"))
                        host.history(t, false)
                        host.received(t)
                        return
                    }
                }
            }
        } catch (e: TransferError) {
            set(t, "error", e.text())
            host.history(t, true)
        } catch (e: Exception) {
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            if (t.status == "active") set(t, "offline")
        } finally {
            runCatching { part?.close() }
            t.conn = null
            t.speed = 0.0
            if (t.status == "cancelled") deleteParts(t)
            publish(true)
        }
    }

    // ---- control ----------------------------------------------------------
    private suspend fun onCtl(d: Device, conn: Conn, msg: Msg): Reply? {
        val t = transfers[msg.header.str("tid") ?: ""] ?: return null
        if (t.dev != d.id) return null
        when (msg.header.str("op")) {
            "cancel" -> {
                localCancel(t)
                host.notice("bad", "cancelled", t)
            }
            "pause" -> if (t.status !in FINAL) localPause(t)
            "resume" -> if (t.status == "paused") {
                set(t, "queued")
                if (t.dir == "out") kick()
            }
        }
        return null
    }

    private fun localPause(t: Transfer) {
        set(t, "paused")
        t.job?.cancel()
        t.conn?.close()
    }

    private fun localCancel(t: Transfer) {
        t.job?.cancel()
        val hadConn = t.conn != null
        t.conn?.close()
        set(t, "cancelled")
        if (t.dir == "in" && !hadConn) deleteParts(t)       // otherwise the stream deletes them once closed
    }

    private fun deleteParts(t: Transfer) {
        for ((i, it) in t.items.withIndex()) {
            if (i !in t.complete && !it.dir) it.handle?.let { h -> runCatching { host.inbox().deletePart(h) } }
        }
    }

    suspend fun toggle(tid: String) {
        val t = transfers[tid] ?: return
        if (t.status in FINAL || t.status == "ask" || t.status == "waiting") return
        if (t.status == "paused") {
            set(t, "queued")
            peers.send(t.dev, obj("type" to "file_ctl", "tid" to tid, "op" to "resume"))
            if (t.dir == "out") kick()
        } else {
            localPause(t)
            peers.send(t.dev, obj("type" to "file_ctl", "tid" to tid, "op" to "pause"))
        }
    }

    suspend fun cancel(tid: String) {
        val t = transfers[tid] ?: return
        if (t.status in FINAL) return
        localCancel(t)
        peers.send(t.dev, obj("type" to "file_ctl", "tid" to tid, "op" to "cancel"))
        if (t.dir == "out") kick()
    }

    /** Send a failed outgoing transfer again from the start. */
    suspend fun retry(tid: String): Boolean {
        val t = transfers[tid] ?: return false
        if (t.dir != "out" || t.status != "error") return false
        transfers.remove(tid)
        host.delete(tid)
        send(t.dev, t.name, t.items.map { Item(it.rel, it.size, it.dir, it.src) }, t.path, t.kind == "folder")
        return true
    }

    suspend fun pauseAll() { for (t in list()) if (t.status in setOf("active", "queued", "offline")) toggle(t.id) }
    suspend fun resumeAll() { for (t in list()) if (t.status == "paused") toggle(t.id) }

    fun clearDone() {
        for (t in list()) if (t.status in FINAL) {
            transfers.remove(t.id)
            host.delete(t.id)
        }
        publish(true)
    }
}
