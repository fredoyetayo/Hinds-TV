#!/usr/bin/env bash
#
# Builds libffmpegJNI.so, the native half of AndroidX Media3's FFmpeg audio
# decoder extension (libraries/decoder_ffmpeg), for armeabi-v7a only, and copies
# it into ffmpeg-audio/src/main/jniLibs/armeabi-v7a/.
#
# FFmpeg is configured as LGPL-2.1-or-later (no --enable-gpl, no
# --enable-nonfree) with only the audio decoders that Media3's
# FfmpegLibrary.getCodecName() maps for MPEG audio (incl. MP2), AAC, AC-3,
# E-AC-3 (incl. JOC), DTS and TrueHD.
#
# Usage:
#   scripts/build-ffmpeg.sh [WORK_DIR]
#
#   WORK_DIR  where downloads and build trees go (default: <repo>/build/ffmpeg-work).
#
# Environment (all optional):
#   ANDROID_NDK_ROOT / ANDROID_NDK_HOME / NDK_PATH
#            An existing NDK. It is used only if its Pkg.Revision equals
#            NDK_REVISION below; otherwise the pinned NDK is downloaded.
#   JOBS     Parallel make/ninja jobs (default: number of CPUs).
#   FORCE=1  Rebuild FFmpeg even if a matching build is already present.
#
# The script is idempotent: verified downloads and checkouts are reused, and the
# FFmpeg build is skipped when its inputs have not changed. It needs bash, curl,
# git, unzip, make, cmake (>= 3.21) and a POSIX userland; ninja is used if
# present. It runs on Linux x86_64 hosts (including GitHub Actions ubuntu
# runners); on other hosts point NDK_PATH at a matching NDK.

set -euo pipefail

# ---------------------------------------------------------------------------
# Pinned inputs
# ---------------------------------------------------------------------------
NDK_RELEASE="r27c"
NDK_REVISION="27.2.12479018"
NDK_ZIP_URL="https://dl.google.com/android/repository/android-ndk-${NDK_RELEASE}-linux.zip"
NDK_ZIP_SHA256="59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc"

MEDIA3_REPO="https://github.com/androidx/media.git"
MEDIA3_TAG="1.11.1"
MEDIA3_COMMIT="8c6678b657ede1e7883fc164ef73ed483c7796c3"

# Media3 1.11.1's decoder_ffmpeg README recommends FFmpeg 6.0; this is the head
# of the maintained release/6.0 branch (reports itself as 6.0.2 + backports).
FFMPEG_REPO="https://github.com/FFmpeg/FFmpeg.git"
FFMPEG_BRANCH="release/6.0"
FFMPEG_COMMIT="b37bf81a7a9782438e4fe06a249329e279694494"

ABI="armeabi-v7a"
# Native API level. Must not exceed the app's minSdk (28).
ANDROID_API=23

# FFmpeg decoder names used by Media3 1.11.1 FfmpegLibrary.getCodecName():
#   AUDIO_MPEG, AUDIO_MPEG_L1, AUDIO_MPEG_L2 -> "mp3" (FFmpeg's mp3 decoder
#   decodes Layer I/II/III based on the frame header; mp1/mp2 are enabled too,
#   they share the same object file and cost nothing)
#   AUDIO_AAC -> "aac"; AUDIO_AC3 -> "ac3"; AUDIO_E_AC3(_JOC) -> "eac3";
#   AUDIO_DTS / DTS_EXPRESS / DTS_HD -> "dca"; AUDIO_TRUEHD -> "truehd"
ENABLED_DECODERS=(mp1 mp2 mp3 aac ac3 eac3 dca truehd)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log() { printf '\n==> %s\n' "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

ndk_revision() {
  sed -n 's/^Pkg\.Revision *= *//p' "$1/source.properties" 2>/dev/null | tr -d '\r'
}

# git_checkout DIR URL REF EXPECTED_COMMIT [SPARSE_PATH...]
# Shallow-fetches REF (a tag ref or a commit SHA) into DIR, verifies that it
# resolves to EXPECTED_COMMIT and checks it out. Reuses DIR when HEAD already
# matches.
git_checkout() {
  local dir="$1" url="$2" ref="$3" commit="$4"
  shift 4
  if [[ -d "$dir/.git" ]] &&
    [[ "$(git -C "$dir" rev-parse -q --verify 'HEAD^{commit}' 2>/dev/null || true)" == "$commit" ]] &&
    [[ -z "$(git -C "$dir" status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
    echo "Reusing $dir @ $commit"
    return
  fi
  rm -rf "$dir"
  git init -q "$dir"
  git -C "$dir" remote add origin "$url"
  local fetch_args=(-q --depth 1 --no-tags)
  if (($# > 0)); then
    # Sparse + partial (blobless) checkout: only the listed paths are downloaded.
    git -C "$dir" config core.sparseCheckout true
    git -C "$dir" config remote.origin.promisor true
    git -C "$dir" config remote.origin.partialclonefilter blob:none
    printf '%s\n' "$@" >"$dir/.git/info/sparse-checkout"
    fetch_args+=(--filter=blob:none)
  fi
  local attempt
  for attempt in 1 2 3; do
    if git -C "$dir" fetch "${fetch_args[@]}" origin "$ref"; then
      break
    fi
    ((attempt == 3)) && die "git fetch of $ref from $url failed"
    sleep $((attempt * 5))
  done
  local got
  got="$(git -C "$dir" rev-parse 'FETCH_HEAD^{commit}')"
  [[ "$got" == "$commit" ]] || die "$url $ref resolved to $got, expected $commit"
  git -C "$dir" -c advice.detachedHead=false checkout -q "$commit"
}

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${1:-$REPO_ROOT/build/ffmpeg-work}"
mkdir -p "$WORK_DIR"
WORK_DIR="$(cd "$WORK_DIR" && pwd)"
case "$WORK_DIR" in
  *[[:space:]]*) die "WORK_DIR must not contain whitespace: $WORK_DIR" ;;
esac

MODULE_DIR="$REPO_ROOT/ffmpeg-audio"
OUT_DIR="$MODULE_DIR/src/main/jniLibs/$ABI"
DL_DIR="$WORK_DIR/downloads"
SRC_DIR="$WORK_DIR/src"
MEDIA3_DIR="$SRC_DIR/media3"
FFMPEG_SRC="$SRC_DIR/ffmpeg"
FFMPEG_BUILD="$WORK_DIR/build/ffmpeg-$ABI"
JNI_STAGE="$WORK_DIR/build/jni-src"          # CMake source dir, laid out like Media3's src/main/jni
JNI_BUILD="$WORK_DIR/build/jni-$ABI"
JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

mkdir -p "$DL_DIR" "$SRC_DIR" "$WORK_DIR/build"

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *) die "Unsupported host $(uname -s)" ;;
esac

for tool in curl git unzip make cmake; do
  command -v "$tool" >/dev/null 2>&1 || die "'$tool' is required but not installed"
done

# ---------------------------------------------------------------------------
# 1. Android NDK
# ---------------------------------------------------------------------------
log "Android NDK $NDK_RELEASE ($NDK_REVISION)"
NDK=""
for candidate in "${NDK_PATH:-}" "${ANDROID_NDK_ROOT:-}" "${ANDROID_NDK_HOME:-}"; do
  if [[ -n "$candidate" && "$(ndk_revision "$candidate")" == "$NDK_REVISION" ]]; then
    NDK="$candidate"
    break
  fi
done
if [[ -z "$NDK" ]]; then
  NDK="$WORK_DIR/android-ndk-$NDK_RELEASE"
  if [[ "$(ndk_revision "$NDK")" != "$NDK_REVISION" ]]; then
    [[ "$HOST_TAG" == "linux-x86_64" ]] ||
      die "Automatic NDK download is only supported on Linux; set NDK_PATH to NDK $NDK_REVISION"
    zip="$DL_DIR/android-ndk-$NDK_RELEASE-linux.zip"
    if [[ ! -f "$zip" || "$(sha256_of "$zip")" != "$NDK_ZIP_SHA256" ]]; then
      echo "Downloading $NDK_ZIP_URL"
      curl -fL --retry 3 --retry-delay 5 -o "$zip.part" "$NDK_ZIP_URL"
      mv "$zip.part" "$zip"
    fi
    [[ "$(sha256_of "$zip")" == "$NDK_ZIP_SHA256" ]] || die "NDK zip checksum mismatch"
    rm -rf "$NDK" "$WORK_DIR/ndk-unzip"
    mkdir -p "$WORK_DIR/ndk-unzip"
    unzip -q "$zip" -d "$WORK_DIR/ndk-unzip"
    mv "$WORK_DIR/ndk-unzip/android-ndk-$NDK_RELEASE" "$NDK"
    rmdir "$WORK_DIR/ndk-unzip"
  fi
fi
NDK="$(cd "$NDK" && pwd)"
TOOLCHAIN_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -x "$TOOLCHAIN_BIN/armv7a-linux-androideabi$ANDROID_API-clang" ]] ||
  die "NDK at $NDK has no armv7a-linux-androideabi$ANDROID_API-clang"
echo "Using NDK at $NDK"

# ---------------------------------------------------------------------------
# 2. Sources: Media3 (decoder_ffmpeg only) and FFmpeg
# ---------------------------------------------------------------------------
log "Media3 $MEDIA3_TAG ($MEDIA3_COMMIT)"
git_checkout "$MEDIA3_DIR" "$MEDIA3_REPO" "refs/tags/$MEDIA3_TAG" "$MEDIA3_COMMIT" \
  "/LICENSE" "/libraries/decoder_ffmpeg/"
MEDIA3_JNI="$MEDIA3_DIR/libraries/decoder_ffmpeg/src/main/jni"
[[ -f "$MEDIA3_JNI/ffmpeg_jni.cc" && -f "$MEDIA3_JNI/CMakeLists.txt" ]] ||
  die "Media3 checkout is missing decoder_ffmpeg JNI sources"

log "FFmpeg $FFMPEG_BRANCH ($FFMPEG_COMMIT)"
git_checkout "$FFMPEG_SRC" "$FFMPEG_REPO" "$FFMPEG_COMMIT" "$FFMPEG_COMMIT"

# ---------------------------------------------------------------------------
# 3. FFmpeg static libraries (avutil, swresample, avcodec)
# ---------------------------------------------------------------------------
# Options mirror Media3's src/main/jni/build_ffmpeg.sh (COMMON_OPTIONS and the
# armeabi-v7a configure call), plus:
#   --disable-autodetect   never pick up optional system libs (zlib, iconv...)
#   --disable-debug        no DWARF in the static libs (the .so is stripped anyway)
#   -ffunction/data-sections so the final link can garbage-collect unused code.
# Tools are resolved via PATH and FFmpeg is configured out of tree through a
# relative "src" symlink, so no absolute paths end up in the binary (__FILE__,
# the embedded configuration string) and the output is reproducible.
export PATH="$TOOLCHAIN_BIN:$PATH"
FFMPEG_CONFIGURE_ARGS=(
  --target-os=android
  --arch=arm
  --cpu=armv7-a
  --cross-prefix="armv7a-linux-androideabi$ANDROID_API-"
  --nm=llvm-nm
  --ar=llvm-ar
  --ranlib=llvm-ranlib
  --strip=llvm-strip
  --extra-cflags="-march=armv7-a -mfloat-abi=softfp -ffunction-sections -fdata-sections"
  --extra-ldflags="-Wl,--fix-cortex-a8"
  --enable-static
  --disable-shared
  --disable-doc
  --disable-programs
  --disable-everything
  --disable-avdevice
  --disable-avformat
  --disable-swscale
  --disable-postproc
  --disable-avfilter
  --disable-symver
  --enable-swresample
  --extra-ldexeflags=-pie
  --disable-v4l2-m2m
  --disable-vulkan
  --disable-autodetect
  --disable-debug
)
for decoder in "${ENABLED_DECODERS[@]}"; do
  FFMPEG_CONFIGURE_ARGS+=("--enable-decoder=$decoder")
done

FFMPEG_STAMP_CONTENT="ffmpeg=$FFMPEG_COMMIT ndk=$NDK_REVISION api=$ANDROID_API args=${FFMPEG_CONFIGURE_ARGS[*]}"
FFMPEG_STAMP="$FFMPEG_BUILD/.build-stamp"
FFMPEG_INSTALL="$FFMPEG_BUILD/install/usr/local"   # make install DESTDIR=... with the default prefix
FFMPEG_UP_TO_DATE=0
if [[ "${FORCE:-0}" != "1" && -f "$FFMPEG_STAMP" &&
  "$(cat "$FFMPEG_STAMP")" == "$FFMPEG_STAMP_CONTENT" ]]; then
  FFMPEG_UP_TO_DATE=1
  for lib in avutil swresample avcodec; do
    [[ -f "$FFMPEG_INSTALL/lib/lib$lib.a" ]] || FFMPEG_UP_TO_DATE=0
  done
fi

if [[ "$FFMPEG_UP_TO_DATE" == "1" ]]; then
  log "FFmpeg build is up to date, skipping (FORCE=1 to rebuild)"
else
  log "Configuring and building FFmpeg for $ABI (API $ANDROID_API)"
  rm -rf "$FFMPEG_BUILD"
  mkdir -p "$FFMPEG_BUILD"
  ln -s "$FFMPEG_SRC" "$FFMPEG_BUILD/src"
  (
    cd "$FFMPEG_BUILD"
    ./src/configure "${FFMPEG_CONFIGURE_ARGS[@]}"
    make -j"$JOBS"
    make install-libs install-headers DESTDIR="$FFMPEG_BUILD/install"
  )
  grep -q 'FFMPEG_LICENSE "LGPL version 2.1 or later"' "$FFMPEG_BUILD/config.h" ||
    die "FFmpeg is not configured as LGPL-2.1-or-later"
  for decoder in "${ENABLED_DECODERS[@]}"; do
    grep -q "^#define CONFIG_$(echo "$decoder" | tr '[:lower:]' '[:upper:]')_DECODER 1" "$FFMPEG_BUILD/config_components.h" ||
      die "decoder '$decoder' was not enabled by configure"
  done
  printf '%s' "$FFMPEG_STAMP_CONTENT" >"$FFMPEG_STAMP"
fi
echo "Enabled decoders: $(sed -n 's/^#define CONFIG_\(.*\)_DECODER 1$/\1/p' "$FFMPEG_BUILD/config_components.h" | tr '[:upper:]' '[:lower:]' | tr '\n' ' ')"

# Stage a CMake source dir with the layout Media3's CMakeLists.txt expects:
#   jni-src/{CMakeLists.txt,ffmpeg_jni.cc}               (unmodified from Media3)
#   jni-src/ffmpeg/lib*/<public headers>
#   jni-src/ffmpeg/android-libs/armeabi-v7a/lib{avutil,swresample,avcodec}.a
log "Staging JNI sources"
rm -rf "$JNI_STAGE"
mkdir -p "$JNI_STAGE/ffmpeg/android-libs/$ABI"
cp "$MEDIA3_JNI/CMakeLists.txt" "$MEDIA3_JNI/ffmpeg_jni.cc" "$JNI_STAGE/"
cp -R "$FFMPEG_INSTALL/include/." "$JNI_STAGE/ffmpeg/"
for lib in avutil swresample avcodec; do
  cp "$FFMPEG_INSTALL/lib/lib$lib.a" "$JNI_STAGE/ffmpeg/android-libs/$ABI/"
done

# ---------------------------------------------------------------------------
# 4. libffmpegJNI.so via Media3's CMakeLists.txt and the NDK CMake toolchain
# ---------------------------------------------------------------------------
log "Building libffmpegJNI.so"
if command -v ninja >/dev/null 2>&1; then
  CMAKE_GENERATOR="Ninja"
else
  CMAKE_GENERATOR="Unix Makefiles"
fi
# --exclude-libs,ALL: do not re-export FFmpeg's API from the .so (smaller,
#   only JNI_OnLoad stays exported, no preemptible-symbol relocations).
# --gc-sections: drop unreferenced code from the static libs.
# -ffile-prefix-map: keep build paths out of debug info so the GNU build-id
#   (and thus the stripped .so) is identical wherever the script runs.
PREFIX_MAP="-ffile-prefix-map=$WORK_DIR/=/work/ -ffile-prefix-map=$NDK/=/ndk/"
rm -rf "$JNI_BUILD"
cmake -S "$JNI_STAGE" -B "$JNI_BUILD" -G "$CMAKE_GENERATOR" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="$PREFIX_MAP" \
  -DCMAKE_CXX_FLAGS="$PREFIX_MAP" \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--gc-sections -Wl,--exclude-libs,ALL"
cmake --build "$JNI_BUILD" -j "$JOBS"

UNSTRIPPED="$JNI_BUILD/libffmpegJNI.so"
[[ -f "$UNSTRIPPED" ]] || die "CMake did not produce $UNSTRIPPED"
STRIPPED="$WORK_DIR/build/libffmpegJNI.so"
"$TOOLCHAIN_BIN/llvm-strip" --strip-all -o "$STRIPPED" "$UNSTRIPPED"

# ---------------------------------------------------------------------------
# 5. Verify
# ---------------------------------------------------------------------------
log "Verifying"
READELF="$TOOLCHAIN_BIN/llvm-readelf"
NM="$TOOLCHAIN_BIN/llvm-nm"

"$READELF" -h "$STRIPPED" | grep -q 'Machine: *ARM$' || die "libffmpegJNI.so is not an ARM (32-bit) ELF"

needed="$("$READELF" -d "$STRIPPED" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort)"
echo "NEEDED: $(echo "$needed" | tr '\n' ' ')"
while read -r lib; do
  case "$lib" in
    libc.so | libm.so | libdl.so | liblog.so | libandroid.so) ;;
    *) die "unexpected NEEDED library: $lib" ;;
  esac
done <<<"$needed"

exports="$("$NM" -D --defined-only "$STRIPPED" | awk '{print $NF}' | sort)"
echo "Exported symbols: $(echo "$exports" | tr '\n' ' ')"
grep -qx 'JNI_OnLoad' <<<"$exports" || die "JNI_OnLoad is not exported"

# Media3 1.11.1 binds its natives with RegisterNatives() in JNI_OnLoad, so the
# method names must appear in the .so's string table.
for method in ffmpegInitialize ffmpegDecode ffmpegGetChannelCount ffmpegGetSampleRate \
  ffmpegReset ffmpegRelease ffmpegGetVersion ffmpegGetInputBufferPaddingSize ffmpegHasDecoder \
  growOutputBuffer androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder \
  androidx/media3/decoder/ffmpeg/FfmpegLibrary; do
  grep -aq "$method" "$STRIPPED" || die "string '$method' missing from libffmpegJNI.so"
done
if grep -aq -- '--enable-gpl\|--enable-nonfree' "$STRIPPED"; then
  die "libffmpegJNI.so was configured with GPL/nonfree options"
fi

# ---------------------------------------------------------------------------
# 6. Install into the Gradle module
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"
cp "$STRIPPED" "$OUT_DIR/libffmpegJNI.so"

log "Done"
echo "Output:   $OUT_DIR/libffmpegJNI.so"
echo "Size:     $(wc -c <"$OUT_DIR/libffmpegJNI.so") bytes"
echo "SHA-256:  $(sha256_of "$OUT_DIR/libffmpegJNI.so")"
echo "Unstripped copy (for symbolizing native crashes): $UNSTRIPPED"
