"""Settings and data paths.

Settings live in ``settings.json`` inside the data directory. A ``--profile``
gives a separate data directory, which lets two instances run side by side on
one machine for testing.
"""
from __future__ import annotations

import copy
import json
import logging
import os
import platform
import socket
import sys
import threading
from pathlib import Path
from typing import Any

APP_NAME = "LocalDrop"
VERSION = "1.0.0"
DEFAULT_PORT = 53317

IS_WIN = sys.platform == "win32"
IS_MAC = sys.platform == "darwin"

log = logging.getLogger(__name__)


def data_root() -> Path:
    if IS_WIN:
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
    elif IS_MAC:
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / APP_NAME


def data_dir(profile: str | None = None) -> Path:
    d = data_root()
    if profile:
        d = d / "profiles" / profile
    d.mkdir(parents=True, exist_ok=True)
    return d


def default_save_dir() -> str:
    return str(Path.home() / "Downloads" / APP_NAME)


def default_device_name() -> str:
    name = socket.gethostname().split(".")[0]
    return name or ("Mac" if IS_MAC else "PC")


def os_label() -> str:
    if IS_WIN:
        build = sys.getwindowsversion().build
        return "Windows 11" if build >= 22000 else "Windows 10"
    if IS_MAC:
        return "macOS " + platform.mac_ver()[0]
    return platform.system()


def os_kind() -> str:
    return "macos" if IS_MAC else "windows" if IS_WIN else "linux"


def default_icon() -> str:
    """Material Symbols icon name for this machine."""
    if IS_MAC:
        return "laptop_mac" if "Book" in platform.node() else "desktop_mac"
    if IS_WIN:
        try:
            import psutil
            if psutil.sensors_battery() is not None:
                return "laptop_windows"
        except Exception:
            pass
        return "desktop_windows"
    return "computer"


DEFAULTS: dict[str, Any] = {
    "device_name": None,          # filled on first load
    "port": DEFAULT_PORT,
    "interface": "auto",          # "auto" or an IPv4 address
    "lang": "ru",
    "theme": "dark",              # dark | light | system
    "feature_colors": True,
    "onboarded": False,
    "features": {"files": True, "clip": True, "audio": True},
    "autostart": True,
    "tray": True,
    "visible": True,
    "auto_accept": True,
    "notify": True,
    "sounds": False,
    "save_dir": None,             # filled on first load
    "clip_auto": True,
    "clip_devices": [],           # device ids that receive the clipboard
    "clip_types": {"text": True, "image": True},
    "audio": {
        "source": "system",       # system | input:<id> | app:<pid>
        "mute_local": True,
        "quality": "balance",     # low | balance | hq
        "buffer": 60,             # ms
        "targets": [],            # [{dev, on, vol, out}]
    },
}


def _merge(defaults: dict, loaded: dict) -> dict:
    out = copy.deepcopy(defaults)
    for k, v in loaded.items():
        if k in out and isinstance(out[k], dict) and isinstance(v, dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


class Settings:
    """Thread-safe settings store backed by a JSON file."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self._data = copy.deepcopy(DEFAULTS)
        self._listeners: list = []
        self.load()

    def load(self) -> None:
        loaded = {}
        if self.path.exists():
            try:
                loaded = json.loads(self.path.read_text("utf-8"))
            except (OSError, ValueError):
                log.exception("settings file is broken, using defaults")
        with self._lock:
            self._data = _merge(DEFAULTS, loaded)
            if not self._data["device_name"]:
                self._data["device_name"] = default_device_name()
            if not self._data["save_dir"]:
                self._data["save_dir"] = default_save_dir()
        self.save()

    def save(self) -> None:
        with self._lock:
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self._data, ensure_ascii=False, indent=2), "utf-8")
            os.replace(tmp, self.path)

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return copy.deepcopy(self._data.get(key, default))

    def all(self) -> dict:
        with self._lock:
            return copy.deepcopy(self._data)

    def update(self, changes: dict) -> dict:
        """Apply a (possibly nested) partial update and notify listeners."""
        with self._lock:
            self._data = _merge(self._data, changes)
            self.save()
            snapshot = copy.deepcopy(self._data)
        for fn in list(self._listeners):
            try:
                fn(changes, snapshot)
            except Exception:
                log.exception("settings listener failed")
        return snapshot

    def on_change(self, fn) -> None:
        self._listeners.append(fn)


def setup_logging(ddir: Path, debug: bool = False) -> None:
    from logging.handlers import RotatingFileHandler

    fmt = logging.Formatter("%(asctime)s %(levelname)-7s %(name)s: %(message)s")
    root = logging.getLogger()
    root.setLevel(logging.DEBUG if debug else logging.INFO)
    fh = RotatingFileHandler(ddir / "localdrop.log", maxBytes=2_000_000, backupCount=2, encoding="utf-8")
    fh.setFormatter(fmt)
    root.addHandler(fh)
    if sys.stderr is not None:
        sh = logging.StreamHandler()
        sh.setFormatter(fmt)
        root.addHandler(sh)
    for noisy in ("zeroconf", "asyncio", "PIL", "comtypes"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
