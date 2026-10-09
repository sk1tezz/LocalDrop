import asyncio
import hashlib
import os
from pathlib import Path

import pytest

from localdrop.files import transfer as tr
from localdrop.files.transfer import FileService, safe_rel
from localdrop.net.peers import PeerManager


async def wait_for(cond, timeout=15):
    for _ in range(int(timeout / 0.02)):
        if cond():
            return
        await asyncio.sleep(0.02)
    raise AssertionError("condition not met")


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


@pytest.fixture
async def pair(make_app):
    A, B = make_app("A"), make_app("B")
    pa, pb = PeerManager(A, A.ident), PeerManager(B, B.ident)
    fa, fb = FileService(A, pa), FileService(B, pb)
    for x in (pa, pb, fa, fb):
        await x.start()
    pa.seen(B.ident.id, {"name": "B", "ip": "127.0.0.1", "port": B.port})
    pb.seen(A.ident.id, {"name": "A", "ip": "127.0.0.1", "port": A.port})
    pa.set_paired(B.ident.id, trusted=True)
    pb.set_paired(A.ident.id, trusted=True)
    await wait_for(lambda: pa.connected(B.ident.id))
    yield A, B, pa, pb, fa, fb
    for x in (fa, fb, pa, pb):
        await x.stop()


async def test_send_file_and_folder(pair, tmp_path):
    A, B, pa, pb, fa, fb = pair
    src = tmp_path / "src"
    (src / "Album" / "sub").mkdir(parents=True)
    (src / "Album" / "empty").mkdir()
    (src / "Album" / "a.txt").write_text("hello")
    (src / "Album" / "sub" / "b.bin").write_bytes(os.urandom(300_000))
    (src / "doc.pdf").write_bytes(os.urandom(5_000))
    ts = await fa.send_paths(B.ident.id, [str(src / "doc.pdf"), str(src / "Album")])
    await wait_for(lambda: all(t.status == "done" for t in ts))
    inbox = Path(B.settings.get("save_dir"))
    assert sha(inbox / "doc.pdf") == sha(src / "doc.pdf")
    assert (inbox / "Album" / "a.txt").read_text() == "hello"
    assert sha(inbox / "Album" / "sub" / "b.bin") == sha(src / "Album" / "sub" / "b.bin")
    assert (inbox / "Album" / "empty").is_dir()
    await wait_for(lambda: all(t.status == "done" for t in fb.transfers.values()))
    assert {h["title"] for h in B.storage.history()} == {"doc.pdf", "Album"}
    # same name again -> renamed, never overwritten
    ts = await fa.send_paths(B.ident.id, [str(src / "doc.pdf")])
    await wait_for(lambda: ts[0].status == "done")
    assert (inbox / "doc (1).pdf").exists()


async def test_untrusted_needs_approval(pair, tmp_path):
    A, B, pa, pb, fa, fb = pair
    pb.set_trust(A.ident.id, False)
    f = tmp_path / "x.txt"
    f.write_text("x")
    (t,) = await fa.send_paths(B.ident.id, [str(f)])
    assert t.status == "waiting"
    (rt,) = fb.transfers.values()
    assert rt.status == "ask"
    await fb.api_offer_respond(rt.id, True)
    await wait_for(lambda: t.status == "done")
    (t2,) = await fa.send_paths(B.ident.id, [str(f)])
    rt2 = fb.transfers[t2.id]
    await fb.api_offer_respond(rt2.id, False)
    await wait_for(lambda: t2.status == "declined")


async def test_resume_after_broken_connection(pair, tmp_path, monkeypatch):
    A, B, pa, pb, fa, fb = pair
    monkeypatch.setattr(tr, "CHUNK", 64 * 1024)
    big = tmp_path / "big.bin"
    big.write_bytes(os.urandom(12 * 1024 * 1024))
    (t,) = await fa.send_paths(B.ident.id, [str(big)])
    await wait_for(lambda: t.status == "active" and t.done > 3 * 1024 * 1024)
    t.conn.close()                                   # network drop
    await wait_for(lambda: t.status in ("offline", "active") and t.done < t.size)
    rt = fb.transfers[t.id]
    await wait_for(lambda: t.status == "done", timeout=30)
    assert sha(Path(B.settings.get("save_dir")) / "big.bin") == sha(big)
    assert not (Path(B.settings.get("save_dir")) / ("big.bin" + tr.PART)).exists()
    assert rt.status == "done"


async def test_pause_resume_from_receiver_and_cancel(pair, tmp_path, monkeypatch):
    A, B, pa, pb, fa, fb = pair
    monkeypatch.setattr(tr, "CHUNK", 32 * 1024)
    big = tmp_path / "v.mp4"
    big.write_bytes(os.urandom(8 * 1024 * 1024))
    (t,) = await fa.send_paths(B.ident.id, [str(big)])
    await wait_for(lambda: t.status == "active" and t.done > 1024 * 1024)
    await fb.api_toggle(t.id)                         # receiver pauses
    await wait_for(lambda: t.status == "paused" and fb.transfers[t.id].status == "paused")
    paused_at = t.done
    await asyncio.sleep(0.3)
    assert t.status == "paused"
    await fb.api_toggle(t.id)                         # receiver resumes
    await wait_for(lambda: t.status == "done", timeout=30)
    assert sha(Path(B.settings.get("save_dir")) / "v.mp4") == sha(big)
    assert paused_at > 0
    (t2,) = await fa.send_paths(B.ident.id, [str(big)])
    await wait_for(lambda: t2.status == "active" and t2.done > 0)
    await fa.api_cancel(t2.id)
    await wait_for(lambda: fb.transfers[t2.id].status == "cancelled")
    inbox = Path(B.settings.get("save_dir"))
    await wait_for(lambda: not list(inbox.glob("*" + tr.PART)))      # deleted once the stream closes the file


def test_safe_rel():
    assert safe_rel("../../etc/passwd").as_posix() == "etc/passwd"
    assert safe_rel(r"C:\Windows\x").as_posix() == "C_/Windows/x"
    assert safe_rel("a/./b/../c").as_posix() == "a/b/c"
    with pytest.raises(tr.TransferError):
        safe_rel("../..")


async def test_progress_matches_receiver(pair, tmp_path, monkeypatch):
    A, B, pa, pb, fa, fb = pair
    monkeypatch.setattr(tr, "CHUNK", 256 * 1024)
    big = tmp_path / "p.bin"
    big.write_bytes(os.urandom(48 * 1024 * 1024))
    (t,) = await fa.send_paths(B.ident.id, [str(big)])
    worst = 0
    while t.status != "done":
        rt = fb.transfers.get(t.id)
        if rt is not None and t.status == "active":
            worst = max(worst, t.done - rt.done)          # sender may never run ahead of what arrived
        await asyncio.sleep(0.01)
    assert worst <= 0


async def test_stalled_receiver_is_detected_and_resumed(pair, tmp_path, monkeypatch):
    A, B, pa, pb, fa, fb = pair
    monkeypatch.setattr(tr, "CHUNK", 256 * 1024)
    monkeypatch.setattr(tr, "STALL", 2)
    monkeypatch.setattr(tr, "WINDOW", 2 * 1024 * 1024)
    big = tmp_path / "s.bin"
    big.write_bytes(os.urandom(24 * 1024 * 1024))
    real_write = tr._write_hash
    frozen = asyncio.Event()

    def slow_write(f, data, hasher):                     # the receiver's disk "hangs"
        if frozen.is_set():
            import time as _t
            _t.sleep(4)
        real_write(f, data, hasher)

    monkeypatch.setattr(tr, "_write_hash", slow_write)
    (t,) = await fa.send_paths(B.ident.id, [str(big)])
    await wait_for(lambda: t.status == "active" and t.done > 4 * 1024 * 1024)
    frozen.set()
    await wait_for(lambda: t.status == "offline", timeout=15)
    stuck = t.done
    assert stuck <= fb.transfers[t.id].done + tr.WINDOW
    frozen.clear()
    await wait_for(lambda: t.status == "done", timeout=40)
    assert sha(Path(B.settings.get("save_dir")) / "s.bin") == sha(big)
