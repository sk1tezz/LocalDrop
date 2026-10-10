package com.skz.localdrop.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.skz.localdrop.protocol.net.Device as PeerDevice

/** SQLite: known devices, history, clipboard history, transfer state (schema of desktop/localdrop/storage.py). */
class Storage(ctx: Context) : SQLiteOpenHelper(ctx, "localdrop.sqlite", null, 1) {
    companion object {
        const val CLIP_LIMIT = 200
        const val HISTORY_DAYS = 90
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, os TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL DEFAULT '',
               icon TEXT NOT NULL DEFAULT 'computer', ip TEXT NOT NULL DEFAULT '', port INTEGER NOT NULL DEFAULT 0,
               paired INTEGER NOT NULL DEFAULT 0, trusted INTEGER NOT NULL DEFAULT 0, last_seen REAL NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL, type TEXT NOT NULL, dir TEXT NOT NULL,
               title TEXT NOT NULL, sub TEXT NOT NULL DEFAULT '', dev TEXT NOT NULL DEFAULT '', err INTEGER NOT NULL DEFAULT 0,
               ref TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX history_ts ON history(ts)")
        db.execSQL(
            """CREATE TABLE clips (id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL DEFAULT '',
               image TEXT NOT NULL DEFAULT '', meta TEXT NOT NULL DEFAULT '', dev TEXT NOT NULL DEFAULT 'me', hash TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE transfers (id TEXT PRIMARY KEY, dir TEXT NOT NULL, dev TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL,
               status TEXT NOT NULL, created REAL NOT NULL, data TEXT NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun now() = System.currentTimeMillis() / 1000.0

    private inline fun <T> rows(c: Cursor, f: (Cursor) -> T): List<T> = c.use { cur ->
        val out = ArrayList<T>()
        while (cur.moveToNext()) out += f(cur)
        out
    }

    private fun Cursor.s(col: String) = getString(getColumnIndexOrThrow(col)) ?: ""
    private fun Cursor.l(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.d(col: String) = getDouble(getColumnIndexOrThrow(col))

    // devices ---------------------------------------------------------------
    @Synchronized
    fun pairedDevices(): List<PeerDevice> = rows(readableDatabase.rawQuery("SELECT * FROM devices WHERE paired=1", null)) {
        PeerDevice(it.s("id"), it.s("name"), it.s("os"), it.s("kind"), it.s("icon"), it.s("ip"), it.l("port").toInt(),
            paired = true, trusted = it.l("trusted") != 0L, lastSeen = 0)
    }

    @Synchronized
    fun saveDevice(d: PeerDevice) {
        val v = ContentValues().apply {
            put("id", d.id); put("name", d.name); put("os", d.os); put("kind", d.kind); put("icon", d.icon); put("ip", d.ip)
            put("port", d.port); put("paired", if (d.paired) 1 else 0); put("trusted", if (d.trusted) 1 else 0); put("last_seen", d.lastSeen / 1000.0)
        }
        writableDatabase.insertWithOnConflict("devices", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun forgetDevice(id: String) {
        writableDatabase.execSQL("UPDATE devices SET paired=0, trusted=0 WHERE id=?", arrayOf(id))
    }

    // history ---------------------------------------------------------------
    @Synchronized
    fun addHistory(type: String, dir: String, title: String, sub: String = "", dev: String = "", err: Boolean = false, ref: String = ""): Long =
        writableDatabase.insert("history", null, ContentValues().apply {
            put("ts", now()); put("type", type); put("dir", dir); put("title", title); put("sub", sub); put("dev", dev)
            put("err", if (err) 1 else 0); put("ref", ref)
        })

    @Synchronized
    fun history(limit: Int = 500): List<HistoryItem> = rows(readableDatabase.rawQuery("SELECT * FROM history ORDER BY ts DESC LIMIT $limit", null)) {
        HistoryItem(it.l("id"), it.d("ts"), it.s("type"), it.s("dir"), it.s("title"), it.s("sub"), it.s("dev"), it.l("err") != 0L, it.s("ref"))
    }

    @Synchronized
    fun historyItem(id: Long): HistoryItem? = history(100_000).firstOrNull { it.id == id }

    @Synchronized
    fun clearHistory() = writableDatabase.execSQL("DELETE FROM history")

    @Synchronized
    fun prune() = writableDatabase.execSQL("DELETE FROM history WHERE ts < ?", arrayOf(now() - HISTORY_DAYS * 86400))

    // clips -----------------------------------------------------------------
    /** Insert a clip; returns its id and the image files of clips evicted by the limit. */
    @Synchronized
    fun addClip(type: String, text: String = "", image: String = "", meta: String = "", dev: String = "me", hash: String = ""): Pair<Long, List<String>> {
        val db = writableDatabase
        val id = db.insert("clips", null, ContentValues().apply {
            put("ts", now()); put("type", type); put("text", text); put("image", image); put("meta", meta); put("dev", dev); put("hash", hash)
        })
        val old = rows(db.rawQuery("SELECT id, image FROM clips ORDER BY id DESC LIMIT -1 OFFSET $CLIP_LIMIT", null)) { it.l("id") to it.s("image") }
        for ((oid, _) in old) db.delete("clips", "id=?", arrayOf(oid.toString()))
        return id to old.map { it.second }.filter { it.isNotEmpty() }
    }

    class ClipRow(val id: Long, val ts: Double, val type: String, val text: String, val image: String, val meta: String, val dev: String, val hash: String)

    @Synchronized
    fun clips(): List<ClipRow> = rows(readableDatabase.rawQuery("SELECT * FROM clips ORDER BY id DESC", null)) {
        ClipRow(it.l("id"), it.d("ts"), it.s("type"), it.s("text"), it.s("image"), it.s("meta"), it.s("dev"), it.s("hash"))
    }

    @Synchronized
    fun clip(id: Long): ClipRow? = clips().firstOrNull { it.id == id }

    @Synchronized
    fun deleteClip(id: Long): String {
        val img = clip(id)?.image ?: ""
        writableDatabase.delete("clips", "id=?", arrayOf(id.toString()))
        return img
    }

    @Synchronized
    fun clearClips(): List<String> {
        val imgs = clips().map { it.image }.filter { it.isNotEmpty() }
        writableDatabase.execSQL("DELETE FROM clips")
        return imgs
    }

    // transfers -------------------------------------------------------------
    class TransferRow(val id: String, val dir: String, val dev: String, val name: String, val kind: String, val status: String, val created: Double, val data: String)

    @Synchronized
    fun saveTransfer(t: TransferRow) {
        writableDatabase.insertWithOnConflict("transfers", null, ContentValues().apply {
            put("id", t.id); put("dir", t.dir); put("dev", t.dev); put("name", t.name); put("kind", t.kind); put("status", t.status)
            put("created", t.created); put("data", t.data)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun transfers(): List<TransferRow> = rows(readableDatabase.rawQuery("SELECT * FROM transfers ORDER BY created", null)) {
        TransferRow(it.s("id"), it.s("dir"), it.s("dev"), it.s("name"), it.s("kind"), it.s("status"), it.d("created"), it.s("data"))
    }

    @Synchronized
    fun deleteTransfer(id: String) {
        writableDatabase.delete("transfers", "id=?", arrayOf(id))
    }
}
