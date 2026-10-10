"""Start LocalDrop when the user logs in: HKCU Run key (Windows) or a LaunchAgent (macOS)."""
from __future__ import annotations

import logging
import os
import plistlib
import sys
from pathlib import Path

from ..config import IS_MAC, IS_WIN

log = logging.getLogger(__name__)

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
NAME = "LocalDrop"
AGENT = Path.home() / "Library" / "LaunchAgents" / "app.localdrop.plist"


def launch_command(*extra: str) -> list[str]:
    """How to start this LocalDrop: the frozen executable or pythonw + main.py."""
    if getattr(sys, "frozen", False):
        return [sys.executable, *extra]
    exe = Path(sys.executable)
    if IS_WIN and exe.name.lower() == "python.exe" and (exe.parent / "pythonw.exe").exists():
        exe = exe.parent / "pythonw.exe"
    main = Path(__file__).resolve().parent.parent.parent / "main.py"
    return [str(exe), str(main), *extra]


def _quote(args: list[str]) -> str:
    return " ".join(f'"{a}"' if " " in a or not a else a for a in args)


def is_enabled() -> bool:
    if IS_WIN:
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
                winreg.QueryValueEx(k, NAME)
                return True
        except OSError:
            return False
    if IS_MAC:
        return AGENT.exists()
    return False


def set_enabled(on: bool) -> None:
    try:
        if IS_WIN:
            import winreg
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
                if on:
                    winreg.SetValueEx(k, NAME, 0, winreg.REG_SZ, _quote(launch_command("--hidden")))
                else:
                    try:
                        winreg.DeleteValue(k, NAME)
                    except FileNotFoundError:
                        pass
        elif IS_MAC:
            if on:
                AGENT.parent.mkdir(parents=True, exist_ok=True)
                AGENT.write_bytes(plistlib.dumps({
                    "Label": "app.localdrop", "ProgramArguments": launch_command("--hidden"),
                    "RunAtLoad": True, "ProcessType": "Interactive",
                }))
            elif AGENT.exists():
                AGENT.unlink()
    except OSError:
        log.exception("changing autostart failed")


def sync(want: bool, profile: str | None) -> bool:
    """Make the system match the setting and return the real state.

    Test profiles never register themselves. When running from sources nothing is
    registered automatically either (only when the user flips the switch), so the
    setting is corrected to what is really configured.
    """
    if profile:
        return False
    if not getattr(sys, "frozen", False) and not os.environ.get("LOCALDROP_AUTOSTART_DEV"):
        return is_enabled()
    if want != is_enabled():
        set_enabled(want)
    return is_enabled()
