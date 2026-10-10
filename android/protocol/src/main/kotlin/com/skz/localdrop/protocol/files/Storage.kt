package com.skz.localdrop.protocol.files

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Where received files go. Paths are relative to the inbox ("Photos/2026/a.jpg"); a *handle*
 * names a partial file between sessions (a content uri on Android, a path in tests).
 */
interface Inbox {
    fun freeSpace(): Long

    /** A top-level name that does not collide with existing files: "a.txt", "a (1).txt". */
    fun uniqueRoot(top: String): String

    fun mkdirs(rel: String)

    /** Bytes already stored for a partial file, 0 if none. */
    fun partLength(handle: String?): Long

    /** Open (creating if needed) the partial file for [rel], truncated to [offset]. */
    fun openPart(rel: String, handle: String?, offset: Long): PartFile

    fun readPart(handle: String): InputStream

    /** Make the partial file a normal file; returns where it ended up (for "open"). */
    fun finish(rel: String, handle: String): String

    fun deletePart(handle: String)

    /** Location of a received root (file or folder) for "open". */
    fun location(root: String): String
}

interface PartFile {
    val handle: String
    fun write(b: ByteArray, off: Int, len: Int)
    fun close()
}

/** Reads the files being sent; [src] is what the app put into the item (uri or path). */
interface Source {
    fun open(src: String, offset: Long): InputStream
}

const val PART = ".ldpart"

private val BAD_CHARS = Regex("[<>:\"|?*\\x00-\\x1f]")

/** A relative path from a peer, made safe to use under the inbox (desktop transfer.safe_rel). */
fun safeRel(rel: String): String {
    val parts = rel.replace('\\', '/').split('/').mapNotNull { p ->
        if (p.isEmpty() || p == "." || p == "..") return@mapNotNull null
        val c = BAD_CHARS.replace(p, "_").trimEnd(' ', '.')
        c.ifEmpty { null }?.take(200)
    }
    if (parts.isEmpty()) throw TransferError("bad path")
    return parts.joinToString("/")
}

/** "name (n).ext" candidates, like desktop unique_path. */
fun numbered(name: String, n: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
}

/** Plain files under a directory: used in tests (and fits any java.io.File storage). */
class DirInbox(val root: File) : Inbox {
    init { root.mkdirs() }

    override fun freeSpace() = root.usableSpace

    private fun taken(f: File) = f.exists() || File(f.path + PART).exists()

    override fun uniqueRoot(top: String): String {
        if (!taken(File(root, top))) return top
        for (n in 1 until 10000) numbered(top, n).let { if (!taken(File(root, it))) return it }
        throw TransferError("no free file name")
    }

    override fun mkdirs(rel: String) { File(root, rel).mkdirs() }

    override fun partLength(handle: String?) = handle?.let { File(it).takeIf { f -> f.exists() }?.length() } ?: 0L

    override fun openPart(rel: String, handle: String?, offset: Long): PartFile {
        val f = File(handle ?: (File(root, rel).path + PART))
        f.parentFile?.mkdirs()
        val raf = RandomAccessFile(f, "rw")
        if (raf.length() < offset) {
            raf.close()
            throw TransferError("bad resume offset")
        }
        raf.setLength(offset)
        raf.seek(offset)
        return object : PartFile {
            override val handle = f.path
            override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
            override fun close() = raf.close()
        }
    }

    override fun readPart(handle: String): InputStream = File(handle).inputStream()

    override fun finish(rel: String, handle: String): String {
        var target = File(root, rel)
        if (target.exists()) {
            val name = target.name
            for (n in 1 until 10000) {
                val c = File(target.parentFile, numbered(name, n))
                if (!c.exists()) { target = c; break }
            }
        }
        if (!File(handle).renameTo(target)) throw TransferError("cannot write {0}", rel)
        return target.path
    }

    override fun deletePart(handle: String) { File(handle).delete() }

    override fun location(root: String) = File(this.root, root).path
}

class FileSource : Source {
    override fun open(src: String, offset: Long): InputStream = File(src).inputStream().also { it.skipFully(offset) }
}

fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s <= 0) {
            if (read() < 0) throw java.io.EOFException("short file")
            left--
        } else left -= s
    }
}

/** Items for one top-level file or folder on disk (desktop transfer.scan); used by tests and JVM tools. */
fun scanFile(path: File): List<Item> {
    if (path.isFile) return listOf(Item(path.name, path.length(), src = path.path))
    val out = ArrayList<Item>()
    fun walk(dir: File, rel: String) {
        val kids = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (kids.isEmpty()) out += Item(rel, 0, dir = true)
        for (k in kids) if (k.isDirectory) walk(k, "$rel/${k.name}") else out += Item("$rel/${k.name}", k.length(), src = k.path)
    }
    walk(path, path.name)
    return out
}
