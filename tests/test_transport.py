import asyncio

import pytest

from localdrop.net.identity import Identity
from localdrop.net.peers import PeerManager
from localdrop.net.transport import HandshakeError, dial, serve
from conftest import free_port


async def test_handshake_and_messages():
    a, b = Identity.generate(), Identity.generate()
    got = asyncio.get_running_loop().create_future()

    async def on_conn(conn):
        msg, body = await conn.recv()
        await conn.send({"echo": msg["x"]}, body[::-1])
        got.set_result(conn)
        await asyncio.sleep(0.1)

    port = free_port()
    server = await serve("127.0.0.1", port, b, on_conn)
    c = await dial("127.0.0.1", port, a, expected_id=b.id)
    await c.send({"x": 1}, b"hello")
    msg, body = await c.recv()
    assert msg == {"echo": 1} and body == b"olleh"
    sconn = await got
    assert c.peer_id == b.id and sconn.peer_id == a.id
    assert c.sas == sconn.sas and len(c.sas) == 6
    assert c.proof("123456") == sconn.proof("123456")
    c.close()
    server.close()


async def test_wrong_identity_rejected():
    a, b, other = Identity.generate(), Identity.generate(), Identity.generate()

    async def on_conn(conn):
        await asyncio.sleep(0.1)

    port = free_port()
    server = await serve("127.0.0.1", port, b, on_conn)
    with pytest.raises(HandshakeError):
        await dial("127.0.0.1", port, a, expected_id=other.id)
    server.close()


async def _pair(make_app):
    A, B = make_app("A"), make_app("B")
    pa, pb = PeerManager(A, A.ident), PeerManager(B, B.ident)
    await pa.start()
    await pb.start()
    return A, B, pa, pb


async def test_unpaired_cannot_send_data(make_app):
    A, B, pa, pb = await _pair(make_app)
    pb.on("hi", lambda d, c, m, b: asyncio.sleep(0, {"ok": True}))
    pa.seen(B.ident.id, {"name": "B", "ip": "127.0.0.1", "port": B.port})
    conn = await pa.connect("127.0.0.1", B.port, expected_id=B.ident.id)
    asyncio.create_task(pa._run_control(conn))
    reply, _ = await pa.request(B.ident.id, {"type": "hi"})
    assert reply["ok"] is False and reply["error"] == "not paired"
    await pa.stop()
    await pb.stop()


async def test_paired_request_reply_and_streams(make_app):
    A, B, pa, pb = await _pair(make_app)

    async def hi(d, c, m, body):
        return {"ok": True, "from": d.name, "n": m["n"] * 2}, body + b"!"

    pb.on("hi", hi)
    streamed = asyncio.get_running_loop().create_future()

    async def on_stream(d, conn, hello):
        msg, body = await conn.recv()
        streamed.set_result((hello["purpose"], hello["extra"], body))

    pb.on_stream("files", on_stream)
    pa.seen(B.ident.id, {"name": "B", "ip": "127.0.0.1", "port": B.port})
    pb.seen(A.ident.id, {"name": "A", "ip": "127.0.0.1", "port": A.port})
    pa.set_paired(B.ident.id, trusted=True)
    pb.set_paired(A.ident.id, trusted=False)
    reply, body = await pa.request(B.ident.id, {"type": "hi", "n": 21})
    assert reply["n"] == 42 and reply["from"] == "A" and body == b"!"
    s = await pa.open_stream(B.ident.id, "files", extra=7)
    await s.send({"type": "data"}, b"xyz")
    assert await asyncio.wait_for(streamed, 5) == ("files", 7, b"xyz")
    assert B.storage.device(A.ident.id)["paired"] == 1
    assert pb.devices[A.ident.id].trusted is False
    await pa.stop()
    await pb.stop()
