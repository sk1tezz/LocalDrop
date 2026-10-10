import asyncio
import threading
import time

import numpy as np
import pytest

from localdrop.audio import service as svc
from localdrop.audio import sources
from localdrop.audio.service import AudioService
from localdrop.net.peers import PeerManager


class FakeCapture:
    def __init__(self, on_audio):
        self.on_audio = on_audio
        self.stop_ev = threading.Event()

    def start(self):
        def run():
            n = 0
            while not self.stop_ev.is_set():
                t = (n + np.arange(480)) / 48000
                self.on_audio(np.stack([0.4 * np.sin(2 * np.pi * 330 * t)] * 2, 1).astype(np.float32), 48000)
                n += 480
                time.sleep(0.01)
        threading.Thread(target=run, daemon=True).start()

    def stop(self):
        self.stop_ev.set()


class FakePlayer:
    def __init__(self, receiver, device):
        self.receiver, self.device = receiver, device

    def start(self):
        pass

    def stop(self):
        pass


async def wait_for(cond, timeout=8):
    for _ in range(int(timeout / 0.02)):
        if cond():
            return
        await asyncio.sleep(0.02)
    raise AssertionError("condition not met")


@pytest.fixture
async def nodes(make_app, monkeypatch):
    mutes = []
    monkeypatch.setattr(sources, "open_capture", lambda src, cb: FakeCapture(cb))
    monkeypatch.setattr(sources, "start_capture", lambda cap: cap.start())
    monkeypatch.setattr(sources, "list_sources", lambda: [{"id": "system", "label": "x", "group": "system"}])
    monkeypatch.setattr(sources, "set_output_mute", lambda m: mutes.append(m) or False)
    monkeypatch.setattr(svc, "Player", FakePlayer)
    monkeypatch.setattr(svc, "list_outputs", lambda: ["Speakers", "Headphones"])
    A, B = make_app("A"), make_app("B")
    pa, pb = PeerManager(A, A.ident), PeerManager(B, B.ident)
    sa, sb = AudioService(A, pa), AudioService(B, pb)
    for x in (pa, pb, sa, sb):
        await x.start()
    pa.seen(B.ident.id, {"name": "B", "ip": "127.0.0.1", "port": B.port})
    pb.seen(A.ident.id, {"name": "A", "ip": "127.0.0.1", "port": A.port})
    pa.set_paired(B.ident.id, trusted=True)
    pb.set_paired(A.ident.id, trusted=True)
    await wait_for(lambda: pa.connected(B.ident.id))
    A.settings.update({"audio": {"targets": [{"dev": B.ident.id, "on": True, "vol": 70, "out": ""}]}})
    yield A, B, pa, pb, sa, sb, mutes
    for x in (sa, sb, pa, pb):
        await x.stop()


async def test_stream_update_and_receiver_disconnect(nodes):
    A, B, pa, pb, sa, sb, mutes = nodes
    await sa.api_toggle()
    assert sa.on and B.ident.id in sa.sessions
    assert mutes == [True]                                   # local output muted while streaming
    await wait_for(lambda: sb.incoming is not None and sb.incoming["receiver"].ready)
    rec = sb.incoming["receiver"]
    await wait_for(lambda: rec.packets > 20)
    out = rec.render(480, time.perf_counter_ns() + 10_000_000)
    assert np.sqrt((out ** 2).mean()) > 0.05
    # volume / output changes reach the receiver
    A.settings.update({"audio": {"targets": [{"dev": B.ident.id, "on": True, "vol": 30, "out": "Headphones"}]}})
    await wait_for(lambda: sb.incoming["vol"] == 30 and sb.incoming["out"] == "Headphones")
    assert sa.remote[B.ident.id]["outputs"] == ["Speakers", "Headphones"]
    # the receiver user presses "Disconnect"
    await sb.api_incoming_stop()
    await wait_for(lambda: B.ident.id not in sa.sessions)
    assert A.settings.get("audio")["targets"][0]["on"] is False
    await sa.api_toggle()
    assert not sa.on and mutes == [True, False]


async def test_untrusted_receiver_refuses(nodes):
    A, B, pa, pb, sa, sb, mutes = nodes
    pb.set_trust(A.ident.id, False)
    await sa.api_toggle()
    assert sb.incoming is None
    assert "доверяет" in sa.remote[B.ident.id]["error"]
    await sa.api_toggle()


async def test_receiver_requests_and_releases_the_stream(nodes):
    """A phone presses Listen: it asks the computer to stream to it, then stops."""
    A, B, pa, pb, sa, sb, mutes = nodes
    A.settings.update({"audio": {"targets": []}})
    reply, _ = await pb.request(A.ident.id, {"type": "audio_request", "vol": 55})
    assert reply["ok"]
    assert sa.on and B.ident.id in sa.sessions
    assert A.settings.get("audio")["targets"] == [{"dev": B.ident.id, "on": True, "vol": 55, "out": ""}]
    await wait_for(lambda: sb.incoming is not None)
    reply, _ = await pb.request(A.ident.id, {"type": "audio_release"})
    assert reply["ok"]
    assert not sa.on and not sa.sessions
    await wait_for(lambda: sb.incoming is None)


async def test_request_from_untrusted_device_is_refused(nodes):
    A, B, pa, pb, sa, sb, mutes = nodes
    pa.set_trust(B.ident.id, False)
    reply, _ = await pb.request(A.ident.id, {"type": "audio_request"})
    assert reply == {"ok": False, "error": "not trusted", "type": "reply", "re": reply["re"]}
    assert not sa.on


async def test_release_keeps_streaming_the_user_started(nodes):
    A, B, pa, pb, sa, sb, mutes = nodes
    await sa.api_toggle()                         # the user streams to B already
    await pb.request(A.ident.id, {"type": "audio_request"})
    await pb.request(A.ident.id, {"type": "audio_release"})
    assert sa.on                                  # not switched off: the user turned it on
