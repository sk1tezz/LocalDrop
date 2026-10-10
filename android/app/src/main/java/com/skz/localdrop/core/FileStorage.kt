package com.skz.localdrop.core

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.skz.localdrop.protocol.files.Inbox
import com.skz.localdrop.protocol.files.Item
import com.skz.localdrop.protocol.files.PART
import com.skz.localdrop.protocol.files.PartFile
import com.skz.localdrop.protocol.files.Source
import com.skz.localdrop.protocol.files.TransferError
import com.skz.localdrop.protocol.files.numbered
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

private fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

private fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

private fun ContentResolver.size(uri: Uri): Long =
    runCatching { openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L

/** A partial file behind a content uri, opened read-write at [offset]. */
private fun ContentResolver.part(uri: Uri, offset: Long): PartFile {
    val pfd = openFileDescriptor(uri, "rw") ?: throw TransferError("cannot write {0}", uri.toString())
    val out = FileOutputStream(pfd.fileDescriptor)
    val ch = out.channel
    if (ch.size() < offset) {
        pfd.close()
        throw TransferError("bad resume offset")
    }
    ch.truncate(offset)
    ch.position(offset)
    return object : PartFile {
        override val handle = uri.toString()
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() {
            runCatching { out.flush() }
            pfd.close()
        }
    }
}

/** Download/LocalDrop through MediaStore: no permission needed, visible in Files. Pending items act as .ldpart. */
class MediaStoreInbox(private val ctx: Context) : Inbox {
    private val cr = ctx.contentResolver
    private val base = Environment.DIRECTORY_DOWNLOADS + "/LocalDrop/"
    private val coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun freeSpace() = freeBytes()

    private fun exists(top: String): Boolean {
        val sel = "(${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?) OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(base, top, "$base$top/%")
        val q = android.os.Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return runCatching { cr.query(coll, arrayOf(MediaStore.MediaColumns._ID), q, null)?.use { it.count > 0 } }.getOrNull() == true ||
            java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalDrop/$top").exists()
    }

    override fun uniqueRoot(top: String): String {
        if (!exists(top)) return top
        for (n in 1 until 10000) numbered(top, n).let { if (!exists(it)) return it }
        throw TransferError("no free file name")
    }

    override fun mkdirs(rel: String) {}       // MediaStore keeps no empty folders

    override fun partLength(handle: String?) = handle?.let { cr.size(Uri.parse(it)) } ?: 0L

    override fun openPart(rel: String, handle: String?, offset: Long): PartFile {
        val existing = handle?.let { Uri.parse(it) }?.takeIf { runCatching { cr.openFileDescriptor(it, "r")?.close(); true }.getOrDefault(false) }
        val uri = existing ?: run {
            if (offset > 0) throw TransferError("bad resume offset")
            val name = rel.substringAfterLast('/')
            val dir = rel.substringBeforeLast('/', "")
            cr.insert(coll, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, base + if (dir.isEmpty()) "" else "$dir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw TransferError("cannot write {0}", rel)
        }
        return cr.part(uri, offset)
    }

    override fun readPart(handle: String): InputStream = cr.openInputStream(Uri.parse(handle)) ?: throw TransferError("bad resume offset")

    override fun finish(rel: String, handle: String): String {
        cr.update(Uri.parse(handle), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return handle
    }

    override fun deletePart(handle: String) { runCatching { cr.delete(Uri.parse(handle), null, null) } }

    override fun location(root: String) = base + root
}

/** A folder the user picked (SAF tree); partial files are "name.ldpart" documents renamed at the end. */
class SafInbox(private val ctx: Context, private val tree: Uri) : Inbox {
    private val cr = ctx.contentResolver
    private val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val dirs = HashMap<String, Uri>()

    override fun freeSpace() = freeBytes()

    private fun children(dir: Uri): Map<String, Uri> {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
        val out = HashMap<String, Uri>()
        cr.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return out
    }

    @Synchronized
    private fun dir(rel: String): Uri {
        if (rel.isEmpty()) return rootDoc
        dirs[rel]?.let { return it }
        val parent = dir(rel.substringBeforeLast('/', ""))
        val name = rel.substringAfterLast('/')
        val uri = children(parent)[name]
            ?: DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw TransferError("cannot write {0}", rel)
        dirs[rel] = uri
        return uri
    }

    override fun uniqueRoot(top: String): String {
        val names = children(rootDoc).keys
        fun taken(n: String) = n in names || "$n$PART" in names
        if (!taken(top)) return top
        for (n in 1 until 10000) numbered(top, n).let { if (!taken(it)) return it }
        throw TransferError("no free file name")
    }

    override fun mkdirs(rel: String) { dir(rel) }

    override fun partLength(handle: String?) = handle?.let { cr.size(Uri.parse(it)) } ?: 0L

    override fun openPart(rel: String, handle: String?, offset: Long): PartFile {
        val existing = handle?.let { Uri.parse(it) }?.takeIf { runCatching { cr.openFileDescriptor(it, "r")?.close(); true }.getOrDefault(false) }
        val uri = existing ?: run {
            if (offset > 0) throw TransferError("bad resume offset")
            DocumentsContract.createDocument(cr, dir(rel.substringBeforeLast('/', "")), "application/octet-stream", rel.substringAfterLast('/') + PART)
                ?: throw TransferError("cannot write {0}", rel)
        }
        return cr.part(uri, offset)
    }

    override fun readPart(handle: String): InputStream = cr.openInputStream(Uri.parse(handle)) ?: throw TransferError("bad resume offset")

    override fun finish(rel: String, handle: String): String {
        val name = rel.substringAfterLast('/')
        val names = children(dir(rel.substringBeforeLast('/', ""))).keys
        var final = name
        if (final in names) final = (1 until 10000).map { numbered(name, it) }.first { it !in names }
        return (DocumentsContract.renameDocument(cr, Uri.parse(handle), final) ?: Uri.parse(handle)).toString()
    }

    override fun deletePart(handle: String) { runCatching { DocumentsContract.deleteDocument(cr, Uri.parse(handle)) } }

    override fun location(root: String) = (children(rootDoc)[root] ?: rootDoc).toString()
}

/** Reads content uris (documents, photo picker, shares). */
class UriSource(private val ctx: Context) : Source {
    override fun open(src: String, offset: Long): InputStream {
        val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(src), "r") ?: throw java.io.FileNotFoundException(src)
        val inp = FileInputStream(pfd.fileDescriptor)
        if (offset > 0) inp.channel.position(offset)
        return object : java.io.FilterInputStream(inp) {
            override fun close() {
                super.close()
                pfd.close()
            }
        }
    }
}

/** One top-level selection: what goes into one transfer. */
class Picked(val name: String, val items: List<Item>, val isDir: Boolean, val path: String)

object Picker {
    /** Keep access across restarts so a transfer can resume (not every source allows it). */
    fun persist(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun file(ctx: Context, uri: Uri): Picked? {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size < 0) size = ctx.contentResolver.size(uri)
        val safe = name.replace('/', '_').ifEmpty { "file" }
        return Picked(safe, listOf(Item(safe, size, src = uri.toString())), false, uri.toString())
    }

    /** A folder chosen with OpenDocumentTree, walked recursively. */
    fun tree(ctx: Context, tree: Uri): Picked? {
        val cr = ctx.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun list(docId: String): List<Triple<String, String, Long>> {      // id, name, size (-1 = folder)
            val out = ArrayList<Triple<String, String, Long>>()
            cr.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Triple(c.getString(0), c.getString(1) ?: "?", if (dir) -1L else if (c.isNull(3)) 0L else c.getLong(3))
                }
            }
            return out.sortedBy { it.second }
        }
        val rootName = cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Folder"
        val items = ArrayList<Item>()
        fun walk(docId: String, rel: String) {
            val kids = list(docId)
            if (kids.isEmpty()) items += Item(rel, 0, dir = true)
            for ((id, name, size) in kids) {
                val clean = name.replace('/', '_')
                if (size < 0) walk(id, "$rel/$clean")
                else items += Item("$rel/$clean", size, src = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
            }
        }
        walk(rootId, rootName.replace('/', '_'))
        return Picked(rootName, items, true, tree.toString())
    }
}
