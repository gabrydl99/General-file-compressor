**Windows** — download `FileCompressor-Windows.zip`, extract it and open `FileCompressor.exe`. If SmartScreen warns, click **More info → Run anyway** (the exe isn't signed). Keep the `_internal` folder next to the exe. To update, extract the new zip over the old folder.

**Android** — download `FileCompressor-Android.apk` on the phone and open it. Allow installing from this source when asked; if Play Protect warns, choose **Install anyway** (the app isn't on the Play Store). On first start, tap **Allow** on the "Allow access to all files" card.

Both apps look for a newer release at start-up and offer to update (on Android the app downloads and installs it).

### New in 1.3.0
- The app is now called **FileCompressor** (exe, Android app and download files). On Windows, extract the new zip into a new folder and delete the old "General File Compressor" folder. On Android it installs over the old app; new files go to `Download/FileCompressor`.
- Android: **Discord** preset (about 19 MB per file, under Discord's 20 MB free limit), like the Discord chip on Windows.

### New in 1.2.0
- **Android app** with the same engine as on Windows (FFmpeg inside): videos (MP4, MKV, MOV, WebM, or audio only), photos (JPG, PNG, WebP, iPhone HEIC), audio (MP3, M4A, OGG, Opus), PDFs and Word/PowerPoint/Excel; presets for WhatsApp, Telegram, Instagram and Email; Share → FileCompressor; keeps working with the screen off.
- Update check at start-up on Windows and Android.
- Since 1.1.0 (Windows): retry button next to "Why did it fail?", Discord size chip.
- Both apps are built and tested by GitHub Actions.
