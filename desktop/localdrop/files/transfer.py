"""File and folder transfers with pause, resume after a broken connection and hash checks.

Offer (control connection)::

    sender -> file_offer {tid, name, kind, items:[{rel, size, dir?}]}
    receiver -> reply {state: accepted | pending} | {ok: false}
    receiver -> file_answer {tid, accept}            (when the user decides later)

Data (a "files" stream connection, hello carries tid)::

    receiver -> resume {have: [bytes already stored per item, -1 = complete]}
    sender   -> file {i, offset}, c (body = chunk) ..., end {i, hash}
    receiver -> prog {n}  (total bytes received, every few MB)
    receiver -> ack {i, ok}
    sender   -> fin;  receiver -> fin_ok

Progress on both sides is what the receiver confirmed with ``prog``; the sender
keeps at most ``WINDOW`` bytes unconfirmed, so OS buffers never hold hundreds of
megabytes, and a connection that confirms nothing for ``STALL`` seconds is
treated as broken (the transfer resumes when the device is back).

Either side can send ``file_ctl {tid, op: pause | resume | cancel}``.
Partial data lives in ``<target>.ldpart`` until the file's SHA-256 matches.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import shutil
import sys
import time
import uuid
from pathlib import Path, PurePosixPath

from ..net.framing import FrameError
from ..net.peers import PeerError
from ..net.transport import HandshakeError

log = logging.getLogger(__name__)

CHUNK = 1024 * 1024
WINDOW = 16 * 1024 * 1024      # bytes in flight beyond what the receiver confirmed
PROG_EVERY = 4 * 1024 * 1024   # receiver confirms at least this often...
PROG_SECONDS = 0.25            # ...or this often
STALL = 30                     # seconds without any confirmation = broken connection
PART = ".ldpart"
FINAL = ("done", "error", "declined", "cancelled")
NET_ERRORS = (ConnectionError, OSError, asyncio.IncompleteReadError, asyncio.TimeoutError, FrameError,
              PeerError, HandshakeError)

_EXT = {
    "video": {"mp4", "mov", "mkv", "avi", "webm", "m4v", "wmv"},
    "image": {"png", "jpg", "jpeg", "gif", "webp", "heic", "bmp", "tif", "tiff", "svg", "raw"},
    "audio": {"mp3", "wav", "flac", "aac", "ogg", "m4a", "opus"},
    "archive": {"zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg"},
    "doc": {"pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "odt", "rtf", "md", "csv", "pages", "key"},
}
_BAD_CHARS = re.compile(r'[<>:"|?*\x00-\x1f]')


class TransferError(Exception):
    """Error shown to the user: a translatable key plus arguments, stored as JSON."""

    def __init__(self, key: str, *args):
        super().__init__(json.dumps({"k": key, "a": [str(a) for a in args]}, ensure_ascii=False))


def kind_of(name: str, is_dir: bool = False) -> str:
    if is_dir:
        return "folder"
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    for k, exts in _EXT.items():
        if ext in exts:
            return k
    return "file"


def safe_rel(rel: str) -> PurePosixPath:
    """A relative path from a peer, made safe to join under the inbox folder."""
    parts = []
    for p in PurePosixPath(str(rel).replace("\\", "/")).parts:
        if p in ("", ".", "..", "/"):
            continue
        p = _BAD_CHARS.sub("_", p).rstrip(" .")
        if not p:
            continue
        parts.append(p[:200])
    if not parts:
        raise TransferError("bad path")
    return PurePosixPath(*parts)


def unique_path(p: Path) -> Path:
    if not p.exists() and not Path(str(p) + PART).exists():
        return p
    stem, suffix = (p.stem, p.suffix) if p.is_file() or "." in p.name else (p.name, "")
    for n in range(1, 10000):
        cand = p.with_name(f"{stem} ({n}){suffix}")
        if not cand.exists() and not Path(str(cand) + PART).exists():
            return cand
    raise TransferError("no free file name")


def scan(path: Path) -> tuple[list[dict], int, int]:
    """Items for one top-level path: (items, total size, file count)."""
    path = path.resolve()
    if path.is_file():
        size = path.stat().st_size
        return [{"rel": path.name, "path": str(path), "size": size}], size, 1
    if not path.is_dir():
        raise TransferError("файл не найден: {0}", path)
    items, total, count = [], 0, 0
    base = path.parent
    for root, dirs, files in os.walk(path):
        dirs.sort()
        rp = Path(root)
        rel_dir = rp.relative_to(base).as_posix()
        if not files and not dirs:
            items.append({"rel": rel_dir, "path": str(rp), "size": 0, "dir": True})
        for f in sorted(files):
            fp = rp / f
            try:
                size = fp.stat().st_size
            except OSError:
                continue
            items.append({"rel": f"{rel_dir}/{f}", "path": str(fp), "size": size})
            total += size
            count += 1
    return items, total, count


def _hash_prefix(path: str, n: int) -> "hashlib._Hash":
    h = hashlib.sha256()
    left = n
    with open(path, "rb") as f:
        while left > 0:
            data = f.read(min(CHUNK, left))
            if not data:
                break
            h.update(data)
            left -= len(data)
    return h


def _read_hash(f, n: int, hasher) -> bytes:
    data = f.read(n)
    hasher.update(data)
    return data


def _write_hash(f, data: bytes, hasher) -> None:
    f.write(data)
    hasher.update(data)


def beep() -> None:
    try:
        if sys.platform == "win32":
            import winsound
            winsound.MessageBeep(winsound.MB_ICONASTERISK)
        elif sys.platform == "darwin":
            import subprocess
            subprocess.Popen(["afplay", "/System/Library/Sounds/Glass.aiff"])
    except Exception:
        pass


class _Acks:
    """Messages the receiver sends back on a data stream, read by a background task."""

    def __init__(self, conn, base: int):
        self.conn = conn
        self.acked = base
        self.last = time.monotonic()
        self.event = asyncio.Event()
        self.queue: asyncio.Queue = asyncio.Queue()
        self.error: BaseException | None = None
        self.on_progress = None

    async def run(self) -> None:
        try:
            while True:
                msg, _ = await self.conn.recv()
                self.last = time.monotonic()
                if msg.get("type") == "prog":
                    self.acked = max(self.acked, int(msg.get("n", 0)))
                    self.event.set()
                    if self.on_progress:
                        self.on_progress(self.acked)
                else:
                    self.queue.put_nowait(msg)
        except asyncio.CancelledError:
            raise
        except Exception as e:                 # connection closed: wake up whoever waits
            self.error = e
            self.event.set()
            self.queue.put_nowait(None)

    def _check(self) -> None:
        if self.error is not None:
            raise ConnectionError(f"stream closed: {self.error!r}")
        if time.monotonic() - self.last > STALL:
            raise ConnectionError("the receiver stopped confirming data")

    async def window(self, sent: int) -> None:
        """Wait until no more than WINDOW bytes are unconfirmed."""
        while sent - self.acked > WINDOW:
            self._check()
            self.event.clear()
            try:
                await asyncio.wait_for(self.event.wait(), 1)
            except asyncio.TimeoutError:
                pass
        self._check()

    async def next(self, timeout: float) -> dict:
        deadline = time.monotonic() + timeout
        while True:
            self._check()
            try:
                msg = await asyncio.wait_for(self.queue.get(), 1)
            except asyncio.TimeoutError:
                if time.monotonic() > deadline:
                    raise
                continue
            if msg is None:
                self._check()
                raise ConnectionError("stream closed")
            return msg


class Transfer:
    def __init__(self, **kw):
        self.id: str = kw.get("id") or uuid.uuid4().hex
        self.dir: str = kw["dir"]
        self.dev: str = kw["dev"]
        self.name: str = kw["name"]
        self.kind: str = kw.get("kind", "file")
        self.items: list[dict] = kw.get("items", [])
        self.size: int = kw.get("size", 0)
        self.files: int = kw.get("files", 1)
        self.status: str = kw.get("status", "queued")
        self.done: int = kw.get("done", 0)
        self.path: str = kw.get("path", "")           # sender: source; receiver: destination root
        self.complete: list[int] = kw.get("complete", [])
        self.error: str = kw.get("error", "")
        self.when: float = kw.get("when", 0.0)
        self.created: float = kw.get("created", time.time())
        self.offered: bool = kw.get("offered", False)
        self.speed = 0.0
        self.task: asyncio.Task | None = None
        self.conn = None
        self._rate_t = time.time()
        self._rate_b = self.done
        self.retry_at = 0.0

    def row(self) -> dict:
        data = {k: getattr(self, k) for k in ("items", "size", "files", "done", "path", "complete", "error",
                                               "when", "offered")}
        return {"id": self.id, "dir": self.dir, "dev": self.dev, "name": self.name, "kind": self.kind,
                "status": self.status, "created": self.created, "data": data}

    @classmethod
    def from_row(cls, r: dict) -> "Transfer":
        return cls(id=r["id"], dir=r["dir"], dev=r["dev"], name=r["name"], kind=r["kind"], status=r["status"],
                   created=r["created"], **r["data"])

    def view(self) -> dict:
        return {"id": self.id, "dir": self.dir, "dev": self.dev, "name": self.name, "kind": self.kind,
                "files": self.files, "size": self.size, "done": self.done,
                "speed": self.speed if self.status == "active" else 0, "status": self.status,
                "when": self.when, "error": self.error, "path": self.path}

    def rate(self) -> None:
        now = time.time()
        dt = now - self._rate_t
        if dt >= 0.5:
            inst = (self.done - self._rate_b) / dt
            self.speed = inst if not self.speed else self.speed * 0.6 + inst * 0.4
            self._rate_t, self._rate_b = now, self.done


class FileService:
    def __init__(self, app, peers):
        self.app = app
        self.peers = peers
        self.transfers: dict[str, Transfer] = {}
        self._pub_at = 0.0
        self._pub_pending = False
        self._tasks: list[asyncio.Task] = []
        for r in app.storage.transfers():
            t = Transfer.from_row(r)
            if t.status in ("active", "queued"):
                t.status = "offline"
            self.transfers[t.id] = t

        app.add_slice("transfers", self.view)
        for name, fn in {
            "send_paths": self.api_send_paths, "pick_and_send": self.api_pick_and_send,
            "offer_respond": self.api_offer_respond, "transfer_toggle": self.api_toggle,
            "transfer_cancel": self.api_cancel, "transfer_retry": self.api_retry,
            "transfers_pause_all": self.api_pause_all, "transfers_resume_all": self.api_resume_all,
            "transfers_clear_done": self.api_clear_done, "open_save_dir": self.api_open_save_dir,
            "change_save_dir": self.api_change_save_dir,
        }.items():
            app.expose(name, fn)
        peers.on("file_offer", self._on_offer)
        peers.on("file_answer", self._on_answer)
        peers.on("file_ctl", self._on_ctl)
        peers.on_stream("files", self._on_stream)
        peers.online_listeners.append(lambda dev, online: online and self.app.call_soon(self._kick))

    async def start(self) -> None:
        self._tasks.append(asyncio.create_task(self._loop()))

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        for t in self.transfers.values():
            if t.task:
                t.task.cancel()
            if t.status == "active":
                t.status = "offline"
            self._save(t)

    # ---- state ------------------------------------------------------------
    def view(self) -> list[dict]:
        return [t.view() for t in sorted(self.transfers.values(), key=lambda x: x.created)]

    def _save(self, t: Transfer) -> None:
        self.app.storage.save_transfer(t.row())

    def _set(self, t: Transfer, status: str, error: str = "") -> None:
        t.status = status
        t.error = error
        if status in FINAL:
            t.when = time.time()
            t.speed = 0
        self._save(t)
        self._publish(now=True)

    def _publish(self, now: bool = False) -> None:
        if now or time.time() - self._pub_at > 0.4:
            self._pub_at = time.time()
            self.app.publish("transfers")

    def save_dir(self) -> Path:
        p = Path(self.app.settings.get("save_dir"))
        p.mkdir(parents=True, exist_ok=True)
        return p

    def _dev_name(self, dev_id: str) -> str:
        d = self.peers.get(dev_id)
        return d.name if d else "?"

    def _history(self, t: Transfer, err: bool = False) -> None:
        verb = "отправлено" if t.dir == "out" else "получено"
        if err:
            sub = {"k": "{0} · ошибка: {1}", "a": [{"size": t.size}, t.error or "ошибка"]}
        elif t.kind == "folder":
            sub = {"k": "Папка · {0} · {1} · " + verb, "a": [{"files": t.files}, {"size": t.size}]}
        else:
            sub = {"k": "{0} · " + verb, "a": [{"size": t.size}]}
        import json
        self.app.storage.add_history("files", t.dir, t.name, json.dumps(sub, ensure_ascii=False), t.dev, err,
                                     ref=t.path if not err else t.id)
        self.app.publish("history")

    # ---- sending ----------------------------------------------------------
    def api_pick_and_send(self, dev_id: str, kind: str) -> None:
        import webview
        w = self.app.window
        if w is None:
            return
        if kind == "folder":
            res = w.create_file_dialog(webview.FileDialog.FOLDER)
        else:
            res = w.create_file_dialog(webview.FileDialog.OPEN, allow_multiple=True)
        if res:
            self.app.spawn(self.send_paths(dev_id, list(res)))

    async def api_send_paths(self, dev_id: str, paths: list[str]) -> None:
        await self.send_paths(dev_id, paths)

    async def send_paths(self, dev_id: str, paths: list[str]) -> list[Transfer]:
        d = self.peers.get(dev_id)
        if d is None or not d.paired:
            return []
        out = []
        loop = asyncio.get_running_loop()
        for p in paths:
            try:
                items, size, count = await loop.run_in_executor(None, scan, Path(p))
            except (TransferError, OSError) as e:
                log.warning("cannot send %s: %s", p, e)
                continue
            path = Path(p)
            t = Transfer(dir="out", dev=dev_id, name=path.name or str(path), kind=kind_of(path.name, path.is_dir()),
                         items=items, size=size, files=count, path=str(path), status="waiting")
            self.transfers[t.id] = t
            out.append(t)
            self._save(t)
        if not out:
            return out
        self._publish(now=True)
        self.app.toast("files", "Добавлено в очередь", "{0} → {1}", args=(self._count_label(len(out)), d.name))
        for t in out:
            await self._offer(t)
        return out

    def _count_label(self, n: int) -> str:
        return str(n)

    async def _offer(self, t: Transfer) -> None:
        items = [{"rel": it["rel"], "size": it["size"], **({"dir": True} if it.get("dir") else {})} for it in t.items]
        msg = {"type": "file_offer", "tid": t.id, "name": t.name, "kind": t.kind, "size": t.size,
               "files": t.files, "items": items}
        try:
            reply, _ = await self.peers.request(t.dev, msg, timeout=20)
        except NET_ERRORS:
            t.offered = False
            self._set(t, "offline")
            return
        t.offered = True
        if not reply.get("ok"):
            self._set(t, "declined" if reply.get("error") == "declined" else "error",
                      "" if reply.get("error") == "declined" else self._err_text(reply.get("error")))
            if t.status == "error":
                self._history(t, err=True)
            return
        self._set(t, "queued" if reply.get("state") == "accepted" else "waiting")
        self._kick()

    @staticmethod
    def _err_text(code) -> str:
        return {"disabled": "приём файлов выключен", "space": "недостаточно места",
                "unknown": "передача не найдена"}.get(code, code or "ошибка")

    def _kick(self) -> None:
        """Start the next outgoing transfer if none is running."""
        if any(t.dir == "out" and t.status == "active" for t in self.transfers.values()):
            return
        now = time.time()
        for t in sorted(self.transfers.values(), key=lambda x: x.created):
            if t.dir != "out" or now < t.retry_at:
                continue
            if t.status == "offline" and self.peers.connected(t.dev):
                if not t.offered:
                    t.retry_at = now + 3
                    asyncio.ensure_future(self._offer(t))
                    continue
            elif t.status != "queued":
                continue
            if not self.peers.connected(t.dev):
                if t.status != "offline":
                    self._set(t, "offline")
                self.peers.ensure_control(t.dev)
                continue
            t.status = "active"
            t.task = asyncio.ensure_future(self._run_send(t))
            self._publish(now=True)
            return

    async def _run_send(self, t: Transfer) -> None:
        try:
            await self._send(t)
        except asyncio.CancelledError:
            if t.status == "active":
                self._set(t, "paused")
        except TransferError as e:
            log.warning("transfer %s failed: %s", t.name, e)
            self._set(t, "error", str(e))
            self._history(t, err=True)
            self.app.toast("bad", "Ошибка передачи", "{0}: {1}", args=(t.name, str(e)))
        except NET_ERRORS as e:
            log.info("transfer %s interrupted: %s", t.name, e)
            if t.status == "active":
                t.retry_at = time.time() + 3
                self._set(t, "offline")
        finally:
            if t.conn:
                t.conn.close()
                t.conn = None
            t.task = None
            t.speed = 0
            self._publish(now=True)
            asyncio.get_running_loop().call_later(0.1, self._kick)

    async def _send(self, t: Transfer) -> None:
        loop = asyncio.get_running_loop()
        conn = t.conn = await self.peers.open_stream(t.dev, "files", tid=t.id)
        msg, _ = await asyncio.wait_for(conn.recv(), 15)
        if msg.get("type") != "resume":
            raise TransferError(self._err_text(msg.get("error")))
        have = msg.get("have", [])
        if len(have) != len(t.items):
            raise TransferError("несовпадение списка файлов")
        t.done = sum(it["size"] if h == -1 else min(h, it["size"]) for it, h in zip(t.items, have))
        t._rate_t, t._rate_b, t.speed = time.time(), t.done, 0
        self._publish(now=True)
        acks = _Acks(conn, t.done)

        def progress(n: int) -> None:
            t.done = min(n, t.size)
            t.rate()
            self._publish()

        acks.on_progress = progress
        reader = asyncio.ensure_future(acks.run())
        try:
            await self._send_items(t, conn, have, acks)
        finally:
            reader.cancel()

    async def _send_items(self, t: Transfer, conn, have: list, acks: _Acks) -> None:
        loop = asyncio.get_running_loop()
        pos = t.done                       # bytes handed to the connection, counted like t.done
        for i, (it, h) in enumerate(zip(t.items, have)):
            if h == -1 or it.get("dir"):
                continue
            offset = max(0, min(int(h), it["size"]))
            path = it["path"]
            try:
                hasher = await loop.run_in_executor(None, _hash_prefix, path, offset)
                f = open(path, "rb")
            except OSError as e:
                raise TransferError("не удалось прочитать {0}", it["rel"]) from e
            sent = offset
            try:
                f.seek(offset)
                await conn.send({"type": "file", "i": i, "offset": offset})
                size = it["size"]
                # read + hash the next chunk in a thread while the current one is encrypted and sent
                nxt = loop.run_in_executor(None, _read_hash, f, min(CHUNK, size - sent), hasher) if sent < size else None
                while nxt is not None:
                    data = await nxt
                    if not data:
                        break
                    ahead = sent + len(data)
                    nxt = (loop.run_in_executor(None, _read_hash, f, min(CHUNK, size - ahead), hasher)
                           if ahead < size else None)
                    await acks.window(pos)
                    await conn.send({"type": "c"}, data)
                    sent = ahead
                    pos += len(data)
            finally:
                f.close()
            if sent != it["size"]:
                raise TransferError("файл изменился во время отправки: {0}", it["rel"])
            await conn.send({"type": "end", "i": i, "hash": hasher.hexdigest()})
            ack = await acks.next(120)
            if not ack.get("ok"):
                raise TransferError("контрольная сумма не совпала: {0}", it["rel"])
        await conn.send({"type": "fin"})
        ack = await acks.next(60)
        if ack.get("type") != "fin_ok":
            raise TransferError("получатель не подтвердил завершение")
        t.done = t.size
        self._set(t, "done")
        self._history(t)
        self.app.toast("files", "Передача завершена", "{0} → {1}", args=(t.name, self._dev_name(t.dev)))

    # ---- receiving --------------------------------------------------------
    async def _on_offer(self, d, conn, msg, body):
        s = self.app.settings
        if not s.get("features").get("files"):
            return {"ok": False, "error": "disabled"}
        tid = str(msg.get("tid", ""))
        if not re.fullmatch(r"[0-9a-f]{32}", tid):
            return {"ok": False, "error": "bad offer"}
        if tid in self.transfers:
            t = self.transfers[tid]
            return {"ok": True, "state": "pending" if t.status == "ask" else "accepted"}
        try:
            raw = msg.get("items") or []
            items = []
            for it in raw:
                rel = safe_rel(it["rel"])
                items.append({"rel": rel.as_posix(), "size": max(0, int(it.get("size", 0))),
                              **({"dir": True} if it.get("dir") else {})})
            if not items:
                raise TransferError("empty offer")
            tops = {PurePosixPath(it["rel"]).parts[0] for it in items}
            if len(tops) != 1:
                raise TransferError("several roots")
        except (TransferError, KeyError, TypeError, ValueError):
            return {"ok": False, "error": "bad offer"}
        size = sum(it["size"] for it in items)
        inbox = self.save_dir()
        if shutil.disk_usage(inbox).free < size + 50 * 1024 * 1024:
            return {"ok": False, "error": "space"}
        top = tops.pop()
        root = unique_path(inbox / top)
        for it in items:
            parts = PurePosixPath(it["rel"]).parts
            it["dest"] = str(root.joinpath(*parts[1:])) if len(parts) > 1 else str(root)
        kind = "folder" if len(items) > 1 or "/" in items[0]["rel"] or items[0].get("dir") else kind_of(top)
        auto = d.trusted and s.get("auto_accept")
        t = Transfer(id=tid, dir="in", dev=d.id, name=root.name, kind=kind, items=items, size=size,
                     files=sum(1 for it in items if not it.get("dir")), path=str(root),
                     status="queued" if auto else "ask", offered=True)
        self.transfers[tid] = t
        self._save(t)
        self._publish(now=True)
        if auto:
            self.app.toast("files", "Входящий файл", "{0} · {1} от {2}",
                           args=(t.name, {"size": size}, d.name))
        else:
            self.app.toast("files", "Хотят отправить вам файлы", "{0} · {1} от {2}",
                           args=(t.name, {"size": size}, d.name))
            self.app.emit("navigate", {"screen": "files"})
        return {"ok": True, "state": "accepted" if auto else "pending"}

    async def api_offer_respond(self, tid: str, accept: bool) -> None:
        t = self.transfers.get(tid)
        if not t or t.status != "ask":
            return
        self._set(t, "queued" if accept else "declined")
        if not accept:
            self.transfers.pop(tid, None)
            self.app.storage.delete_transfer(tid)
            self._publish(now=True)
        await self.peers.send(t.dev, {"type": "file_answer", "tid": tid, "accept": bool(accept)})

    async def _on_answer(self, d, conn, msg, body):
        t = self.transfers.get(msg.get("tid", ""))
        if not t or t.dir != "out" or t.dev != d.id or t.status != "waiting":
            return
        if msg.get("accept"):
            self._set(t, "queued")
            self._kick()
        else:
            self._set(t, "declined")
            self.app.toast("bad", "Передача отклонена", "{0} · {1}", args=(t.name, d.name))

    def _part_have(self, t: Transfer) -> list[int]:
        have = []
        for i, it in enumerate(t.items):
            if i in t.complete or it.get("dir"):
                have.append(-1)
                continue
            p = Path(it["dest"] + PART)
            have.append(p.stat().st_size if p.exists() else 0)
        return have

    async def _on_stream(self, d, conn, hello):
        t = self.transfers.get(str(hello.get("tid", "")))
        if t is None or t.dir != "in" or t.dev != d.id:
            await conn.send({"type": "error", "error": "unknown"})
            return
        if t.status not in ("queued", "offline", "active"):
            await conn.send({"type": "error", "error": "paused" if t.status == "paused" else t.status})
            return
        loop = asyncio.get_running_loop()
        for it in t.items:
            if it.get("dir"):
                Path(it["dest"]).mkdir(parents=True, exist_ok=True)
        have = await loop.run_in_executor(None, self._part_have, t)
        t.done = sum(it["size"] if h == -1 else min(h, it["size"]) for it, h in zip(t.items, have))
        t._rate_t, t._rate_b, t.speed = time.time(), t.done, 0
        t.conn = conn
        self._set(t, "active")
        await conn.send({"type": "resume", "have": have})
        f = None
        hasher = None
        pending = None
        cur = -1
        confirmed, confirmed_at = t.done, time.monotonic()
        try:
            while True:
                msg, body = await asyncio.wait_for(conn.recv(), STALL * 2)
                mt = msg.get("type")
                if mt == "c":
                    if f is None:
                        raise TransferError("data without file")
                    if pending is not None:
                        await pending
                    # write + hash in a thread while the next record is received and decrypted
                    pending = loop.run_in_executor(None, _write_hash, f, body, hasher)
                    t.done += len(body)
                    if t.done - confirmed >= PROG_EVERY or time.monotonic() - confirmed_at >= PROG_SECONDS:
                        confirmed, confirmed_at = t.done, time.monotonic()
                        await conn.send({"type": "prog", "n": t.done})
                    t.rate()
                    self._publish()
                elif mt == "file":
                    cur = int(msg["i"])
                    it = t.items[cur]
                    offset = int(msg.get("offset", 0))
                    part = Path(it["dest"] + PART)
                    part.parent.mkdir(parents=True, exist_ok=True)
                    if not part.exists():
                        part.touch()
                    if part.stat().st_size < offset:
                        raise TransferError("bad resume offset")
                    f = open(part, "r+b")
                    f.truncate(offset)
                    hasher = await loop.run_in_executor(None, _hash_prefix, str(part), offset)
                    f.seek(offset)
                elif mt == "end":
                    it = t.items[cur]
                    if pending is not None:
                        await pending
                        pending = None
                    f.close()
                    f = None
                    part = Path(it["dest"] + PART)
                    if hasher.hexdigest() != msg.get("hash"):
                        part.unlink(missing_ok=True)
                        t.done -= it["size"]
                        await conn.send({"type": "ack", "i": cur, "ok": False})
                        raise TransferError("контрольная сумма не совпала: {0}", it["rel"])
                    final = Path(it["dest"])
                    if final.exists():
                        final = unique_path(final)
                        it["dest"] = str(final)
                    os.replace(part, final)
                    t.complete.append(cur)
                    self._save(t)
                    await conn.send({"type": "ack", "i": cur, "ok": True})
                elif mt == "fin":
                    t.done = t.size
                    self._set(t, "done")
                    await conn.send({"type": "fin_ok"})
                    self._history(t)
                    self.app.toast("files", "Файл получен", "{0} от {1}", args=(t.name, d.name))
                    if self.app.settings.get("sounds"):
                        beep()
                    return
        except TransferError as e:
            self._set(t, "error", str(e))
            self._history(t, err=True)
        except NET_ERRORS:
            if t.status == "active":
                self._set(t, "offline")
        finally:
            if pending is not None:
                try:
                    await pending
                except OSError:
                    pass
            if f is not None:
                f.close()
            t.conn = None
            t.speed = 0
            if t.status == "cancelled":
                self._delete_parts(t)
            self._publish(now=True)

    # ---- control ----------------------------------------------------------
    async def _on_ctl(self, d, conn, msg, body):
        t = self.transfers.get(msg.get("tid", ""))
        if not t or t.dev != d.id:
            return
        op = msg.get("op")
        if op == "cancel":
            self._local_cancel(t)
            self.app.toast("bad", "Передача отменена", "{0} · {1}", args=(t.name, d.name))
        elif op == "pause" and t.status not in FINAL:
            self._local_pause(t)
        elif op == "resume" and t.status == "paused":
            self._set(t, "queued")
            if t.dir == "out":
                self._kick()

    def _local_pause(self, t: Transfer) -> None:
        self._set(t, "paused")
        if t.task:
            t.task.cancel()
        if t.conn:
            t.conn.close()

    def _local_cancel(self, t: Transfer) -> None:
        if t.task:
            t.task.cancel()
        if t.conn:
            t.conn.close()
        self._set(t, "cancelled")
        if t.dir == "in" and t.conn is None:
            self._delete_parts(t)      # otherwise the stream deletes them once the file is closed

    def _delete_parts(self, t: Transfer) -> None:
        for i, it in enumerate(t.items):
            if i not in t.complete and not it.get("dir"):
                try:
                    Path(it["dest"] + PART).unlink(missing_ok=True)
                except OSError:
                    log.warning("cannot delete %s", it["dest"] + PART)

    async def api_toggle(self, tid: str) -> None:
        t = self.transfers.get(tid)
        if not t or t.status in FINAL or t.status in ("ask", "waiting"):
            return
        if t.status == "paused":
            self._set(t, "queued")
            await self.peers.send(t.dev, {"type": "file_ctl", "tid": tid, "op": "resume"})
            if t.dir == "out":
                self._kick()
        else:
            self._local_pause(t)
            await self.peers.send(t.dev, {"type": "file_ctl", "tid": tid, "op": "pause"})

    async def api_cancel(self, tid: str) -> None:
        t = self.transfers.get(tid)
        if not t or t.status in FINAL:
            return
        self._local_cancel(t)
        await self.peers.send(t.dev, {"type": "file_ctl", "tid": tid, "op": "cancel"})
        if t.dir == "out":
            self._kick()

    async def api_retry(self, tid: str) -> None:
        t = self.transfers.get(tid)
        if not t or t.dir != "out" or t.status != "error":
            return
        self.transfers.pop(tid)
        self.app.storage.delete_transfer(tid)
        await self.send_paths(t.dev, [t.path])

    async def retry_from_history(self, tid: str) -> bool:
        t = self.transfers.get(tid)
        if t is None:
            return False
        await self.api_retry(tid)
        return True

    async def api_pause_all(self) -> None:
        for t in list(self.transfers.values()):
            if t.status in ("active", "queued", "offline"):
                await self.api_toggle(t.id)

    async def api_resume_all(self) -> None:
        for t in list(self.transfers.values()):
            if t.status == "paused":
                await self.api_toggle(t.id)

    def api_clear_done(self) -> None:
        for tid in [t.id for t in self.transfers.values() if t.status in FINAL]:
            self.transfers.pop(tid)
            self.app.storage.delete_transfer(tid)
        self._publish(now=True)

    def api_open_save_dir(self) -> None:
        from ..app import open_path
        open_path(str(self.save_dir()))

    def api_change_save_dir(self) -> None:
        import webview
        w = self.app.window
        if w is None:
            return
        res = w.create_file_dialog(webview.FileDialog.FOLDER, directory=self.app.settings.get("save_dir"))
        if res:
            self.app.settings.update({"save_dir": res[0]})

    async def _loop(self) -> None:
        while True:
            await asyncio.sleep(2)
            self._kick()
            for t in self.transfers.values():
                if t.status == "active":
                    t.rate()
                    self._save(t)
            if any(t.status == "active" for t in self.transfers.values()):
                self._publish(now=True)
