"""Platform-independent list of audio sources and capture factory.

Source ids: ``system`` | ``input:<device name>`` | ``app:<exe name or bundle id>``.
"""
from __future__ import annotations

from ..config import IS_MAC, IS_WIN


class CaptureError(Exception):
    pass


def _mod():
    if IS_WIN:
        from . import capture_win as m
    elif IS_MAC:
        from . import capture_mac as m
    else:
        raise CaptureError("захват звука не поддерживается в этой системе")
    return m


def list_sources() -> list[dict]:
    m = _mod()
    out = [{"id": "system", "label": "Весь звук системы", "group": "system"}]
    try:
        names = [d["name"] for d in m.list_inputs()] if IS_WIN else m.list_inputs()
    except Exception:
        names = []
    seen = set()
    for n in names:
        if n not in seen:
            seen.add(n)
            out.append({"id": "input:" + n, "label": n, "group": "input"})
    try:
        apps = m.list_apps()
    except Exception:
        apps = []
    for a in apps:
        out.append({"id": "app:" + a["name"], "label": a["label"], "group": "app"})
    return out


def open_capture(source: str, on_audio):
    m = _mod()
    try:
        if source == "system":
            return m.SystemCapture(on_audio)
        if source.startswith("input:"):
            return m.InputCapture(on_audio, source[6:])
        if source.startswith("app:"):
            name = source[4:]
            if IS_WIN:
                pid = m.find_app_pid(name)
                if pid is None:
                    raise CaptureError("приложение не запущено")
                return m.ProcessCapture(on_audio, pid)
            return m.AppCapture(on_audio, name)
    except m.CaptureError as e:
        raise CaptureError(str(e)) from e
    raise CaptureError("неизвестный источник")


def start_capture(cap) -> None:
    m = _mod()
    try:
        cap.start()
    except m.CaptureError as e:
        raise CaptureError(str(e)) from e


def set_output_mute(mute: bool) -> bool | None:
    return _mod().set_output_mute(mute)
