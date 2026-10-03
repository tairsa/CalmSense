#!/usr/bin/env bash
# Publish the current phone build as a GitHub release, where the app's
# Settings > App updates finds and installs it.
#
# Run it on the PC whose debug key signed the installed app
# (~/.android/debug.keystore). Android refuses an update signed with any other
# key, and the only way past that is uninstalling, which wipes the phone's data.
# Keep a backup of that file.
#
# Needs: the commit pushed, and a VERSIONING.md History row for this version
# naming the phone (its text becomes the release notes shown in the app).
set -euo pipefail
cd "$(dirname "$0")/.."
VER=$(sed -n 's/^calmsense.versionName=//p' android-app/gradle.properties | tr -d '\r')
NOTES=$(awk -F'|' -v v=" $VER " '$2 == v && $4 ~ /phone/ {print $5}' VERSIONING.md | sed 's/^ *//; s/ *$//')
[ -n "$NOTES" ] || { echo "No VERSIONING.md History row for $VER (phone)" >&2; exit 1; }
(cd android-app && ./gradlew -q :app:packageDebug --rerun :wear:packageDebug --rerun)
OUT=$(mktemp -d)
cp android-app/app/build/outputs/apk/debug/app-debug.apk "$OUT/calmsense-phone-$VER.apk"
# The watch cannot download (no INTERNET permission); attached so the matching
# build is on record and can be installed over adb.
cp android-app/wear/build/outputs/apk/debug/wear-debug.apk "$OUT/calmsense-watch-$VER.apk"
gh release create "v$VER" "$OUT"/*.apk --title "CalmSense $VER" --notes "$NOTES" --target "$(git rev-parse HEAD)"
