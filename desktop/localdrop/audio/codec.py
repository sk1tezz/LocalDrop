"""Opus (PyAV/libopus) and raw PCM for 10 ms stereo frames at 48 kHz."""
from __future__ import annotations

import av
import numpy as np

RATE = 48000
CHANNELS = 2
FRAME = 480                       # samples per channel in 10 ms

QUALITY = {                       # quality preset -> (codec, bitrate)
    "low": ("opus", 96_000),
    "balance": ("opus", 192_000),
    "hq": ("pcm", RATE * CHANNELS * 16),
}


class Encoder:
    def __init__(self, codec: str, bitrate: int):
        self.codec = codec
        self.bitrate = bitrate
        self._ctx = None
        self._pts = 0
        if codec == "opus":
            ctx = av.CodecContext.create("libopus", "w")
            ctx.sample_rate = RATE
            ctx.layout = "stereo"
            ctx.format = "s16"
            ctx.bit_rate = bitrate
            ctx.options = {"frame_duration": "10", "application": "lowdelay", "packet_loss": "5", "fec": "1"}
            ctx.open()
            self._ctx = ctx

    def encode(self, frame: np.ndarray) -> bytes:
        """``frame``: float32 (480, 2) in [-1, 1]."""
        pcm = np.clip(frame * 32767.0, -32768, 32767).astype("<i2")
        if self._ctx is None:
            return pcm.tobytes()
        f = av.AudioFrame.from_ndarray(pcm.reshape(1, -1), format="s16", layout="stereo")
        f.sample_rate = RATE
        f.pts = self._pts
        self._pts += FRAME
        packets = self._ctx.encode(f)
        return b"".join(bytes(p) for p in packets)


class Decoder:
    def __init__(self, codec: str):
        self.codec = codec
        self._ctx = None
        if codec == "opus":
            ctx = av.CodecContext.create("libopus", "r")
            ctx.sample_rate = RATE
            ctx.layout = "stereo"
            self._ctx = ctx

    def decode(self, payload: bytes) -> np.ndarray | None:
        """float32 (n, 2) or None."""
        if self._ctx is None:
            if len(payload) % 4:
                return None
            return np.frombuffer(payload, dtype="<i2").astype(np.float32).reshape(-1, 2) / 32768.0
        try:
            frames = self._ctx.decode(av.Packet(payload))
        except av.error.FFmpegError:
            return None
        out = []
        for fr in frames:
            arr = fr.to_ndarray()
            if fr.format.is_planar:
                arr = arr.T
            else:
                arr = arr.reshape(-1, 2)
            if arr.dtype != np.float32:
                arr = arr.astype(np.float32) / (32768.0 if arr.dtype == np.int16 else 1.0)
            out.append(arr)
        return np.concatenate(out) if out else None


def downmix(pcm: np.ndarray) -> np.ndarray:
    """WAVE channel order (FL FR FC LFE BL BR SL SR) -> stereo; centre and surrounds at -3 dB, LFE dropped."""
    n = pcm.shape[1]
    left = pcm[:, 0].copy()
    right = pcm[:, 1].copy()
    if n >= 3:
        left += 0.7071 * pcm[:, 2]
        right += 0.7071 * pcm[:, 2]
    for li, ri in ((4, 5), (6, 7)):
        if n > ri:
            left += 0.7071 * pcm[:, li]
            right += 0.7071 * pcm[:, ri]
    return np.clip(np.stack((left, right), axis=1), -1.0, 1.0)


class Resampler:
    """Any rate / channel count -> 48 kHz stereo float32, chopped into 10 ms frames."""

    def __init__(self):
        self._rs = None
        self._key = None
        self._buf = np.zeros((0, 2), dtype=np.float32)

    def push(self, pcm: np.ndarray, rate: int) -> list[np.ndarray]:
        if pcm.ndim == 1:
            pcm = pcm.reshape(-1, 1)
        if pcm.shape[1] == 1:
            pcm = np.repeat(pcm, 2, axis=1)
        elif pcm.shape[1] > 2:
            pcm = downmix(pcm)
        pcm = np.ascontiguousarray(pcm, dtype=np.float32)
        if rate != RATE:
            if self._key != rate:
                self._rs = av.AudioResampler(format="flt", layout="stereo", rate=RATE)
                self._key = rate
            f = av.AudioFrame.from_ndarray(pcm.reshape(1, -1), format="flt", layout="stereo")
            f.sample_rate = rate
            outs = [o.to_ndarray().reshape(-1, 2) for o in self._rs.resample(f)]
            pcm = np.concatenate(outs) if outs else np.zeros((0, 2), dtype=np.float32)
        self._buf = np.concatenate([self._buf, pcm]) if len(self._buf) else pcm
        n = len(self._buf) // FRAME
        frames = [self._buf[i * FRAME:(i + 1) * FRAME] for i in range(n)]
        self._buf = self._buf[n * FRAME:]
        return frames
