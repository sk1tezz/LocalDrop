"""System notifications (shown when the window is hidden)."""
from __future__ import annotations

import asyncio
import logging
from pathlib import Path

log = logging.getLogger(__name__)


class Notifier:
    def __init__(self, app):
        self.app = app
        self._dn = None
        try:
            from desktop_notifier import DesktopNotifier, Icon
            icon = Icon(path=Path(__file__).resolve().parent.parent / "assets" / "icon.png")
            self._dn = DesktopNotifier(app_name="LocalDrop", app_icon=icon)
        except Exception:
            log.warning("system notifications are unavailable", exc_info=True)

    def notify(self, title: str, text: str) -> None:
        if self._dn is None:
            return

        async def send():
            try:
                await self._dn.send(title=title, message=text or " ",
                                    on_clicked=lambda: self.app.show_window())
            except Exception:
                log.debug("notification failed", exc_info=True)

        try:
            asyncio.run_coroutine_threadsafe(send(), self.app.loop)
        except RuntimeError:
            pass
