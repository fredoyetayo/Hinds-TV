# ffmpeg-audio

Software audio decoding for ExoPlayer on devices that lack a platform decoder,
mainly **MPEG-1 Layer II (MP2)**, which is common in UK IPTV MPEG-TS streams and
not supported by the Fire TV Stick 4K Max's MediaCodec.

This module is the **audio-only** part of AndroidX Media3's FFmpeg decoder
extension (`libraries/decoder_ffmpeg`, which Google does not publish to Maven),
plus a prebuilt `libffmpegJNI.so` for **armeabi-v7a only** (Fire OS 8 runs a
32-bit userspace), built from LGPL FFmpeg with only the decoders listed below.

## Versions

| Component | Version |
|---|---|
| AndroidX Media3 | `1.11.1` (tag `1.11.1`, commit `8c6678b657ede1e7883fc164ef73ed483c7796c3`) |
| FFmpeg | `release/6.0` @ `b37bf81a7a9782438e4fe06a249329e279694494` (6.0.2 + backported fixes, `Lavc60.3.100`); 6.0 is what the Media3 1.11.1 README recommends |
| Android NDK | `r27c` (`27.2.12479018`, clang 18.0.3) |
| Native API level | 23 (the app's minSdk is 28) |
| ABI | `armeabi-v7a` only |

## Contents

| Path | What |
|---|---|
| `src/main/java/androidx/media3/decoder/ffmpeg/` | `FfmpegAudioRenderer`, `FfmpegAudioDecoder`, `FfmpegLibrary`, `FfmpegDecoderException`, `package-info`, copied **unmodified** from Media3 1.11.1. The package name must stay as is: the native code looks the classes up by name. |
| `src/main/jniLibs/armeabi-v7a/libffmpegJNI.so` | Media3's `ffmpeg_jni.cc` (unmodified) built with Media3's `CMakeLists.txt`, statically linked with FFmpeg's `libavcodec`, `libswresample` and `libavutil`, stripped. |
| `consumer-rules.pro` | R8 keep rules applied to the app (native methods, JNI callback, reflective `FfmpegAudioRenderer` constructor). |
| `licenses/` | Apache-2.0 (Media3) and LGPL-2.1 (FFmpeg) license texts. |

`ExperimentalFfmpegVideoRenderer` from upstream is left out: in 1.11.1 it is a
non-functional stub that rejects every format. `DefaultRenderersFactory` loads
it with `Class.forName(...)` inside `catch (ClassNotFoundException e)`
("Expected if the app was built without the extension"), so its absence is
harmless.

## Enabled FFmpeg decoders

Exactly the audio decoders that Media3's `FfmpegLibrary.getCodecName()` maps
for these formats:

| Media3 MIME type(s) | FFmpeg decoder |
|---|---|
| `audio/mpeg`, `audio/mpeg-L1`, `audio/mpeg-L2` (MP3, MP1, **MP2**) | `mp3` (FFmpeg's `mp3` decoder decodes Layer I, II and III from the frame header; `mp1` and `mp2` are also enabled, they live in the same object file) |
| `audio/mp4a-latm` (AAC) | `aac` |
| `audio/ac3` | `ac3` |
| `audio/eac3`, `audio/eac3-joc` | `eac3` |
| `audio/vnd.dts`, `audio/vnd.dts.hd`, `audio/vnd.dts.hd;profile=lbr` | `dca` |
| `audio/true-hd` | `truehd` |

Everything else is off: no video decoders, encoders, demuxers, muxers,
protocols, filters, network, hardware acceleration or autodetected external
libraries (`--disable-everything --disable-autodetect`, plus the options of
Media3's `build_ffmpeg.sh`). FFmpeg also pulls in the `ac3` and `mlp` parsers
that these decoders depend on.

## The native library

| | |
|---|---|
| File | `src/main/jniLibs/armeabi-v7a/libffmpegJNI.so` |
| Size | 1,128,852 bytes (1.08 MiB) |
| SHA-256 | `e8ee43ba624753b97b792512a92d1321f79c45810d00b4ea5878c42fee7737f9` |
| ELF | 32-bit ARM (EABI5), `DT_NEEDED`: `libandroid.so`, `liblog.so`, `libm.so`, `libdl.so`, `libc.so` |
| Exported symbols | `JNI_OnLoad` only |

Media3 1.11.1 does not use `Java_androidx_media3_decoder_ffmpeg_*` symbol names:
`JNI_OnLoad` registers the natives with `RegisterNatives()` on
`FfmpegAudioDecoder` (`ffmpegInitialize`, `ffmpegDecode`,
`ffmpegGetChannelCount`, `ffmpegGetSampleRate`, `ffmpegReset`,
`ffmpegRelease`, plus the `growOutputBuffer` callback) and `FfmpegLibrary`
(`ffmpegGetVersion`, `ffmpegGetInputBufferPaddingSize`, `ffmpegHasDecoder`).
Those names and signatures match the `native` methods in the Java sources, and
they survive R8 with `consumer-rules.pro`.

The build is reproducible: running the script from any directory produces the
same SHA-256.

## Using it from the app

```kotlin
// settings.gradle.kts
include(":ffmpeg-audio")

// app/build.gradle.kts
dependencies { implementation(project(":ffmpeg-audio")) }

// Where the player is created (DefaultRenderersFactory is @UnstableApi):
@OptIn(UnstableApi::class)
val renderersFactory = DefaultRenderersFactory(context)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
val player = ExoPlayer.Builder(context, renderersFactory).build()
```

With `EXTENSION_RENDERER_MODE_ON`, `FfmpegAudioRenderer` is added after
`MediaCodecAudioRenderer`, so it is used only for formats the platform cannot
decode (MP2 on Fire TV). AC-3/E-AC-3 passthrough and hardware decoding keep
working as before. `EXTENSION_RENDERER_MODE_PREFER` would decode everything it
supports in software instead.

`FfmpegLibrary.isAvailable()` and `FfmpegLibrary.getVersion()` can be logged
to confirm the library loaded.

## Licenses

- **Media3** Java sources and `ffmpeg_jni.cc`: Apache License 2.0
  (`licenses/Apache-2.0.txt`). The license headers are kept; the files are
  unmodified.
- **FFmpeg**: LGPL version 2.1 or later (`licenses/LGPL-2.1.txt`). It is built
  **without** `--enable-gpl`, `--enable-nonfree` or `--enable-version3`, and
  `configure` reports `License: LGPL version 2.1 or later`. No GPL code is
  included.

FFmpeg is statically linked into `libffmpegJNI.so`, which is a separate shared
library inside the APK. When distributing the app:

- credit FFmpeg and its LGPL-2.1 license, for example on an about or licenses
  screen;
- make the corresponding source available: the pinned FFmpeg commit, the Media3
  JNI sources and `scripts/build-ffmpeg.sh` (which shows exactly how the library
  was built);
- don't prevent users from rebuilding `libffmpegJNI.so` against a modified
  FFmpeg and using it in place of the shipped one.

This is not legal advice. Some formats (such as AC-3/E-AC-3, DTS and AAC) may
also be covered by patents in some countries, which is a separate question from
the copyright licenses.

## Rebuilding the native library

```sh
scripts/build-ffmpeg.sh               # work dir: build/ffmpeg-work (ignored by git)
scripts/build-ffmpeg.sh /tmp/ffwork   # or any other work dir
```

The script:

1. downloads the pinned NDK zip and checks its SHA-256, or reuses an NDK from
   `NDK_PATH`, `ANDROID_NDK_ROOT` or `ANDROID_NDK_HOME` if it is the same
   revision;
2. shallow-fetches Media3 `1.11.1` (only `libraries/decoder_ffmpeg`) and the
   pinned FFmpeg commit, and verifies both commit hashes;
3. configures and builds FFmpeg for armeabi-v7a out of tree, using Media3's
   `build_ffmpeg.sh` options plus `--disable-autodetect`, `--disable-debug` and
   `-ffunction-sections -fdata-sections`;
4. builds `libffmpegJNI.so` from Media3's unmodified `CMakeLists.txt` and
   `ffmpeg_jni.cc` with the NDK CMake toolchain, linking with
   `-Wl,--gc-sections -Wl,--exclude-libs,ALL`, then strips it;
5. checks the ELF machine, `DT_NEEDED` entries, the `JNI_OnLoad` export, the
   JNI method names and the license, then copies the library to
   `src/main/jniLibs/armeabi-v7a/` and prints its size and SHA-256.

Running it again reuses the downloads, checkouts and FFmpeg build (set
`FORCE=1` to rebuild FFmpeg). `JOBS` sets the build parallelism. It needs
`bash`, `curl`, `git`, `unzip`, `make` and `cmake` 3.21 or newer; `ninja` is
used if present. All of these are available on GitHub Actions `ubuntu-*`
runners. Automatic NDK download works on Linux x86_64 only; on other hosts, set
`NDK_PATH` to an r27c NDK.

To change versions or decoders, edit the pinned values and `ENABLED_DECODERS`
at the top of the script. Update this README with the new SHA-256, and keep the
Java sources in sync with the Media3 version used to build the JNI library.
