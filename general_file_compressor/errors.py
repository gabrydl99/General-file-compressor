"""Turns an error into likely causes and things to try, shown to the user."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Diagnosis:
    title: str
    causes: list[str]
    tips: list[str]


_RULES: list[tuple[tuple[str, ...], Diagnosis]] = [
    (("password",), Diagnosis(
        "The PDF is locked with a password",
        ["The file is protected, so its content can't be read or rewritten."],
        ["Open it with the password and save or print it to a new PDF without protection, then try again."])),
    (("isn't a valid office",), Diagnosis(
        "The Office file couldn't be opened",
        ["It may be an old .doc/.ppt/.xls file renamed to .docx/.pptx/.xlsx.",
         "It may be protected with a password.",
         "The file may be damaged or only partly downloaded."],
        ["Open it in Word, PowerPoint or Excel and use Save As to save a fresh .docx/.pptx/.xlsx.",
         "Remove the password, then try again."])),
    (("already well compressed",), Diagnosis(
        "The result wasn't smaller than the original",
        ["The file is already stored efficiently, so re-compressing it doesn't save space.",
         "The size you picked is very close to the current size."],
        ["Pick a lower percentage or a smaller size in MB.",
         "For videos, lower the resolution or the frame rate.",
         "For photos, try converting to WebP, which is usually much smaller."])),
    (("already smaller than the size",), Diagnosis(
        "The file is already below the size you picked",
        ["In 'Size in MB' mode, files that are already smaller are left alone."],
        ["Type a smaller size, or switch to Percentage."])),
    (("can only be compressed, not converted",), Diagnosis(
        "This type can't be converted",
        ["'Convert only' works for videos, photos and audio. PDFs and Office files only support compression."],
        ["Switch to Percentage or Size in MB to compress it."])),
    (("pick a", "it's already"), Diagnosis(
        "Nothing to convert",
        ["The format chosen for this type is 'Keep original', or it's the same format the file already has."],
        ["In the Format section, pick a different format for this kind of file."])),
    (("stopped responding",), Diagnosis(
        "The video engine got stuck",
        ["The file may have broken timestamps or an unusual structure.",
         "The PC may have run out of memory or be very busy."],
        ["Try again with fewer programs open.",
         "Use 'Convert only' to MP4 first, then compress the converted file."])),
    (("ffmpeg could not process", "no readable video or audio", "couldn't read the length"), Diagnosis(
        "The video/audio engine couldn't read or write this file",
        ["The file may be damaged or incomplete (for example an interrupted download or copy).",
         "It may use an unusual codec, or be copy-protected (DRM, like purchased movies).",
         "The disk may be full, or the output folder may not allow writing."],
        ["Check that the file plays correctly in a media player.",
         "Try 'Convert only' to MP4 first.",
         "Free up some disk space or choose another output folder."])),
    (("no audio track",), Diagnosis(
        "There's no sound in this file",
        ["The file has no audio track to compress or extract."],
        ["If it's a video, choose a video format instead of MP3/M4A."])),
    (("couldn't open this picture",), Diagnosis(
        "The picture couldn't be opened",
        ["The file may be damaged, or not really an image despite its extension.",
         "Some rare variants (unusual TIFF or HEIC flavours) aren't supported."],
        ["Open it in Photos or Paint and save it again as JPG or PNG, then retry."])),
    (("didn't allow writing", "permission"), Diagnosis(
        "Windows blocked writing the result",
        ["The output folder may be protected (for example inside Program Files).",
         "The file may be open in another program.",
         "An antivirus may be blocking the program."],
        ["Choose another output folder with 'Change folder' (for example Documents or the Desktop).",
         "Close programs that are using the file and try again."])),
    (("disk is full", "no space"), Diagnosis(
        "The disk is full",
        ["There isn't enough free space to write the compressed copy."],
        ["Free up some space, or choose an output folder on another drive."])),
    (("not enough memory",), Diagnosis(
        "Not enough memory",
        ["The file is very large (for example a huge photo or PDF) and the PC ran out of RAM."],
        ["Close other programs and try again, or process this file on its own."])),
    (("file not found",), Diagnosis(
        "The file isn't there anymore",
        ["It was moved, renamed or deleted after it was added to the list."],
        ["Remove it from the list and add it again from its new location."])),
    (("couldn't move the original",), Diagnosis(
        "The original couldn't be moved",
        ["The original file is open in another program, or its folder is read-only."],
        ["Close the program using it and move it into the Original folder yourself."])),
]

_FALLBACK = Diagnosis(
    "Something unexpected went wrong",
    ["The file may be damaged or in a variant the program doesn't handle.",
     "A temporary problem (disk, memory, antivirus) may have interrupted the work."],
    ["Try the file again on its own.",
     "If it's a video, try 'Convert only' to MP4 first.",
     "Check that the file opens correctly in its usual program."])


def diagnose(message: str) -> Diagnosis:
    low = message.lower()
    for keys, diag in _RULES:
        if any(k in low for k in keys):
            return diag
    return _FALLBACK
