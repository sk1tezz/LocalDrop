"""Finding LocalDrop devices on the LAN: mDNS (zeroconf) plus a UDP beacon.

mDNS works on most networks; the beacon (multicast + subnet broadcast) covers
routers that filter mDNS. The beacon always uses UDP port 53317 regardless of
the TCP port, so instances on non-default ports still find each other.
"""
from __future__ import annotations

import asyncio
import json
import logging
import socket
import struct
import time

from zeroconf import IPVersion, ServiceStateChange
from zeroconf.asyncio import AsyncServiceBrowser, AsyncServiceInfo, AsyncZeroconf

from . import ifaces

log = logging.getLogger(__name__)

SERVICE = "_localdrop._tcp.local."
BEACON_GROUP = "224.0.0.167"
BEACON_PORT = 53317
BEACON_EVERY = 5


class Discovery:
    def __init__(self, app, peers):
        self.app = app
        self.peers = peers
        self.ident = peers.ident
        self.scanning = False
        self._zc: AsyncZeroconf | None = None
        self._browser = None
        self._info: AsyncServiceInfo | None = None
        self._sock: socket.socket | None = None
        self._transport = None
        self._tasks: list[asyncio.Task] = []
        self._ips: list[dict] = []
        self._names: dict[str, str] = {}     # mdns service name -> device id
        app.add_slice("scanning", lambda: self.scanning)
        app.expose("rescan", self.rescan)
        app.settings.on_change(self._on_settings)

    # ---- lifecycle --------------------------------------------------------
    async def start(self) -> None:
        self._ips = [x for x in ifaces.list_ipv4() if not x["virtual"]] or ifaces.list_ipv4()
        await self._start_beacon()
        await self._start_mdns()
        self._tasks.append(asyncio.create_task(self._beacon_loop()))

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        if self._transport:
            try:
                self._send_beacon(bye=True)
            except OSError:
                pass
            self._transport.close()
        if self._zc:
            try:
                if self._info:
                    await self._zc.async_unregister_service(self._info)
                await self._zc.async_close()
            except Exception:
                log.debug("zeroconf close failed", exc_info=True)

    @property
    def visible(self) -> bool:
        return bool(self.app.settings.get("visible"))

    def announcement(self) -> dict:
        me = self.app.me()
        return {"ld": 1, "id": self.ident.id, "name": self.app.settings.get("device_name"), "os": me["os"],
                "kind": me["kind"], "icon": me["icon"], "port": self.app.port}

    def _on_settings(self, changes: dict, _all: dict) -> None:
        if "device_name" in changes or "visible" in changes:
            self.app.spawn(self._reannounce())

    async def _reannounce(self) -> None:
        if self._zc is None:
            return
        try:
            if self._info:
                await self._zc.async_unregister_service(self._info)
                self._info = None
            if self.visible:
                self._info = self._service_info()
                await self._zc.async_register_service(self._info, allow_name_change=True)
        except Exception:
            log.exception("mDNS re-announce failed")
        self._send_beacon()

    # ---- mDNS -------------------------------------------------------------
    def _service_info(self) -> AsyncServiceInfo:
        a = self.announcement()
        props = {k: str(v) for k, v in a.items() if k != "ld"}
        return AsyncServiceInfo(
            SERVICE, f"{self.ident.id}.{SERVICE}", port=self.app.port, properties=props,
            parsed_addresses=[x["ip"] for x in self._ips], server=f"ld-{self.ident.id[:12]}.local.")

    async def _start_mdns(self) -> None:
        try:
            ips = [x["ip"] for x in self._ips]
            self._zc = AsyncZeroconf(interfaces=ips or None, ip_version=IPVersion.V4Only)
            if self.visible:
                self._info = self._service_info()
                await self._zc.async_register_service(self._info, allow_name_change=True)
            self._browser = AsyncServiceBrowser(self._zc.zeroconf, [SERVICE], handlers=[self._on_mdns])
        except Exception:
            log.exception("mDNS failed to start; relying on the UDP beacon")
            self._zc = None

    def _on_mdns(self, zeroconf, service_type, name, state_change) -> None:
        if state_change is ServiceStateChange.Removed:
            dev_id = self._names.pop(name, None)
            if dev_id:
                self.peers.lost(dev_id)
            return
        asyncio.ensure_future(self._resolve(service_type, name))

    async def _resolve(self, service_type: str, name: str) -> None:
        info = AsyncServiceInfo(service_type, name)
        if not await info.async_request(self._zc.zeroconf, 3000):
            return
        props = {k.decode(): (v.decode() if v else "") for k, v in (info.properties or {}).items()}
        dev_id = props.get("id")
        if not dev_id or dev_id == self.ident.id:
            return
        addrs = info.parsed_addresses(IPVersion.V4Only)
        ip = self._best_addr(addrs)
        if not ip:
            return
        self._names[name] = dev_id
        self.peers.seen(dev_id, {**props, "ip": ip, "port": info.port})

    def _best_addr(self, addrs: list[str]) -> str:
        for a in addrs:
            for mine in self._ips:
                if ifaces.same_subnet(a, mine["ip"], mine["prefix"]):
                    return a
        return addrs[0] if addrs else ""

    # ---- UDP beacon -------------------------------------------------------
    async def _start_beacon(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except OSError:
                pass
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
        sock.bind(("", BEACON_PORT))
        for x in self._ips:
            try:
                mreq = struct.pack("4s4s", socket.inet_aton(BEACON_GROUP), socket.inet_aton(x["ip"]))
                sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
            except OSError as e:
                log.debug("join multicast on %s failed: %s", x["ip"], e)
        sock.setblocking(False)
        self._sock = sock
        loop = asyncio.get_running_loop()
        self._transport, _ = await loop.create_datagram_endpoint(lambda: _BeaconProto(self), sock=sock)

    def _send_beacon(self, query: bool = False, bye: bool = False, to: tuple | None = None) -> None:
        if self._transport is None:
            return
        msg = self.announcement()
        if query:
            msg["q"] = 1
        if bye or not self.visible:
            msg["hidden"] = 1
        data = json.dumps(msg).encode()
        if to:
            self._transport.sendto(data, to)
            return
        for x in self._ips:
            try:
                self._sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(x["ip"]))
                self._transport.sendto(data, (BEACON_GROUP, BEACON_PORT))
                net = ifaces.broadcast_addr(x["ip"], x["prefix"])
                if net:
                    self._transport.sendto(data, (net, BEACON_PORT))
            except OSError as e:
                log.debug("beacon on %s failed: %s", x["ip"], e)

    def on_beacon(self, data: bytes, addr: tuple) -> None:
        try:
            msg = json.loads(data)
        except ValueError:
            return
        if not isinstance(msg, dict) or msg.get("ld") != 1:
            return
        dev_id = msg.get("id")
        if not isinstance(dev_id, str) or dev_id == self.ident.id:
            return
        info = {k: msg[k] for k in ("name", "os", "kind", "icon", "port") if k in msg}
        info["ip"] = addr[0]
        info["hidden"] = bool(msg.get("hidden"))
        self.peers.seen(dev_id, info)
        if msg.get("q") and self.visible:
            self._send_beacon(to=(addr[0], BEACON_PORT))

    async def _beacon_loop(self) -> None:
        self._send_beacon(query=True)
        while True:
            await asyncio.sleep(BEACON_EVERY)
            if self.visible:
                self._send_beacon()

    async def rescan(self) -> None:
        self.scanning = True
        self.app.publish("scanning")
        new_ips = [x for x in ifaces.list_ipv4() if not x["virtual"]] or ifaces.list_ipv4()
        if [x["ip"] for x in new_ips] != [x["ip"] for x in self._ips]:
            log.info("network interfaces changed, restarting discovery")
            await self.stop()
            self._tasks = []
            await self.start()
        self._send_beacon(query=True)
        await asyncio.sleep(1.5)
        self.scanning = False
        self.app.publish("scanning")
        n = sum(1 for d in self.peers.view() if d["online"])
        self.app.toast("acc", "Поиск завершён", "Устройств в сети: {0}", args=(n,), system=False)


class _BeaconProto(asyncio.DatagramProtocol):
    def __init__(self, disc: Discovery):
        self.disc = disc

    def datagram_received(self, data: bytes, addr) -> None:
        self.disc.on_beacon(data, addr)

    def error_received(self, exc) -> None:
        log.debug("beacon socket error: %s", exc)
