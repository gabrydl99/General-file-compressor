# FileCompressor

A Windows and Android app that compresses and converts videos, photos, PDFs, audio and Office files — **completely offline**. Drop files in, pick how small they should get (or which format they should become), press Compress. Every file gets its own progress bar.

## Download

Get the latest version from **[Releases](https://github.com/gabrydl99/General-file-compressor/releases/latest)**:

- **Windows**: `FileCompressor-Windows.zip`: extract it and open `FileCompressor.exe`.
- **Android**: `FileCompressor-Android.apk`: open it on the phone to install (not on the Play Store). The Android app's code is in [`android/`](android/).

Both apps check for a newer release at start-up.

## Making a release

1. Raise `__version__` in `general_file_compressor/__init__.py` (Android uses the same number) and update `RELEASE_NOTES.md`.
2. Push to `main`. When the **Windows exe** and **Android app** workflows pass (the Android one also runs the app on an emulator), GitHub publishes release `v<version>` with both files. Pushes that don't change the version don't make a release.

## Where the files go

By default everything is saved next to the program (the folder that contains `FileCompressor.exe`). Use **Change folder** to pick another place; **Use default** goes back to the program folder.

```
<output folder>/
  Compressed/   name_compressed.mp4, photo_compressed.jpg, ...
  Original/     the original files, moved here after they're done
```

Originals are moved only after their compressed copy has been written successfully. Turn off **Move originals to the Original folder** to leave them where they are.

## Three modes

- **Percentage** — the result is about that percentage of the original. At 30%, a 1 GB video becomes about 300 MB, a 2 GB video about 600 MB.
- **Size in MB** — every file is brought to about that size (handy for email or chat limits). Files already smaller are skipped.
- **Convert only** — change the format without aiming for a size. When the container allows it (for example MKV → MP4) the video isn't re-encoded at all, so no quality is lost and it takes seconds.

## Formats

| Type | Input | Output |
|---|---|---|
| Video | mp4, mov, mkv, avi, wmv, webm, m4v, mts, 3gp, mpg… | MP4, MKV, MOV (H.264), WebM (VP9), or audio only as MP3/M4A |
| Photos | jpg, png, webp, heic (iPhone), bmp, tiff | JPG, PNG, WebP |
| Audio | mp3, m4a, aac, wav, flac, ogg, opus, wma, aiff… | MP3, M4A, OGG, Opus |
| PDF | pdf | PDF — pictures and scans inside are re-compressed |
| Office | docx, pptx, xlsx (and macro versions) | same format — pictures inside are re-compressed |

Videos use two-pass encoding at the exact bitrate the target needs, so they usually land within a few percent of it. Resolution and frame rate can be lowered automatically (Auto) or set by hand.

## When something fails

A failed file turns red. Click **Why did it fail?** to see what happened, the likely causes (damaged file, password-protected PDF, protected output folder, full disk…) and what to try. **Abort** stops the work at any time: the file in progress is discarded and its original isn't touched.

## Download and run

1. Download `FileCompressor.zip` and extract it.
2. Open the extracted folder and double-click **FileCompressor.exe**.
3. The exe isn't signed, so Windows SmartScreen may warn the first time: click **More info → Run anyway**.

Keep the `_internal` folder next to the exe — the app needs it.

## Build it yourself (Windows)

Install [Python 3.12](https://www.python.org/downloads/) (64-bit), then double-click `build_windows.bat`. The result is in `dist\FileCompressor\`.

To run from source instead:

```bat
py -3.12 -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt imageio-ffmpeg
pip install --no-deps PyMuPDF
python main.py
```

FFmpeg is looked up in `packaging\ffmpeg\ffmpeg.exe` or on the PATH (`build_windows.bat` puts it there).

Command-line mode, useful for testing:

```bat
"FileCompressor.exe" --cli --percent 30 video.mp4 photo.jpg
"FileCompressor.exe" --cli --mb 10 --resolution 720 lesson.mov
"FileCompressor.exe" --cli --convert --video-format mp4 --out D:\Converted clip.mkv
```

## Project layout

```
main.py                         entry point (window, or --cli)
general_file_compressor/
  engine.py                     picks the engine, moves originals
  media.py                      video + audio (FFmpeg)
  image.py                      photos (Pillow, pillow-heif)
  pdf.py                        PDF (PyMuPDF)
  office.py                     docx / pptx / xlsx
  errors.py                     likely causes and tips for each error
  window.py, theme.py           the interface (PySide6 / Qt)
  assets/fonts/                 Space Grotesk and Manrope (SIL Open Font License)
packaging/                      PyInstaller spec, icon, version info
build_windows.bat               one-click Windows build
```

## Credits and licenses

Built with [PySide6](https://doc.qt.io/qtforpython/) (LGPL), [FFmpeg](https://ffmpeg.org/) (GPL build, from imageio-ffmpeg), [PyMuPDF](https://pymupdf.readthedocs.io/) (AGPL-3.0), [Pillow](https://python-pillow.org/), [pillow-heif](https://github.com/bigcat88/pillow_heif), and the [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [Manrope](https://github.com/sharanda/manrope) fonts (OFL). Because PyMuPDF is AGPL-3.0, this project is distributed under the AGPL-3.0 as well.
