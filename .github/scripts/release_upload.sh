#!/usr/bin/env bash
# Adds one file to the GitHub release of the current version (__version__ in
# general_file_compressor/__init__.py, e.g. 1.2.0 -> release v1.2.0).
# The release stays a draft until both the Windows zip and the Android APK are
# on it, then it's published as the latest one (the apps' update check reads it)
# and GitHub creates its tag. If that version is already published, nothing happens:
# raise __version__ to make a new release.
# usage: release_upload.sh <file>
set -euo pipefail
FILE=$1
VER=$(grep -oP '__version__\s*=\s*"\K[^"]+' general_file_compressor/__init__.py)
TAG="v$VER"
NEEDED=("FileCompressor-Windows.zip" "FileCompressor-Android.apk")

state=$(gh release view "$TAG" --json isDraft --jq .isDraft 2>/dev/null || echo missing)
case "$state" in
  false)
    echo "$TAG is already published: nothing to do (raise __version__ for a new release)."
    exit 0 ;;
  missing)
    gh release create "$TAG" --draft --target "$GITHUB_SHA" \
      --title "FileCompressor $VER" --notes-file RELEASE_NOTES.md ;;
  *)
    gh release edit "$TAG" --target "$GITHUB_SHA" --notes-file RELEASE_NOTES.md >/dev/null ;;
esac
gh release upload "$TAG" "$FILE" --clobber
echo "Added $(basename "$FILE") to $TAG"

have=$(gh release view "$TAG" --json assets --jq '.assets[].name')
for n in "${NEEDED[@]}"; do
  if ! grep -qxF "$n" <<<"$have"; then
    echo "Waiting for $n: $TAG stays a draft for now."
    exit 0
  fi
done
gh release edit "$TAG" --draft=false --latest
echo "Published $TAG"
