"""A desktop-side peer for the Android interop tests (android/protocol/src/test).

It runs the real desktop protocol code (localdrop.net.*), so the Kotlin tests prove
wire compatibility rather than agreement with a copy of the spec.

    python desktop/tools/interop_peer.py serve          -> prints "READY <port> <device id>", echoes messages
    python desktop/tools/interop_peer.py dial <port>    -> handshake with a Kotlin server, prints JSON lines

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


def main() -> None:
    mode = sys.argv[1]
    if mode == "serve":
        asyncio.run(serve())
    elif mode == "dial":
        asyncio.run(dial(int(sys.argv[2])))
    else:
        raise SystemExit("unknown mode " + mode)


if __name__ == "__main__":
    main()
