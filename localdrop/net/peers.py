"""Known devices and connections to them.

Every pair of online, paired devices keeps a *control* connection open: small
messages (clipboard, audio control, file offers, pings) travel over it as
request/reply pairs. Bulk data (file contents) uses separate *stream*
connections so it never blocks control traffic.

Unpaired devices may only exchange pairing messages.
"""
from __future__ import annotations

import asyncio
import itertools
import logging
import time
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

from .framing import FrameError
from .identity import Identity
from .transport import Conn, HandshakeError, dial, serve

log = logging.getLogger(__name__)

ONLINE_TTL = 35          # seconds a discovery sighting keeps a device "online"
PING_EVERY = 12
DEAD_AFTER = 40
PAIRING_TYPES = {"pair_request", "pair_cancel", "pair_code", "ping", "pong", "reply"}

Handler = Callable[["Device", Conn, dict, bytes], Awaitable[Any]]


@dataclass
class Device:
    id: str
    name: str = "?"
    os: str = ""
    kind: str = ""
    icon: str = "computer"
    ip: str = ""
    port: int = 0
    paired: bool = False
    trusted: bool = False
    last_seen: float = 0.0
    hidden: bool = False       # announced itself as invisible

    def info(self) -> dict:
        return {"name": self.name, "os": self.os, "kind": self.kind, "icon": self.icon, "ip": self.ip, "port": self.port}


class PeerError(Exception):
    pass


class PeerManager:
    def __init__(self, app, ident: Identity):
        self.app = app
        self.ident = ident
        self.devices: dict[str, Device] = {}
        self.control: dict[str, list[Conn]] = {}
        self.handlers: dict[str, Handler] = {}
        self.stream_handlers: dict[str, Callable[[Device, Conn, dict], Awaitable[None]]] = {}
        self.online_listeners: list[Callable[[str, bool], None]] = []
        self._pending: dict[str, asyncio.Future] = {}
        self._rid = itertools.count(1)
        self._dialing: dict[str, asyncio.Task] = {}
        self._backoff: dict[str, float] = {}
        self._last_rx: dict[int, float] = {}
        self._was_online: dict[str, bool] = {}
        self._server = None
        self._last_view: list = []
        self._tasks: list[asyncio.Task] = []
        for row in app.storage.devices():
            if row["paired"]:
                self.devices[row["id"]] = Device(
                    id=row["id"], name=row["name"], os=row["os"], kind=row["kind"], icon=row["icon"],
                    ip=row["ip"], port=row["port"], paired=True, trusted=bool(row["trusted"]))
        self.on("ping", self._on_ping)

    # ---- lifecycle --------------------------------------------------------
    async def start(self) -> None:
        self._server = await serve("0.0.0.0", self.app.port, self.ident, self._on_conn)
        log.info("listening on tcp/%d as %s", self.app.port, self.ident.id)
        self._tasks.append(asyncio.create_task(self._maintain()))

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        if self._server:
            self._server.close()
        for conns in list(self.control.values()):
            for c in conns:
                c.close()

    # ---- registry ---------------------------------------------------------
    def on(self, msg_type: str, fn: Handler) -> None:
        self.handlers[msg_type] = fn

    def on_stream(self, purpose: str, fn) -> None:
        self.stream_handlers[purpose] = fn

    def hello(self, purpose: str = "control", **extra) -> dict:
        s = self.app.settings
        me = self.app.me()
        return {"type": "hello", "purpose": purpose, "name": s.get("device_name"), "os": me["os"],
                "kind": me["kind"], "icon": me["icon"], "port": self.app.port, **extra}

    def get(self, dev_id: str) -> Device | None:
        return self.devices.get(dev_id)

    def is_online(self, d: Device) -> bool:
        if self.control.get(d.id):
            return True
        return not d.hidden and time.time() - d.last_seen < ONLINE_TTL

    def connected(self, dev_id: str) -> bool:
        return bool(self.control.get(dev_id))

    def view(self) -> list[dict]:
        out = []
        for d in self.devices.values():
            online = self.is_online(d)
            if not d.paired and not online:
                continue
            out.append({"id": d.id, "name": d.name, "os": d.os, "kind": d.kind, "icon": d.icon, "ip": d.ip,
                        "port": d.port, "online": online, "paired": d.paired, "trusted": d.trusted})
        out.sort(key=lambda x: (not x["online"], not x["paired"], x["name"].lower()))
        return out

    def _changed(self) -> None:
        view = self.view()
        if view != self._last_view:
            self._last_view = view
            self.app.publish("devices")
        for d in self.devices.values():
            now = self.is_online(d)
            if self._was_online.get(d.id) != now:
                self._was_online[d.id] = now
                for fn in self.online_listeners:
                    try:
                        fn(d.id, now)
                    except Exception:
                        log.exception("online listener failed")

    def _save(self, d: Device) -> None:
        if d.paired:
            self.app.storage.upsert_device(d.id, name=d.name, os=d.os, kind=d.kind, icon=d.icon, ip=d.ip,
                                           port=d.port, paired=1, trusted=int(d.trusted), last_seen=d.last_seen)

    def _update(self, dev_id: str, info: dict, seen: bool = True) -> Device | None:
        if dev_id == self.ident.id:
            return None
        d = self.devices.get(dev_id)
        if d is None:
            d = self.devices[dev_id] = Device(id=dev_id)
        before = d.info()
        for k in ("name", "os", "kind", "icon", "ip"):
            v = info.get(k)
            if isinstance(v, str) and v:
                setattr(d, k, v[:64])
        if isinstance(info.get("port"), int):
            d.port = info["port"]
        if "hidden" in info:
            d.hidden = bool(info["hidden"])
        if seen:
            d.last_seen = time.time()
        if d.info() != before:
            self._save(d)
        return d

    def seen(self, dev_id: str, info: dict) -> None:
        """Called by discovery when a device announces itself."""
        d = self._update(dev_id, info)
        if d is None:
            return
        self._changed()
        if d.paired and not self.control.get(d.id):
            self.ensure_control(d.id)

    def lost(self, dev_id: str) -> None:
        d = self.devices.get(dev_id)
        if d and not self.control.get(dev_id):
            d.last_seen = 0
            self._changed()

    def set_paired(self, dev_id: str, trusted: bool) -> None:
        d = self.devices[dev_id]
        d.paired, d.trusted = True, trusted
        self._save(d)
        self._changed()

    def set_trust(self, dev_id: str, trusted: bool) -> None:
        d = self.devices.get(dev_id)
        if d and d.paired:
            d.trusted = trusted
            self._save(d)
            self._changed()

    def forget(self, dev_id: str) -> None:
        d = self.devices.get(dev_id)
        if not d:
            return
        d.paired = d.trusted = False
        self.app.storage.forget_device(dev_id)
        for c in self.control.pop(dev_id, []):
            c.close()
        self._changed()

    # ---- connections ------------------------------------------------------
    def ensure_control(self, dev_id: str) -> asyncio.Task | None:
        if self.control.get(dev_id):
            return None
        t = self._dialing.get(dev_id)
        if t and not t.done():
            return t
        if time.time() < self._backoff.get(dev_id, 0):
            return None
        t = asyncio.create_task(self._dial_control(dev_id))
        self._dialing[dev_id] = t
        return t

    async def _dial_control(self, dev_id: str) -> Conn | None:
        d = self.devices.get(dev_id)
        if not d or not d.ip or not d.port:
            return None
        try:
            conn = await self.connect(d.ip, d.port, expected_id=dev_id, purpose="control")
        except (OSError, HandshakeError, asyncio.TimeoutError, asyncio.IncompleteReadError, PeerError, FrameError) as e:
            log.debug("dial %s (%s:%s) failed: %s", d.name, d.ip, d.port, e)
            self._backoff[dev_id] = time.time() + 5
            return None
        self._backoff.pop(dev_id, None)
        asyncio.create_task(self._run_control(conn))
        return conn

    async def connect(self, ip: str, port: int, expected_id: str | None = None, purpose: str = "control",
                      **extra) -> Conn:
        """Dial, authenticate and exchange hello. Returns an open connection."""
        conn = await dial(ip, port, self.ident, expected_id)
        if conn.peer_id == self.ident.id:
            conn.close()
            raise PeerError("connected to myself")
        await conn.send(self.hello(purpose, **extra))
        msg, _ = await asyncio.wait_for(conn.recv(), 8)
        if msg.get("type") != "hello":
            conn.close()
            raise PeerError(msg.get("error") or "no hello")
        conn.hello = msg
        self._update(conn.peer_id, {**msg, "ip": ip})
        self._changed()
        return conn

    async def _on_conn(self, conn: Conn) -> None:
        msg, _ = await asyncio.wait_for(conn.recv(), 8)
        if msg.get("type") != "hello":
            return
        conn.hello = msg
        purpose = msg.get("purpose", "control")
        d = self._update(conn.peer_id, {**msg, "ip": conn.peer_ip})
        if d is None:
            return
        if purpose != "control" and not d.paired:
            await conn.send({"type": "error", "error": "not paired"})
            return
        await conn.send(self.hello(purpose))
        if purpose == "control":
            await self._run_control(conn)
            return
        fn = self.stream_handlers.get(purpose)
        if fn is None:
            return
        await fn(d, conn, msg)

    async def _run_control(self, conn: Conn) -> None:
        dev_id = conn.peer_id
        self.control.setdefault(dev_id, []).append(conn)
        self._last_rx[id(conn)] = time.time()
        self._changed()
        log.info("control connection with %s (%s)", conn.hello.get("name"), dev_id[:8])
        try:
            while True:
                msg, body = await conn.recv()
                self._last_rx[id(conn)] = time.time()
                d = self.devices.get(dev_id)
                if d is not None:
                    d.last_seen = time.time()
                asyncio.create_task(self._dispatch(conn, msg, body))
        except (asyncio.IncompleteReadError, ConnectionError, FrameError, OSError):
            pass
        finally:
            conn.close()
            self._last_rx.pop(id(conn), None)
            lst = self.control.get(dev_id, [])
            if conn in lst:
                lst.remove(conn)
            if not lst:
                self.control.pop(dev_id, None)
            self._changed()
            log.info("control connection with %s closed", dev_id[:8])

    async def _dispatch(self, conn: Conn, msg: dict, body: bytes) -> None:
        mtype = msg.get("type")
        if mtype == "reply":
            fut = self._pending.pop(msg.get("re", ""), None)
            if fut and not fut.done():
                fut.set_result((msg, body))
            return
        d = self.devices.get(conn.peer_id)
        rid = msg.get("rid")
        if d is None or (not d.paired and mtype not in PAIRING_TYPES):
            if rid:
                await self._reply(conn, rid, {"ok": False, "error": "not paired"})
            return
        fn = self.handlers.get(mtype)
        result: Any = None
        if fn is None:
            result = {"ok": False, "error": "unknown message"}
        else:
            try:
                result = await fn(d, conn, msg, body)
            except Exception as e:
                log.exception("handler %s failed", mtype)
                result = {"ok": False, "error": str(e)}
        if rid:
            rbody = b""
            if isinstance(result, tuple):
                result, rbody = result
            await self._reply(conn, rid, result if isinstance(result, dict) else {"ok": True}, rbody)

    async def _reply(self, conn: Conn, rid: str, msg: dict, body: bytes = b"") -> None:
        try:
            await conn.send({**msg, "type": "reply", "re": rid}, body)
        except (ConnectionError, OSError):
            pass

    async def _on_ping(self, d, conn, msg, body):
        return {"ok": True}

    # ---- messaging --------------------------------------------------------
    async def _control_conn(self, dev_id: str, dial_timeout: float = 6) -> Conn:
        conns = self.control.get(dev_id)
        if conns:
            return conns[-1]
        t = self.ensure_control(dev_id) or self._dialing.get(dev_id)
        if t is None and dev_id in self._backoff:
            self._backoff.pop(dev_id)
            t = self.ensure_control(dev_id)
        if t is not None:
            try:
                await asyncio.wait_for(asyncio.shield(t), dial_timeout)
            except asyncio.TimeoutError:
                pass
            await asyncio.sleep(0)
        conns = self.control.get(dev_id)
        if not conns:
            raise PeerError("device is offline")
        return conns[-1]

    async def request(self, dev_id: str, msg: dict, body: bytes = b"", timeout: float = 15,
                      conn: Conn | None = None) -> tuple[dict, bytes]:
        conn = conn or await self._control_conn(dev_id)
        rid = "%s-%d" % (self.ident.id[:6], next(self._rid))
        fut = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        try:
            await conn.send({**msg, "rid": rid}, body)
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._pending.pop(rid, None)

    async def send(self, dev_id: str, msg: dict, body: bytes = b"") -> bool:
        try:
            conn = await self._control_conn(dev_id)
            await conn.send(msg, body)
            return True
        except (PeerError, ConnectionError, OSError):
            return False

    async def open_stream(self, dev_id: str, purpose: str, **extra) -> Conn:
        d = self.devices.get(dev_id)
        if not d or not d.ip:
            raise PeerError("unknown device")
        conn = await self.connect(d.ip, d.port, expected_id=dev_id, purpose=purpose, **extra)
        return conn

    # ---- upkeep -----------------------------------------------------------
    async def _maintain(self) -> None:
        while True:
            await asyncio.sleep(4)
            now = time.time()
            for dev_id, conns in list(self.control.items()):
                for c in list(conns):
                    idle = now - self._last_rx.get(id(c), now)
                    if idle > DEAD_AFTER:
                        log.info("control connection with %s timed out", dev_id[:8])
                        c.close()
                    elif idle > PING_EVERY:
                        try:
                            await c.send({"type": "ping"})
                        except (ConnectionError, OSError):
                            c.close()
            for d in list(self.devices.values()):
                if d.paired and not self.control.get(d.id) and now - d.last_seen < ONLINE_TTL:
                    self.ensure_control(d.id)
            self._changed()
