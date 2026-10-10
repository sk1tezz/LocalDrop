import asyncio

import numpy as np

from localdrop.net.pairing import Pairing, parse_qr, qr_payload
from localdrop.net.peers import PeerManager


class App:
    """FakeApp plus the registry methods Pairing needs."""

    def __init__(self, fake):
        self._fake = fake
        self.api = {}
        self.slices = {}

    def __getattr__(self, name):
        return getattr(self._fake, name)

    def add_slice(self, name, fn):
        self.slices[name] = fn

    def expose(self, name, fn):
        self.api[name] = fn

    def call_soon(self, fn, *a):
        asyncio.get_running_loop().call_soon(fn, *a)


async def setup(make_app):
    A, B = App(make_app("A")), App(make_app("B"))
    pa, pb = PeerManager(A, A.ident), PeerManager(B, B.ident)
    ka, kb = Pairing(A, pa), Pairing(B, pb)
    for x in (pa, pb, ka, kb):
        await x.start()
    pa.seen(B.ident.id, {"name": "B", "ip": "127.0.0.1", "port": B.port})
    pb.seen(A.ident.id, {"name": "A", "ip": "127.0.0.1", "port": A.port})
    return A, B, pa, pb, ka, kb


async def stop(*xs):
    for x in xs:
        await x.stop()


async def wait_for(cond, timeout=5):
    for _ in range(int(timeout / 0.02)):
        if cond():
            return
        await asyncio.sleep(0.02)
    raise AssertionError("condition not met")


async def test_request_accepted_with_matching_sas(make_app):
    A, B, pa, pb, ka, kb = await setup(make_app)
    await ka.api_request(B.ident.id)
    await wait_for(lambda: kb.pair is not None)
    assert ka.pair["code"] == kb.pair["code"]
    assert ka.pair["dir"] == "out" and kb.pair["dir"] == "in"
    ka.api_out_trust(B.ident.id, False)
    await kb.api_respond(A.ident.id, True, True)
    await wait_for(lambda: pa.devices[B.ident.id].paired)
    assert pa.devices[B.ident.id].trusted is False
    assert pb.devices[A.ident.id].paired and pb.devices[A.ident.id].trusted
    assert ka.pair is None and kb.pair is None
    await stop(ka, kb, pa, pb)


async def test_request_declined_and_cancelled(make_app):
    A, B, pa, pb, ka, kb = await setup(make_app)
    await ka.api_request(B.ident.id)
    await wait_for(lambda: kb.pair is not None)
    await kb.api_respond(A.ident.id, False)
    await wait_for(lambda: ka.pair is None)
    assert not pa.devices[B.ident.id].paired and not pb.devices[A.ident.id].paired
    await ka.api_request(B.ident.id)
    await wait_for(lambda: kb.pair is not None)
    await ka.api_respond(B.ident.id, False)          # initiator cancels
    await wait_for(lambda: kb.pair is None)
    assert not pb.devices[A.ident.id].paired
    await stop(ka, kb, pa, pb)


async def test_pair_by_code(make_app):
    A, B, pa, pb, ka, kb = await setup(make_app)
    old = kb.code
    await ka.api_code("000000" if kb.code != "000000" else "111111")
    assert not pa.devices[B.ident.id].paired
    await ka.api_code(kb.code[:3] + " " + kb.code[3:])
    assert pa.devices[B.ident.id].paired and pb.devices[A.ident.id].paired
    assert kb.code != old            # one-time: rotated after use
    await stop(ka, kb, pa, pb)


async def test_code_attempts_are_limited(make_app):
    A, B, pa, pb, ka, kb = await setup(make_app)
    for _ in range(6):
        await ka.api_code("999999" if kb.code != "999999" else "888888")
    await ka.api_code(kb.code)
    assert not pa.devices[B.ident.id].paired
    await stop(ka, kb, pa, pb)


def test_qr_roundtrip_through_opencv():
    import cv2
    text = qr_payload("192.168.1.5", 53317, "ab" * 16, "123456")
    assert parse_qr(text) == {"ip": "192.168.1.5", "port": 53317, "id": "ab" * 16, "code": "123456"}
    img = cv2.QRCodeEncoder.create().encode(text)
    img = cv2.resize(img, None, fx=8, fy=8, interpolation=cv2.INTER_NEAREST)
    img = cv2.copyMakeBorder(img, 40, 40, 40, 40, cv2.BORDER_CONSTANT, value=255)
    decoded, _, _ = cv2.QRCodeDetector().detectAndDecode(np.dstack([img] * 3))
    assert parse_qr(decoded)["code"] == "123456"
    assert parse_qr("https://example.com") is None
