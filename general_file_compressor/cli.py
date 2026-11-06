"""Command-line mode, handy for testing:  "FileCompressor.exe" --cli --percent 30 file1 file2"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .core import Job, Settings, human_size
from .engine import compress


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(prog="FileCompressor --cli")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--percent", type=int, help="final size as %% of the original")
    g.add_argument("--mb", type=float, help="final size per file in MB")
    g.add_argument("--convert", action="store_true", help="only change format")
    ap.add_argument("--resolution", default="auto")
    ap.add_argument("--fps", default="auto")
    ap.add_argument("--video-format", default="keep")
    ap.add_argument("--image-format", default="keep")
    ap.add_argument("--audio-format", default="keep")
    ap.add_argument("--out", help="output folder (default: the program folder)")
    ap.add_argument("--keep-originals", action="store_true", help="don't move originals into Original")
    ap.add_argument("--log", help="also write results to this file")
    ap.add_argument("files", nargs="+")
    a = ap.parse_args(argv)
    s = Settings(resolution=a.resolution, fps=a.fps, video_format=a.video_format,
                 image_format=a.image_format, audio_format=a.audio_format,
                 output_root=Path(a.out) if a.out else None, move_originals=not a.keep_originals)
    if a.convert:
        s.mode = "convert"
    elif a.mb is not None:
        s.mode, s.target_mb = "mb", a.mb
    else:
        s.mode, s.percent = "percent", a.percent or 30
    rc = 0
    log = open(a.log, "a", encoding="utf-8") if a.log else None

    def say(line: str) -> None:
        if sys.stdout:
            print(line)
            sys.stdout.flush()
        if log:
            log.write(line + "\n")
            log.flush()

    for f in a.files:
        r = compress(Path(f), s, Job())
        if r.ok:
            say(f"OK   {f}: {human_size(r.original_size)} -> {human_size(r.new_size)} "
                f"(target {human_size(r.target_size)}) -> {r.output}  {'; '.join(r.notes)}")
        else:
            rc = 1
            say(f"FAIL {f}: {r.message}  {'; '.join(r.notes)}")
    return rc
