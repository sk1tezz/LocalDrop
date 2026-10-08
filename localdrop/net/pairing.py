"""Pairing: confirm a short code on both screens, enter another device's code, or scan its QR.

* From the list / by IP: the initiator opens a connection and sends
  ``pair_request``. Both screens show the 6-digit SAS of that session; a man
  in the middle would produce different codes. The receiver accepts or declines.
* By code: every device shows a rotating "my code". The other side proves it
  knows the code with an HMAC bound to the session (``Conn.proof``); the
  receiver accepts automatically. Attempts are rate limited.
* By QR: the QR carries ip, port, device id and the current code, so the
  scanner also verifies the device identity.
"""
from __future__ import annotations

import asyncio
import base64
import hmac
import logging
import secrets
import threading
import time

from .peers import PeerError
from .transport import Conn, HandshakeError

log = logging.getLogger(__name__)

CODE_TTL = 60
PAIR_TIMEOUT = 90
MAX_CODE_TRIES = 5          # per peer ip per minute
QR_PREFIX = "localdrop:1:"


def fmt_code(code: str) -> str:
    return code[:3] + " " + code[3:]


def qr_payload(ip: str, port: int, dev_id: str, code: str) -> str:
    return f"{QR_PREFIX}{ip}:{port}:{dev_id}:{code}"


def parse_qr(text: str) -> dict | None:
    if not text.startswith(QR_PREFIX):
        return None
    parts = text[len(QR_PREFIX):].split(":")
    if len(parts) != 4 or not parts[1].isdigit() or len(parts[3]) != 6 or not parts[3].isdigit():
        return None
    return {"ip": parts[0], "port": int(parts[1]), "id": parts[2], "code": parts[3]}


class Pairing:
    def __init__(self, app, peers):
        self.app = app
        self.peers = peers
        self.code = ""
        self._prev_code = ("", 0.0)
        self._code_at = 0.0
        self.pair: dict | None = None        # the open pairing dialog
        self._pair_task: asyncio.Task | None = None
        self._pair_future: asyncio.Future | None = None
        self._tries: dict[str, list[float]] = {}
        self.qr: dict | None = None
        self._qr_stop = threading.Event()
        self._tasks: list[asyncio.Task] = []

        app.add_slice("pair", self.view)
        app.add_slice("qr", lambda: self.qr)
        app.expose("pair_request", self.api_request)
        app.expose("pair_respond", self.api_respond)
        app.expose("pair_out_trust", self.api_out_trust)
        app.expose("pair_code", self.api_code)
        app.expose("connect_ip", self.api_connect_ip)
        app.expose("new_code", self.new_code)
        app.expose("qr_scan_start", self.qr_start)
        app.expose("qr_scan_stop", self.qr_stop)
        peers.on("pair_request", self._on_request)
        peers.on("pair_cancel", self._on_cancel)
        peers.on("pair_code", self._on_code)

    async def start(self) -> None:
        self.new_code()
        self._tasks.append(asyncio.create_task(self._rotate()))

    async def stop(self) -> None:
        self._qr_stop.set()
        for t in self._tasks:
            t.cancel()

    # ---- my code ----------------------------------------------------------
    def new_code(self) -> None:
        if self.code:
            self._prev_code = (self.code, time.time())
        self.code = "%06d" % secrets.randbelow(1_000_000)
        self._code_at = time.time()
        self.app.me_extra["code"] = fmt_code(self.code)
        self.app.me_extra["qr"] = qr_payload(self.app.ip, self.app.port, self.peers.ident.id, self.code)
        self.app.publish("me")

    async def _rotate(self) -> None:
        while True:
            await asyncio.sleep(5)
            if time.time() - self._code_at >= CODE_TTL:
                self.new_code()
            elif self.app.me_extra.get("qr", "").split(":")[2] != self.app.ip:
                self.app.me_extra["qr"] = qr_payload(self.app.ip, self.app.port, self.peers.ident.id, self.code)
                self.app.publish("me")

    def _code_ok(self, conn: Conn, proof: str) -> bool:
        candidates = [self.code]
        prev, at = self._prev_code
        if prev and time.time() - at < 15:
            candidates.append(prev)
        return any(hmac.compare_digest(conn.proof(c), proof) for c in candidates)

    # ---- dialog state -----------------------------------------------------
    def view(self) -> dict | None:
        p = self.pair
        if not p:
            return None
        return {k: p[k] for k in ("id", "dir", "name", "os", "icon", "ip", "code")}

    def _open(self, d, conn: Conn, direction: str) -> None:
        self.pair = {"id": d.id, "dir": direction, "name": d.name, "os": d.os, "icon": d.icon, "ip": d.ip,
                     "code": fmt_code(conn.sas), "trust": True}
        self.app.publish("pair")

    def _close(self) -> None:
        self.pair = None
        self.app.publish("pair")

    # ---- 1/2: request with SAS confirmation --------------------------------
    async def api_request(self, dev_id: str) -> None:
        d = self.peers.get(dev_id)
        if d is None:
            return
        await self._start_outgoing(d.ip, d.port, dev_id)

    async def api_connect_ip(self, ip: str, port: int) -> None:
        await self._start_outgoing(ip, int(port or 53317), None)

    async def _start_outgoing(self, ip: str, port: int, expected_id: str | None) -> None:
        if self.pair:
            return
        try:
            conn = await self.peers.connect(ip, port, expected_id=expected_id, purpose="control")
        except (OSError, HandshakeError, asyncio.TimeoutError, asyncio.IncompleteReadError, PeerError) as e:
            log.info("pairing connect to %s:%s failed: %s", ip, port, e)
            self.app.toast("bad", "Не удалось подключиться", "{0}:{1} не отвечает", args=(ip, port))
            return
        asyncio.create_task(self.peers._run_control(conn))
        d = self.peers.get(conn.peer_id)
        if d.paired:
            self.app.toast("ok", "Уже подключено", d.name)
            return
        self._open(d, conn, "out")
        self._pair_task = asyncio.create_task(self._outgoing(d, conn))

    async def _outgoing(self, d, conn: Conn) -> None:
        try:
            reply, _ = await self.peers.request(d.id, {"type": "pair_request"}, timeout=PAIR_TIMEOUT + 5, conn=conn)
        except (asyncio.TimeoutError, ConnectionError, OSError, PeerError):
            reply = {"ok": False, "error": "timeout"}
        except asyncio.CancelledError:
            await self.peers.send(d.id, {"type": "pair_cancel"})
            self._close()
            raise
        trust = self.pair["trust"] if self.pair else True
        self._close()
        if reply.get("ok"):
            self.peers.set_paired(d.id, trust)
            self.app.toast("ok", "Устройство подключено", d.name)
        elif reply.get("error") == "busy":
            self.app.toast("bad", "Устройство занято", "На {0} уже открыт другой запрос", args=(d.name,))
        else:
            self.app.toast("bad", "Подключение отклонено", d.name)

    async def _on_request(self, d, conn: Conn, msg: dict, body: bytes):
        if d.paired:
            return {"ok": True}
        if self.pair:
            return {"ok": False, "error": "busy"}
        self._open(d, conn, "in")
        self._pair_future = asyncio.get_running_loop().create_future()
        self.app.toast("acc", "Запрос на подключение", d.name)
        try:
            accepted, trust = await asyncio.wait_for(self._pair_future, PAIR_TIMEOUT)
        except asyncio.TimeoutError:
            accepted, trust = False, False
        finally:
            self._pair_future = None
            self._close()
        if accepted:
            self.peers.set_paired(d.id, trust)
            self.app.toast("ok", "Устройство подключено", d.name)
        return {"ok": accepted}

    async def _on_cancel(self, d, conn, msg, body):
        if self.pair and self.pair["id"] == d.id and self._pair_future and not self._pair_future.done():
            self._pair_future.set_result((False, False))
            self.app.toast("acc", "Запрос отменён", d.name)

    async def api_respond(self, dev_id: str, accept: bool, trust: bool = True) -> None:
        if not self.pair or self.pair["id"] != dev_id:
            return
        if self.pair["dir"] == "in":
            if self._pair_future and not self._pair_future.done():
                self._pair_future.set_result((bool(accept), bool(trust)))
        elif self._pair_task:
            self._pair_task.cancel()

    def api_out_trust(self, dev_id: str, trust: bool) -> None:
        if self.pair and self.pair["id"] == dev_id:
            self.pair["trust"] = bool(trust)

    # ---- 3/4: by code -----------------------------------------------------
    async def api_code(self, code: str) -> None:
        code = "".join(ch for ch in str(code) if ch.isdigit())
        if len(code) != 6:
            return
        if code == self.code:
            self.app.toast("bad", "Это ваш собственный код", "Введите код, показанный на другом устройстве")
            return
        candidates = [d for d in self.peers.devices.values() if not d.paired and self.peers.is_online(d) and d.ip]
        results = await asyncio.gather(*(self._try_code(d.ip, d.port, d.id, code) for d in candidates))
        if not any(results):
            self.app.toast("bad", "Код не подошёл", "Проверьте код и что устройство в сети")

    async def _try_code(self, ip: str, port: int, dev_id: str | None, code: str) -> bool:
        try:
            conn = await self.peers.connect(ip, port, expected_id=dev_id, purpose="control")
        except (OSError, HandshakeError, asyncio.TimeoutError, asyncio.IncompleteReadError, PeerError):
            return False
        asyncio.create_task(self.peers._run_control(conn))
        try:
            reply, _ = await self.peers.request(conn.peer_id, {"type": "pair_code", "proof": conn.proof(code)},
                                                timeout=10, conn=conn)
        except (asyncio.TimeoutError, ConnectionError, OSError, PeerError):
            return False
        d = self.peers.get(conn.peer_id)
        if reply.get("ok"):
            self.peers.set_paired(d.id, True)
            self.app.toast("ok", "Устройство подключено", d.name)
            return True
        if not self.peers.get(conn.peer_id).paired:
            conn.close()
        return False

    async def _on_code(self, d, conn: Conn, msg: dict, body: bytes):
        now = time.time()
        tries = [t for t in self._tries.get(conn.peer_ip, []) if now - t < 60]
        if len(tries) >= MAX_CODE_TRIES:
            return {"ok": False, "error": "too many attempts"}
        tries.append(now)
        self._tries[conn.peer_ip] = tries
        if not self._code_ok(conn, str(msg.get("proof", ""))):
            return {"ok": False}
        self.peers.set_paired(d.id, True)
        self.new_code()
        self.app.toast("ok", "Устройство подключено", "{0} — по коду сопряжения", args=(d.name,))
        return {"ok": True}

    # ---- QR scanning ------------------------------------------------------
    def qr_start(self) -> None:
        if self.qr is not None:
            return
        self._qr_stop.clear()
        self.qr = {"frame": None, "error": None}
        self.app.publish("qr")
        threading.Thread(target=self._qr_worker, name="qr", daemon=True).start()

    def qr_stop(self) -> None:
        self._qr_stop.set()
        self.qr = None
        self.app.publish("qr")

    def _qr_worker(self) -> None:
        try:
            import cv2
        except ImportError:
            self._qr_error("Камера недоступна: не установлен OpenCV")
            return
        cap = cv2.VideoCapture(0, cv2.CAP_DSHOW) if self.app_is_win() else cv2.VideoCapture(0)
        if not cap.isOpened():
            self._qr_error("Камера не найдена или занята другой программой")
            return
        detector = cv2.QRCodeDetector()
        last_pub = 0.0
        try:
            while not self._qr_stop.is_set():
                ok, frame = cap.read()
                if not ok:
                    time.sleep(0.05)
                    continue
                text, _, _ = detector.detectAndDecode(frame)
                info = parse_qr(text) if text else None
                if info:
                    self.app.call_soon(self.qr_stop)
                    self.app.spawn(self._qr_pair(info))
                    return
                if time.time() - last_pub > 0.15 and self.qr is not None:
                    last_pub = time.time()
                    small = cv2.resize(frame, (480, int(480 * frame.shape[0] / frame.shape[1])))
                    small = cv2.flip(small, 1)
                    ok, jpg = cv2.imencode(".jpg", small, [cv2.IMWRITE_JPEG_QUALITY, 70])
                    if ok:
                        self.qr = {"frame": "data:image/jpeg;base64," + base64.b64encode(jpg.tobytes()).decode(),
                                   "error": None}
                        self.app.publish("qr")
        finally:
            cap.release()

    @staticmethod
    def app_is_win() -> bool:
        from ..config import IS_WIN
        return IS_WIN

    def _qr_error(self, text: str) -> None:
        self.qr = {"frame": None, "error": self.app.tr(text)}
        self.app.publish("qr")

    async def _qr_pair(self, info: dict) -> None:
        if info["id"] == self.peers.ident.id:
            self.app.toast("bad", "Это QR-код этого же устройства")
            return
        d = self.peers.get(info["id"])
        if d and d.paired:
            self.app.toast("ok", "Уже подключено", d.name)
            return
        if not await self._try_code(info["ip"], info["port"], info["id"], info["code"]):
            self.app.toast("bad", "Не удалось подключиться по QR-коду", "Код мог устареть — попробуйте ещё раз")
