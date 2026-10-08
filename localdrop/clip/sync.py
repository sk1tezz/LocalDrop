"""Clipboard sync: watch the local clipboard, keep a history, exchange it with paired devices.

Message (control connection)::

    clip {ctype: text | image, text?, w?, h?, hash, manual?}   body = PNG for images

Clips from trusted devices go straight into the system clipboard. Clips from
paired but untrusted devices only land in the history (one click to copy).
A hash of what we wrote ourselves stops clips from echoing back and forth.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import io
import json
import logging
from pathlib import Path

from PIL import Image

log = logging.getLogger(__name__)

POLL = 0.4
MAX_TEXT = 1_000_000
MAX_IMAGE = 25 * 1024 * 1024


def load_backend():
    from ..config import IS_MAC, IS_WIN
    if IS_WIN:
        from . import backend_win as b
        return b
    if IS_MAC:
        from . import backend_mac as b
        return b
    return None


def text_hash(text: str) -> str:
    return "t" + hashlib.sha256(text.encode("utf-8", "surrogatepass")).hexdigest()


def image_hash(img: Image.Image) -> str:
    rgb = img.convert("RGB")
    return "i" + hashlib.sha256(f"{rgb.size}".encode() + rgb.tobytes()).hexdigest()


def fmt_kb(n: int) -> dict:
    return {"size": n}


class ClipService:
    def __init__(self, app, peers, backend=None):
        self.app = app
        self.peers = peers
        self.backend = backend if backend is not None else load_backend()
        self.dir = Path(app.ddir) / "clips"
        self.dir.mkdir(parents=True, exist_ok=True)
        self._seq = None
        self._last_hash = ""          # last content seen on the local clipboard
        self._applied_hash = ""       # last content we wrote ourselves
        self._thumbs: dict[int, str] = {}
        self._task: asyncio.Task | None = None

        app.add_slice("clips", self.view)
        for name, fn in {
            "clip_send_current": self.api_send_current, "clip_copy": self.api_copy, "clip_send": self.api_send,
            "clip_delete": self.api_delete, "clip_clear": self.api_clear,
        }.items():
            app.expose(name, fn)
        peers.on("clip", self._on_clip)
        peers.paired_listeners.append(self._on_paired)
        if hasattr(app, "history_handlers"):
            app.history_handlers["clip"] = self._history_action

    async def start(self) -> None:
        if self.backend is None:
            log.warning("no clipboard backend for this platform")
            return
        self._task = asyncio.create_task(self._watch())

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()

    # ---- state ------------------------------------------------------------
    def _thumb(self, c: dict) -> str:
        t = self._thumbs.get(c["id"])
        if t is None and c["image"]:
            try:
                img = Image.open(self.dir / c["image"])
                img.thumbnail((160, 110))
                buf = io.BytesIO()
                img.convert("RGB").save(buf, "JPEG", quality=80)
                t = "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()
            except OSError:
                t = ""
            self._thumbs[c["id"]] = t
        return t or ""

    def view(self) -> list[dict]:
        out = []
        for c in self.app.storage.clips():
            out.append({"id": c["id"], "type": c["type"], "text": c["text"], "meta": c["meta"], "dev": c["dev"],
                        "ts": c["ts"], "thumb": self._thumb(c) if c["type"] == "image" else ""})
        return out

    def enabled(self) -> bool:
        return bool(self.app.settings.get("features").get("clip"))

    def type_on(self, ctype: str) -> bool:
        return bool(self.app.settings.get("clip_types").get(ctype, True))

    def targets(self, manual: bool = False) -> list[str]:
        chosen = [d for d in self.app.settings.get("clip_devices") if self.peers.get(d) and self.peers.get(d).paired]
        if not chosen and manual:
            chosen = [d.id for d in self.peers.devices.values() if d.paired]
        return [d for d in chosen if self.peers.connected(d)]

    def _on_paired(self, dev_id: str) -> None:
        devs = self.app.settings.get("clip_devices")
        if dev_id not in devs:
            self.app.settings.update({"clip_devices": devs + [dev_id]})

    # ---- local clipboard --------------------------------------------------
    async def _watch(self) -> None:
        loop = asyncio.get_running_loop()
        while True:
            await asyncio.sleep(POLL)
            try:
                seq = await loop.run_in_executor(None, self.backend.change_count)
                if seq == self._seq:
                    continue
                first = self._seq is None
                self._seq = seq
                got = await loop.run_in_executor(None, self.backend.read)
            except Exception as e:
                log.debug("clipboard read failed: %s", e)
                continue
            if got is None:
                continue
            clip = self._make(*got)
            if clip is None:
                continue
            if first:                     # don't treat what was already there at startup as new
                self._last_hash = clip["hash"]
                continue
            await self.on_local_change(clip)

    def _make(self, ctype: str, value) -> dict | None:
        if ctype == "text":
            text = value if isinstance(value, str) else ""
            if not text.strip() or len(text) > MAX_TEXT:
                return None
            return {"ctype": "text", "text": text, "hash": text_hash(text)}
        img = value
        try:
            img.load()
        except OSError:
            return None
        return {"ctype": "image", "image": img, "hash": image_hash(img)}

    async def on_local_change(self, clip: dict) -> None:
        if clip["hash"] in (self._last_hash, self._applied_hash):
            self._last_hash = clip["hash"]
            return
        self._last_hash = clip["hash"]
        if not self.type_on(clip["ctype"]):
            return
        self._store(clip, "me")
        if self.enabled() and self.app.settings.get("clip_auto"):
            await self._broadcast(clip, self.targets())

    def _png(self, clip: dict) -> bytes:
        if "png" not in clip:
            buf = io.BytesIO()
            clip["image"].save(buf, "PNG", optimize=False)
            clip["png"] = buf.getvalue()
        return clip["png"]

    def _store(self, clip: dict, dev: str) -> int:
        if clip["ctype"] == "text":
            cid, evicted = self.app.storage.add_clip("text", text=clip["text"], dev=dev, hash_=clip["hash"])
        else:
            img = clip["image"]
            name = clip["hash"][1:17] + ".png"
            path = self.dir / name
            if not path.exists():
                path.write_bytes(self._png(clip))
            meta = json.dumps({"k": "{0}×{1} · {2}", "a": [img.width, img.height, fmt_kb(path.stat().st_size)]})
            cid, evicted = self.app.storage.add_clip("image", text="", image=name, meta=meta, dev=dev,
                                                     hash_=clip["hash"])
        self._cleanup(evicted)
        self.app.publish("clips")
        return cid

    def _cleanup(self, images: list[str]) -> None:
        used = {c["image"] for c in self.app.storage.clips()}
        for name in images:
            if name and name not in used:
                (self.dir / name).unlink(missing_ok=True)

    # ---- network ----------------------------------------------------------
    async def _broadcast(self, clip: dict, targets: list[str], manual: bool = False) -> int:
        if not targets:
            return 0
        msg = {"type": "clip", "ctype": clip["ctype"], "hash": clip["hash"], "manual": manual}
        body = b""
        if clip["ctype"] == "text":
            msg["text"] = clip["text"]
        else:
            body = self._png(clip)
            if len(body) > MAX_IMAGE:
                return 0
            msg["w"], msg["h"] = clip["image"].size
        results = await asyncio.gather(*(self.peers.send(d, msg, body) for d in targets))
        n = sum(1 for r in results if r)
        if manual:
            for d, ok in zip(targets, results):
                if ok:
                    self._history(clip, "out", d)
        return n

    def _history(self, clip: dict, dir_: str, dev: str) -> None:
        verb = "отправлено" if dir_ == "out" else "получено"
        if clip["ctype"] == "text":
            title = " ".join(clip["text"].split())[:200]
            sub = {"k": "Текст · " + verb, "a": []}
        else:
            title = "{0}×{1}".format(*clip["image"].size)
            sub = {"k": "Изображение · " + verb, "a": []}
        self.app.storage.add_history("clip", dir_, title, json.dumps(sub, ensure_ascii=False), dev,
                                     ref=clip["hash"])
        self.app.publish("history")

    async def _on_clip(self, d, conn, msg, body):
        if not self.enabled():
            return {"ok": False, "error": "disabled"}
        ctype = msg.get("ctype")
        if ctype == "text":
            text = msg.get("text")
            if not isinstance(text, str) or len(text) > MAX_TEXT:
                return {"ok": False}
            clip = {"ctype": "text", "text": text, "hash": text_hash(text)}
        elif ctype == "image":
            if len(body) > MAX_IMAGE:
                return {"ok": False}
            try:
                img = Image.open(io.BytesIO(body))
                if img.width * img.height > 80_000_000:
                    return {"ok": False}
                img.load()
            except (OSError, Image.DecompressionBombError):
                return {"ok": False}
            clip = {"ctype": "image", "image": img, "png": body, "hash": image_hash(img)}
        else:
            return {"ok": False}
        if not self.type_on(ctype):
            return {"ok": False, "error": "type disabled"}
        self._store(clip, d.id)
        self._history(clip, "in", d.id)
        if d.trusted:
            await self._apply(clip)
            if msg.get("manual"):
                self.app.toast("clip", "Буфер обмена обновлён", "{0} с {1}",
                               args=("Текст" if ctype == "text" else "Изображение", d.name))
        else:
            self.app.toast("clip", "Получен буфер обмена", "От {0} — скопируйте его из истории", args=(d.name,))
        return {"ok": True}

    async def _apply(self, clip: dict) -> None:
        if self.backend is None:
            return
        self._applied_hash = clip["hash"]
        self._last_hash = clip["hash"]
        loop = asyncio.get_running_loop()
        try:
            if clip["ctype"] == "text":
                await loop.run_in_executor(None, self.backend.write_text, clip["text"])
            else:
                await loop.run_in_executor(None, self.backend.write_image, clip["image"])
        except Exception:
            log.exception("writing the clipboard failed")

    # ---- api --------------------------------------------------------------
    def _clip_from_row(self, c: dict) -> dict | None:
        if c["type"] == "text":
            return {"ctype": "text", "text": c["text"], "hash": c["hash"] or text_hash(c["text"])}
        try:
            img = Image.open(self.dir / c["image"])
            img.load()
        except OSError:
            return None
        return {"ctype": "image", "image": img, "hash": c["hash"] or image_hash(img)}

    async def api_send_current(self) -> None:
        if self.backend is None:
            return
        loop = asyncio.get_running_loop()
        try:
            got = await loop.run_in_executor(None, self.backend.read)
        except Exception:
            got = None
        clip = self._make(*got) if got else None
        if clip is None:
            self.app.toast("bad", "Буфер пуст", "Скопируйте текст или картинку")
            return
        if clip["hash"] != self._last_hash:
            self._last_hash = clip["hash"]
            self._store(clip, "me")
        await self._send_manual(clip)

    async def _send_manual(self, clip: dict) -> None:
        targets = self.targets(manual=True)
        n = await self._broadcast(clip, targets, manual=True)
        if n:
            self.app.toast("clip", "Буфер обмена отправлен", "На {0}", args=(self._n_devices(n),))
        else:
            self.app.toast("bad", "Некому отправить", "Нет подключённых устройств")

    def _n_devices(self, n: int) -> str:
        if self.app.settings.get("lang") == "en":
            return f"{n} device" + ("" if n == 1 else "s")
        a, b = n % 10, n % 100
        word = "устройство" if a == 1 and b != 11 else "устройства" if 2 <= a <= 4 and not 12 <= b <= 14 else "устройств"
        return f"{n} {word}"

    async def api_copy(self, cid: int) -> None:
        c = self.app.storage.clip(int(cid))
        clip = self._clip_from_row(c) if c else None
        if clip:
            await self._apply(clip)
            self.app.toast("clip", "Скопировано", "Можно вставлять", system=False)

    async def api_send(self, cid: int) -> None:
        c = self.app.storage.clip(int(cid))
        clip = self._clip_from_row(c) if c else None
        if clip:
            await self._send_manual(clip)

    def api_delete(self, cid: int) -> None:
        img = self.app.storage.delete_clip(int(cid))
        self._thumbs.pop(int(cid), None)
        self._cleanup([img])
        self.app.publish("clips")

    def api_clear(self) -> None:
        imgs = self.app.storage.clear_clips()
        self._thumbs.clear()
        self._cleanup(imgs)
        self.app.publish("clips")

    async def _history_action(self, h: dict) -> None:
        for c in self.app.storage.clips():
            if c["hash"] == h["ref"]:
                await self.api_copy(c["id"])
                return
        self.app.toast("bad", "Запись уже удалена из истории буфера")
