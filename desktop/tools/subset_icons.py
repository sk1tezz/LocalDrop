"""Rebuild ui/fonts/material-symbols-subset.woff2 with only the icons the UI uses.

Run after adding a new icon name to the UI or to the backend device icons:
    python desktop/tools/subset_icons.py
Needs: pip install fonttools brotli. The full font is downloaded on first use.
"""
import re
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT.parent / "ui"
FULL = ROOT / "tools" / "material-symbols-rounded.woff2"
FULL_URL = "https://cdn.jsdelivr.net/npm/material-symbols@0.27/material-symbols-rounded.woff2"
OUT = UI / "fonts" / "material-symbols-subset.woff2"
# icons named only in Python code (device icons) or built dynamically
EXTRA = ("draft desktop_windows laptop_windows laptop_mac desktop_mac computer tv tablet cast devices_other "
         "music_note image movie folder_zip description folder speaker error check_circle").split()


def main() -> None:
    if not FULL.exists():
        import urllib.request
        print("downloading", FULL_URL)
        urllib.request.urlretrieve(FULL_URL, FULL)
    src = "".join(p.read_text("utf-8") for p in UI.glob("*.js"))
    font = TTFont(str(FULL))
    names = set(font.getGlyphOrder())
    icons = sorted({n for n in re.findall(r"\b[a-z][a-z0-9_]+\b", src) if n in names and len(n) > 2} | set(EXTRA))
    opts = subset.Options()
    opts.flavor = "woff2"
    opts.layout_features = ["liga", "rlig", "calt"]
    opts.layout_closure = False
    opts.name_IDs = ["*"]
    sub = subset.Subsetter(opts)
    sub.populate(glyphs=[i for i in icons if i in names], text="abcdefghijklmnopqrstuvwxyz0123456789_")
    sub.subset(font)
    font.flavor = "woff2"
    font.save(str(OUT))
    print(f"{len(icons)} icons -> {OUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
