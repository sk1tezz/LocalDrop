import threading
import time

import numpy as np
import pytest

from localdrop.audio.codec import RATE, Resampler, downmix
from localdrop.audio.stream import NS, ClockSync, JitterBuffer, Receiver, Sender, new_keys, now_ns


def tone_feeder(sender, stop, freq=440.0):
    """Feed the sender a continuous sine at 44.1 kHz (exercises the resampler)."""
    n = 0
    rate = 44100
    block = 441
    t_next = time.perf_counter()
    while not stop.is_set():
        t = (n + np.arange(block)) / rate
        sender.on_audio((0.5 * np.sin(2 * np.pi * freq * t)).astype(np.float32).reshape(-1, 1), rate)
        n += block
        t_next += block / rate
        time.sleep(max(0, t_next - time.perf_counter()))


@pytest.mark.parametrize("quality", ["balance", "hq"])
def test_two_receivers_play_in_sync(quality):
    sender = Sender(quality)
    receivers = []
    for vol in (100, 100):
        keys = new_keys()
        r = Receiver(keys, ("127.0.0.1", sender.port), delay_ms=80, volume=vol)
        sender.add_target(f"dev{len(receivers)}", ("127.0.0.1", r.port), keys)
        receivers.append(r)
    stop = threading.Event()
    feeder = threading.Thread(target=tone_feeder, args=(sender, stop), daemon=True)
    feeder.start()
    sender.start()
    for r in receivers:
        r.start()
    try:
        deadline = time.time() + 5
        while not all(r.ready for r in receivers) and time.time() < deadline:
            time.sleep(0.02)
        assert all(r.ready for r in receivers)
        time.sleep(0.6)
        # both receivers render the samples meant for the same instant
        play_at = now_ns() + 20_000_000
        outs = [r.render(480, play_at) for r in receivers]
        assert abs(receivers[0].target_index(play_at) - receivers[1].target_index(play_at)) < RATE * 0.001
        assert np.sqrt((outs[0] ** 2).mean()) > 0.2           # real signal, not silence
        a, b = outs[0][:, 0], outs[1][:, 0]
        lags = range(-48, 49)
        best = max(lags, key=lambda k: float(np.dot(a[48:-48], np.roll(b, k)[48:-48])))
        assert abs(best) <= 48                                   # within 1 ms of each other
        # a whole second rendered block by block stays continuous (no dropouts)
        chunks = []
        t = now_ns()
        for k in range(100):
            chunks.append(receivers[0].render(480, t + k * 10_000_000))
            time.sleep(0.01)
        sig = np.concatenate(chunks)[:, 0]
        assert np.sqrt((sig ** 2).mean()) > 0.3
        assert (np.abs(sig) < 1e-4).sum() < 200
    finally:
        stop.set()
        sender.stop()
        for r in receivers:
            r.stop()


def test_clock_sync_prefers_low_rtt():
    c = ClockSync()
    offset = 5_000_000_000
    for i, rtt in enumerate([9e6, 1e6, 7e6, 2e6, 30e6]):
        t1 = i * NS
        t3 = t1 + int(rtt)
        skew = int(rtt * 0.4)                  # asymmetric delay grows with rtt
        c.add(t1, t1 + offset + int(rtt / 2) + skew, t3)
    assert abs(c.offset - offset) < 1_500_000


def test_jitter_buffer_interpolates_and_fills_gaps():
    jb = JitterBuffer(1)
    jb.write(100, np.ones((480, 2), dtype=np.float32))
    out = jb.read(90, 20, 1.0)
    assert (out[:9] == 0).all() and np.allclose(out[10:], 1)
    assert np.allclose(jb.read(300.5, 4, 1.0), 1)


def test_surround_capture_is_downmixed_to_stereo():
    pcm = np.zeros((480, 8), dtype=np.float32)
    pcm[:, 2] = 0.5                       # centre only (dialogue in a 7.1 mix)
    st = downmix(pcm)
    assert st.shape == (480, 2) and np.allclose(st, 0.5 * 0.7071, atol=1e-4)
    pcm[:, 0] = pcm[:, 4] = pcm[:, 6] = 1.0
    assert downmix(pcm)[:, 0].max() <= 1.0
    frames = Resampler().push(np.full((RATE // 100, 6), 0.1, dtype=np.float32), RATE)
    assert len(frames) == 1 and frames[0].shape[1] == 2
