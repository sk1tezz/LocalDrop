"""“Send with LocalDrop” in Explorer: context menu for files and folders plus a Send To shortcut.

Everything lives under HKCU / the user profile, so no administrator rights are needed.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

from .autostart import _quote, launch_command

log = logging.getLogger(__name__)

KEYS = (r"Software\Classes\*\shell\LocalDrop", r"Software\Classes\Directory\shell\LocalDrop")


def _sendto() -> Path:
    return Path(os.environ.get("APPDATA", "")) / "Microsoft" / "Windows" / "SendTo" / "LocalDrop.lnk"


def is_installed() -> bool:
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, KEYS[0]):
            return True
    except OSError:
        return False


def install(label: str, icon: str) -> None:
    import winreg
    cmd = launch_command("--send")
    for key in KEYS:
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key) as k:
            winreg.SetValueEx(k, "MUIVerb", 0, winreg.REG_SZ, label)
            winreg.SetValueEx(k, "Icon", 0, winreg.REG_SZ, icon)
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key + r"\command") as k:
            winreg.SetValueEx(k, "", 0, winreg.REG_SZ, _quote(cmd) + ' "%1"')
    try:
        import win32com.client
        shell = win32com.client.Dispatch("WScript.Shell")
        lnk = shell.CreateShortcut(str(_sendto()))
        lnk.TargetPath = cmd[0]
        lnk.Arguments = _quote(cmd[1:])
        lnk.IconLocation = icon
        lnk.Description = label
        lnk.Save()
    except Exception:
        log.exception("creating the Send To shortcut failed")


def uninstall() -> None:
    import winreg
    for key in KEYS:
        for sub in (key + r"\command", key):
            try:
                winreg.DeleteKey(winreg.HKEY_CURRENT_USER, sub)
            except OSError:
                pass
    try:
        _sendto().unlink()
    except OSError:
        pass
