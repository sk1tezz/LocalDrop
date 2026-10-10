"""Message framing and record encryption.

A message is a JSON header plus an optional binary body::

    [u32 header length][header json][body]

On the wire every message is one encrypted record::

    [u32 ciphertext length][AES-256-GCM ciphertext]

Each direction has its own key; the nonce is a 64-bit record counter.
"""
from __future__ import annotations

import json
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_RECORD = 16 * 1024 * 1024
_U32 = struct.Struct(">I")


class FrameError(Exception):
    pass


def pack_msg(header: dict, body: bytes = b"") -> bytes:
    h = json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode()
    return _U32.pack(len(h)) + h + body


def unpack_msg(data: bytes) -> tuple[dict, bytes]:
    if len(data) < 4:
        raise FrameError("short message")
    (n,) = _U32.unpack_from(data)
    if n > len(data) - 4:
        raise FrameError("bad header length")
    try:
        header = json.loads(data[4:4 + n])
    except ValueError as e:
        raise FrameError("bad header") from e
    if not isinstance(header, dict):
        raise FrameError("header is not an object")
    return header, data[4 + n:]


class Cipher:
    """One direction of an encrypted channel."""

    def __init__(self, key: bytes):
        self._aead = AESGCM(key)
        self._n = 0

    def _nonce(self) -> bytes:
        nonce = b"\0\0\0\0" + self._n.to_bytes(8, "big")
        self._n += 1
        return nonce

    def seal(self, plaintext: bytes) -> bytes:
        return self._aead.encrypt(self._nonce(), plaintext, None)

    def open(self, ciphertext: bytes) -> bytes:
        try:
            return self._aead.decrypt(self._nonce(), ciphertext, None)
        except Exception as e:
            raise FrameError("decryption failed") from e


def u32(n: int) -> bytes:
    return _U32.pack(n)


def read_u32(b: bytes) -> int:
    return _U32.unpack(b)[0]
