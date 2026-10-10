import subprocess
import sys
from pathlib import Path


def test_every_string_has_an_english_translation():
    root = Path(__file__).resolve().parent.parent
    r = subprocess.run([sys.executable, str(root / "tools" / "check_i18n.py")], capture_output=True,
                       text=True, encoding="utf-8", env={"PYTHONIOENCODING": "utf-8", "SYSTEMROOT": "C:\Windows"})
    assert r.returncode == 0, r.stdout
