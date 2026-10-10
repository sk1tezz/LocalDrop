"""Audio streaming service: what to capture, where to play it, and the control protocol.

Control messages (encrypted control connection)::

    audio_start  {session, keys, port, delay, vol, out} -> {ok, port, outputs}
    audio_update {session, delay?, vol?, out?}
    audio_stop   {session}
    audio_outputs {}                                   -> {outputs}
    audio_stopped {session}          receiver -> sender: the user disconnected
    audio_request {vol?}             receiver -> sender: "stream to me" (a phone pressing Listen)
    audio_release {}                 receiver -> sender: "stop streaming to me"

Only trusted devices may start playback on a receiver, or ask this computer to stream.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid

from . import sources
from .stream import Player, Receiver, Sender, list_outputs, new_keys

log = logging.getLogger(__name__)

QUALITY_LABEL = {"low": "Низкая задержка", "balance": "Баланс", "hq": "Высокое качество"}
EXTRA_LATENCY_MS = 30          # capture backlog + output buffer on top of the jitter buffer


class AudioService:
    def __init__(self, app, peers):
        self.app = app
        self.peers = peers
        self.on = False
        self.error = ""
        self.sender: Sender | None = None
        self.capture = None
        self.capture_source = None
        self.sessions: dict[str, dict] = {}        # dev -> {id, vol, out, delay, started}
        self.remote: dict[str, dict] = {}          # dev -> {outputs, connected, error}
        self.incoming: dict | None = None          # {dev, session, receiver, player, vol, out, started}
        self.sources: list[dict] = []
        self._requested: set[str] = set()          # devices that asked us to stream (audio_request)
        self._on_by_request = False                # streaming was switched on by a request, not by the user
        self._mute_prev: bool | None = None
        self._lock = asyncio.Lock()
        self._tasks: list[asyncio.Task] = []

        app.add_slice("audio", self.view)
        app.expose("audio_toggle", self.api_toggle)
        app.expose("audio_target_changed", self.api_target_changed)
        app.expose("audio_sources_refresh", self.api_refresh_sources)
        app.expose("audio_incoming_stop", self.api_incoming_stop)
        app.settings.on_change(self._on_settings)
        peers.on("audio_start", self._on_start)
        peers.on("audio_update", self._on_update)
        peers.on("audio_stop", self._on_stop)
        peers.on("audio_outputs", self._on_outputs)
        peers.on("audio_stopped", self._on_stopped)
        peers.on("audio_request", self._on_request)
        peers.on("audio_release", self._on_release)
        peers.online_listeners.append(lambda dev, online: self.app.call_soon(self._kick))

    # ---- lifecycle --------------------------------------------------------
    async def start(self) -> None:
        await self.api_refresh_sources()
        self._tasks.append(asyncio.create_task(self._loop()))

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        self.on = False
        await self._stop_sending()
        self._stop_incoming(notify=False)

    def _kick(self) -> None:
        asyncio.ensure_future(self.reconcile())

    async def _loop(self) -> None:
        n = 0
        while True:
            await asyncio.sleep(2)
            n += 1
            await self.reconcile()
            if n % 5 == 1:
                await self._refresh_outputs()
            if self.incoming and time.monotonic() - self.incoming["receiver"].last_packet > 15 \
                    and time.time() - self.incoming["started"] > 15:
                log.info("incoming audio timed out")
                self._stop_incoming(notify=False)

    # ---- state ------------------------------------------------------------
    def view(self) -> dict:
        a = self.app.settings.get("audio")
        inc = None
        if self.incoming:
            inc = {"dev": self.incoming["dev"], "vol": self.incoming["vol"], "out": self.incoming["out"] or "",
                   "connected": self.incoming["receiver"].ready}
        remote = {}
        for dev, r in self.remote.items():
            remote[dev] = {**r, "connected": dev in self.sessions}
        return {"on": self.on, "error": self.error, "sources": self.sources,
                "latency": a["buffer"] + EXTRA_LATENCY_MS if self.on else 0,
                "remote": remote, "incoming": inc, "outputs": list_outputs()}

    def _publish(self) -> None:
        self.app.publish("audio")

    # ---- api --------------------------------------------------------------
    async def api_toggle(self) -> None:
        self.on = not self.on
        self._on_by_request = False
        if not self.on:
            self._requested.clear()
        self.error = ""
        if not self.app.settings.get("features").get("audio") and self.on:
            self.app.settings.update({"features": {"audio": True}})
        await self.reconcile()
        targets = [t for t in self.app.settings.get("audio")["targets"] if t["on"]]
        if self.on and not self.error:
            names = ", ".join(self._name(t["dev"]) for t in targets) or "—"
            self.app.toast("audio", "Трансляция запущена", names)
        elif not self.on:
            self.app.toast("audio", "Трансляция остановлена", "Звук снова играет на этом ПК")

    async def api_target_changed(self, dev: str) -> None:
        await self.reconcile()

    async def api_refresh_sources(self) -> None:
        loop = asyncio.get_running_loop()
        try:
            self.sources = await loop.run_in_executor(None, sources.list_sources)
        except Exception:
            log.exception("listing audio sources failed")
            self.sources = [{"id": "system", "label": "Весь звук системы", "group": "system"}]
        self._publish()

    def _on_settings(self, changes: dict, _all: dict) -> None:
        if "audio" in changes:
            self.app.call_soon(self._kick)

    def _name(self, dev: str) -> str:
        d = self.peers.get(dev)
        return d.name if d else "?"

    # ---- sending ----------------------------------------------------------
    async def reconcile(self) -> None:
        async with self._lock:
            try:
                await self._reconcile()
            except Exception:
                log.exception("audio reconcile failed")
        self._publish()

    async def _reconcile(self) -> None:
        a = self.app.settings.get("audio")
        if not self.on:
            await self._stop_sending()
            return
        if not await self._ensure_capture(a["source"], a["quality"]):
            return
        if self.sender.codec != {"low": "opus", "balance": "opus", "hq": "pcm"}[a["quality"]] or \
                self.sender._encoder.bitrate != _bitrate(a["quality"]):
            self.sender.set_quality(a["quality"])
        self._apply_mute(a["mute_local"] and not a["source"].startswith("input:"))
        desired = {t["dev"]: t for t in a["targets"] if t["on"] and self.peers.connected(t["dev"])}
        for dev in list(self.sessions):
            if dev not in desired:
                await self._end_session(dev, notify=True)
        for dev, t in desired.items():
            s = self.sessions.get(dev)
            if s is None:
                await self._start_session(dev, t, a)
            elif (s["vol"], s["out"], s["delay"]) != (t["vol"], t.get("out", ""), a["buffer"]):
                ok = await self.peers.send(dev, {"type": "audio_update", "session": s["id"], "vol": t["vol"],
                                                 "out": t.get("out", ""), "delay": a["buffer"]})
                if ok:
                    s.update(vol=t["vol"], out=t.get("out", ""), delay=a["buffer"])

    async def _ensure_capture(self, source: str, quality: str) -> bool:
        if self.sender is not None and self.capture_source == source:
            return True
        await self._stop_capture()
        if self.sender is None:
            self.sender = Sender(quality)
            self.sender.start()
        loop = asyncio.get_running_loop()
        try:
            cap = sources.open_capture(source, self.sender.on_audio)
            await loop.run_in_executor(None, sources.start_capture, cap)
        except sources.CaptureError as e:
            self.error = self.app.tr("Не удалось захватить звук: {0}", str(e))
            self.on = False
            await self._stop_sending()
            self.app.toast("bad", "Не удалось захватить звук", str(e))
            return False
        self.capture, self.capture_source = cap, source
        self.error = ""
        return True

    async def _stop_capture(self) -> None:
        if self.capture is not None:
            cap, self.capture = self.capture, None
            self.capture_source = None
            await asyncio.get_running_loop().run_in_executor(None, cap.stop)

    async def _stop_sending(self) -> None:
        for dev in list(self.sessions):
            await self._end_session(dev, notify=True)
        await self._stop_capture()
        if self.sender is not None:
            self.sender.stop()
            self.sender = None
        self._apply_mute(False)

    async def _start_session(self, dev: str, t: dict, a: dict) -> None:
        d = self.peers.get(dev)
        sid = uuid.uuid4().hex
        keys = new_keys()
        msg = {"type": "audio_start", "session": sid, "keys": keys, "port": self.sender.port,
               "delay": a["buffer"], "vol": t["vol"], "out": t.get("out", "")}
        try:
            reply, _ = await self.peers.request(dev, msg, timeout=10)
        except Exception as e:
            self.remote.setdefault(dev, {})["error"] = self.app.tr("нет ответа")
            log.info("audio_start to %s failed: %s", dev[:8], e)
            return
        r = self.remote.setdefault(dev, {})
        if not reply.get("ok"):
            r["error"] = self.app.tr({"not trusted": "это устройство не доверяет вашему ПК",
                                      "disabled": "приём звука выключен"}.get(reply.get("error"), "отказ"))
            return
        r["error"] = ""
        if reply.get("outputs") is not None:
            r["outputs"] = reply["outputs"]
        self.sender.add_target(dev, (d.ip, int(reply["port"])), keys)
        self.sessions[dev] = {"id": sid, "vol": t["vol"], "out": t.get("out", ""), "delay": a["buffer"],
                              "started": time.time(), "quality": a["quality"]}

    async def _end_session(self, dev: str, notify: bool) -> None:
        s = self.sessions.pop(dev, None)
        if s is None:
            return
        if self.sender:
            self.sender.remove_target(dev)
        if notify:
            await self.peers.send(dev, {"type": "audio_stop", "session": s["id"]})
        self._history("out", dev, time.time() - s["started"], s["quality"])

    def _history(self, dir_: str, dev: str, seconds: float, quality: str | None) -> None:
        if seconds < 5:
            return
        args = [{"dur": int(seconds)}]
        k = "{0}"
        if quality:
            k = "{0} · {1}"
            args.append(QUALITY_LABEL.get(quality, quality))
        self.app.storage.add_history("audio", dir_, "Трансляция звука",
                                     json.dumps({"k": k, "a": args}, ensure_ascii=False), dev)
        self.app.publish("history")

    def _apply_mute(self, want: bool) -> None:
        if want and self._mute_prev is None:
            prev = sources.set_output_mute(True)
            self._mute_prev = bool(prev) if prev is not None else False
        elif not want and self._mute_prev is not None:
            if not self._mute_prev:
                sources.set_output_mute(False)
            self._mute_prev = None

    async def _refresh_outputs(self) -> None:
        for d in list(self.peers.devices.values()):
            if d.paired and self.peers.connected(d.id):
                try:
                    reply, _ = await self.peers.request(d.id, {"type": "audio_outputs"}, timeout=5)
                    if reply.get("ok"):
                        self.remote.setdefault(d.id, {})["outputs"] = reply.get("outputs") or []
                except Exception:
                    pass
        self._publish()

    async def _on_stopped(self, d, conn, msg, body):
        s = self.sessions.get(d.id)
        if s and s["id"] == msg.get("session"):
            await self._end_session(d.id, notify=False)
            targets = self.app.settings.get("audio")["targets"]
            self.app.settings.update({"audio": {"targets": [dict(t, on=False) if t["dev"] == d.id else t
                                                            for t in targets]}})
            self.app.toast("audio", "Устройство отключилось", "{0} больше не принимает звук", args=(d.name,))
            self._publish()

    # ---- requests from receivers (a phone pressing "Listen") -----------------
    def _set_target(self, dev: str, on: bool, vol: int | None = None) -> None:
        targets = self.app.settings.get("audio")["targets"]
        for t in targets:
            if t["dev"] == dev:
                t["on"] = on
                if vol is not None:
                    t["vol"] = vol
                break
        else:
            targets.append({"dev": dev, "on": on, "vol": 70 if vol is None else vol, "out": ""})
        self.app.settings.update({"audio": {"targets": targets}})

    async def _on_request(self, d, conn, msg, body):
        if not self.app.settings.get("features").get("audio"):
            return {"ok": False, "error": "disabled"}
        if not d.trusted:
            return {"ok": False, "error": "not trusted"}
        vol = msg.get("vol")
        self._requested.add(d.id)
        self._set_target(d.id, True, int(vol) if isinstance(vol, (int, float)) else None)
        if not self.on:
            self.on = True
            self._on_by_request = True
            self.error = ""
        await self.reconcile()
        if self.error:
            return {"ok": False, "error": self.error}
        self.app.toast("audio", "Трансляция запущена", "{0} слушает звук этого ПК", args=(d.name,))
        return {"ok": True}

    async def _on_release(self, d, conn, msg, body):
        if d.id not in self._requested:
            return {"ok": True}
        self._requested.discard(d.id)
        self._set_target(d.id, False)
        others = [t for t in self.app.settings.get("audio")["targets"] if t["on"]]
        if self._on_by_request and not others:
            self.on = False
            self._on_by_request = False
        await self.reconcile()
        return {"ok": True}

    # ---- receiving --------------------------------------------------------
    async def _on_outputs(self, d, conn, msg, body):
        loop = asyncio.get_running_loop()
        return {"ok": True, "outputs": await loop.run_in_executor(None, list_outputs)}

    async def _on_start(self, d, conn, msg, body):
        if not self.app.settings.get("features").get("audio"):
            return {"ok": False, "error": "disabled"}
        if not d.trusted:
            return {"ok": False, "error": "not trusted"}
        try:
            keys = msg["keys"]
            port = int(msg["port"])
        except (KeyError, TypeError, ValueError):
            return {"ok": False, "error": "bad request"}
        self._stop_incoming(notify=False)
        loop = asyncio.get_running_loop()
        rec = Receiver(keys, (d.ip or conn.peer_ip, port), int(msg.get("delay", 60)), int(msg.get("vol", 60)))
        player = Player(rec, msg.get("out") or None)
        try:
            await loop.run_in_executor(None, player.start)
        except Exception as e:
            log.exception("audio output failed")
            player = Player(rec, None)
            try:
                await loop.run_in_executor(None, player.start)
            except Exception:
                rec.stop()
                return {"ok": False, "error": str(e)}
        rec.start()
        self.incoming = {"dev": d.id, "session": msg.get("session"), "receiver": rec, "player": player,
                         "vol": rec.volume, "out": msg.get("out") or "", "started": time.time()}
        self._publish()
        self.app.toast("audio", "Входящий звук", "Играет звук с {0}", args=(d.name,))
        return {"ok": True, "port": rec.port, "outputs": await loop.run_in_executor(None, list_outputs)}

    async def _on_update(self, d, conn, msg, body):
        inc = self.incoming
        if not inc or inc["dev"] != d.id or inc["session"] != msg.get("session"):
            return
        rec = inc["receiver"]
        if "vol" in msg:
            rec.volume = inc["vol"] = int(msg["vol"])
        if "delay" in msg:
            rec.delay_ns = int(msg["delay"]) * 1_000_000
        out = msg.get("out") or ""
        if out != inc["out"]:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, inc["player"].stop)
            inc["player"] = Player(rec, out or None)
            try:
                await loop.run_in_executor(None, inc["player"].start)
                inc["out"] = out
            except Exception:
                log.exception("switching audio output failed")
                inc["player"] = Player(rec, None)
                await loop.run_in_executor(None, inc["player"].start)
                inc["out"] = ""
        self._publish()

    async def _on_stop(self, d, conn, msg, body):
        inc = self.incoming
        if inc and inc["dev"] == d.id and inc["session"] == msg.get("session"):
            self._stop_incoming(notify=False)

    def _stop_incoming(self, notify: bool) -> None:
        inc, self.incoming = self.incoming, None
        if not inc:
            return
        inc["player"].stop()
        inc["receiver"].stop()
        self._history("in", inc["dev"], time.time() - inc["started"], None)
        if notify:
            asyncio.ensure_future(self.peers.send(inc["dev"], {"type": "audio_stopped", "session": inc["session"]}))
        self._publish()

    async def api_incoming_stop(self) -> None:
        self._stop_incoming(notify=True)


def _bitrate(quality: str) -> int:
    from .codec import QUALITY
    return QUALITY.get(quality, QUALITY["balance"])[1]
