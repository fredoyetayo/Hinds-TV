#!/bin/bash
# Claude Code cloud sessions: install the Android SDK so `./gradlew` can build and test.
# Idempotent; the container snapshot is cached after the first run, so later sessions are fast.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK=/opt/android-sdk
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
PACKAGES=("platform-tools" "platforms;android-37.0" "build-tools;36.0.0")

if [ ! -x "$SDKMANAGER" ]; then
  echo "Installing Android command-line tools into $SDK" >&2
  mkdir -p "$SDK/cmdline-tools"
  zip_name=$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml \
    | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | sort -t- -k3 -n | tail -1)
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/cmdline-tools.zip" "https://dl.google.com/android/repository/$zip_name"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

missing=()
for pkg in "${PACKAGES[@]}"; do
  [ -d "$SDK/${pkg//;//}" ] || missing+=("$pkg")
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "Installing Android SDK packages: ${missing[*]}" >&2
  yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --sdk_root="$SDK" "${missing[@]}" >&2
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=$SDK" >> "$CLAUDE_ENV_FILE"
  echo "export ANDROID_SDK_ROOT=$SDK" >> "$CLAUDE_ENV_FILE"
fi
# Gradle also reads sdk.dir from local.properties (git-ignored).
echo "sdk.dir=$SDK" > "${CLAUDE_PROJECT_DIR:-$(pwd)}/local.properties"

# Warm the Gradle wrapper and dependency cache so the first build in the session is quick.
cd "${CLAUDE_PROJECT_DIR:-$(pwd)}"
ANDROID_HOME=$SDK ./gradlew --quiet help >/dev/null 2>&1 || echo "Gradle warm-up failed; builds will retry downloads" >&2
