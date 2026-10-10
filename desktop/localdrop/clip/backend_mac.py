"""macOS clipboard (NSPasteboard): text and images. Not tested on a Mac yet."""
from __future__ import annotations

import io

from AppKit import NSPasteboard, NSPasteboardTypePNG, NSPasteboardTypeString, NSPasteboardTypeTIFF
from Foundation import NSData
from PIL import Image


class ClipboardBusy(Exception):
    pass


def _pb():
    return NSPasteboard.generalPasteboard()


def change_count() -> int:
    return int(_pb().changeCount())


def read() -> tuple[str, object] | None:
    pb = _pb()
    types = pb.types() or []
    if "public.file-url" in types:
        return None                          # copied files are not synced
    if NSPasteboardTypeString in types:
        s = pb.stringForType_(NSPasteboardTypeString)
        if s is not None:
            return "text", str(s)
    for t in (NSPasteboardTypePNG, NSPasteboardTypeTIFF):
        if t in types:
            data = pb.dataForType_(t)
            if data is not None:
                return "image", Image.open(io.BytesIO(bytes(data)))
    return None


def write_text(text: str) -> None:
    pb = _pb()
    pb.clearContents()
    pb.setString_forType_(text, NSPasteboardTypeString)


def write_image(img: Image.Image) -> None:
    buf = io.BytesIO()
    img.convert("RGBA").save(buf, "PNG")
    data = NSData.dataWithBytes_length_(buf.getvalue(), len(buf.getvalue()))
    pb = _pb()
    pb.clearContents()
    pb.setData_forType_(data, NSPasteboardTypePNG)
