"""Long-term device identity: an Ed25519 key pair. Device id = sha256(public key)[:16]."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey


def id_from_pub(pub: bytes) -> str:
    return hashlib.sha256(pub).digest()[:16].hex()


def verify(pub: bytes, sig: bytes, data: bytes) -> bool:
    try:
        Ed25519PublicKey.from_public_bytes(pub).verify(sig, data)
        return True
    except (InvalidSignature, ValueError):
        return False


class Identity:
    def __init__(self, key: Ed25519PrivateKey):
        self._key = key
        self.pub = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        self.id = id_from_pub(self.pub)

    @classmethod
    def generate(cls) -> "Identity":
        return cls(Ed25519PrivateKey.generate())

    @classmethod
    def load_or_create(cls, path: Path) -> "Identity":
        if path.exists():
            key = serialization.load_pem_private_key(path.read_bytes(), password=None)
            if isinstance(key, Ed25519PrivateKey):
                return cls(key)
        ident = cls.generate()
        pem = ident._key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption())
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(pem)
        os.replace(tmp, path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
        return ident

    def sign(self, data: bytes) -> bytes:
        return self._key.sign(data)
