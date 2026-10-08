# PyInstaller spec for Windows: python -m PyInstaller packaging/localdrop_win.spec --noconfirm
from pathlib import Path

from PyInstaller.utils.hooks import collect_submodules

ROOT = Path(SPECPATH).parent

hidden = (collect_submodules("zeroconf") + collect_submodules("desktop_notifier")
          + ["pystray._win32", "webview.platforms.edgechromium", "webview.platforms.winforms",
             "pyaudiowpatch", "comtypes.stream", "win32timezone"])

a = Analysis(
    [str(ROOT / "main.py")],
    pathex=[str(ROOT)],
    datas=[(str(ROOT / "ui"), "ui"), (str(ROOT / "localdrop" / "assets"), "localdrop/assets")],
    hiddenimports=hidden,
    excludes=["tkinter", "pytest", "PyInstaller", "fontTools"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, [],
    exclude_binaries=True,
    name="LocalDrop",
    console=False,
    icon=str(ROOT / "localdrop" / "assets" / "icon.ico"),
    version=None,
)
coll = COLLECT(exe, a.binaries, a.datas, name="LocalDrop")
