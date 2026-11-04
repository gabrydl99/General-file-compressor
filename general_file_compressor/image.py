"""Photo compression with Pillow: quality search first, then gentle downscaling."""
from __future__ import annotations

import io
from pathlib import Path

from PIL import Image, ImageOps

from .core import CompressionError, Job, Result, Settings, output_path

try:  # iPhone photos
    from pillow_heif import register_heif_opener
    register_heif_opener()
except Exception:  # pragma: no cover
    pass

Image.MAX_IMAGE_PIXELS = 400_000_000

QUALITY_FLOOR = 50   # below this JPEG/WebP gets ugly; shrink the picture instead
QUALITY_MIN = 15     # last resort


def _has_alpha(img: Image.Image) -> bool:
    if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
        alpha = img.convert("RGBA").getchannel("A")
        return alpha.getextrema()[0] < 255
    return False


class _Encoder:
    def __init__(self, img: Image.Image, fmt: str, icc: bytes | None, exif: bytes | None):
        self.img, self.fmt, self.icc, self.exif = img, fmt, icc, exif
        self._scaled: dict[float, Image.Image] = {}

    def scaled(self, scale: float) -> Image.Image:
        scale = round(scale, 4)
        if scale >= 0.9999:
            return self.img
        if scale not in self._scaled:
            w, h = self.img.size
            size = (max(16, int(w * scale)), max(16, int(h * scale)))
            self._scaled = {scale: self.img.resize(size, Image.Resampling.LANCZOS)}
        return self._scaled[scale]

    def encode(self, quality: int, scale: float, palette: bool = False) -> bytes:
        img = self.scaled(scale)
        buf = io.BytesIO()
        extra = {}
        if self.icc:
            extra["icc_profile"] = self.icc
        if self.fmt == "JPEG":
            if self.exif:
                extra["exif"] = self.exif
            img.convert("RGB").save(buf, "JPEG", quality=quality, optimize=True,
                                    progressive=True, subsampling="4:2:0" if quality < 90 else "4:4:4",
                                    **extra)
        elif self.fmt == "WEBP":
            if self.exif:
                extra["exif"] = self.exif
            mode = "RGBA" if _has_alpha(img) else "RGB"
            img.convert(mode).save(buf, "WEBP", quality=quality, method=4, **extra)
        else:  # PNG
            if palette:
                src = img.convert("RGBA")
                img = src.quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.FLOYDSTEINBERG)
            img.save(buf, "PNG", optimize=True, **extra)
        return buf.getvalue()


def _search_quality(enc: _Encoder, target: int, scale: float, lo: int, hi: int) -> tuple[int, bytes] | None:
    """Highest quality in [lo, hi] that fits the target at this scale."""
    best = None
    data = enc.encode(lo, scale)
    if len(data) > target:
        return None
    best = (lo, data)
    a, b = lo + 1, hi
    while a <= b:
        mid = (a + b) // 2
        data = enc.encode(mid, scale)
        if len(data) <= target:
            best = (mid, data)
            a = mid + 1
        else:
            b = mid - 1
    return best


def compress_image(src: Path, settings: Settings, job: Job) -> Result:
    orig = src.stat().st_size
    target = settings.target_bytes(orig)
    notes: list[str] = []
    job.progress(0.05, "Reading")
    try:
        im = Image.open(src)
        im.load()
    except Exception as e:
        raise CompressionError(f"Couldn't open this picture ({e.__class__.__name__}).")
    if getattr(im, "n_frames", 1) > 1 and im.format not in ("JPEG", "HEIF"):
        notes.append("Only the first page/frame was kept")
    icc = im.info.get("icc_profile")
    exif_bytes = None
    try:
        exif = im.getexif()
        if exif:
            exif[0x0112] = 1  # orientation is applied to the pixels below
            exif_bytes = exif.tobytes()
    except Exception:
        exif_bytes = None
    img = ImageOps.exif_transpose(im)
    if img.mode not in ("RGB", "RGBA", "L", "LA", "P"):
        img = img.convert("RGBA" if "A" in img.getbands() else "RGB")

    ext = src.suffix.lower()
    is_jpeg = ext in (".jpg", ".jpeg", ".jfif")
    want = settings.image_format
    alpha = _has_alpha(img)
    if want == "jpg":
        fmt, out_ext = "JPEG", (ext if is_jpeg else ".jpg")
    elif want == "png":
        fmt, out_ext = "PNG", ".png"
    elif want == "webp":
        fmt, out_ext = "WEBP", ".webp"
    elif settings.convert_only:
        raise CompressionError("Pick a photo format to convert to.")
    elif is_jpeg:
        fmt, out_ext = "JPEG", ext
    elif ext == ".webp":
        fmt, out_ext = "WEBP", ".webp"
    elif alpha:
        fmt, out_ext = "PNG", ".png"
    else:
        fmt, out_ext = "JPEG", ".jpg"
    if settings.convert_only and (out_ext == ext or (is_jpeg and fmt == "JPEG")):
        raise CompressionError(f"It's already {out_ext.lstrip('.').upper()}.")
    if out_ext != ext and not (is_jpeg and fmt == "JPEG"):
        notes.append(f"Saved as {out_ext.lstrip('.').upper()} (from {ext.lstrip('.').upper()})")
    if fmt == "JPEG" and (alpha or img.mode in ("RGBA", "LA", "P")):
        if alpha:
            base = Image.new("RGB", img.size, (255, 255, 255))
            base.paste(img.convert("RGBA"), mask=img.convert("RGBA").getchannel("A"))
            img = base
            notes.append("Transparent areas filled with white")
        else:
            img = img.convert("RGB")

    enc = _Encoder(img, fmt, icc, exif_bytes)
    job.check()
    data: bytes | None = None

    if settings.convert_only:
        job.progress(0.5, "Converting")
        data = enc.encode({"JPEG": 92, "WEBP": 90}.get(fmt, 0), 1.0)
        out = output_path(src, settings, out_ext)
        out.write_bytes(data)
        return Result(True, src, out, orig, len(data), orig, "", notes)

    if fmt == "PNG":
        data = _compress_png(enc, target, job, notes)
    else:
        scale = 1.0
        for step in range(8):
            job.check()
            job.progress(0.15 + step * 0.1, "Compressing")
            found = _search_quality(enc, target, scale, QUALITY_FLOOR, 92)
            if found:
                data = found[1]
                break
            probe_size = len(enc.encode(QUALITY_FLOOR + 10, scale))
            scale *= max(0.35, min(0.92, (target / probe_size) ** 0.5 * 0.97))
            if min(img.size) * scale < 48:
                break
        if data is None:  # very small target: accept lower quality
            found = _search_quality(enc, target, scale, QUALITY_MIN, QUALITY_FLOOR)
            data = found[1] if found else enc.encode(QUALITY_MIN, scale)
            notes.append("Target is very small, quality is reduced a lot")
        if scale < 0.9999:
            w, h = enc.scaled(scale).size
            notes.append(f"Resized {img.size[0]}×{img.size[1]} → {w}×{h}")

    job.progress(0.95, "Saving")
    if len(data) >= orig:
        return Result(False, src, None, orig, 0, target,
                      "Already well compressed — couldn't make it smaller.", notes)
    out = output_path(src, settings, out_ext)
    out.write_bytes(data)
    if len(data) > target * 1.06:
        notes.append("Couldn't fully reach the target.")
    return Result(True, src, out, orig, len(data), target, "", notes)


def _compress_png(enc: _Encoder, target: int, job: Job, notes: list[str]) -> bytes:
    data = enc.encode(0, 1.0)
    if len(data) <= target:
        return data
    data = enc.encode(0, 1.0, palette=True)
    if len(data) <= target:
        notes.append("Reduced to 256 colors")
        return data
    scale = 1.0
    for _ in range(8):
        job.check()
        scale *= max(0.35, min(0.92, (target / len(data)) ** 0.5 * 0.97))
        data = enc.encode(0, scale, palette=True)
        if len(data) <= target:
            break
    w, h = enc.scaled(scale).size
    notes.append(f"Reduced to 256 colors, resized to {w}×{h}")
    return data
