"""Tray (Windows) / menu bar (macOS) icon."""
from __future__ import annotations

import logging
from pathlib import Path

log = logging.getLogger(__name__)


class Tray:
    def __init__(self, app):
        self.app = app
        self.icon = None

    def start(self) -> bool:
        try:
            import pystray
            from PIL import Image
        except ImportError:
            return False
        app = self.app
        tr = app.tr
        img = Image.open(Path(__file__).resolve().parent.parent / "assets" / "tray.png")

        def show(icon=None, item=None):
            app.show_window()

        def toggle_clip(icon, item):
            app.settings.update({"clip_auto": not app.settings.get("clip_auto")})

        def toggle_audio(icon, item):
            app.spawn(app.audio.api_toggle())

        def quit_app(icon, item):
            app.quit()

        menu = pystray.Menu(
            pystray.MenuItem(lambda item: tr("Открыть LocalDrop"), show, default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(lambda item: tr("Синхронизация буфера"), toggle_clip,
                             checked=lambda item: bool(app.settings.get("clip_auto"))),
            pystray.MenuItem(lambda item: tr("Трансляция звука"), toggle_audio,
                             checked=lambda item: bool(app.audio.on)),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(lambda item: tr("Выход"), quit_app),
        )
        try:
            self.icon = pystray.Icon("LocalDrop", img, "LocalDrop", menu)
            self.icon.run_detached()
        except Exception:
            log.exception("tray icon failed")
            self.icon = None
            return False
        return True

    def refresh(self) -> None:
        if self.icon is not None:
            try:
                self.icon.update_menu()
            except Exception:
                pass

    def stop(self) -> None:
        if self.icon is not None:
            try:
                self.icon.stop()
            except Exception:
                pass
            self.icon = None
