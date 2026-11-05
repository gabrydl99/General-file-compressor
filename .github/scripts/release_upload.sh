#!/usr/bin/env bash
# Adds one file to the GitHub release of the tag being built (v1.2.0, …).
# The release stays a draft until both the Windows zip and the Android APK are
# on it, then it's published and marked as the latest one (that's what the apps'
# update check looks at).
# usage: release_upload.sh <file>
set -euo pipefail
FILE=$1
TAG=${GITHUB_REF_NAME:?run this on a tag build}
VER=${TAG#v}
NEEDED=("General-File-Compressor-Windows.zip" "General-File-Compressor-Android.apk")

if ! gh release view "$TAG" >/dev/null 2>&1; then
  gh release create "$TAG" --draft --verify-tag \
    --title "General File Compressor $VER" --notes-file RELEASE_NOTES.md
fi
gh release upload "$TAG" "$FILE" --clobber
echo "Added $(basename "$FILE") to $TAG"

have=$(gh release view "$TAG" --json assets --jq '.assets[].name')
for n in "${NEEDED[@]}"; do
  if ! grep -qxF "$n" <<<"$have"; then
    echo "Waiting for $n: $TAG stays a draft for now."
    exit 0
  fi
done
if [ "$(gh release view "$TAG" --json isDraft --jq .isDraft)" = "true" ]; then
  gh release edit "$TAG" --draft=false --latest
  echo "Published $TAG"
fi
