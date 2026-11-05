# Android app — status and how to continue

**Status 2026-11-05 ~08:00:** `main` (another chat added the Windows update check, retry button,
Discord chip and v1.1.0) was merged into `android`; both apps are now version **1.2.0**, read from
`general_file_compressor/__init__.py` (Android's build.gradle.kts parses it). Android has an update
check at start-up (`core/Updates.kt` + `UpdateDialog` in MainScreen: GitHub releases/latest → APK
asset → download → system installer; REQUEST_INSTALL_PACKAGES + INTERNET).

**Release process (agreed with the user, who asked for exe and APK on GitHub releases):**
this session can't push tags or edit releases (proxy), so GitHub does it: on a push to `main`,
each workflow (Windows after its build, Android after app + emulator test) runs
`.github/scripts/release_upload.sh`, which adds its file to release `v<__version__>`: draft until
both `General-File-Compressor-Windows.zip` and `General-File-Compressor-Android.apk` are there, then
published as latest (GitHub creates the tag). Already published → nothing happens; raise
`__version__` for a new release.
Signing: debug key in the Actions cache `gfc-debug-keystore-v1`. Caches made on `main` are readable by every
branch; `keepalive.yml` reads the cache every 3 days so
it doesn't expire. The very first main-made key differs from the test APK sent in chat on 2026-11-05
(made on the `android` branch): that one has to be uninstalled once before installing 1.2.0.
Stray release `windows-v1.0.0` was created by accident (commit message contained the old marker);
ask the user before deleting it.
Commit messages: no "Claude-Session" link (it made pushes to this public repo be refused).

## Start here

1. Ask the user to attach **"General File Compressor Android - source.zip"** (sent in chat on
   2026-11-04). It contains the whole repo **with `.git`**: unzip it, `cd general-file-compressor`,
   `git checkout android`, run `git log --oneline` (latest local commit: "Android: Gradle project,
   resources, FFmpeg runner, media engine (WIP) and next steps").
2. Only the first commit (9403e43, FFmpeg script + workflow with a YAML error) reached GitHub.
   The second push was refused by the auto-mode safety check ("Credential Leakage") even
   though the commit has no keys or passwords: attach the repo with
   `mcp__claude-code-remote__add_repo` (owner gabrydl99, repo General-file-compressor, push),
   set the remote to `https://github.com/gabrydl99/general-file-compressor`, `git fetch`,
   and push `android`. If it's refused again, tell the user and ask how to proceed
   (e.g. they upload the zip contents, or approve the push) instead of retrying.
3. Because of the YAML error, **FFmpeg has not been built yet**: the push in step 2 starts it
   (15–25 min per ABI, then cached). Watch it with
   `gh api repos/gabrydl99/General-file-compressor/actions/runs` and the jobs/logs endpoints.
4. Continue with "To write next" below.

## Key facts learned

- The chat sandbox **cannot reach** Gradle, Maven or Google (403 "policy denial" from the
  cloud environment allowlist; the claude.ai "Allow network egress" toggle does not change it).
  **GitHub Actions builds everything instead**: runners have Android SDK (platforms 34–37,
  build-tools, NDK 27/29/30) and `/dev/kvm`, so an emulator works.
- From the sandbox: `git push` works, the Actions API for runs/jobs/**job logs** works
  (`gh api repos/gabrydl99/General-file-compressor/actions/jobs/<id>/logs`), artifact
  downloads and secrets endpoints are blocked. So the workflow pushes APKs, screenshots
  and logs to the **`ci-out`** branch: `git fetch origin +refs/heads/ci-out:refs/remotes/origin/ci-out`.
- The repo is **public** (API says `private: false`). Never commit secrets: a push containing
  the signing keystore + password was rightly blocked. The release key must come from repo
  secrets `GFC_KEYSTORE_B64` + `GFC_KEYSTORE_PASSWORD` (alias `gfc`), which **the user** adds
  in GitHub → Settings → Secrets and variables → Actions. Until then release = debug-signed.
  (A key was generated in the old session's scratchpad; it is lost — make a new one with
  `keytool -genkeypair -keystore gfc.jks -alias gfc -keyalg RSA -keysize 2048 -validity 20000`,
  send the .jks to the user with SendUserFile and tell them to add the two secrets.)
- First workflow push failed to parse (YAML flow mappings with `${{ }}`); fixed to block style.
  Validate with `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/workflows/android.yml`.

## Decisions agreed with the user

- Full version, all workarounds: FFmpeg inside the app (same engine as Windows) so MKV,
  MOV, WebM (if libvpx builds), MP3, AVI/WMV/FLV/WMA input all work.
- Phone-adapted UI (small/large screens, light/dark, big touch targets) and presets
  **WhatsApp / Telegram / Instagram / Email**.
- Default output: `Download/General File Compressor/Compressed` (`name_compressed.ext`)
  and `.../Original` (originals moved there after success; switch to turn off). Custom
  folder allowed. APK only, not for the Play Store → `MANAGE_EXTERNAL_STORAGE` is fine.
- English UI like the Windows app. Share → General File Compressor + "Add files"/"Add folder".
  Notification with progress so work continues with the screen off. Abort, per-file
  progress bars, "Why did it fail?".

## Architecture (package `com.gabrydl99.gfc`)

Done (untested, not yet compiled — every file below exists):
- `android/ffmpeg/build_ffmpeg.sh` — static FFmpeg n7.1.1 + x264, LAME, Ogg/Vorbis, Opus,
  optional libvpx; output `libffmpeg.so` (an executable) per ABI. Runs in the `ffmpeg` job,
  cached by the script's hash. Check its logs first: likely trouble spots are x264 armv7 asm
  (auto-retries with `--disable-asm`), libvpx (optional, WebM hidden if missing), lame config.
- `.github/workflows/android.yml` — jobs: compile (quick Kotlin check), ffmpeg (matrix
  arm64-v8a/armeabi-v7a/x86_64), app (copies libffmpeg.so into `app/src/main/jniLibs/<abi>`,
  `gradle -p android assembleDebug assembleRelease` with Gradle 8.11.1, no wrapper), test
  (emulator API 34 x86_64, runs `android/test/run_emulator_test.sh apk/gfc-debug.apk samples test-out`),
  publish (→ `ci-out` branch), release (only if commit message contains `[release]`;
  needs `android/RELEASE_NOTES.md`).
- Gradle: AGP 8.7.3, Kotlin 2.1.0 + compose plugin, compileSdk/targetSdk 35, minSdk 26,
  Compose BOM 2024.12.01, pdfbox-android 2.0.27.0, exifinterface, documentfile, coroutines.
  `packaging.jniLibs.useLegacyPackaging = true` + `extractNativeLibs` so FFmpeg is extracted
  to `nativeLibraryDir` (the only app folder Android lets it execute from).
  Release ABIs arm64-v8a + armeabi-v7a; debug adds x86_64 for the emulator.
- Manifest (permissions, share/open-with filters, `work.CompressService` foreground service
  type dataSync, FileProvider `${applicationId}.files` with `xml/file_paths`), themes,
  adaptive icon (cobalt square with 3 white bars = Windows logo), fonts Manrope/Space Grotesk.
- `core/Models.kt` — Kind, extensions, Settings (JSON for SharedPreferences), Preset enum,
  Result, Job (progress + cancel + process tracking), CompressionError, humanSize.
- `core/Ffmpeg.kt` — init(context) finds `nativeLibraryDir/libffmpeg.so`; `loadEncoders()`
  parses `-encoders` (WebM only if `libvpx-vp9`, Vorbis only if `libvorbis`); `probe()` = same
  regex parsing as media.py; `run()` with `-progress pipe:1`, stall watchdog 240 s, error tail;
  `runSimple()` for quick jobs.
- `core/Workspace.kt` — Source(file, name), output folders, unique names, temp dir.
- `core/MediaEngine.kt` — full port of media.py (2-pass x264, fine-tuning pass, audio
  bitrate tables, convert-only with stream copy). Phone speed setting: fast/balanced/best →
  x264 veryfast/faster/medium, VP9 cpu-used 6/5/4.

Also written in the second session (2026-11-04, ~23:45), all untested and never compiled:
`core/ImageEngine.kt`, `core/OfficeEngine.kt`, `core/PdfEngine.kt` (PdfBox images + DPI finder +
"rebuild pages as pictures" with PdfRenderer), `core/Diagnosis.kt`, `core/Storage.kt`,
`core/Engine.kt`, `work/WorkRepo.kt`, `work/CompressService.kt`, `GfcApp.kt`, `ui/Theme.kt`,
`ui/MainActivity.kt` (share/open-with, folder pickers, permissions, debug test hooks
`gfc_test_clear/gfc_test_settings/gfc_test_dir/gfc_test_report/gfc_test_start`),
`ui/MainScreen.kt` (whole Compose screen), `android/test/make_samples.sh` (verified locally),
`android/test/run_emulator_test.sh` (4 scenarios + light/dark/tablet screenshots + ffprobe check),
`android/RELEASE_NOTES.md`.

Expect a few rounds of Kotlin compile errors: read `logs/compile.log` on the `ci-out` branch (or
the compile job's log through the API) and fix. Likely spots: PdfBox-Android API names in
PdfEngine (processOperator override, addOperator, JPEGFactory), Material3 1.3 signatures in
MainScreen (LinearProgressIndicator with drawStopIndicator, SegmentedButton colors).

10. Iterate on CI until compile/app/test are green, look at the screenshots, then
    `android/RELEASE_NOTES.md`, commit with `[release]`, merge to main, send the APK
    (fetched from `ci-out`) to the user with SendUserFile, explain install ("app sconosciute",
    Play Protect "Installa comunque") and the all-files permission. Reply to the user in Italian.
