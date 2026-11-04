"""Picks the right engine for each file."""
from __future__ import annotations

import errno
from pathlib import Path

from .core import (KIND_AUDIO, KIND_IMAGE, KIND_OFFICE, KIND_PDF, KIND_VIDEO,
                   Cancelled, CompressionError, Job, Result, Settings, kind_of, move_original)


def compress(path: Path, settings: Settings, job: Job) -> Result:
    result = _compress(path, settings, job)
    if result.ok and settings.move_originals:
        try:
            result.original_moved_to = move_original(path, settings)
        except OSError as e:
            result.notes.append(f"Couldn't move the original into the Original folder ({e.strerror or e}).")
    return result


def _compress(path: Path, settings: Settings, job: Job) -> Result:
    kind = kind_of(path)
    try:
        size = path.stat().st_size
    except OSError:
        return Result(False, path, message="File not found.")
    if size == 0:
        return Result(False, path, message="The file is empty.")
    if settings.convert_only and kind in (KIND_PDF, KIND_OFFICE):
        return Result(False, path, original_size=size,
                      message="PDF and Office files can only be compressed, not converted.")
    if not settings.convert_only and settings.target_bytes(size) >= size:
        return Result(False, path, original_size=size, target_size=settings.target_bytes(size),
                      message="Already smaller than the size you picked.")
    try:
        if kind == KIND_VIDEO:
            from .media import compress_video
            return compress_video(path, settings, job)
        if kind == KIND_AUDIO:
            from .media import compress_audio
            return compress_audio(path, settings, job)
        if kind == KIND_IMAGE:
            from .image import compress_image
            return compress_image(path, settings, job)
        if kind == KIND_PDF:
            from .pdf import compress_pdf
            return compress_pdf(path, settings, job)
        if kind == KIND_OFFICE:
            from .office import compress_office
            return compress_office(path, settings, job)
        return Result(False, path, original_size=size, message="This file type isn't supported.")
    except Cancelled:
        raise
    except CompressionError as e:
        return Result(False, path, original_size=size, message=str(e))
    except PermissionError:
        return Result(False, path, original_size=size,
                      message="Windows didn't allow writing in the output folder.")
    except OSError as e:
        if e.errno == errno.ENOSPC:
            return Result(False, path, original_size=size, message="The disk is full.")
        return Result(False, path, original_size=size, message=f"File system error: {e.strerror or e}"[:300])
    except MemoryError:
        return Result(False, path, original_size=size, message="Not enough memory for this file.")
    except Exception as e:  # keep the app alive whatever happens
        return Result(False, path, original_size=size,
                      message=f"Unexpected error: {e.__class__.__name__}: {e}"[:300])
