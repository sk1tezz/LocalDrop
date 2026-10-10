import asyncio
import sys

import pytest
from PIL import Image

from localdrop.clip import sync as clipsync
from localdrop.clip.sync import ClipService, image_hash
from localdrop.net.peers import PeerManager


class FakeClipboard:
    def __init__(self):
        self.seq = 0
        self.value = None
        self.writes = 0

    def change_count(self):
        return self.seq

    def read(self):
        return self.value

    def set(self, ctype, v):
        self.value = (ctype, v)
        self.seq += 1

    def write_text(self, text):
        self.writes += 1
        self.set("text", text)

    def write_image(self, img):
        self.writes += 1
        self.set("image", img.copy())


async def wait_for(cond, timeout=8):
    for _ in range(int(timeout / 0.02)):
        if cond():
            return
        await asyncio.sleep(0.02)
    raise AssertionError("condition not met")


@pytest.fixture
async def nodes(make_app, monkeypatch):
    monkeypatch.setattr(clipsync, "POLL", 0.05)
    A, B = make_app("A"), make_app("B")
    pa, pb = PeerManager(A, A.ident), PeerManager(B, B.ident)
    ka, kb = FakeClipboard(), FakeClipboard()
    ca, cb = ClipService(A, pa, ka), ClipService(B, pb, kb)
    for x in (pa, pb, ca, cb):
        await x.start()
    pa.seen(B.ident.id, {"name": "B", "ip": "127.0.0.1", "port": B.port})
    pb.seen(A.ident.id, {"name": "A", "ip": "127.0.0.1", "port": A.port})
    pa.set_paired(B.ident.id, trusted=True)
    pb.set_paired(A.ident.id, trusted=True)
    await wait_for(lambda: pa.connected(B.ident.id))
    await asyncio.sleep(0.2)                       # watchers record the initial state
    yield A, B, pa, pb, ka, kb, ca, cb
    for x in (ca, cb, pa, pb):
        await x.stop()


async def test_text_syncs_without_echo(nodes):
    A, B, pa, pb, ka, kb, ca, cb = nodes
    assert A.settings.get("clip_devices") == [B.ident.id]   # added on pairing
    ka.set("text", "hello from A")
    await wait_for(lambda: kb.value == ("text", "hello from A"))
    await asyncio.sleep(0.4)
    assert len(A.storage.clips()) == 1 and len(B.storage.clips()) == 1   # no echo back to A
    assert B.storage.clips()[0]["dev"] == A.ident.id
    assert [h["dir"] for h in B.storage.history()] == ["in"]


async def test_image_sync(nodes):
    A, B, pa, pb, ka, kb, ca, cb = nodes
    img = Image.new("RGB", (64, 40), (200, 30, 90))
    ka.set("image", img)
    await wait_for(lambda: kb.value is not None and kb.value[0] == "image")
    assert image_hash(kb.value[1]) == image_hash(img)
    (c,) = cb.view()
    assert c["type"] == "image" and c["thumb"].startswith("data:image/jpeg")


async def test_untrusted_goes_to_history_only(nodes):
    A, B, pa, pb, ka, kb, ca, cb = nodes
    pb.set_trust(A.ident.id, False)
    ka.set("text", "secret")
    await wait_for(lambda: len(B.storage.clips()) == 1)
    assert kb.writes == 0
    await cb.api_copy(B.storage.clips()[0]["id"])
    assert kb.value == ("text", "secret")


async def test_auto_off_and_manual_send(nodes):
    A, B, pa, pb, ka, kb, ca, cb = nodes
    A.settings.update({"clip_auto": False})
    ka.set("text", "manual only")
    await asyncio.sleep(0.4)
    assert kb.value is None
    await ca.api_send_current()
    await wait_for(lambda: kb.value == ("text", "manual only"))
    assert any(h["dir"] == "out" for h in A.storage.history())


@pytest.mark.skipif(sys.platform != "win32", reason="Windows clipboard")
def test_windows_backend_roundtrip():
    from localdrop.clip import backend_win as b
    saved = b.read()
    try:
        b.write_text("LocalDrop тест ✓")
        assert b.read() == ("text", "LocalDrop тест ✓")
        img = Image.new("RGB", (33, 21), (10, 120, 250))
        b.write_image(img)
        kind, got = b.read()
        assert kind == "image" and image_hash(got) == image_hash(img)
    finally:
        if saved and saved[0] == "text":
            b.write_text(saved[1])
        elif saved:
            b.write_image(saved[1])
