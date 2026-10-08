"""Application core: asyncio loop, services, UI bridge and the window."""
from __future__ import annotations

import asyncio
import inspect
import json
import logging
import os
import subprocess
import sys
import threading
from pathlib import Path
from typing import Any, Callable

from . import config
from .config import IS_MAC, IS_WIN, Settings
from .net import ifaces
from .storage import Storage

log = logging.getLogger(__name__)


def resource_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parent.parent


def load_en_strings() -> dict[str, str]:
    """English strings shared with the UI (ui/i18n_en.js holds plain JSON)."""
    try:
        s = (resource_dir() / "ui" / "i18n_en.js").read_text("utf-8")
        return json.loads(s[s.index("{"): s.rindex("}") + 1])
    except (OSError, ValueError):
        return {}


def open_path(path: str, reveal: bool = False) -> None:
    p = str(path)
    if IS_WIN:
        if reveal:
            subprocess.Popen(["explorer", "/select,", os.path.normpath(p)])
        else:
            os.startfile(p)  # noqa: S606
    elif IS_MAC:
        subprocess.Popen(["open", "-R", p] if reveal else ["open", p])
    else:
        subprocess.Popen(["xdg-open", str(Path(p).parent) if reveal else p])


class Bridge:
    """Object exposed to JavaScript as ``window.pywebview.api``."""

    def __init__(self, app: "App"):
        self._app = app

    def call(self, name: str, args: list | None = None) -> Any:
        return self._app.dispatch(name, args or [])


class App:
    def __init__(self, profile: str | None = None, port: int | None = None, debug: bool = False):
        self.profile = profile
        self.ddir = config.data_dir(profile)
        config.setup_logging(self.ddir, debug)
        self.settings = Settings(self.ddir / "settings.json")
        if port:
            self.settings.update({"port": port})
        if profile and self.settings.get("device_name") == config.default_device_name():
            self.settings.update({"device_name": f"{config.default_device_name()} ({profile})"})
        self.storage = Storage(self.ddir / "localdrop.sqlite")
        self.storage.prune()
        self.en = load_en_strings()

        self.loop = asyncio.new_event_loop()
        self._loop_thread = threading.Thread(target=self._run_loop, name="asyncio", daemon=True)
        self.window = None
        self.window_visible = True
        self.quitting = False

        self.api: dict[str, Callable] = {}
        self.slices: dict[str, Callable[[], Any]] = {}
        self.services: list = []
        self.tray = None
        self.notifier = None
        self._dirty: set[str] = set()
        self._flush_scheduled = False

        self.port = int(self.settings.get("port"))
        self.ip = ifaces.pick_ip(self.settings.get("interface"))
        self.me_extra: dict[str, Any] = {}

        self._register_core()

        from .net.identity import Identity
        from .net.peers import PeerManager
        self.ident = Identity.load_or_create(self.ddir / "identity.pem")
        self.me_extra["id"] = self.ident.id
        self.peers = PeerManager(self, self.ident)
        self.add_service(self.peers)
        self.add_slice("devices", self.peers.view)
        self.expose("set_trust", self.peers.set_trust)
        self.expose("forget", self.peers.forget)

        from .net.discovery import Discovery
        self.discovery = Discovery(self, self.peers)
        self.add_service(self.discovery)

    # ---- translation ------------------------------------------------------
    def tr(self, s: str, *args) -> str:
        if self.settings.get("lang") == "en":
            s = self.en.get(s, s)
        for i, a in enumerate(args):
            s = s.replace("{%d}" % i, str(a))
        return s

    # ---- registry ---------------------------------------------------------
    def expose(self, name: str, fn: Callable) -> None:
        self.api[name] = fn

    def add_slice(self, name: str, builder: Callable[[], Any]) -> None:
        self.slices[name] = builder

    def add_service(self, svc) -> None:
        self.services.append(svc)

    def dispatch(self, name: str, args: list) -> Any:
        fn = self.api.get(name)
        if fn is None:
            log.warning("unknown api call %s", name)
            return {"error": "unknown call " + name}
        try:
            if inspect.iscoroutinefunction(fn):
                return asyncio.run_coroutine_threadsafe(fn(*args), self.loop).result(60)
            return fn(*args)
        except Exception as e:
            log.exception("api %s failed", name)
            return {"error": str(e)}

    def call_soon(self, fn: Callable, *args) -> None:
        self.loop.call_soon_threadsafe(fn, *args)

    def spawn(self, coro) -> "asyncio.Future":
        """Schedule a coroutine on the app loop from any thread."""
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    # ---- state & events ---------------------------------------------------
    def publish(self, *names: str) -> None:
        """Mark state slices as changed; they are sent to the UI in one batch."""
        def mark():
            self._dirty.update(names)
            if not self._flush_scheduled:
                self._flush_scheduled = True
                self.loop.call_later(0.05, self._flush)
        if threading.current_thread() is self._loop_thread:
            mark()
        else:
            self.loop.call_soon_threadsafe(mark)

    def _flush(self) -> None:
        self._flush_scheduled = False
        names, self._dirty = self._dirty, set()
        data = {}
        for n in names:
            try:
                data[n] = self.slices[n]()
            except Exception:
                log.exception("slice %s failed", n)
        if data:
            self.emit("state", data)

    def full_state(self) -> dict:
        return {n: b() for n, b in self.slices.items()}

    def emit(self, name: str, data: Any) -> None:
        w = self.window
        if w is None:
            return
        js = "window.__ld && window.__ld.emit(%s, %s)" % (json.dumps(name), json.dumps(data, ensure_ascii=False))
        try:
            w.run_js(js)
        except Exception:
            log.debug("emit %s failed", name, exc_info=True)

    def toast(self, kind: str, title: str, text: str = "", args: tuple = (), targs: tuple = (),
              system: bool = True) -> None:
        """In-app toast; also an OS notification when the window is hidden."""
        if not self.settings.get("notify"):
            return
        self.emit("toast", {"kind": kind, "title": title, "text": text, "args": list(args), "targs": list(targs)})
        if system and not self.window_visible and self.notifier is not None:
            self.notifier.notify(self.tr(title, *targs), self.tr(text, *args) if text else "")

    def me(self) -> dict:
        return {
            "name": self.settings.get("device_name"), "ip": self.ip, "port": self.port,
            "ips": [x for x in ifaces.list_ipv4()], "os": config.os_label(), "kind": config.os_kind(),
            "icon": config.default_icon(), "version": config.VERSION, **self.me_extra,
        }

    # ---- core api ---------------------------------------------------------
    def _register_core(self) -> None:
        self.add_slice("settings", self.settings.all)
        self.add_slice("me", self.me)
        self.expose("init", self.full_state)
        self.expose("settings", self._api_settings)
        self.expose("win", self._api_win)
        self.expose("jslog", lambda m: logging.getLogger("ui").error("%s", m))
        self.expose("open_logs", lambda: open_path(str(self.ddir / "localdrop.log")))
        self.expose("reveal", lambda p: open_path(p, reveal=True))
        self.expose("open_path", lambda p: open_path(p))
        self.settings.on_change(self._on_settings)

    def _api_settings(self, changes: dict) -> dict:
        return self.settings.update(changes)

    def _on_settings(self, changes: dict, _all: dict) -> None:
        self.publish("settings")
        if "interface" in changes:
            self.ip = ifaces.pick_ip(changes["interface"])
            self.publish("me")
        if "device_name" in changes:
            self.publish("me")

    def _api_win(self, action: str) -> None:
        w = self.window
        if w is None:
            return
        if action == "minimize":
            w.minimize()
        elif action == "maximize":
            if getattr(self, "_maximized", False):
                w.restore()
                self._maximized = False
            else:
                w.maximize()
                self._maximized = True
        elif action == "close":
            self.close_window()

    def close_window(self) -> None:
        if self.tray is not None and self.settings.get("tray"):
            self.hide_window()
        else:
            self.quit()

    def show_window(self) -> None:
        if self.window is not None:
            self.window.show()
            self.window.restore()
            self.window_visible = True

    def hide_window(self) -> None:
        if self.window is not None:
            self.window.hide()
            self.window_visible = False

    # ---- lifecycle --------------------------------------------------------
    def _run_loop(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    async def _start_services(self) -> None:
        for svc in self.services:
            start = getattr(svc, "start", None)
            if start is None:
                continue
            try:
                await start()
            except Exception:
                log.exception("service %s failed to start", type(svc).__name__)

    async def _stop_services(self) -> None:
        for svc in reversed(self.services):
            stop = getattr(svc, "stop", None)
            if stop is None:
                continue
            try:
                await asyncio.wait_for(stop(), 5)
            except Exception:
                log.exception("service %s failed to stop", type(svc).__name__)

    def quit(self) -> None:
        if self.quitting:
            return
        self.quitting = True
        log.info("quitting")
        try:
            asyncio.run_coroutine_threadsafe(self._stop_services(), self.loop).result(10)
        except Exception:
            log.exception("stopping services failed")
        if self.tray is not None:
            self.tray.stop()
        if self.window is not None:
            self.window.destroy()

    def _on_closing(self) -> bool:
        if self.quitting:
            return True
        if self.tray is not None and self.settings.get("tray"):
            threading.Thread(target=self.hide_window, daemon=True).start()
            return False
        threading.Thread(target=self.quit, daemon=True).start()
        return False

    def _on_drop(self, e: dict) -> None:
        files = (e.get("dataTransfer") or {}).get("files") or []
        paths = [f.get("pywebviewFullPath") for f in files if f.get("pywebviewFullPath")]
        self.emit("dropped", paths)

    def _on_loaded(self) -> None:
        from webview.dom import DOMEventHandler
        doc = self.window.dom.document
        doc.events.dragover += DOMEventHandler(lambda e: None, prevent_default=True)
        doc.events.drop += DOMEventHandler(self._on_drop, prevent_default=True, stop_propagation=True)

    def run(self, start_hidden: bool = False) -> None:
        import webview

        self._loop_thread.start()
        asyncio.run_coroutine_threadsafe(self._start_services(), self.loop).result(30)

        dark = self.settings.get("theme") != "light"
        self.window = webview.create_window(
            "LocalDrop" + (f" ({self.profile})" if self.profile else ""), url=str(resource_dir() / "ui" / "index.html"), js_api=Bridge(self),
            width=1200, height=780, min_size=(1060, 700), frameless=True, easy_drag=False,
            background_color="#18191B" if dark else "#F6F6F7", hidden=start_hidden,
        )
        self.window_visible = not start_hidden
        self.window.events.closing += self._on_closing
        self.window.events.loaded += self._on_loaded
        storage_path = str(self.ddir / "webview")
        webview.start(self._after_start, gui="edgechromium" if IS_WIN else None,
                      debug=bool(os.environ.get("LOCALDROP_DEVTOOLS")), private_mode=False, storage_path=storage_path)
        # webview.start returns when the last window is gone
        if not self.quitting:
            self.quitting = True
            try:
                asyncio.run_coroutine_threadsafe(self._stop_services(), self.loop).result(10)
            except Exception:
                pass
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.storage.close()

    def _after_start(self) -> None:
        for svc in self.services:
            hook = getattr(svc, "on_window", None)
            if hook:
                try:
                    hook(self.window)
                except Exception:
                    log.exception("on_window hook failed")
