"""A desktop-side peer for the Android interop tests (android/protocol/src/test).

It runs the real desktop protocol code (localdrop.net.*), so the Kotlin tests prove
wire compatibility rather than agreement with a copy of the spec.

    python desktop/tools/interop_peer.py serve          -> prints "READY <port> <device id>", echoes messages
    python desktop/tools/interop_peer.py dial <port>    -> handshake with a Kotlin server, prints JSON lines
    python desktop/tools/interop_peer.py node <dir>     -> a whole desktop node (peers, pairing, files, clipboard)
                                                           driven by python statements on stdin, one per line

Every line on stdout is one JSON object (except READY); the test reads them.
"""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from localdrop.net import transport  # noqa: E402
from localdrop.net.identity import Identity  # noqa: E402


def out(obj) -> None:
    print(json.dumps(obj, ensure_ascii=False), flush=True)


async def serve() -> None:
    ident = Identity.generate()
    done = asyncio.Event()

    async def on_conn(conn):
        while True:
            msg, body = await conn.recv()
            if msg.get("type") == "bye":
                done.set()
                return
            await conn.send({"type": "echo", "sas": conn.sas, "peer": conn.peer_id, "got": msg,
                             "proof": conn.proof("123456"), "n": len(body)}, body[::-1])

    server = await transport.serve("127.0.0.1", 0, ident, on_conn)
    port = server.sockets[0].getsockname()[1]
    print(f"READY {port} {ident.id}", flush=True)
    try:
        await asyncio.wait_for(done.wait(), 60)
    finally:
        server.close()


async def dial(port: int) -> None:
    ident = Identity.generate()
    conn = await transport.dial("127.0.0.1", port, ident)
    out({"sas": conn.sas, "peer": conn.peer_id, "me": ident.id, "proof": conn.proof("123456")})
    await conn.send({"type": "hi", "text": "привет", "n": 3}, b"abc")
    msg, body = await conn.recv()
    out({"reply": msg, "body": body.decode("utf-8", "replace")})
    await conn.send({"type": "bye"})
    await conn.wait_closed()


async def node(ddir: str) -> None:
    """Desktop services on a fake app (as in desktop/tests). Each stdin line is evaluated with
    `p` (PeerManager), `k` (Pairing), `f` (FileService), `c` (ClipService), `a` (AudioService, not started), `app`;
    awaitables are awaited.
    A line starting with "!" is executed as a statement (to keep objects between lines).
    The result goes to stdout as {"r": ...}; toasts as {"toast": [...]}."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))
    from conftest import FakeApp  # noqa: E402
    from localdrop.clip.sync import ClipService
    from localdrop.files.transfer import FileService
    from localdrop.net.pairing import Pairing
    from localdrop.net.peers import PeerManager

    class App(FakeApp):
        def __init__(self):
            super().__init__(Path(ddir), "Desktop")
            self.history_handlers = {}
            self.api, self.slices = {}, {}

        def toast(self, kind, title, text="", args=(), targs=(), system=True):
            out({"toast": [kind, title, text, [str(a) for a in args]]})

    app = App()
    p = PeerManager(app, app.ident)
    k = Pairing(app, p)
    f = FileService(app, p)
    c = ClipService(app, p, backend=None)
    from localdrop.audio.service import AudioService
    a = AudioService(app, p)
    for svc in (p, k, f):
        await svc.start()
    print(f"READY {app.port} {app.ident.id}", flush=True)
    env = {"p": p, "k": k, "f": f, "c": c, "a": a, "app": app, "asyncio": asyncio, "json": json}
    loop = asyncio.get_running_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line or line.strip() == "quit":
            break
        try:
            if line.startswith("!"):                       # a statement: "!x = 1"
                exec(compile(line[1:], "<cmd>", "exec"), env)
                out({"r": None})
                continue
            r = eval(compile(line, "<cmd>", "eval"), env)
            if asyncio.iscoroutine(r) or isinstance(r, asyncio.Future):
                r = await r
            out({"r": json.loads(json.dumps(r, default=str))})
        except Exception as e:  # noqa: BLE001 - reported to the test
            out({"err": repr(e)})


def main() -> None:
    mode = sys.argv[1]
    if mode == "serve":
        asyncio.run(serve())
    elif mode == "node":
        asyncio.run(node(sys.argv[2]))
    elif mode == "dial":
        asyncio.run(dial(int(sys.argv[2])))
    else:
        raise SystemExit("unknown mode " + mode)


if __name__ == "__main__":
    main()
