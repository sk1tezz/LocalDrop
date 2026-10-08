"""Draw the LocalDrop icon (rounded accent square with two arrows) into localdrop/assets."""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "localdrop" / "assets"
ACCENT = (56, 189, 214)
INK = (13, 26, 29)


def draw(size: int) -> Image.Image:
    s = 4 * size                                    # supersample, then shrink for smooth edges
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.24), fill=ACCENT)
    w = max(4, int(s * 0.075))
    head = int(s * 0.13)
    y1, y2 = int(s * 0.37), int(s * 0.63)
    x0, x1 = int(s * 0.24), int(s * 0.76)
    d.line((x0 + head // 2, y1, x1 - head, y1), fill=INK, width=w)     # top arrow ->
    d.polygon([(x1 + w // 2, y1), (x1 - head, y1 - head), (x1 - head, y1 + head)], fill=INK)
    d.line((x0 + head, y2, x1 - head // 2, y2), fill=INK, width=w)     # bottom arrow <-
    d.polygon([(x0 - w // 2, y2), (x0 + head, y2 - head), (x0 + head, y2 + head)], fill=INK)
    return im.resize((size, size), Image.LANCZOS)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    big = draw(1024)
    big.save(OUT / "icon.png")
    draw(64).save(OUT / "tray.png")
    big.save(OUT / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    big.save(OUT / "icon.icns") if hasattr(Image, "registered_extensions") and ".icns" in Image.registered_extensions() else None
    print("icons written to", OUT)


if __name__ == "__main__":
    main()
