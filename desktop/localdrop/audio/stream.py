"""Real-time audio over UDP with synchronized playback on several receivers.

Timeline: the sender emits one 10 ms frame every 10 ms of its own monotonic
clock. Sample index ``i`` therefore has the sender time ``t0 + i / 48000``.
Receivers keep an estimate of the sender clock (NTP-like ping over the same
UDP socket, minimum-RTT filtered) and play sample ``i`` at sender time
``t0 + i / 48000 + delay``. Every receiver aims at the same moment, which keeps
rooms in sync; small clock drifts are absorbed by resampling by a tiny ratio.

Datagram: ``[u8 type][u64 seq][AES-GCM ciphertext]`` (nonce = salt + seq).

* type 1, sender -> receiver: ``[u64 sample index][u64 t0 ns][u8 codec][payload]``
* type 2, receiver -> sender: ``[u64 receiver time ns]``             (sync request)
* type 3, sender -> receiver: ``[u64 receiver time][u64 sender time]`` (sync reply)
"""
from __future__ import annotations

import logging
import os
import socket
import struct
import threading
import time
from collections import deque

import numpy as np
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .codec import FRAME, QUALITY, RATE, Decoder, Encoder, Resampler

log = logging.getLogger(__name__)

T_AUDIO, T_SYNC_REQ, T_SYNC_RESP = 1, 2, 3
CODECS = {"opus": 1, "pcm": 2}
CODEC_NAMES = {v: k for k, v in CODECS.items()}
NS = 1_000_000_000
_HDR = struct.Struct(">BQ")
_AUDIO = struct.Struct(">QQB")


def now_ns() -> int:
    return time.perf_counter_ns()


class Channel:
    """One encrypted direction of a datagram flow."""

    def __init__(self, key: bytes, salt: bytes):
        self._aead = AESGCM(key)
        self._salt = salt
        self._seq = 0
        self._lock = threading.Lock()      # a nonce must never repeat, even across threads

    def seal(self, ptype: int, plaintext: bytes) -> bytes:
        with self._lock:
            self._seq += 1
            seq = self._seq
        nonce = self._salt + seq.to_bytes(8, "big")
        return _HDR.pack(ptype, seq) + self._aead.encrypt(nonce, plaintext, bytes([ptype]))

    def open(self, data: bytes) -> tuple[int, bytes] | None:
        if len(data) < 9 + 16:
            return None
        ptype, seq = _HDR.unpack_from(data)
        try:
            return ptype, self._aead.decrypt(self._salt + seq.to_bytes(8, "big"), data[9:], bytes([ptype]))
        except Exception:
            return None


def new_keys() -> dict:
    return {"k_audio": os.urandom(32).hex(), "s_audio": os.urandom(4).hex(),
            "k_sync": os.urandom(32).hex(), "s_sync": os.urandom(4).hex()}


# ---- sender -------------------------------------------------------------------
class Target:
    def __init__(self, dev: str, addr: tuple[str, int], keys: dict):
        self.dev = dev
        self.addr = addr
        self.tx = Channel(bytes.fromhex(keys["k_audio"]), bytes.fromhex(keys["s_audio"]))
        self.rx = Channel(bytes.fromhex(keys["k_sync"]), bytes.fromhex(keys["s_sync"]))
        self.started = time.time()


class Sender:
    """Captures audio, encodes it and sends it to every target on a fixed clock."""

    PRELOAD = 2          # frames of capture backlog to build before playing out
    MAX_BACKLOG = 6      # frames; trimmed back to PRELOAD when exceeded

    def __init__(self, quality: str = "balance"):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("0.0.0.0", 0))
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1 << 20)
        self.port = self.sock.getsockname()[1]
        self.targets: dict[str, Target] = {}
        self._lock = threading.Lock()
        self._resampler = Resampler()
        self._queue: deque[np.ndarray] = deque()
        self._qlock = threading.Lock()
        self._stop = threading.Event()
        self.t0 = 0
        self.index = 0
        self.level = 0.0
        self.set_quality(quality)
        self._threads = [threading.Thread(target=self._run, name="audio-send", daemon=True),
                         threading.Thread(target=self._serve_sync, name="audio-sync", daemon=True)]

    def set_quality(self, quality: str) -> None:
        codec, bitrate = QUALITY.get(quality, QUALITY["balance"])
        self._encoder = Encoder(codec, bitrate)
        self.codec = codec

    def start(self) -> None:
        for t in self._threads:
            t.start()

    def stop(self) -> None:
        self._stop.set()
        try:
            self.sock.close()
        except OSError:
            pass

    def add_target(self, dev: str, addr: tuple[str, int], keys: dict) -> None:
        with self._lock:
            self.targets[dev] = Target(dev, addr, keys)

    def remove_target(self, dev: str) -> Target | None:
        with self._lock:
            return self.targets.pop(dev, None)

    def on_audio(self, pcm: np.ndarray, rate: int) -> None:
        """Capture callback (any thread)."""
        frames = self._resampler.push(pcm, rate)
        if not frames:
            return
        with self._qlock:
            self._queue.extend(frames)

    def _next_frame(self) -> np.ndarray:
        with self._qlock:
            if len(self._queue) > self.MAX_BACKLOG:
                for _ in range(len(self._queue) - self.PRELOAD):
                    self._queue.popleft()
            if self._queue:
                return self._queue.popleft()
        return np.zeros((FRAME, 2), dtype=np.float32)

    def _run(self) -> None:
        period = FRAME * NS // RATE
        # wait for some backlog so capture jitter does not cause gaps
        deadline = time.monotonic() + 0.2
        while len(self._queue) < self.PRELOAD and time.monotonic() < deadline and not self._stop.is_set():
            time.sleep(0.002)
        self.t0 = now_ns()
        self.index = 0
        while not self._stop.is_set():
            due = self.t0 + self.index * NS // RATE
            delay = due - now_ns()
            if delay > 0:
                time.sleep(delay / NS)
            frame = self._next_frame()
            self.level = float(np.abs(frame).max()) if len(frame) else 0.0
            self._send(frame)
            self.index += FRAME
            behind = now_ns() - (self.t0 + self.index * NS // RATE)
            if behind > 20 * period:            # stalled (sleep, debugger): skip ahead
                skip = (behind // period) * FRAME
                self.index += skip

    def _send(self, frame: np.ndarray) -> None:
        codec_id = CODECS[self.codec]
        if self.codec == "pcm":                   # two 5 ms packets: no IP fragmentation
            half = FRAME // 2
            parts = [(self.index, self._encoder.encode(frame[:half])),
                     (self.index + half, self._encoder.encode(frame[half:]))]
        else:
            parts = [(self.index, self._encoder.encode(frame))]
        with self._lock:
            targets = list(self.targets.values())
        for idx, payload in parts:
            if not payload:
                continue
            plain = _AUDIO.pack(idx, self.t0, codec_id) + payload
            for t in targets:
                try:
                    self.sock.sendto(t.tx.seal(T_AUDIO, plain), t.addr)
                except OSError:
                    pass

    def _serve_sync(self) -> None:
        while not self._stop.is_set():
            try:
                data, addr = self.sock.recvfrom(2048)
            except OSError:
                if self._stop.is_set():
                    return
                continue
            t2 = now_ns()
            with self._lock:
                targets = [t for t in self.targets.values() if t.addr[0] == addr[0]]
            for t in targets:
                got = t.rx.open(data)
                if got and got[0] == T_SYNC_REQ and len(got[1]) == 8:
                    t1 = struct.unpack(">Q", got[1])[0]
                    reply = t.tx.seal(T_SYNC_RESP, struct.pack(">QQ", t1, t2))
                    try:
                        self.sock.sendto(reply, (addr[0], addr[1]))
                    except OSError:
                        pass
                    break


# ---- receiver -----------------------------------------------------------------
class ClockSync:
    """Estimate of ``sender_time - local_time`` from ping replies."""

    def __init__(self):
        self.samples: deque[tuple[int, int]] = deque(maxlen=24)   # (rtt, offset)
        self.offset: int | None = None
        self.rtt = 0

    def add(self, t1: int, t2: int, t3: int) -> None:
        rtt = t3 - t1
        if rtt < 0 or rtt > NS:
            return
        self.samples.append((rtt, t2 - (t1 + t3) // 2))
        best = sorted(self.samples)[: max(1, len(self.samples) // 4)]
        est = sum(o for _, o in best) // len(best)
        self.rtt = best[0][0]
        self.offset = est          # the min-RTT filter is the smoothing; playback absorbs small steps


class JitterBuffer:
    """Samples by absolute index in a ring; missing samples read as silence."""

    def __init__(self, seconds: float = 4.0):
        self.n = int(RATE * seconds)
        self.data = np.zeros((self.n, 2), dtype=np.float32)
        self.stamp = np.full(self.n, -1, dtype=np.int64)
        self.latest = -1
        self.lock = threading.Lock()

    def write(self, idx: int, pcm: np.ndarray) -> None:
        k = len(pcm)
        if k == 0:
            return
        pos = (idx + np.arange(k)) % self.n
        with self.lock:
            self.data[pos] = pcm
            self.stamp[pos] = idx + np.arange(k)
            self.latest = max(self.latest, idx + k)

    def read(self, start: float, count: int, ratio: float) -> np.ndarray:
        x = start + np.arange(count) * ratio
        i0 = np.floor(x).astype(np.int64)
        frac = (x - i0).astype(np.float32)[:, None]
        with self.lock:
            a_ok = self.stamp[i0 % self.n] == i0
            b_ok = self.stamp[(i0 + 1) % self.n] == i0 + 1
            a = np.where(a_ok[:, None], self.data[i0 % self.n], 0)
            b = np.where(b_ok[:, None], self.data[(i0 + 1) % self.n], a)
        return (a * (1 - frac) + b * frac).astype(np.float32)


class Receiver:
    """Decrypts, decodes and plays one incoming stream."""

    def __init__(self, keys: dict, sender_addr: tuple[str, int], delay_ms: int, volume: int):
        self.rx = Channel(bytes.fromhex(keys["k_audio"]), bytes.fromhex(keys["s_audio"]))
        self.tx = Channel(bytes.fromhex(keys["k_sync"]), bytes.fromhex(keys["s_sync"]))
        self.sender_addr = sender_addr
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("0.0.0.0", 0))
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
        self.port = self.sock.getsockname()[1]
        self.clock = ClockSync()
        self.buf = JitterBuffer()
        self.delay_ns = delay_ms * 1_000_000
        self.volume = volume
        self._decoders: dict[int, Decoder] = {}
        self.t0: int | None = None
        self.pos: float | None = None
        self.ratio = 1.0
        self.last_packet = 0.0
        self.packets = 0
        self.lost = 0
        self._stop = threading.Event()
        self._threads = [threading.Thread(target=self._recv, name="audio-recv", daemon=True),
                         threading.Thread(target=self._ping, name="audio-ping", daemon=True)]

    def start(self) -> None:
        for t in self._threads:
            t.start()

    def stop(self) -> None:
        self._stop.set()
        try:
            self.sock.close()
        except OSError:
            pass

    def _ping(self) -> None:
        n = 0
        while not self._stop.is_set():
            try:
                self.sock.sendto(self.tx.seal(T_SYNC_REQ, struct.pack(">Q", now_ns())), self.sender_addr)
            except OSError:
                pass
            n += 1
            self._stop.wait(0.05 if n < 20 else 0.5)

    def _recv(self) -> None:
        while not self._stop.is_set():
            try:
                data, addr = self.sock.recvfrom(4096)
            except OSError:
                if self._stop.is_set():
                    return
                continue
            got = self.rx.open(data)
            if not got:
                continue
            ptype, plain = got
            if ptype == T_SYNC_RESP and len(plain) == 16:
                t1, t2 = struct.unpack(">QQ", plain)
                self.clock.add(t1, t2, now_ns())
            elif ptype == T_AUDIO and len(plain) > _AUDIO.size:
                idx, t0, codec_id = _AUDIO.unpack_from(plain)
                dec = self._decoders.get(codec_id)
                if dec is None:
                    if codec_id not in CODEC_NAMES:
                        continue
                    dec = self._decoders[codec_id] = Decoder(CODEC_NAMES[codec_id])
                pcm = dec.decode(plain[_AUDIO.size:])
                if pcm is None:
                    continue
                if self.t0 != t0:                # sender (re)started its timeline
                    self.t0 = t0
                    self.pos = None
                self.buf.write(idx, pcm)
                self.packets += 1
                self.last_packet = time.monotonic()

    @property
    def ready(self) -> bool:
        return self.t0 is not None and self.clock.offset is not None

    def target_index(self, play_local_ns: int) -> float:
        """Sample index that should be heard at local time ``play_local_ns``."""
        sender_time = play_local_ns + self.clock.offset - self.delay_ns
        return (sender_time - self.t0) * RATE / NS

    def render(self, count: int, play_local_ns: int) -> np.ndarray:
        """``count`` stereo samples that start playing at ``play_local_ns``."""
        if not self.ready:
            return np.zeros((count, 2), dtype=np.float32)
        target = self.target_index(play_local_ns)
        if self.pos is None or abs(target - self.pos) > RATE * 0.08:
            self.pos = target                     # (re)lock: jump
            self.ratio = 1.0
        else:
            err = target - self.pos               # positive: we are late
            self.ratio = 1.0 + max(-0.003, min(0.003, err / (RATE * 0.5)))
        out = self.buf.read(self.pos, count, self.ratio)
        self.pos += count * self.ratio
        g = (max(0, min(100, self.volume)) / 100.0) ** 1.6
        return out * g

    @property
    def latency_ms(self) -> int:
        return int(self.delay_ns / 1_000_000)


class Player:
    """Plays a Receiver through sounddevice on a chosen output."""

    def __init__(self, receiver: Receiver, device: str | None):
        import sounddevice as sd
        self.sd = sd
        self.receiver = receiver
        self.device = device
        self.stream = None

    def start(self) -> None:
        sd = self.sd
        dev = output_index(self.device)
        extra = None
        if os.name == "nt":
            try:
                extra = sd.WasapiSettings(auto_convert=True)
            except Exception:
                extra = None

        def cb(outdata, frames, time_info, status):
            now = now_ns()
            dac = time_info.outputBufferDacTime - time_info.currentTime
            if not 0 < dac < 1:
                dac = self.stream.latency if self.stream else 0.02
            outdata[:] = self.receiver.render(frames, now + int(dac * NS))

        try:
            self.stream = sd.OutputStream(samplerate=RATE, channels=2, dtype="float32", blocksize=240,
                                          latency="low", device=dev, extra_settings=extra, callback=cb)
        except Exception:
            self.stream = sd.OutputStream(samplerate=RATE, channels=2, dtype="float32", blocksize=240,
                                          latency="low", device=dev, callback=cb)
        self.stream.start()

    def stop(self) -> None:
        if self.stream:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:
                pass
            self.stream = None


def _output_devices() -> list[dict]:
    import sounddevice as sd
    devs = sd.query_devices()
    apis = sd.query_hostapis()
    want = None
    for i, a in enumerate(apis):
        if os.name == "nt" and "WASAPI" in a["name"]:
            want = i
    out = []
    for i, d in enumerate(devs):
        if d["max_output_channels"] > 0 and (want is None or d["hostapi"] == want):
            out.append({"index": i, "name": d["name"]})
    return out


def list_outputs() -> list[str]:
    try:
        return [d["name"] for d in _output_devices()]
    except Exception:
        log.exception("listing outputs failed")
        return []


def output_index(name: str | None) -> int | None:
    if not name:
        if os.name == "nt":
            import sounddevice as sd
            for i, a in enumerate(sd.query_hostapis()):
                if "WASAPI" in a["name"] and a["default_output_device"] >= 0:
                    return a["default_output_device"]
        return None
    for d in _output_devices():
        if d["name"] == name:
            return d["index"]
    return None
