"""Shared types and helpers for all compression engines."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

MB = 1024 * 1024  # sizes are shown and targeted like Windows Explorer does

VIDEO_EXT = {".mp4", ".mov", ".m4v", ".mkv", ".avi", ".wmv", ".flv", ".webm",
             ".mts", ".m2ts", ".ts", ".3gp", ".mpg", ".mpeg", ".vob", ".ogv"}
AUDIO_EXT = {".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".oga", ".opus",
             ".wma", ".aiff", ".aif", ".amr", ".ac3", ".mka", ".m4b"}
IMAGE_EXT = {".jpg", ".jpeg", ".jfif", ".png", ".webp", ".bmp", ".tif", ".tiff",
             ".heic", ".heif"}
PDF_EXT = {".pdf"}
OFFICE_EXT = {".docx", ".docm", ".pptx", ".pptm", ".xlsx", ".xlsm"}

KIND_VIDEO, KIND_AUDIO, KIND_IMAGE, KIND_PDF, KIND_OFFICE = (
    "video", "audio", "image", "pdf", "office")

ALL_EXT = VIDEO_EXT | AUDIO_EXT | IMAGE_EXT | PDF_EXT | OFFICE_EXT


def kind_of(path: str | Path) -> Optional[str]:
    ext = Path(path).suffix.lower()
    if ext in VIDEO_EXT:
        return KIND_VIDEO
    if ext in AUDIO_EXT:
        return KIND_AUDIO
    if ext in IMAGE_EXT:
        return KIND_IMAGE
    if ext in PDF_EXT:
        return KIND_PDF
    if ext in OFFICE_EXT:
        return KIND_OFFICE
    return None


VIDEO_FORMATS = ("keep", "mp4", "mkv", "mov", "webm", "mp3", "m4a")   # mp3/m4a = audio only
IMAGE_FORMATS = ("keep", "jpg", "png", "webp")
AUDIO_FORMATS = ("keep", "mp3", "m4a", "ogg", "opus")


@dataclass
class Settings:
    mode: str = "percent"          # "percent", "mb" or "convert" (format change only)
    percent: int = 30              # final size as % of the original
    target_mb: float = 10.0        # final size per file, in MB
    resolution: str = "auto"       # auto | original | 2160 | 1440 | 1080 | 720 | 480 | 360
    fps: str = "auto"              # auto | original | 60 | 30 | 25 | 24 | 15
    video_format: str = "keep"
    image_format: str = "keep"
    audio_format: str = "keep"

    @property
    def convert_only(self) -> bool:
        return self.mode == "convert"

    output_root: Optional[Path] = None   # None = folder of the program
    move_originals: bool = True          # move the source into "Original" after success

    @property
    def suffix(self) -> str:
        return "_compressed"

    def root(self) -> Path:
        return Path(self.output_root) if self.output_root else default_output_root()

    def compressed_dir(self) -> Path:
        d = self.root() / "Compressed"
        d.mkdir(parents=True, exist_ok=True)
        return d

    def original_dir(self) -> Path:
        d = self.root() / "Original"
        d.mkdir(parents=True, exist_ok=True)
        return d

    def target_bytes(self, original: int) -> int:
        if self.mode == "mb":
            return max(1, int(self.target_mb * MB))
        if self.mode == "convert":
            return original
        return max(1, int(original * self.percent / 100.0))


@dataclass
class Result:
    ok: bool
    source: Path
    output: Optional[Path] = None
    original_size: int = 0
    new_size: int = 0
    target_size: int = 0
    message: str = ""
    notes: list[str] = field(default_factory=list)
    original_moved_to: Optional[Path] = None

    @property
    def saved_ratio(self) -> float:
        if not self.original_size or not self.new_size:
            return 0.0
        return 1.0 - self.new_size / self.original_size


class Cancelled(Exception):
    """Raised inside an engine when the user presses Cancel."""


class CompressionError(Exception):
    """A readable error to show to the user."""


ProgressFn = Callable[[float, str], None]


class Job:
    """Carries progress reporting and cancellation into an engine."""

    def __init__(self, progress: Optional[ProgressFn] = None,
                 cancel_event: Optional[threading.Event] = None):
        self._progress = progress
        self.cancel_event = cancel_event or threading.Event()
        self.processes: list[subprocess.Popen] = []

    def progress(self, fraction: float, text: str = "") -> None:
        if self._progress:
            self._progress(max(0.0, min(1.0, fraction)), text)

    def check(self) -> None:
        if self.cancel_event.is_set():
            raise Cancelled()

    @property
    def cancelled(self) -> bool:
        return self.cancel_event.is_set()


def human_size(n: float) -> str:
    n = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unit == "TB":
            if unit in ("B", "KB"):
                return f"{n:.0f} {unit}"
            return f"{n:.1f} {unit}" if n < 100 else f"{n:.0f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def unique_path(folder: Path, stem: str, ext: str) -> Path:
    cand = folder / f"{stem}{ext}"
    i = 2
    while cand.exists():
        cand = folder / f"{stem} ({i}){ext}"
        i += 1
    return cand


def output_path(source: Path, settings: "Settings", new_ext: Optional[str] = None) -> Path:
    """`photo.jpg` -> `<output folder>/Compressed/photo_compressed.jpg`, never overwriting."""
    ext = new_ext if new_ext is not None else source.suffix
    return unique_path(settings.compressed_dir(), f"{source.stem}{settings.suffix}", ext)


def default_output_root() -> Path:
    """The folder the program is in (next to the .exe), as asked."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


def move_original(source: Path, settings: "Settings") -> Path:
    dest_dir = settings.original_dir()
    if source.parent.resolve() == dest_dir.resolve():
        return source
    dest = unique_path(dest_dir, source.stem, source.suffix)
    shutil.move(str(source), str(dest))
    return dest


def resource_dir() -> Path:
    """Folder that holds bundled files (works both from source and when frozen)."""
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parent


def ffmpeg_path() -> str:
    exe = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
    for cand in (resource_dir() / "bin" / exe, resource_dir().parent / "bin" / exe,
                 resource_dir().parent / "packaging" / "ffmpeg" / exe):
        if cand.exists():
            return str(cand)
    found = shutil.which("ffmpeg")
    if found:
        return found
    raise CompressionError("FFmpeg was not found, so videos and audio can't be processed.")


NO_WINDOW = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW


def popen(args: list[str], job: Optional[Job] = None, **kw) -> subprocess.Popen:
    kw.setdefault("stdin", subprocess.DEVNULL)
    if os.name == "nt":
        kw["creationflags"] = kw.get("creationflags", 0) | NO_WINDOW
    p = subprocess.Popen(args, **kw)
    if job is not None:
        job.processes.append(p)
    return p


def kill_all(job: Job) -> None:
    for p in job.processes:
        try:
            if p.poll() is None:
                p.kill()
        except Exception:
            pass
