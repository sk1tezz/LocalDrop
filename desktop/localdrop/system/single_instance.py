"""One LocalDrop per user profile.

The running instance listens on 127.0.0.1 and writes its port and a random
token to ``instance.json`` in the data directory (readable only by the user).
A second launch forwards its request (show the window, send files) and exits.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import secrets
import socket
from pathlib import Path

log = logging.getLogger(__name__)

FILE = "instance.json"


def forward(ddir: Path, request: dict) -> bool:
    """Hand ``request`` to a running instance. Returns False if none is running."""
    try:
        info = json.loads((ddir / FILE).read_text("utf-8"))
        with socket.create_connection(("127.0.0.1", int(info["port"])), timeout=2) as s:
            s.sendall(json.dumps({"token": info["token"], **request}).encode() + b"\n")
            s.settimeout(5)
            return s.recv(16).startswith(b"ok")
    except (OSError, ValueError, KeyError):
        return False


class InstanceServer:
    def __init__(self, app):
        self.app = app
        self.token = secrets.token_hex(16)
        self._server = None

    async def start(self) -> None:
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        port = self._server.sockets[0].getsockname()[1]
        path = self.app.ddir / FILE
        path.write_text(json.dumps({"port": port, "token": self.token, "pid": os.getpid()}), "utf-8")
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass

    async def stop(self) -> None:
        if self._server:
            self._server.close()
        try:
            (self.app.ddir / FILE).unlink()
        except OSError:
            pass

    async def _handle(self, reader, writer) -> None:
        try:
            line = await asyncio.wait_for(reader.readline(), 5)
            req = json.loads(line)
            if not secrets.compare_digest(str(req.get("token", "")), self.token):
                return
            writer.write(b"ok\n")
            await writer.drain()
            self.app.handle_request(req)
        except (OSError, ValueError, asyncio.TimeoutError):
            pass
        finally:
            writer.close()
