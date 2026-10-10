import asyncio
import socket

import pytest

from localdrop import config
from localdrop.config import Settings
from localdrop.net.identity import Identity
from localdrop.storage import Storage


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class FakeApp:
    """Just enough of App for services to run in tests."""

    def __init__(self, tmp, name):
        tmp.mkdir(parents=True, exist_ok=True)
        self.ddir = tmp
        self.settings = Settings(tmp / "settings.json")
        self.settings.update({"device_name": name, "save_dir": str(tmp / "inbox")})
        self.storage = Storage(tmp / "db.sqlite")
        self.port = free_port()
        self.ip = "127.0.0.1"
        self.ident = Identity.generate()
        self.published = set()
        self.toasts = []
        self.events = []
        self.me_extra = {}

    def me(self):
        return {"name": self.settings.get("device_name"), "os": "Test OS", "kind": config.os_kind(),
                "icon": "computer", "ip": self.ip, "port": self.port}

    def publish(self, *names):
        self.published.update(names)

    def toast(self, kind, title, text="", args=(), targs=(), system=True):
        self.toasts.append((kind, title, text, args))

    def emit(self, name, data):
        self.events.append((name, data))

    def tr(self, s, *args):
        return s

    def spawn(self, coro):
        return asyncio.ensure_future(coro)

    # registry used by services
    window = None

    def add_slice(self, name, fn):
        self.__dict__.setdefault("slices", {})[name] = fn

    def expose(self, name, fn):
        self.__dict__.setdefault("api", {})[name] = fn

    def call_soon(self, fn, *a):
        asyncio.get_running_loop().call_soon(fn, *a)


@pytest.fixture
def make_app(tmp_path):
    def make(name):
        return FakeApp(tmp_path / name, name)
    return make
