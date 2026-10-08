"""SQLite storage: known devices, history, clipboard history, transfer state."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

CLIP_LIMIT = 200
HISTORY_DAYS = 90

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id        TEXT PRIMARY KEY,          -- sha256 of the device certificate
    name      TEXT NOT NULL,
    os        TEXT NOT NULL DEFAULT '',
    kind      TEXT NOT NULL DEFAULT '',  -- windows | macos
    icon      TEXT NOT NULL DEFAULT 'computer',
    ip        TEXT NOT NULL DEFAULT '',
    port      INTEGER NOT NULL DEFAULT 0,
    paired    INTEGER NOT NULL DEFAULT 0,
    trusted   INTEGER NOT NULL DEFAULT 0,
    last_seen REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS history (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    ts     REAL NOT NULL,
    type   TEXT NOT NULL,                -- files | clip | audio
    dir    TEXT NOT NULL,                -- in | out
    title  TEXT NOT NULL,
    sub    TEXT NOT NULL DEFAULT '',
    dev    TEXT NOT NULL DEFAULT '',
    err    INTEGER NOT NULL DEFAULT 0,
    ref    TEXT NOT NULL DEFAULT ''      -- path or transfer id for the row action
);
CREATE INDEX IF NOT EXISTS history_ts ON history(ts);
CREATE TABLE IF NOT EXISTS clips (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    ts     REAL NOT NULL,
    type   TEXT NOT NULL,                -- text | image
    text   TEXT NOT NULL DEFAULT '',
    image  TEXT NOT NULL DEFAULT '',     -- file name inside clips dir
    meta   TEXT NOT NULL DEFAULT '',
    dev    TEXT NOT NULL DEFAULT 'me',
    hash   TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS transfers (
    id       TEXT PRIMARY KEY,
    dir      TEXT NOT NULL,
    dev      TEXT NOT NULL,
    name     TEXT NOT NULL,
    kind     TEXT NOT NULL,
    status   TEXT NOT NULL,
    created  REAL NOT NULL,
    data     TEXT NOT NULL               -- json: manifest, paths, progress
);
"""


class Storage:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def _all(self, sql: str, args: tuple = ()) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self._db.execute(sql, args).fetchall()]

    def _one(self, sql: str, args: tuple = ()) -> dict | None:
        with self._lock:
            r = self._db.execute(sql, args).fetchone()
            return dict(r) if r else None

    def _exec(self, sql: str, args: tuple = ()) -> int:
        with self._lock:
            cur = self._db.execute(sql, args)
            return cur.lastrowid

    # devices ---------------------------------------------------------------
    def devices(self) -> list[dict]:
        return self._all("SELECT * FROM devices ORDER BY name COLLATE NOCASE")

    def device(self, dev_id: str) -> dict | None:
        return self._one("SELECT * FROM devices WHERE id=?", (dev_id,))

    def upsert_device(self, dev_id: str, **fields: Any) -> None:
        cur = self.device(dev_id)
        if cur is None:
            row = {"id": dev_id, "name": fields.get("name", "?"), **fields}
            cols = ",".join(row)
            self._exec(f"INSERT INTO devices ({cols}) VALUES ({','.join('?' * len(row))})", tuple(row.values()))
        elif fields:
            sets = ",".join(f"{k}=?" for k in fields)
            self._exec(f"UPDATE devices SET {sets} WHERE id=?", (*fields.values(), dev_id))

    def forget_device(self, dev_id: str) -> None:
        self._exec("UPDATE devices SET paired=0, trusted=0 WHERE id=?", (dev_id,))

    # history ---------------------------------------------------------------
    def add_history(self, type_: str, dir_: str, title: str, sub: str = "", dev: str = "",
                    err: bool = False, ref: str = "", ts: float | None = None) -> int:
        return self._exec(
            "INSERT INTO history (ts,type,dir,title,sub,dev,err,ref) VALUES (?,?,?,?,?,?,?,?)",
            (ts or time.time(), type_, dir_, title, sub, dev, int(err), ref))

    def history(self, limit: int = 500) -> list[dict]:
        return self._all("SELECT * FROM history ORDER BY ts DESC LIMIT ?", (limit,))

    def history_item(self, hid: int) -> dict | None:
        return self._one("SELECT * FROM history WHERE id=?", (hid,))

    def clear_history(self) -> None:
        self._exec("DELETE FROM history")

    def prune(self) -> None:
        self._exec("DELETE FROM history WHERE ts < ?", (time.time() - HISTORY_DAYS * 86400,))

    # clips -----------------------------------------------------------------
    def add_clip(self, type_: str, text: str = "", image: str = "", meta: str = "",
                 dev: str = "me", hash_: str = "") -> tuple[int, list[str]]:
        """Insert a clip; returns its id and image files of clips evicted by the limit."""
        cid = self._exec("INSERT INTO clips (ts,type,text,image,meta,dev,hash) VALUES (?,?,?,?,?,?,?)",
                         (time.time(), type_, text, image, meta, dev, hash_))
        old = self._all("SELECT id,image FROM clips ORDER BY id DESC LIMIT -1 OFFSET ?", (CLIP_LIMIT,))
        if old:
            self._exec(f"DELETE FROM clips WHERE id IN ({','.join('?' * len(old))})", tuple(o["id"] for o in old))
        return cid, [o["image"] for o in old if o["image"]]

    def clips(self) -> list[dict]:
        return self._all("SELECT * FROM clips ORDER BY id DESC")

    def clip(self, cid: int) -> dict | None:
        return self._one("SELECT * FROM clips WHERE id=?", (cid,))

    def delete_clip(self, cid: int) -> str:
        c = self.clip(cid)
        self._exec("DELETE FROM clips WHERE id=?", (cid,))
        return c["image"] if c else ""

    def clear_clips(self) -> list[str]:
        imgs = [c["image"] for c in self.clips() if c["image"]]
        self._exec("DELETE FROM clips")
        return imgs

    # transfers -------------------------------------------------------------
    def save_transfer(self, t: dict) -> None:
        data = json.dumps(t.get("data", {}), ensure_ascii=False)
        self._exec(
            "INSERT INTO transfers (id,dir,dev,name,kind,status,created,data) VALUES (?,?,?,?,?,?,?,?) "
            "ON CONFLICT(id) DO UPDATE SET status=excluded.status, data=excluded.data",
            (t["id"], t["dir"], t["dev"], t["name"], t["kind"], t["status"], t.get("created", time.time()), data))

    def transfers(self) -> list[dict]:
        rows = self._all("SELECT * FROM transfers ORDER BY created")
        for r in rows:
            r["data"] = json.loads(r["data"])
        return rows

    def delete_transfer(self, tid: str) -> None:
        self._exec("DELETE FROM transfers WHERE id=?", (tid,))
