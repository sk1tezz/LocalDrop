"""Audio capture on Windows: system loopback, input devices and single applications.

* System sound: WASAPI loopback of the default output (PyAudioWPatch).
* Input devices (microphones, line in): WASAPI input (PyAudioWPatch).
* One application (and its child processes): Process Loopback, Windows 10 2004+,
  through ``ActivateAudioInterfaceAsync("VAD\\Process_Loopback")``.

Every capture calls ``on_audio(float32 ndarray (frames, channels), rate)`` from
its own thread.
"""
from __future__ import annotations

import ctypes
import logging
import threading
from ctypes import POINTER, Structure, byref, c_int, c_longlong, c_ubyte, c_uint32, c_ulong, c_ushort, c_void_p
from ctypes.wintypes import DWORD, HANDLE, LPCWSTR, WORD

import comtypes
import numpy as np
import psutil
from comtypes import COMMETHOD, GUID, HRESULT, COMObject, IUnknown

log = logging.getLogger(__name__)


class CaptureError(Exception):
    pass


# ---- PyAudioWPatch based captures --------------------------------------------
class _PaCapture:
    def __init__(self, on_audio, device: dict | None):
        import pyaudiowpatch as pa
        self.pa_mod = pa
        self.on_audio = on_audio
        self.device = device
        self._pa = None
        self._stream = None

    def start(self) -> None:
        pa = self.pa_mod
        self._pa = pa.PyAudio()
        dev = self.device or self._default(self._pa)
        ch = max(1, min(2, int(dev["maxInputChannels"])))
        rate = int(dev["defaultSampleRate"])
        self.rate, self.channels = rate, ch

        def cb(data, frames, info, status):
            self.on_audio(np.frombuffer(data, dtype=np.float32).reshape(-1, ch), rate)
            return None, pa.paContinue

        try:
            self._stream = self._pa.open(format=pa.paFloat32, channels=ch, rate=rate, input=True,
                                         input_device_index=dev["index"], frames_per_buffer=int(rate * 0.01),
                                         stream_callback=cb)
        except OSError as e:
            self._pa.terminate()
            raise CaptureError(str(e)) from e

    def _default(self, p):
        raise NotImplementedError

    def stop(self) -> None:
        try:
            if self._stream:
                self._stream.stop_stream()
                self._stream.close()
        finally:
            if self._pa:
                self._pa.terminate()
            self._stream = self._pa = None


class SystemCapture(_PaCapture):
    def __init__(self, on_audio):
        super().__init__(on_audio, None)

    def _default(self, p):
        try:
            return p.get_default_wasapi_loopback()
        except Exception as e:
            raise CaptureError("нет устройства вывода для захвата") from e


class InputCapture(_PaCapture):
    def __init__(self, on_audio, name: str):
        super().__init__(on_audio, None)
        self.name = name

    def _default(self, p):
        for d in list_inputs(p):
            if d["name"] == self.name:
                return d
        raise CaptureError("устройство ввода не найдено")


def list_inputs(p=None) -> list[dict]:
    import pyaudiowpatch as pa
    own = p is None
    p = p or pa.PyAudio()
    try:
        wasapi = p.get_host_api_info_by_type(pa.paWASAPI)["index"]
        return [d for d in (p.get_device_info_by_index(i) for i in range(p.get_device_count()))
                if d["hostApi"] == wasapi and d["maxInputChannels"] > 0 and not d.get("isLoopbackDevice")]
    finally:
        if own:
            p.terminate()


# ---- Process Loopback ----------------------------------------------------------
class WAVEFORMATEX(Structure):
    _fields_ = [("wFormatTag", WORD), ("nChannels", WORD), ("nSamplesPerSec", DWORD),
                ("nAvgBytesPerSec", DWORD), ("nBlockAlign", WORD), ("wBitsPerSample", WORD), ("cbSize", WORD)]


class IAudioCaptureClient(IUnknown):
    _iid_ = GUID("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}")
    _methods_ = [
        COMMETHOD([], HRESULT, "GetBuffer", (["out"], POINTER(POINTER(c_ubyte)), "data"),
                  (["out"], POINTER(c_uint32), "frames"), (["out"], POINTER(DWORD), "flags"),
                  (["out"], POINTER(c_longlong), "pos"), (["out"], POINTER(c_longlong), "qpc")),
        COMMETHOD([], HRESULT, "ReleaseBuffer", (["in"], c_uint32, "frames")),
        COMMETHOD([], HRESULT, "GetNextPacketSize", (["out"], POINTER(c_uint32), "frames")),
    ]


class IAudioClient(IUnknown):
    _iid_ = GUID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}")
    _methods_ = [
        COMMETHOD([], HRESULT, "Initialize", (["in"], c_int, "mode"), (["in"], DWORD, "flags"),
                  (["in"], c_longlong, "duration"), (["in"], c_longlong, "period"),
                  (["in"], POINTER(WAVEFORMATEX), "fmt"), (["in"], POINTER(GUID), "session")),
        COMMETHOD([], HRESULT, "GetBufferSize", (["out"], POINTER(c_uint32), "frames")),
        COMMETHOD([], HRESULT, "GetStreamLatency", (["out"], POINTER(c_longlong), "latency")),
        COMMETHOD([], HRESULT, "GetCurrentPadding", (["out"], POINTER(c_uint32), "padding")),
        COMMETHOD([], HRESULT, "IsFormatSupported", (["in"], c_int, "mode"), (["in"], POINTER(WAVEFORMATEX), "fmt"),
                  (["out"], POINTER(POINTER(WAVEFORMATEX)), "closest")),
        COMMETHOD([], HRESULT, "GetMixFormat", (["out"], POINTER(POINTER(WAVEFORMATEX)), "fmt")),
        COMMETHOD([], HRESULT, "GetDevicePeriod", (["out"], POINTER(c_longlong), "default"),
                  (["out"], POINTER(c_longlong), "minimum")),
        COMMETHOD([], HRESULT, "Start"),
        COMMETHOD([], HRESULT, "Stop"),
        COMMETHOD([], HRESULT, "Reset"),
        COMMETHOD([], HRESULT, "SetEventHandle", (["in"], HANDLE, "event")),
        COMMETHOD([], HRESULT, "GetService", (["in"], POINTER(GUID), "iid"), (["out"], POINTER(c_void_p), "obj")),
    ]


class IActivateAudioInterfaceAsyncOperation(IUnknown):
    _iid_ = GUID("{72A22D78-CDE4-431D-B8CC-843A71199B6D}")
    _methods_ = [
        COMMETHOD([], HRESULT, "GetActivateResult", (["out"], POINTER(HRESULT), "result"),
                  (["out"], POINTER(POINTER(IUnknown)), "iface")),
    ]


class IActivateAudioInterfaceCompletionHandler(IUnknown):
    _iid_ = GUID("{41D949AB-9862-444A-80F6-C261334DA5EB}")
    _methods_ = [
        COMMETHOD([], HRESULT, "ActivateCompleted", (["in"], POINTER(IActivateAudioInterfaceAsyncOperation), "op")),
    ]


class IAgileObject(IUnknown):
    _iid_ = GUID("{94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90}")
    _methods_ = []


class _BLOB(Structure):
    _fields_ = [("cbSize", c_ulong), ("pBlobData", c_void_p)]


class _PROPVARIANT(Structure):
    _fields_ = [("vt", c_ushort), ("r1", c_ushort), ("r2", c_ushort), ("r3", c_ushort), ("blob", _BLOB)]


class _ACTIVATION_PARAMS(Structure):
    _fields_ = [("ActivationType", c_int), ("TargetProcessId", DWORD), ("ProcessLoopbackMode", c_int)]


class _Handler(COMObject):
    _com_interfaces_ = [IActivateAudioInterfaceCompletionHandler, IAgileObject]

    def __init__(self):
        super().__init__()
        self.done = threading.Event()

    def ActivateCompleted(self, op):
        self.done.set()
        return 0


VT_BLOB = 65
LOOPBACK, EVENTCALLBACK = 0x00020000, 0x00040000
AUTOCONVERTPCM, SRC_DEFAULT_QUALITY = 0x80000000, 0x08000000
BUFFER_SILENT = 0x2


class ProcessCapture:
    """Capture what one process tree plays, as 48 kHz stereo."""

    RATE = 48000

    def __init__(self, on_audio, pid: int):
        self.on_audio = on_audio
        self.pid = pid
        self.rate, self.channels = self.RATE, 2
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._ready = threading.Event()
        self._error: str | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="process-loopback", daemon=True)
        self._thread.start()
        self._ready.wait(5)
        if self._error:
            raise CaptureError(self._error)

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(2)

    def _activate(self) -> IAudioClient:
        params = _ACTIVATION_PARAMS(1, self.pid, 0)           # PROCESS_LOOPBACK, include the process tree
        pv = _PROPVARIANT()
        pv.vt = VT_BLOB
        pv.blob.cbSize = ctypes.sizeof(params)
        pv.blob.pBlobData = ctypes.cast(byref(params), c_void_p)
        handler = _Handler()
        op = POINTER(IActivateAudioInterfaceAsyncOperation)()
        activate = ctypes.windll.Mmdevapi.ActivateAudioInterfaceAsync
        activate.argtypes = [LPCWSTR, POINTER(GUID), POINTER(_PROPVARIANT), c_void_p, POINTER(c_void_p)]
        activate.restype = HRESULT
        h_ptr = handler.QueryInterface(IActivateAudioInterfaceCompletionHandler)
        raw_op = c_void_p()
        activate("VAD\\Process_Loopback", byref(IAudioClient._iid_), byref(pv), h_ptr, byref(raw_op))
        if not handler.done.wait(5):
            raise CaptureError("захват приложения не запустился")
        op = ctypes.cast(raw_op, POINTER(IActivateAudioInterfaceAsyncOperation))
        hr, unk = op.GetActivateResult()
        if hr != 0:
            raise CaptureError("захват приложения недоступен (нужна Windows 10 2004 или новее)")
        return unk.QueryInterface(IAudioClient)

    def _run(self) -> None:
        comtypes.CoInitializeEx(comtypes.COINIT_MULTITHREADED)
        client = None
        try:
            client = self._activate()
            fmt = WAVEFORMATEX(1, 2, self.RATE, self.RATE * 4, 4, 16, 0)
            client.Initialize(0, LOOPBACK | EVENTCALLBACK | AUTOCONVERTPCM | SRC_DEFAULT_QUALITY,
                              200000, 0, byref(fmt), None)
            event = ctypes.windll.kernel32.CreateEventW(None, False, False, None)
            client.SetEventHandle(event)
            cap_ptr = client.GetService(byref(IAudioCaptureClient._iid_))
            cap = ctypes.cast(cap_ptr, POINTER(IAudioCaptureClient))
            client.Start()
        except Exception as e:
            self._error = e.args[0] if isinstance(e, CaptureError) else f"захват приложения не удался: {e}"
            self._ready.set()
            return
        self._ready.set()
        wait = ctypes.windll.kernel32.WaitForSingleObject
        try:
            while not self._stop.is_set():
                if wait(event, 200) != 0:
                    continue
                while True:
                    n = cap.GetNextPacketSize()
                    if not n:
                        break
                    data, frames, flags, _pos, _qpc = cap.GetBuffer()
                    if frames:
                        if flags & BUFFER_SILENT:
                            pcm = np.zeros((frames, 2), dtype=np.float32)
                        else:
                            raw = np.ctypeslib.as_array(data, shape=(frames * 4,)).view(np.int16)
                            pcm = (raw.astype(np.float32) / 32768.0).reshape(-1, 2)
                        cap.ReleaseBuffer(frames)
                        self.on_audio(pcm, self.RATE)
                    else:
                        cap.ReleaseBuffer(frames)
        except Exception:
            log.exception("process capture failed")
        finally:
            try:
                client.Stop()
            except Exception:
                pass
            ctypes.windll.kernel32.CloseHandle(event)


# ---- apps that play sound ------------------------------------------------------
def list_apps() -> list[dict]:
    """Apps with an audio session: [{name, label, pid}] (root process of each app)."""
    from pycaw.pycaw import AudioUtilities
    comtypes.CoInitialize()
    seen: dict[str, dict] = {}
    try:
        for s in AudioUtilities.GetAllSessions():
            proc = s.Process
            if proc is None:
                continue
            try:
                name = proc.name()
                if name.lower() in ("audiodg.exe",) or proc.pid == psutil.Process().pid:
                    continue
                root = proc
                while True:
                    parent = root.parent()
                    if parent is None or parent.name() != name:
                        break
                    root = parent
            except psutil.Error:
                continue
            label = name[:-4] if name.lower().endswith(".exe") else name
            seen.setdefault(name.lower(), {"name": name, "label": label, "pid": root.pid})
    finally:
        comtypes.CoUninitialize()
    return sorted(seen.values(), key=lambda x: x["label"].lower())


def find_app_pid(name: str) -> int | None:
    for a in list_apps():
        if a["name"].lower() == name.lower():
            return a["pid"]
    for p in psutil.process_iter(["name", "ppid"]):
        try:
            if (p.info["name"] or "").lower() == name.lower():
                parent = psutil.Process(p.info["ppid"]) if p.info["ppid"] else None
                if parent is None or parent.name().lower() != name.lower():
                    return p.pid
        except psutil.Error:
            continue
    return None


def set_output_mute(mute: bool) -> bool | None:
    """Mute the default output. Returns the previous state (None if unavailable)."""
    from pycaw.pycaw import AudioUtilities
    comtypes.CoInitialize()
    try:
        vol = AudioUtilities.GetSpeakers().EndpointVolume
        was = bool(vol.GetMute())
        vol.SetMute(int(mute), None)
        return was
    except Exception:
        log.exception("mute failed")
        return None
    finally:
        comtypes.CoUninitialize()
