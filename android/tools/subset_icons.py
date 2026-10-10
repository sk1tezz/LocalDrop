r"""Rebuild app/src/main/res/font/symbols.ttf with only the icons the Android app uses.

Run after adding a new icon name to the Kotlin code:
    .venv\Scripts\python android\tools\subset_icons.py
Uses the full Material Symbols Rounded font downloaded by desktop/tools/subset_icons.py.
Keeps the FILL axis (outlined / filled), pins weight 400, grade 0 and optical size 24.
"""
import re
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
FULL = REPO / "desktop" / "tools" / "material-symbols-rounded.woff2"
FULL_URL = "https://cdn.jsdelivr.net/npm/material-symbols@0.27/material-symbols-rounded.woff2"
OUT = ROOT / "app" / "src" / "main" / "res" / "font" / "symbols.ttf"
SRC = ROOT / "app" / "src" / "main" / "java"
# icon names that come from other devices (desktop device icons)
EXTRA = "desktop_windows laptop_windows laptop_mac desktop_mac computer tv tablet smartphone devices_other".split()


def main() -> None:
    if not FULL.exists():
        import urllib.request
        print("downloading", FULL_URL)
        urllib.request.urlretrieve(FULL_URL, FULL)
    src = "".join(p.read_text("utf-8") for p in SRC.rglob("*.kt"))
    font = TTFont(str(FULL))
    names = set(font.getGlyphOrder())
    words = set(re.findall(r'"([a-z][a-z0-9_]+)"', src))
    icons = sorted({n for n in words if n in names and len(n) > 2} | set(EXTRA))
    opts = subset.Options()
    opts.layout_features = ["liga", "rlig", "calt"]
    opts.layout_closure = False
    opts.name_IDs = ["*"]
    opts.flavor = None
    sub = subset.Subsetter(opts)
    sub.populate(glyphs=[i for i in icons if i in names], text="abcdefghijklmnopqrstuvwxyz0123456789_")
    sub.subset(font)
    font = instancer.instantiateVariableFont(font, {"wght": 400, "GRAD": 0, "opsz": 24})
    font.flavor = None
    font.save(str(OUT))
    print(f"{len(icons)} icons -> {OUT.stat().st_size} bytes")
    if "-v" in sys.argv:
        print(" ".join(icons))


if __name__ == "__main__":
    main()
