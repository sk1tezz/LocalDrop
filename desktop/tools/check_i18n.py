"""List Russian UI/backend strings that have no English translation in ui/i18n_en.js."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CYR = re.compile("[А-Яа-яЁё]")
# fragments assembled elsewhere (units, plural forms, parts of keys)
SKIP = {"Б", "КБ", "МБ", "ГБ", "устройство", "устройства", "устройств", "отправлено", "получено",
        "Текст · ", "Изображение · ", "Папка · {0} · {1} · "}


def main() -> int:
    s = (ROOT / "ui" / "i18n_en.js").read_text("utf-8")
    en = json.loads(s[s.index("{"): s.rindex("}") + 1])
    js = "".join((ROOT / "ui" / f).read_text("utf-8") for f in ("app.js", "core.js"))
    ui_keys = set(re.findall(r"\bt\('((?:[^'\\]|\\.)*)'", js))
    py = "".join(p.read_text("utf-8") for p in (ROOT / "localdrop").rglob("*.py"))
    py_keys = {m.group(1) for m in re.finditer(r'"((?:[^"\\\n]|\\.)*)"', py) if CYR.search(m.group(1))}
    missing = sorted(k for k in ui_keys | py_keys if CYR.search(k) and k not in en and k not in SKIP)
    for k in missing:
        print(k)
    print(f"{len(missing)} missing")
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
