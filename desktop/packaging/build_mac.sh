#!/bin/sh
# Build LocalDrop for macOS 13+ into dist/LocalDrop.app (not tested yet)
set -e
cd "$(dirname "$0")/.."
[ -d .venv ] || python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m PyInstaller packaging/localdrop_mac.spec --noconfirm --distpath dist --workpath build
echo "Done: $(pwd)/dist/LocalDrop.app"
