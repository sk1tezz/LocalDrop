# PyInstaller spec for macOS: python -m PyInstaller packaging/localdrop_mac.spec --noconfirm
# Not tested on a Mac yet.
from pathlib import Path

from PyInstaller.utils.hooks import collect_submodules

ROOT = Path(SPECPATH).parent

hidden = (collect_submodules("zeroconf") + collect_submodules("desktop_notifier")
          + ["pystray._darwin", "webview.platforms.cocoa", "ScreenCaptureKit", "CoreMedia", "dispatch"])

a = Analysis(
    [str(ROOT / "main.py")],
    pathex=[str(ROOT)],
    datas=[(str(ROOT / "ui"), "ui"), (str(ROOT / "localdrop" / "assets"), "localdrop/assets")],
    hiddenimports=hidden,
    excludes=["tkinter", "pytest", "PyInstaller", "fontTools"],
)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="LocalDrop", console=False)
coll = COLLECT(exe, a.binaries, a.datas, name="LocalDrop")
app = BUNDLE(
    coll,
    name="LocalDrop.app",
    icon=str(ROOT / "localdrop" / "assets" / "icon.icns"),
    bundle_identifier="app.localdrop",
    info_plist={
        "CFBundleName": "LocalDrop",
        "CFBundleShortVersionString": "1.0.0",
        "LSMinimumSystemVersion": "13.0",
        "NSHighResolutionCapable": True,
        "NSMicrophoneUsageDescription": "LocalDrop streams the selected input device to your other computers.",
        "NSCameraUsageDescription": "LocalDrop scans the pairing QR code of another device.",
        "NSLocalNetworkUsageDescription": "LocalDrop finds and connects to your devices on the local network.",
        "NSBonjourServices": ["_localdrop._tcp"],
    },
)
