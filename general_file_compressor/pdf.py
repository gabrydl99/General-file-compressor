"""PDF compression with PyMuPDF: clean-up first, then re-encode the pictures inside."""
from __future__ import annotations

from pathlib import Path

import pymupdf

from .core import CompressionError, Job, Result, Settings, output_path

# (dpi, jpeg quality) from gentle to aggressive
LADDER = [(600, 92), (450, 90), (300, 86), (300, 80), (220, 78), (180, 75), (150, 70), (150, 60), (120, 55), (110, 50),
          (96, 45), (85, 40), (72, 35), (72, 28), (60, 22), (50, 16)]

SAVE_OPTS = dict(garbage=4, deflate=True, deflate_images=True, deflate_fonts=True,
                 clean=True, use_objstms=1, encryption=pymupdf.PDF_ENCRYPT_KEEP)


def _open(src: Path) -> pymupdf.Document:
    try:
        doc = pymupdf.open(src)
    except Exception as e:
        raise CompressionError(f"Couldn't open this PDF ({e.__class__.__name__}).")
    if doc.needs_pass:
        doc.close()
        raise CompressionError("This PDF is password-protected.")
    return doc


def _render(src: Path, level: int | None) -> bytes:
    doc = _open(src)
    try:
        try:
            doc.subset_fonts()
        except Exception:
            pass
        if level is not None:
            dpi, q = LADDER[level]
            doc.rewrite_images(dpi_threshold=dpi + 10, dpi_target=dpi, quality=q,
                               lossy=True, lossless=True, bitonal=False,
                               color=True, gray=True)
        return doc.tobytes(**SAVE_OPTS)
    finally:
        doc.close()


def compress_pdf(src: Path, settings: Settings, job: Job) -> Result:
    orig = src.stat().st_size
    target = settings.target_bytes(orig)
    notes: list[str] = []
    job.progress(0.05, "Cleaning up")
    best = _render(src, None)
    job.check()
    if len(best) > target:
        # binary search for the gentlest level that fits
        lo, hi = 0, len(LADDER) - 1
        tries = 0
        results: dict[int, bytes] = {}
        fit: int | None = None
        while lo <= hi:
            mid = (lo + hi) // 2
            tries += 1
            job.progress(min(0.9, 0.1 + tries * 0.18), "Compressing pictures")
            results[mid] = _render(src, mid)
            job.check()
            if len(results[mid]) <= target:
                fit, hi = mid, mid - 1
            else:
                lo = mid + 1
        if fit is not None:
            best = results[fit]
        else:
            strongest = results.get(len(LADDER) - 1) or _render(src, len(LADDER) - 1)
            if len(strongest) < len(best):
                best = strongest
            notes.append("Couldn't fully reach the target: this PDF is mostly text or drawings.")
    job.progress(0.95, "Saving")
    if len(best) >= orig:
        return Result(False, src, None, orig, 0, target,
                      "Already well compressed — couldn't make it smaller.", notes)
    out = output_path(src, settings)
    out.write_bytes(best)
    return Result(True, src, out, orig, len(best), target, "", notes)
