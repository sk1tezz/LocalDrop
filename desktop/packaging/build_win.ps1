# Build LocalDrop for Windows into desktop\dist\LocalDrop\LocalDrop.exe
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot          # desktop\
Set-Location $root
if (-not (Test-Path "..\.venv")) { py -3 -m venv ..\.venv }
& ..\.venv\Scripts\python.exe -m pip install -r requirements.txt
& ..\.venv\Scripts\python.exe -m PyInstaller packaging\localdrop_win.spec --noconfirm --distpath dist --workpath build
Write-Output "Done: $root\dist\LocalDrop\LocalDrop.exe"
