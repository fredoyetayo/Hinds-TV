#!/bin/bash
# Install Hinds TV on the Fire TV over adb, with no presses on the TV.
#   scripts/deploy-tv.sh            # the latest GitHub Release
#   scripts/deploy-tv.sh my.apk     # a local build (installed with -d, since local builds are versionCode 1)
# The TV's address comes from $HINDS_TV, or a one-line `.tv` file in the repo root (git-ignored),
# e.g. 192.168.1.50. Run it from a computer on the same Wi-Fi as the TV.
set -euo pipefail
cd "$(dirname "$0")/.."

tv="${HINDS_TV:-$(cat .tv 2>/dev/null || true)}"
if [ -z "$tv" ]; then
  echo "Set HINDS_TV=<fire-tv-ip> or put the IP in .tv (Settings → My Fire TV → About → Network)." >&2
  exit 1
fi
[[ "$tv" == *:* ]] || tv="$tv:5555"

flags=(-r)
if [ $# -ge 1 ]; then
  apk="$1"
  flags+=(-d)
else
  apk="$(mktemp -d)/hinds-tv.apk"
  echo "Downloading the latest release…"
  curl -fL --progress-bar -o "$apk" https://github.com/fredoyetayo/Hinds-TV/releases/latest/download/hinds-tv.apk
fi

adb connect "$tv" >/dev/null
adb -s "$tv" wait-for-device
adb -s "$tv" install "${flags[@]}" "$apk"
adb -s "$tv" shell monkey -p com.hindstv.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "Installed $(adb -s "$tv" shell dumpsys package com.hindstv.app | grep -m1 versionName | tr -d ' ')"
