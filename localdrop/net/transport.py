"""Authenticated, encrypted connections over TCP.

Handshake (SIGMA-style, like TLS 1.3 with raw public keys)::

    C -> S  M1 = "LDv1" | eC (X25519, 32) | nC (16)
    S -> C  M2 = eS (32) | nS (16) | pubS (Ed25519, 32) | sigS (64)
            sigS = Sign_S("LD-srv" | H(M1 | eS | nS | pubS))
    C -> S  M3 = pubC (32) | sigC (64)
            sigC = Sign_C("LD-cli" | H(M1 | M2 | pubC))

Session keys = HKDF(X25519(eC, eS), salt = H(M1|M2|M3)). Both peers prove
ownership of their long-term keys over a transcript bound to fresh ephemeral
keys, so a man in the middle cannot complete the handshake without one of the
private keys. The short authentication string (6 digits) is derived from the
same transcript and is used to confirm a first pairing by eye. Records are
encrypted with AES-256-GCM (hardware accelerated on x86 and Apple Silicon).
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import os
import socket

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from .framing import MAX_RECORD, Cipher, FrameError, pack_msg, read_u32, u32, unpack_msg
from .identity import Identity, id_from_pub, verify

log = logging.getLogger(__name__)

MAGIC = b"LDv1"
HANDSHAKE_TIMEOUT = 8


class HandshakeError(Exception):
    pass


def _h(*parts: bytes) -> bytes:
    d = hashlib.sha256()
    for p in parts:
        d.update(p)
    return d.digest()


def _hkdf(secret: bytes, salt: bytes, info: bytes, n: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=n, salt=salt, info=info).derive(secret)


def _raw(pub: X25519PublicKey) -> bytes:
    return pub.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


class Conn:
    """An established encrypted connection to an authenticated peer."""

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, *,
                 send_key: bytes, recv_key: bytes, peer_pub: bytes, sas: str, binding: bytes, initiator: bool):
        self.reader = reader
        self.writer = writer
        self._tx = Cipher(send_key)
        self._rx = Cipher(recv_key)
        self._wlock = asyncio.Lock()
        self.peer_pub = peer_pub
        self.peer_id = id_from_pub(peer_pub)
        self.sas = sas
        self.binding = binding          # secret per-session value for proofs (pairing by code)
        self.initiator = initiator
        self.hello: dict = {}           # peer's hello message
        self.closed = False
        peer = writer.get_extra_info("peername") or ("", 0)
        self.peer_ip = peer[0]

    async def send(self, header: dict, body: bytes = b"") -> None:
        data = pack_msg(header, body)
        async with self._wlock:
            ct = self._tx.seal(data)
            self.writer.write(u32(len(ct)) + ct)
            await self.writer.drain()

    async def recv(self) -> tuple[dict, bytes]:
        n = read_u32(await self.reader.readexactly(4))
        if n > MAX_RECORD + 64:
            raise FrameError("record too large")
        ct = await self.reader.readexactly(n)
        return unpack_msg(self._rx.open(ct))

    def proof(self, secret: str) -> str:
        """HMAC of a shared secret bound to this session (cannot be replayed elsewhere)."""
        return hmac.new(secret.encode(), b"LD-proof" + self.binding, hashlib.sha256).hexdigest()

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.writer.close()
        except Exception:
            pass

    async def wait_closed(self) -> None:
        self.close()
        try:
            await self.writer.wait_closed()
        except Exception:
            pass


def _derive(shared: bytes, transcript: bytes, initiator: bool) -> tuple[bytes, bytes, str, bytes]:
    keys = _hkdf(shared, transcript, b"LD keys", 96)
    c2s, s2c, binding = keys[:32], keys[32:64], keys[64:]
    sas_raw = _hkdf(shared, transcript, b"LD sas", 4)
    sas = "%06d" % (int.from_bytes(sas_raw, "big") % 1_000_000)
    return (c2s, s2c, sas, binding) if initiator else (s2c, c2s, sas, binding)


def _tune(writer: asyncio.StreamWriter) -> None:
    # let several records queue up before drain() blocks, so the pipe stays full
    writer.transport.set_write_buffer_limits(high=8 * 1024 * 1024, low=2 * 1024 * 1024)
    sock = writer.get_extra_info("socket")
    if sock is None:
        return
    try:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 * 1024 * 1024)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    except OSError:
        pass


async def client_handshake(reader, writer, ident: Identity, expected_id: str | None = None) -> Conn:
    e = X25519PrivateKey.generate()
    m1 = MAGIC + _raw(e.public_key()) + os.urandom(16)
    writer.write(m1)
    await writer.drain()
    m2 = await reader.readexactly(32 + 16 + 32 + 64)
    e_s, n_s, pub_s, sig_s = m2[:32], m2[32:48], m2[48:80], m2[80:]
    if not verify(pub_s, sig_s, b"LD-srv" + _h(m1, e_s, n_s, pub_s)):
        raise HandshakeError("bad server signature")
    if expected_id and id_from_pub(pub_s) != expected_id:
        raise HandshakeError("unexpected device identity")
    sig_c = ident.sign(b"LD-cli" + _h(m1, m2, ident.pub))
    m3 = ident.pub + sig_c
    writer.write(m3)
    await writer.drain()
    shared = e.exchange(X25519PublicKey.from_public_bytes(e_s))
    tx, rx, sas, binding = _derive(shared, _h(m1, m2, m3), True)
    return Conn(reader, writer, send_key=tx, recv_key=rx, peer_pub=pub_s, sas=sas, binding=binding, initiator=True)


async def server_handshake(reader, writer, ident: Identity) -> Conn:
    m1 = await reader.readexactly(4 + 32 + 16)
    if m1[:4] != MAGIC:
        raise HandshakeError("not a LocalDrop peer")
    e = X25519PrivateKey.generate()
    e_s, n_s = _raw(e.public_key()), os.urandom(16)
    sig_s = ident.sign(b"LD-srv" + _h(m1, e_s, n_s, ident.pub))
    m2 = e_s + n_s + ident.pub + sig_s
    writer.write(m2)
    await writer.drain()
    m3 = await reader.readexactly(32 + 64)
    pub_c, sig_c = m3[:32], m3[32:]
    if not verify(pub_c, sig_c, b"LD-cli" + _h(m1, m2, pub_c)):
        raise HandshakeError("bad client signature")
    shared = e.exchange(X25519PublicKey.from_public_bytes(m1[4:36]))
    tx, rx, sas, binding = _derive(shared, _h(m1, m2, m3), False)
    return Conn(reader, writer, send_key=tx, recv_key=rx, peer_pub=pub_c, sas=sas, binding=binding, initiator=False)


async def dial(host: str, port: int, ident: Identity, expected_id: str | None = None,
               timeout: float = HANDSHAKE_TIMEOUT) -> Conn:
    reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port, limit=MAX_RECORD), timeout)
    _tune(writer)
    try:
        return await asyncio.wait_for(client_handshake(reader, writer, ident, expected_id), timeout)
    except BaseException:
        writer.close()
        raise


async def serve(host: str, port: int, ident: Identity, on_conn) -> asyncio.base_events.Server:
    """Accept connections; ``on_conn(conn)`` runs for each authenticated peer."""

    async def handle(reader, writer):
        _tune(writer)
        try:
            conn = await asyncio.wait_for(server_handshake(reader, writer, ident), HANDSHAKE_TIMEOUT)
        except (HandshakeError, asyncio.IncompleteReadError, asyncio.TimeoutError, ConnectionError, OSError) as e:
            log.debug("handshake from %s failed: %s", writer.get_extra_info("peername"), e)
            writer.close()
            return
        try:
            await on_conn(conn)
        except (asyncio.IncompleteReadError, ConnectionError, FrameError) as e:
            log.debug("connection %s ended: %s", conn.peer_id[:8], e)
        except Exception:
            log.exception("connection handler failed")
        finally:
            conn.close()

    return await asyncio.start_server(handle, host, port, limit=MAX_RECORD, reuse_address=True)
