"""Windows clipboard: text and bitmaps."""
from __future__ import annotations

import io
import time

import win32clipboard as wc
import win32con
from PIL import Image, ImageGrab

CF_PNG = wc.RegisterClipboardFormat("PNG")


class ClipboardBusy(Exception):
    pass


def _open(retries: int = 10) -> None:
    for _ in range(retries):
        try:
            wc.OpenClipboard(None)
            return
        except Exception:
            time.sleep(0.03)
    raise ClipboardBusy()


def change_count() -> int:
    return wc.GetClipboardSequenceNumber()


def read() -> tuple[str, object] | None:
    """('text', str) | ('image', PIL.Image) | None."""
    _open()
    try:
        if wc.IsClipboardFormatAvailable(win32con.CF_UNICODETEXT):
            return "text", wc.GetClipboardData(win32con.CF_UNICODETEXT)
        if wc.IsClipboardFormatAvailable(win32con.CF_HDROP):
            return None                      # copied files are not synced
        has_png = wc.IsClipboardFormatAvailable(CF_PNG)
        has_dib = wc.IsClipboardFormatAvailable(win32con.CF_DIB) or wc.IsClipboardFormatAvailable(win32con.CF_DIBV5)
        png = wc.GetClipboardData(CF_PNG) if has_png else None
    finally:
        wc.CloseClipboard()
    if png:
        return "image", Image.open(io.BytesIO(png))
    if has_dib:
        img = ImageGrab.grabclipboard()
        if isinstance(img, Image.Image):
            return "image", img
    return None


def write_text(text: str) -> None:
    _open()
    try:
        wc.EmptyClipboard()
        wc.SetClipboardData(win32con.CF_UNICODETEXT, text)
    finally:
        wc.CloseClipboard()


def write_image(img: Image.Image) -> None:
    rgba = img.convert("RGBA")
    png = io.BytesIO()
    rgba.save(png, "PNG")
    bmp = io.BytesIO()
    rgba.convert("RGB").save(bmp, "BMP")
    dib = bmp.getvalue()[14:]               # drop BITMAPFILEHEADER
    _open()
    try:
        wc.EmptyClipboard()
        wc.SetClipboardData(win32con.CF_DIB, dib)
        wc.SetClipboardData(CF_PNG, png.getvalue())
    finally:
        wc.CloseClipboard()
