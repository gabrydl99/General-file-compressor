#!/usr/bin/env bash
# Installs the debug APK on the emulator, compresses the sample files in three
# scenarios through the app's debug test hooks, and saves reports, screenshots,
# the results and the log in <out>.
# usage: run_emulator_test.sh <apk> <samples_dir> <out_dir>
set -uo pipefail
APK=$1; SAMPLES=$2; OUT=$3
PKG=com.gabrydl99.gfc
ACT=$PKG/.ui.MainActivity
IN=/sdcard/Download/gfc-in
RES="/sdcard/Download/General File Compressor"
mkdir -p "$OUT/shots" "$OUT/results"
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; }
scroll() {  # swipe up by about two thirds of the screen
  local wh w h; wh=$(adb shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+'); w=${wh%x*}; h=${wh#*x}
  adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 5)) 400
}

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
adb shell cmd uimode night no

push_samples() {
  adb shell rm -rf "$IN"
  adb shell mkdir -p "$IN"
  for f in "$SAMPLES"/*; do adb push "$f" "$IN/" >/dev/null; done
}

adb shell am start -W -n $ACT
sleep 5; shot 01-empty

run_case() {  # run_case <name> <settings json> <timeout s>
  local name=$1 settings=$2 limit=$3 report=/sdcard/Download/gfc-report-$1.json
  push_samples
  adb shell rm -f "$report"
  adb shell am start -n $ACT --ez gfc_test_clear true --es gfc_test_settings "'$settings'" \
    --es gfc_test_dir $IN --es gfc_test_report "$report" --ez gfc_test_start true
  sleep 12; shot "$name-1-running"
  local t=0 n=2
  until adb shell test -f "$report"; do
    sleep 10; t=$((t + 10))
    if [ $((t % 90)) -eq 0 ]; then shot "$name-$n-running"; n=$((n + 1)); fi
    if [ $t -ge "$limit" ]; then echo "case $name timed out"; break; fi
  done
  sleep 2
  adb pull "$report" "$OUT/results/report-$name.json" >/dev/null || echo "no report for $name"
  shot "$name-9-done"
  scroll; sleep 1; shot "$name-9-done-scrolled"
  adb shell ls -lR "'$RES'" > "$OUT/results/listing-$name.txt" 2>&1
  mkdir -p "$OUT/results/$name"
  adb pull "$RES/Compressed" "$OUT/results/$name/" >/dev/null 2>&1
  adb shell rm -rf "'$RES'"
}

run_case percent '{"mode":"PERCENT","percent":30,"moveOriginals":true}' 1500
run_case mb '{"mode":"MB","targetMb":2,"moveOriginals":false,"speed":"fast"}' 1500
run_case convert '{"mode":"CONVERT","videoFormat":"mkv","imageFormat":"webp","audioFormat":"opus","moveOriginals":false}' 1200
run_case mixed '{"mode":"PERCENT","percent":25,"videoFormat":"webm","imageFormat":"png","audioFormat":"ogg","speed":"fast","pdfAsPictures":true,"moveOriginals":false}' 2400

# Update flow: a fake newer release whose APK is this same app
adb push "$APK" /sdcard/Download/gfc-update.apk >/dev/null
cat > "$OUT/fake-release.json" <<JSON
{"tag_name": "v99.0.0", "html_url": "https://github.com/gabrydl99/General-file-compressor/releases",
 "body": "**Test release** used by the emulator test.",
 "assets": [{"name": "General-File-Compressor-Android.apk", "size": $(stat -c %s "$APK"),
             "browser_download_url": "file:///sdcard/Download/gfc-update.apk"}]}
JSON
adb push "$OUT/fake-release.json" /sdcard/Download/gfc-fake-release.json >/dev/null
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow
adb shell am start -n $ACT --ez gfc_test_clear true --es gfc_test_update_json /sdcard/Download/gfc-fake-release.json
sleep 5; shot 80-update-available
adb shell am start -n $ACT --es gfc_test_update_json /sdcard/Download/gfc-fake-release.json --ez gfc_test_update_now true
sleep 3; shot 81-update-downloading
sleep 12; shot 82-update-installer
adb shell input keyevent KEYCODE_BACK; sleep 2
adb logcat -d -s GfcUpdate > "$OUT/results/update-log.txt" 2>&1

# The settings screen, light and dark, scrolled through
adb shell am start -n $ACT --ez gfc_test_clear true --es gfc_test_settings "'{\"mode\":\"PERCENT\",\"percent\":30}'"
sleep 3; shot 50-light-top
for i in 1 2 3 4; do scroll; sleep 1; shot 51-light-scroll-$i; done
adb shell cmd uimode night yes; sleep 3
adb shell am force-stop $PKG; adb shell am start -W -n $ACT; sleep 4; shot 60-dark-top
for i in 1 2 3 4; do scroll; sleep 1; shot 61-dark-scroll-$i; done
adb shell cmd uimode night no
# Wide screen (tablet / landscape)
adb shell wm size 2560x1600; adb shell wm density 320; sleep 3
adb shell am force-stop $PKG; adb shell am start -W -n $ACT; sleep 4; shot 70-tablet
adb shell wm size reset; adb shell wm density reset

# Check the results on the computer side
{
  for f in $(find "$OUT/results" -type f \( -name '*_compressed*' \) | sort); do
    echo "== $f ($(stat -c %s "$f") bytes)"
    ffprobe -v error -show_entries format=format_name,duration:stream=codec_name,width,height \
      -of compact "$f" 2>&1 | head -6
  done
} > "$OUT/results/verify.txt" 2>&1
adb logcat -d > "$OUT/logcat.txt" 2>&1
grep -E "AndroidRuntime|FATAL|gfc" "$OUT/logcat.txt" | head -200 > "$OUT/logcat-errors.txt" || true
ls -R "$OUT" | head -80
exit 0
