"""Word / PowerPoint / Excel (.docx .pptx .xlsx) compression: shrink the pictures inside."""
from __future__ import annotations

import io
import posixpath
import re
import zipfile
from pathlib import Path

from PIL import Image

from .core import CompressionError, Job, Result, Settings, output_path

# (jpeg quality, longest side in pixels) from gentle to aggressive
LEVELS = [(92, 4096), (90, 3200), (87, 2560), (83, 2560), (80, 2048), (75, 1920), (70, 1600), (65, 1400), (60, 1200),
          (55, 1024), (50, 900), (45, 800), (40, 640), (32, 512), (25, 400)]

PICTURE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff"}
OTHER_MEDIA_EXT = {".mp4", ".mov", ".wmv", ".avi", ".m4v", ".mp3", ".wav", ".m4a", ".wma"}
STORED_EXT = {".jpg", ".jpeg", ".png", ".gif"} | OTHER_MEDIA_EXT

_REL_TAG = re.compile(r"<Relationship\b[^>]*>", re.S)
_TARGET = re.compile(r'(\bTarget=")([^"]*)(")')


def _is_media(name: str) -> bool:
    return "/media/" in name or name.startswith("media/")


class _Picture:
    def __init__(self, name: str, data: bytes):
        self.name, self.data = name, data
        self.ext = posixpath.splitext(name)[1].lower()
        self.img: Image.Image | None = None
        self.alpha = False
        self.cache: dict[int, tuple[str, bytes]] = {}
        try:
            im = Image.open(io.BytesIO(data))
            im.load()
            if im.mode not in ("RGB", "RGBA", "L", "LA", "P"):
                im = im.convert("RGBA" if "A" in im.getbands() else "RGB")
            if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
                rgba = im.convert("RGBA")
                self.alpha = rgba.getchannel("A").getextrema()[0] < 255
                im = rgba if self.alpha else im.convert("RGB")
            self.img = im
        except Exception:
            self.img = None

    def variant(self, level: int) -> tuple[str, bytes]:
        """Returns (format, bytes); format is 'keep', 'jpeg' or 'png'."""
        if self.img is None:
            return "keep", self.data
        if level in self.cache:
            return self.cache[level]
        q, max_side = LEVELS[level]
        im = self.img
        w, h = im.size
        if max(w, h) > max_side:
            f = max_side / max(w, h)
            im = im.resize((max(1, int(w * f)), max(1, int(h * f))), Image.Resampling.LANCZOS)
        options: list[tuple[str, bytes]] = []
        if not self.alpha:
            b = io.BytesIO()
            im.convert("RGB").save(b, "JPEG", quality=q, optimize=True, progressive=True)
            options.append(("jpeg", b.getvalue()))
        if self.ext == ".png":
            b = io.BytesIO()
            pim = im
            if self.alpha and level >= 4:
                pim = im.convert("RGBA").quantize(256, method=Image.Quantize.FASTOCTREE)
            pim.save(b, "PNG", optimize=True)
            options.append(("png", b.getvalue()))
        fmt, data = min(options, key=lambda o: len(o[1])) if options else ("keep", self.data)
        if len(data) >= len(self.data):
            fmt, data = "keep", self.data
        if fmt == "jpeg" and self.ext in (".jpg", ".jpeg"):
            fmt = "same-jpeg"
        self.cache[level] = (fmt, data)
        return fmt, data


def _new_name(name: str, existing: set[str]) -> str:
    stem = posixpath.splitext(name)[0]
    cand = stem + ".jpeg"
    i = 2
    while cand in existing:
        cand = f"{stem}_{i}.jpeg"
        i += 1
    return cand


def _fix_rels(xml: str, rels_name: str, renames: dict[str, str]) -> str:
    # word/_rels/document.xml.rels -> base folder "word"
    base = posixpath.dirname(posixpath.dirname(rels_name))

    def fix_tag(m: re.Match) -> str:
        tag = m.group(0)
        if 'TargetMode="External"' in tag:
            return tag
        tm = _TARGET.search(tag)
        if not tm:
            return tag
        target = tm.group(2)
        resolved = posixpath.normpath(target.lstrip("/") if target.startswith("/")
                                      else posixpath.join(base, target))
        if resolved not in renames:
            return tag
        new_base = posixpath.basename(renames[resolved])
        new_target = posixpath.join(posixpath.dirname(target), new_base) if "/" in target else new_base
        return tag[:tm.start(2)] + new_target + tag[tm.end(2):]

    return _REL_TAG.sub(fix_tag, xml)


def _fix_content_types(xml: str, renames: dict[str, str]) -> str:
    if renames and not re.search(r'<Default\b[^>]*Extension="jpeg"', xml, re.I):
        xml = xml.replace("</Types>", '<Default Extension="jpeg" ContentType="image/jpeg"/></Types>', 1)
    for old, new in renames.items():
        xml = re.sub(r'(<Override\b[^>]*PartName=")/' + re.escape(old) + r'("[^>]*ContentType=")[^"]*(")',
                     lambda m: m.group(1) + "/" + new + m.group(2) + "image/jpeg" + m.group(3), xml)
    return xml


def _build(entries: list[tuple[zipfile.ZipInfo, bytes]], pics: dict[str, _Picture], level: int | None) -> bytes:
    names = {zi.filename for zi, _ in entries}
    replaced: dict[str, bytes] = {}
    renames: dict[str, str] = {}
    if level is not None:
        for name, pic in pics.items():
            fmt, data = pic.variant(level)
            if fmt == "keep":
                continue
            if fmt == "jpeg":
                new = _new_name(name, names)
                names.add(new)
                renames[name] = new
            replaced[name] = data

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as zf:
        for zi, data in entries:
            name = zi.filename
            if name.endswith(".rels") and renames:
                data = _fix_rels(data.decode("utf-8"), name, renames).encode("utf-8")
            elif name == "[Content_Types].xml" and renames:
                data = _fix_content_types(data.decode("utf-8"), renames).encode("utf-8")
            if name in replaced:
                data = replaced[name]
            name = renames.get(name, name)
            ext = posixpath.splitext(name)[1].lower()
            info = zipfile.ZipInfo(name, date_time=zi.date_time)
            info.external_attr = zi.external_attr
            if ext in STORED_EXT:
                info.compress_type = zipfile.ZIP_STORED
                zf.writestr(info, data)
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(info, data, compresslevel=9)
    return out.getvalue()


def compress_office(src: Path, settings: Settings, job: Job) -> Result:
    orig = src.stat().st_size
    target = settings.target_bytes(orig)
    notes: list[str] = []
    job.progress(0.05, "Reading")
    try:
        with zipfile.ZipFile(src) as zf:
            entries = [(zi, zf.read(zi)) for zi in zf.infolist() if not zi.is_dir()]
    except zipfile.BadZipFile:
        raise CompressionError("This file isn't a valid Office document (or it's password-protected).")
    names = {zi.filename for zi, _ in entries}
    if "[Content_Types].xml" not in names:
        raise CompressionError("This file isn't a valid Office document.")

    pics: dict[str, _Picture] = {}
    other_media = 0
    for zi, data in entries:
        ext = posixpath.splitext(zi.filename)[1].lower()
        if _is_media(zi.filename) and ext in PICTURE_EXT:
            pics[zi.filename] = _Picture(zi.filename, data)
        elif _is_media(zi.filename) and ext in OTHER_MEDIA_EXT:
            other_media += len(data)
    job.check()

    best = _build(entries, pics, None)
    if len(best) > target and pics:
        lo, hi = 0, len(LEVELS) - 1
        fit: int | None = None
        results: dict[int, bytes] = {}
        tries = 0
        while lo <= hi:
            mid = (lo + hi) // 2
            tries += 1
            job.progress(min(0.9, 0.1 + tries * 0.18), "Compressing pictures")
            results[mid] = _build(entries, pics, mid)
            job.check()
            if len(results[mid]) <= target:
                fit, hi = mid, mid - 1
            else:
                lo = mid + 1
        if fit is not None:
            best = results[fit]
        else:
            strongest = results.get(len(LEVELS) - 1) or _build(entries, pics, len(LEVELS) - 1)
            best = min(best, strongest, key=len)
            notes.append("Couldn't fully reach the target without ruining the pictures.")
    elif len(best) > target:
        notes.append("There are no pictures inside to compress.")
    if other_media > orig * 0.2:
        notes.append("Videos/audio embedded in the file were left as they are.")

    job.progress(0.95, "Saving")
    if len(best) >= orig:
        return Result(False, src, None, orig, 0, target,
                      "Already well compressed — couldn't make it smaller.", notes)
    out = output_path(src, settings)
    out.write_bytes(best)
    return Result(True, src, out, orig, len(best), target, "", notes)
