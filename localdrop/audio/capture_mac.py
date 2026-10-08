"""Audio capture on macOS 13+: system sound and single apps through ScreenCaptureKit,
input devices through sounddevice. Requires the "Screen Recording" permission.

NOT TESTED on a real Mac yet.
"""
from __future__ import annotations

import logging
import subprocess
import threading

import numpy as np

log = logging.getLogger(__name__)


class CaptureError(Exception):
    pass


def _shareable_content(timeout: float = 5.0):
    import ScreenCaptureKit as SCK
    done = threading.Event()
    box = {}

    def handler(content, error):
        box["content"], box["error"] = content, error
        done.set()

    SCK.SCShareableContent.getShareableContentWithCompletionHandler_(handler)
    if not done.wait(timeout) or box.get("content") is None:
        raise CaptureError("нет доступа к захвату звука: разрешите «Запись экрана» для LocalDrop в настройках macOS")
    return box["content"]


class _SCKCapture:
    RATE = 48000

    def __init__(self, on_audio, bundle_id: str | None = None):
        self.on_audio = on_audio
        self.bundle_id = bundle_id
        self.rate, self.channels = self.RATE, 2
        self._stream = None
        self._output = None

    def start(self) -> None:
        import objc
        import ScreenCaptureKit as SCK
        from Foundation import NSObject
        import CoreMedia as CM
        import dispatch  # pyobjc-framework-libdispatch

        content = _shareable_content()
        displays = content.displays()
        if not displays:
            raise CaptureError("нет дисплея для захвата")
        display = displays[0]
        if self.bundle_id:
            apps = [a for a in content.applications() if a.bundleIdentifier() == self.bundle_id]
            if not apps:
                raise CaptureError("приложение не запущено")
            flt = SCK.SCContentFilter.alloc().initWithDisplay_includingApplications_exceptingWindows_(display, apps, [])
        else:
            flt = SCK.SCContentFilter.alloc().initWithDisplay_excludingApplications_exceptingWindows_(display, [], [])
        cfg = SCK.SCStreamConfiguration.alloc().init()
        cfg.setCapturesAudio_(True)
        cfg.setSampleRate_(self.RATE)
        cfg.setChannelCount_(2)
        cfg.setExcludesCurrentProcessAudio_(True)
        cfg.setWidth_(2)
        cfg.setHeight_(2)
        cfg.setMinimumFrameInterval_(CM.CMTimeMake(1, 1))

        on_audio = self.on_audio

        class Output(NSObject):
            def stream_didOutputSampleBuffer_ofType_(self, stream, sbuf, kind):
                if kind != 1:            # SCStreamOutputTypeAudio
                    return
                try:
                    block = CM.CMSampleBufferGetDataBuffer(sbuf)
                    n = CM.CMBlockBufferGetDataLength(block)
                    err, data = CM.CMBlockBufferCopyDataBytes(block, 0, n, None)
                    if err:
                        return
                    pcm = np.frombuffer(bytes(data), dtype=np.float32)
                    pcm = pcm.reshape(2, -1).T if len(pcm) % 2 == 0 else pcm.reshape(-1, 1)
                    on_audio(np.ascontiguousarray(pcm), 48000)
                except Exception:
                    log.exception("SCK audio callback failed")

        objc.registerMetaDataForSelector(b"NSObject", b"stream:didOutputSampleBuffer:ofType:", {})
        self._output = Output.alloc().init()
        self._stream = SCK.SCStream.alloc().initWithFilter_configuration_delegate_(flt, cfg, None)
        queue = dispatch.dispatch_queue_create(b"localdrop.audio", None)
        ok, err = self._stream.addStreamOutput_type_sampleHandlerQueue_error_(self._output, 1, queue, None)
        if not ok:
            log.warning("SCK addStreamOutput failed: %s", err)
            raise CaptureError("захват звука не удался")
        started = threading.Event()
        box = {}

        def done(error):
            box["error"] = error
            started.set()

        self._stream.startCaptureWithCompletionHandler_(done)
        started.wait(5)
        if box.get("error") is not None:
            log.warning("SCK start failed: %s", box["error"])
            raise CaptureError("захват звука не удался")

    def stop(self) -> None:
        if self._stream is not None:
            self._stream.stopCaptureWithCompletionHandler_(lambda e: None)
            self._stream = None


class SystemCapture(_SCKCapture):
    def __init__(self, on_audio):
        super().__init__(on_audio, None)


class AppCapture(_SCKCapture):
    def __init__(self, on_audio, bundle_id: str):
        super().__init__(on_audio, bundle_id)


class InputCapture:
    def __init__(self, on_audio, name: str):
        self.on_audio = on_audio
        self.name = name
        self._stream = None

    def start(self) -> None:
        import sounddevice as sd
        idx = None
        for i, d in enumerate(sd.query_devices()):
            if d["name"] == self.name and d["max_input_channels"] > 0:
                idx = i
        if idx is None:
            raise CaptureError("устройство ввода не найдено")
        info = sd.query_devices(idx)
        rate = int(info["default_samplerate"])
        ch = min(2, int(info["max_input_channels"]))
        self._stream = sd.InputStream(device=idx, channels=ch, samplerate=rate, dtype="float32",
                                      blocksize=int(rate * 0.01),
                                      callback=lambda data, frames, t, status: self.on_audio(data.copy(), rate))
        self._stream.start()

    def stop(self) -> None:
        if self._stream:
            self._stream.stop()
            self._stream.close()


def list_inputs() -> list[str]:
    import sounddevice as sd
    return [d["name"] for d in sd.query_devices() if d["max_input_channels"] > 0]


def list_apps() -> list[dict]:
    try:
        content = _shareable_content(3)
    except CaptureError:
        return []
    out = {}
    for a in content.applications():
        bid, name = a.bundleIdentifier(), a.applicationName()
        if bid and name and not bid.startswith("com.apple.") or bid in ("com.apple.Music", "com.apple.Safari",
                                                                          "com.apple.TV", "com.apple.podcasts"):
            out[bid] = {"name": bid, "label": str(name)}
    return sorted(out.values(), key=lambda x: x["label"].lower())


def set_output_mute(mute: bool) -> bool | None:
    try:
        was = subprocess.run(["osascript", "-e", "output muted of (get volume settings)"],
                             capture_output=True, text=True, timeout=3).stdout.strip() == "true"
        subprocess.run(["osascript", "-e", f"set volume output muted {'true' if mute else 'false'}"], timeout=3)
        return was
    except (OSError, subprocess.SubprocessError):
        return None
