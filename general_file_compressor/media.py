"""Video and audio compression with FFmpeg (bitrate targeting, 2-pass H.264)."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path

from .core import (Cancelled, CompressionError, Job, Result, Settings, ffmpeg_path,
                   kill_all, output_path, popen)

H264_FORMATS = ("mp4", "mkv", "mov")
# what each container can take without re-encoding
COPY_VIDEO = {"mp4": {"h264", "hevc", "mpeg4", "av1"}, "mov": {"h264", "hevc", "mpeg4", "prores"},
              "mkv": None, "webm": {"vp8", "vp9", "av1"}}           # None = anything
COPY_AUDIO = {"mp4": {"aac", "mp3", "ac3", "eac3", "alac"}, "mov": {"aac", "mp3", "ac3", "alac", "pcm_s16le"},
              "mkv": None, "webm": {"opus", "vorbis"}}

# Bits per pixel per frame below which H.264 starts to look blocky.
BPP_MIN = 0.04
RES_STEPS = (2160, 1440, 1080, 720, 576, 480, 360, 240)
CONTAINER_MARGIN = 0.97
STALL_SECONDS = 240  # no progress for this long = FFmpeg is stuck


@dataclass
class MediaInfo:
    duration: float = 0.0
    bitrate_kbps: float = 0.0
    has_video: bool = False
    width: int = 0
    height: int = 0
    fps: float = 0.0
    rotation: int = 0
    video_codec: str = ""
    has_audio: bool = False
    audio_codec: str = ""
    audio_rate: int = 0
    audio_channels: int = 0
    audio_kbps: float = 0.0

    @property
    def display_size(self) -> tuple[int, int]:
        if abs(self.rotation) % 180 == 90:
            return self.height, self.width
        return self.width, self.height


_STREAM_RE = re.compile(r"^\s*Stream #\d+:\d+\S*:\s*(Video|Audio):\s*(.*)$", re.M)


def probe(path: Path) -> MediaInfo:
    p = popen([ffmpeg_path(), "-hide_banner", "-nostdin", "-i", str(path)],
              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
              text=True, encoding="utf-8", errors="replace")
    try:
        _, err = p.communicate(timeout=120)
    except subprocess.TimeoutExpired:
        p.kill()
        raise CompressionError("Reading the file took too long.")
    info = MediaInfo()
    m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", err)
    if m:
        info.duration = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
    m = re.search(r"Duration:.*?bitrate:\s*(\d+)\s*kb/s", err)
    if m:
        info.bitrate_kbps = float(m.group(1))
    for kind, rest in _STREAM_RE.findall(err):
        if kind == "Video" and not info.has_video and "attached pic" not in rest:
            info.has_video = True
            info.video_codec = rest.split()[0].strip(",")
            dm = re.search(r"(?<![0-9a-fA-Fx])(\d{2,5})x(\d{2,5})(?![0-9])", rest)
            if dm:
                info.width, info.height = int(dm.group(1)), int(dm.group(2))
            fm = re.search(r"(\d+(?:\.\d+)?)\s*fps", rest) or re.search(r"(\d+(?:\.\d+)?)\s*tbr", rest)
            if fm:
                info.fps = float(fm.group(1))
        elif kind == "Audio" and not info.has_audio:
            info.has_audio = True
            info.audio_codec = rest.split()[0].strip(",")
            rm = re.search(r"(\d+)\s*Hz", rest)
            if rm:
                info.audio_rate = int(rm.group(1))
            if "mono" in rest:
                info.audio_channels = 1
            elif "stereo" in rest:
                info.audio_channels = 2
            else:
                cm = re.search(r"(\d+)\s*channels", rest) or re.search(r"\b(\d)\.(\d)(?:\(|,|\s)", rest)
                if cm and cm.lastindex == 2:
                    info.audio_channels = int(cm.group(1)) + int(cm.group(2))
                elif cm:
                    info.audio_channels = int(cm.group(1))
                else:
                    info.audio_channels = 2
            bm = re.search(r"(\d+)\s*kb/s", rest)
            if bm:
                info.audio_kbps = float(bm.group(1))
    rot = re.search(r"rotation of\s*(-?\d+(?:\.\d+)?)\s*degrees", err) or \
        re.search(r"rotate\s*:\s*(-?\d+)", err)
    if rot:
        info.rotation = int(round(float(rot.group(1)))) % 360
    if not info.has_video and not info.has_audio:
        raise CompressionError("This file has no readable video or audio.")
    if info.fps <= 0 or info.fps > 240:
        info.fps = 30.0
    return info


def _run(args: list[str], job: Job, duration: float, lo: float, hi: float, label: str) -> None:
    cmd = [ffmpeg_path(), "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
           "-progress", "pipe:1", "-nostats"] + args
    p = popen(cmd, job, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
              text=True, encoding="utf-8", errors="replace", bufsize=1)
    errors: deque[str] = deque(maxlen=12)
    t = threading.Thread(target=lambda: errors.extend(p.stderr), daemon=True)
    t.start()
    last = [time.monotonic()]
    stalled = threading.Event()

    def watchdog() -> None:
        while p.poll() is None:
            if time.monotonic() - last[0] > STALL_SECONDS:
                stalled.set()
                kill_all(job)
                return
            time.sleep(2)

    threading.Thread(target=watchdog, daemon=True).start()
    job.progress(lo, label)
    for line in p.stdout:
        last[0] = time.monotonic()
        if job.cancelled:
            kill_all(job)
            break
        key, _, val = line.strip().partition("=")
        if key in ("out_time_us", "out_time_ms") and duration > 0:
            try:
                secs = int(val) / 1_000_000
            except ValueError:
                continue
            job.progress(lo + (hi - lo) * min(1.0, secs / duration), label)
    p.wait()
    t.join(timeout=5)
    if job.cancelled:
        raise Cancelled()
    if stalled.is_set():
        raise CompressionError("FFmpeg stopped responding while working on this file.")
    if p.returncode != 0:
        msg = " ".join(l.strip() for l in errors if l.strip())[-300:]
        raise CompressionError(f"FFmpeg could not process the file. {msg}".strip())
    job.progress(hi, label)


def _even(x: float) -> int:
    return max(2, int(round(x / 2.0)) * 2)


def _audio_kbps_for_video(total_kbps: float, info: MediaInfo) -> int:
    if not info.has_audio:
        return 0
    for limit, a in ((4000, 160), (1500, 128), (700, 96), (300, 64), (150, 48)):
        if total_kbps >= limit:
            break
    else:
        a = 32
    a = min(a, max(24, int(total_kbps / 4)))
    if info.audio_kbps:
        a = min(a, max(32, int(info.audio_kbps)))
    return a


def _video_format(src: Path, settings: Settings) -> str:
    f = settings.video_format
    if f in ("mp4", "mkv", "mov", "webm"):
        return f
    ext = src.suffix.lower().lstrip(".")
    return ext if ext in ("mp4", "mkv", "mov", "webm") else "mp4"


def _filters(info: MediaInfo, settings: Settings, video_kbps: float | None, notes: list[str]) -> str:
    """Builds the -vf chain. With video_kbps=None (convert only) 'auto' means 'keep'."""
    disp_w, disp_h = info.display_size
    if not disp_w or not disp_h:
        disp_w, disp_h = 1280, 720
    short = min(disp_w, disp_h)

    def bpp(short_side: int, fps: float) -> float:
        f = short_side / short
        return (video_kbps or 0) * 1000 / max(1.0, disp_w * f * disp_h * f * fps)

    out_fps = info.fps
    if settings.fps not in ("auto", "original"):
        if info.fps > float(settings.fps) + 0.5:
            out_fps = float(settings.fps)
    elif settings.fps == "auto" and video_kbps and info.fps > 31 and bpp(short, info.fps) < BPP_MIN:
        out_fps = 30.0

    new_short = short
    if settings.resolution == "auto" and video_kbps:
        cands = [short] + [s for s in RES_STEPS if s < short]
        new_short = cands[-1]
        for s in cands:
            if bpp(s, out_fps) >= BPP_MIN:
                new_short = s
                break
    elif settings.resolution not in ("auto", "original"):
        new_short = min(short, int(settings.resolution))

    filters = []
    if out_fps != info.fps:
        filters.append(f"fps={out_fps:g}")
        notes.append(f"Frame rate {info.fps:g} → {out_fps:g} fps")
    f = new_short / short
    out_w, out_h = _even(disp_w * f), _even(disp_h * f)
    if (out_w, out_h) != (disp_w, disp_h):
        filters.append(f"scale={out_w}:{out_h}:flags=lanczos")
    if new_short < short:
        notes.append(f"Resolution {disp_w}×{disp_h} → {out_w}×{out_h}")
    filters.append("format=yuv420p")
    return ",".join(filters)


def _container_args(fmt: str, info: MediaInfo, copying_video: bool) -> list[str]:
    args = ["-map_metadata", "0"]
    if fmt in ("mp4", "mov"):
        args += ["-movflags", "+faststart"]
        if not copying_video:
            args += ["-tag:v", "avc1"]
        elif info.video_codec == "hevc":
            args += ["-tag:v", "hvc1"]
    return args


def compress_video(src: Path, settings: Settings, job: Job) -> Result:
    info = probe(src)
    if settings.video_format in ("mp3", "m4a") or not info.has_video:
        forced = settings.video_format if settings.video_format in ("mp3", "m4a") else None
        return compress_audio(src, settings, job, info, forced)
    if info.duration <= 0.1:
        raise CompressionError("Couldn't read the length of this video.")
    fmt = _video_format(src, settings)
    if settings.convert_only:
        return _convert_video(src, settings, job, info, fmt)

    orig = src.stat().st_size
    target = settings.target_bytes(orig)
    notes: list[str] = []
    total_kbps = target * 8 / 1000 / info.duration * CONTAINER_MARGIN

    webm = fmt == "webm"
    audio_copy = False
    audio_kbps = _audio_kbps_for_video(total_kbps, info)
    copy_ok = {"opus", "vorbis"} if webm else {"aac"}
    if (info.has_audio and info.audio_codec in copy_ok and info.audio_kbps
            and info.audio_kbps <= audio_kbps * 1.1 and info.audio_channels <= 2):
        audio_copy = True
        audio_kbps = int(info.audio_kbps)
    video_kbps = total_kbps - audio_kbps
    if video_kbps < 40:
        video_kbps = 40
        notes.append("Target is very small for this length, so the file ends up a bit bigger.")
    vf = _filters(info, settings, video_kbps, notes)
    if src.suffix.lower() != "." + fmt:
        notes.append(f"Saved as {fmt.upper()}")

    out = output_path(src, settings, "." + fmt)
    tmpdir = Path(tempfile.mkdtemp(prefix="gfc_"))
    passlog = str(tmpdir / "pass")

    def encode(vkbps: float, first_pass: bool, lo: float, hi: float, label: str) -> None:
        if webm:
            codec = ["-c:v", "libvpx-vp9", "-b:v", f"{int(vkbps)}k", "-row-mt", "1",
                     "-deadline", "good", "-cpu-used", "4"]
        else:
            codec = ["-c:v", "libx264", "-preset", "medium", "-b:v", f"{int(vkbps)}k"]
        base = (["-i", str(src), "-map", "0:v:0", "-vf", vf, "-fps_mode", "passthrough"]
                + codec + ["-passlogfile", passlog])
        if first_pass:
            _run(base + ["-pass", "1", "-an", "-f", "null", os.devnull],
                 job, info.duration, lo, hi, label)
            return
        audio = []
        if info.has_audio:
            audio = ["-map", "0:a:0"]
            if audio_copy:
                audio += ["-c:a", "copy"]
            else:
                ch = 1 if audio_kbps <= 40 else min(2, info.audio_channels or 2)
                audio += ["-c:a", "libopus" if webm else "aac", "-b:a", f"{audio_kbps}k", "-ac", str(ch)]
        _run(base + ["-pass", "2"] + audio + _container_args(fmt, info, False) + [str(out)],
             job, info.duration, lo, hi, label)

    try:
        encode(video_kbps, True, 0.0, 0.35, "Analyzing")
        encode(video_kbps, False, 0.35, 1.0, "Compressing")
        new = out.stat().st_size
        if new > target * 1.04 and video_kbps > 40:
            audio_bytes = audio_kbps * 1000 / 8 * info.duration
            factor = (target - audio_bytes) / max(1.0, new - audio_bytes)
            video_kbps = max(40.0, video_kbps * factor * 0.97)
            encode(video_kbps, False, 0.35, 1.0, "Fine-tuning size")
            new = out.stat().st_size
    except (Cancelled, CompressionError):
        out.unlink(missing_ok=True)
        raise
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    return _finish(src, out, orig, new, target, notes)


def _convert_video(src: Path, settings: Settings, job: Job, info: MediaInfo, fmt: str) -> Result:
    orig = src.stat().st_size
    notes: list[str] = []
    same_format = src.suffix.lower() == "." + fmt
    resize = settings.resolution not in ("auto", "original") or settings.fps not in ("auto", "original")
    if same_format and not resize:
        raise CompressionError(f"It's already {fmt.upper()}. Pick another format, or a size, to change it.")
    out = output_path(src, settings, "." + fmt)
    webm = fmt == "webm"

    vcopy = not resize and (COPY_VIDEO[fmt] is None or info.video_codec in COPY_VIDEO[fmt])
    acopy = info.has_audio and (COPY_AUDIO[fmt] is None or info.audio_codec in COPY_AUDIO[fmt])
    a_enc = ["-c:a", "libopus", "-b:a", "128k"] if webm else ["-c:a", "aac", "-b:a", "192k"]
    audio = ["-map", "0:a:0"] + (["-c:a", "copy"] if acopy else a_enc) if info.has_audio else []

    try:
        if vcopy:
            try:
                _run(["-i", str(src), "-map", "0:v:0", "-c:v", "copy"] + audio +
                     _container_args(fmt, info, True) + [str(out)], job, info.duration, 0.0, 1.0, "Converting")
                notes.append("Converted without re-encoding (no quality lost)")
                return _finish(src, out, orig, out.stat().st_size, orig, notes, convert=True)
            except CompressionError:
                out.unlink(missing_ok=True)   # container didn't accept it: re-encode below
        notes.clear()
        vf = _filters(info, settings, None, notes)
        if webm:
            vcodec = ["-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-row-mt", "1",
                      "-deadline", "good", "-cpu-used", "4"]
            a_enc = ["-c:a", "libopus", "-b:a", "128k"]
        else:
            vcodec = ["-c:v", "libx264", "-preset", "medium", "-crf", "20"]
        audio = ["-map", "0:a:0"] + (["-c:a", "copy"] if acopy else a_enc) if info.has_audio else []
        _run(["-i", str(src), "-map", "0:v:0", "-vf", vf] + vcodec + audio +
             _container_args(fmt, info, False) + [str(out)], job, info.duration, 0.0, 1.0, "Converting")
    except (Cancelled, CompressionError):
        out.unlink(missing_ok=True)
        raise
    return _finish(src, out, orig, out.stat().st_size, orig, notes, convert=True)


# ---------------------------------------------------------------- audio

_MP3_MPEG1 = (32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
_MP3_MPEG2 = (8, 16, 24, 32, 40, 48, 56, 64)
CONVERT_KBPS = {"mp3": 192, "aac": 192, "opus": 128, "vorbis": 160}


def _audio_codec(src: Path, info: MediaInfo, fmt: str, kbps: float) -> tuple[str, str, list[str]]:
    """Returns (codec, extension, notes) for the requested output format."""
    ext = src.suffix.lower()
    notes: list[str] = []
    if fmt == "mp3":
        return "mp3", ".mp3", notes
    if fmt == "m4a":
        return "aac", ".m4a", notes
    if fmt == "opus":
        return "opus", ".opus", notes
    if fmt == "ogg":
        return ("vorbis" if kbps >= 64 else "opus"), ".ogg", notes
    # keep the original format when it's a compressed one
    if ext == ".mp3":
        return "mp3", ".mp3", notes
    if ext in (".m4a", ".aac", ".m4b"):
        return "aac", (".m4b" if ext == ".m4b" else ".m4a"), notes
    if ext == ".opus":
        return "opus", ".opus", notes
    if ext in (".ogg", ".oga"):
        return ("vorbis" if kbps >= 64 and info.audio_codec != "opus" else "opus"), ".ogg", notes
    if ext in (".mp4", ".mov", ".m4v", ".3gp", ".mkv", ".webm"):
        notes.append("No video inside, saved as M4A audio")
        return "aac", ".m4a", notes
    notes.append(f"Saved as MP3 (from {ext.lstrip('.').upper()})")
    return "mp3", ".mp3", notes


def _audio_args(codec: str, info: MediaInfo, kbps: float) -> list[str]:
    ch_in = info.audio_channels or 2
    if codec == "mp3":
        abr: list[str] = []
        if kbps >= 32:
            k = int(min(320, kbps))
            abr = [] if k in _MP3_MPEG1 else ["-abr", "1"]
            rate = min(info.audio_rate or 44100, 48000)
            if rate not in (32000, 44100, 48000):
                rate = 44100
        else:
            k = max(b for b in _MP3_MPEG2 if b <= max(8, kbps))
            rate = 22050 if k >= 16 else 11025
            if k == 8:
                rate = 8000
        ch = 1 if k < 64 else min(2, ch_in)
        return ["-c:a", "libmp3lame", "-b:a", f"{k}k", *abr, "-ar", str(rate), "-ac", str(ch),
                "-id3v2_version", "3"]
    if codec == "aac":
        k = int(min(320, max(12, kbps)))
        ch = 1 if k < 48 else min(2, ch_in)
        args = ["-c:a", "aac", "-b:a", f"{k}k", "-ac", str(ch)]
        return args + (["-ar", "22050"] if k < 24 else [])
    if codec == "opus":
        k = int(min(510, max(6, kbps)))
        ch = 1 if k < 24 else min(2, ch_in)
        args = ["-c:a", "libopus", "-b:a", f"{k}k", "-ac", str(ch)]
        if info.audio_rate and info.audio_rate not in (8000, 12000, 16000, 24000, 48000):
            args += ["-ar", "48000"]
        return args
    k = int(min(500, max(48, kbps)))
    return ["-c:a", "libvorbis", "-b:a", f"{k}k", "-ac", str(min(2, ch_in))]


def compress_audio(src: Path, settings: Settings, job: Job, info: MediaInfo | None = None,
                   forced_format: str | None = None) -> Result:
    info = info or probe(src)
    if not info.has_audio:
        raise CompressionError("No audio track found in this file.")
    if info.duration <= 0.1:
        raise CompressionError("Couldn't read the length of this audio.")
    orig = src.stat().st_size
    fmt = forced_format or settings.audio_format
    convert = settings.convert_only

    if convert:
        if fmt == "keep" and not forced_format:
            raise CompressionError("Pick an audio format to convert to.")
        codec, out_ext, notes = _audio_codec(src, info, fmt, 160)
        if out_ext == src.suffix.lower():
            raise CompressionError(f"It's already {out_ext.lstrip('.').upper()}.")
        kbps = CONVERT_KBPS[codec]
        target = orig
    else:
        target = settings.target_bytes(orig)
        kbps = target * 8 / 1000 / info.duration * 0.98
        codec, out_ext, notes = _audio_codec(src, info, fmt, kbps)
    if forced_format:
        notes = ["Audio only, extracted from the video"]
    args = _audio_args(codec, info, kbps)
    out = output_path(src, settings, out_ext)
    label = "Converting" if convert else "Compressing"

    def encode(a: list[str], lbl: str) -> None:
        _run(["-i", str(src), "-map", "0:a:0", "-vn", "-map_metadata", "0"] + a + [str(out)],
             job, info.duration, 0.0, 1.0, lbl)

    try:
        encode(args, label)
        new = out.stat().st_size
        if not convert and new > target * 1.04:
            args2 = _audio_args(codec, info, kbps * target / new * 0.97)
            if args2 != args:
                encode(args2, "Fine-tuning size")
                new = out.stat().st_size
    except (Cancelled, CompressionError):
        out.unlink(missing_ok=True)
        raise
    return _finish(src, out, orig, new, target, notes, convert=convert or bool(forced_format))


def _finish(src: Path, out: Path, orig: int, new: int, target: int, notes: list[str],
            convert: bool = False) -> Result:
    if convert:
        return Result(True, src, out, orig, new, target, "", notes)
    if new >= orig:
        out.unlink(missing_ok=True)
        return Result(False, src, None, orig, 0, target,
                      "Already well compressed — couldn't make it smaller.", notes)
    if new > target * 1.06:
        notes.append("Couldn't fully reach the target without ruining quality.")
    return Result(True, src, out, orig, new, target, "", notes)
