# Third-party components

LocalDrop is licensed under the GNU General Public License v3.0 (see `LICENSE`).
It uses and, in the packaged builds, redistributes the components below under their own licenses.

## Python packages

| Component | License |
|---|---|
| pywebview | BSD-3-Clause |
| bottle (via pywebview) | MIT |
| proxy_tools (via pywebview) | MIT |
| pythonnet (via pywebview, Windows) | MIT |
| cryptography | Apache-2.0 OR BSD-3-Clause |
| zeroconf | LGPL-2.1-or-later |
| ifaddr | MIT |
| pystray | LGPL-3.0 |
| Pillow | MIT-CMU |
| desktop-notifier | MIT |
| numpy | BSD-3-Clause (and bundled components under 0BSD, MIT, Zlib) |
| sounddevice | MIT |
| PortAudio (via sounddevice, PyAudioWPatch) | MIT |
| PyAV (av) | BSD-3-Clause |
| FFmpeg / libopus (bundled with PyAV) | LGPL-2.1-or-later / BSD-3-Clause |
| opencv-python-headless | Apache-2.0 |
| psutil | BSD-3-Clause |
| pywin32 (Windows) | PSF |
| comtypes (Windows) | MIT |
| pycaw (Windows) | MIT |
| PyAudioWPatch (Windows) | Apache-2.0 |
| PyObjC (macOS) | MIT |

The LGPL components (zeroconf, pystray, FFmpeg) ship as separate, replaceable files in the
folder-style PyInstaller builds.

## User interface

| Component | License |
|---|---|
| React, ReactDOM 18.3.1 | MIT |
| htm 3.1.1 | Apache-2.0 |
| qrcode-generator 1.4.4 | MIT |
| Material Symbols Rounded (subset) | Apache-2.0 |
| Manrope | SIL Open Font License 1.1 |
| JetBrains Mono | SIL Open Font License 1.1 |

## Android app

| Component | License |
|---|---|
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| AndroidX (Compose, Activity, AppCompat, Core, Lifecycle, DocumentFile, CameraX, Glance, WorkManager, Room) | Apache-2.0 |
| Bouncy Castle (bcprov-jdk18on) | MIT (Bouncy Castle License) |
| ZXing core | Apache-2.0 |
| Concentus (pure Java port of libopus) | BSD-3-Clause (the libopus license) |
| Material Symbols Rounded (subset) | Apache-2.0 |
| Manrope | SIL Open Font License 1.1 |
| JetBrains Mono | SIL Open Font License 1.1 |


The full license texts are available from each project's repository and package metadata.
