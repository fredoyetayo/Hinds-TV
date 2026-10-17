# Hinds TV — guide for Claude Code

A fast, lean IPTV player for **Amazon Fire TV**, sideloaded, driven entirely by the Fire TV remote.
It replaces STB Emu for the owner's **legal** IPTV subscription, which is a **Stalker/Ministra "MAC" portal**:
a portal URL plus a MAC address registered with the provider.

- **Target device:** Fire TV Stick 4K Max (2nd gen, `AFTKRT`). It runs Fire OS 8 (Android 11, API 30) on a
  32-bit ARM userspace (armeabi-v7a), with 2 GB RAM and a weak PowerVR GE9215 GPU.
- **Remote:** the Alexa Voice Remote Enhanced (UK). Its D-pad, Select, Back, Menu, ⏪ ⏯ ⏩ and Channel ▲/▼
  buttons reach the app. Home, Alexa, volume, mute, power, settings, recents and the app shortcut buttons
  never reach the app. The Live/Guide (TV icon) key is unverified; check it with `adb logcat -s HindsKeys`.
- **Research and protocol reference:** `docs/RESEARCH.md`. Read §6 before touching the Stalker client.

## Non-negotiables

1. **Performance is the product.** STB Emu was rejected for being slow. Budgets, measured on the stick with
   **release** builds only:
   - Cold start to first video frame: under 2 s.
   - Channel change: under 1 s (allowing for provider latency).
   - Scrolling: no dropped frames with 10k+ channels.
   - Memory while playing: under 300 MB PSS.
   - APK size: under 10 MB.
2. **No bloat.** Ask before adding a dependency, and justify it in the PR or commit message.
   - Not allowed: ads, analytics, Firebase, crash-reporting SaaS, DI frameworks, LibVLC/mpv, Material3,
     navigation libraries.
   - Fire OS has no Google Play Services.
3. **Remote-first UX.** Every screen must be fully usable with the D-pad. Never let focus get lost or trapped.
   - Text input uses `TvTextField`, which only opens the keyboard when you select it.
   - Back always goes back or out.
4. **One stream per account.** Providers allow a single connection.
   - Always `engine.stop()` before opening a new stream.
   - Never preload neighbouring channels.
   - Zapping goes through `ZapController`, which debounces bursts so portals don't rate-limit (HTTP 429/458).
5. **Clean-room protocol code.** Use the protocol facts in `docs/RESEARCH.md`.
   - Never copy code from GPL clients (pvr.stalker, stalkerhek, OwnTV, stalkervod) or unlicensed ones (STB-Proxy).
   - Never generate, guess or scan MAC addresses or device identities. The user supplies them.
6. **Never log or commit credentials.** That covers portal URLs, MACs and tokens. The owner's portal details
   live only on the TV.

## Architecture (single-activity Compose app, manual DI)

```
app/src/main/java/com/hindstv/
  HindsApp.kt                  Application + AppContainer (lazy manual DI) + Coil image loader
  core/Http.kt                 the single shared OkHttpClient, Call.await()
  data/stalker/                Stalker portal client: pure Kotlin, JVM-unit-testable, no android.*
    StalkerClient.kt           discovery, handshake/get_profile, re-auth, channels, create_link, watchdog
    StalkerModels.kt           session/genre/channel models + StalkerException hierarchy
    StalkerJson.kt             tolerant JSON accessors (portals mix "1"/1/true, strings/numbers)
    PortalProfile.kt           profile (URL, MAC, STB model, optional device IDs) + Mac.normalize
  data/db/HindsDatabase.kt     Room: channels, genres (re-syncable cache), favorites (user data)
  data/ChannelRepository.kt    sync portal → DB; UI reads only from the DB; stream URL resolution
  data/ProfileStore.kt         SharedPreferences: profile, cached endpoint, last channel/group, last sync
  player/PlayerEngine.kt       the one ExoPlayer: tuned LoadControl, OkHttp data source, TS flags, FFmpeg ext
  player/ZapController.kt      zap debouncing (lone press = instant, bursts settle 500 ms)
  ui/AppViewModel.kt           all UI state: screen, overlays, playback, zapping, setup, sync, watchdog
  ui/MainActivity.kt           full-screen remote handling in dispatchKeyEvent; lifecycle → VM
  ui/RemoteKeys.kt             keycode → RemoteAction mapping
  ui/tv/                       TvScreen (video, banner, toasts), ChannelListOverlay (groups + channels)
  ui/setup/SetupScreen.kt      portal URL + MAC (+ advanced identity fields)
  ui/components/TvTextField.kt D-pad-safe text input
ffmpeg-audio/                  Media3 FFmpeg *audio* decoder (MP2/AC3/E-AC3/DTS/TrueHD), prebuilt LGPL .so
tools/fake-portal/             fake Stalker portal + test streams for development without a subscription
```

**Key flows:**
- **Launch.**
  1. If a profile exists, the app plays the last channel immediately. The portal endpoint is cached, so
     there's no discovery.
  2. Channels come from the DB.
  3. The DB refreshes in the background if older than 12 h.
- **Full screen.**
  - ▲/▼ and CH± zap within the current group.
  - Select, ◀ or Guide opens the overlay.
  - ▶ shows the info banner.
  - ⏪ goes to the previous channel.
  - ☰ toggles favourite.
  - ⏯ stops or resumes (live TV is released, not paused).
  - Back twice exits.
- **Overlay.** Groups on the left (focus previews a group, debounced), channels on the right. Select plays,
  ☰ toggles favourite, Back closes.
- **Background (`onStop`).** Playback stops, which frees the decoder and the provider connection (Amazon
  requirement). It resumes on `onStart`.

## Build, test, run

The SessionStart hook (`.claude/hooks/session-start.sh`) installs the Android SDK to `/opt/android-sdk` in
cloud sessions and writes `local.properties`. Locally, set `ANDROID_HOME`.

```bash
./gradlew testDebugUnitTest              # JVM unit tests (Stalker client against MockWebServer, zapping)
./gradlew lintDebug                      # Android lint (must pass; CI runs it)
./gradlew assembleRelease                # app/build/outputs/apk/release/app-release.apk (armeabi-v7a)
./gradlew assembleDebug -Phinds.abis=x86 # emulator build (FFmpeg audio is ARM-only, so excluded)
```

**Fake portal for end-to-end testing:**
```bash
tools/fake-portal/make-test-streams.sh build/test-streams
python3 tools/fake-portal/fake_portal.py --port 8080 --media build/test-streams
```
Then use portal `http://10.0.2.2:8080/c/` on the emulator, with any MAC starting `00:1A:79`.

**Emulator smoke test.**
- Cloud containers have no KVM, so the emulator runs in software. Expect about 10 minutes to boot and
  seconds per key press, but it does work.
  ```bash
  sdkmanager "emulator" "system-images;android-30;android-tv;x86"
  avdmanager create avd -n hindstv -k "system-images;android-30;android-tv;x86" -d tv_1080p
  emulator -avd hindstv -no-window -no-audio -no-boot-anim -accel off -gpu swiftshader_indirect -no-snapshot &
  adb install -r app/build/outputs/apk/debug/app-debug.apk   # built with -Phinds.abis=x86
  ```
- Typing through the IME is too slow there. Seed the profile instead: write `shared_prefs/hinds.xml` with
  `portal_url` and `mac` via `adb shell run-as com.hindstv.app.debug`.
- Drive the app with `adb shell input keyevent DPAD_*` and capture it with `adb exec-out screencap -p`.

**Gotchas:**
- Repositories use Google's Maven Central mirror; repo1.maven.org rate-limits cloud IPs.
- `compileSdk` is 37 because current AndroidX requires it. That's unrelated to the devices it runs on
  (`minSdk` 28).
- Release and debug builds are both signed with `keystore/hinds-dev.jks`, so updates install over each
  other.
  - CI can use secrets instead: `HINDS_KEYSTORE_BASE64`, `HINDS_KEYSTORE_PASSWORD`, `HINDS_KEY_ALIAS`,
    `HINDS_KEY_PASSWORD`.
  - Changing the key forces one uninstall/reinstall on the TV.
- Local builds have `versionCode` 1. To install one over a CI build, use `adb install -r -d`.
- Judge performance only on release builds on the real stick. Debug Compose is many times slower.

## Releasing and installing on the Fire TV

- **CI** (`.github/workflows/build.yml`) runs on every push: unit tests, lint, release APK.
  - On `main` it also publishes a GitHub Release with `hinds-tv.apk` (`versionCode` = run number).
- **Install over adb** from a computer on the same network (the cloud container can't reach the TV):
  `adb connect <tv-ip>:5555 && adb install -r hinds-tv.apk`.
- **Install with the Downloader app** using
  `https://github.com/fredoyetayo/Hinds-TV/releases/latest/download/hinds-tv.apk`. This only works if the
  repo (or a release mirror) is public; private-repo assets need auth.

## Conventions

- **Commit authorship:** every commit is authored and committed as **Fred Oyetayo**
  (`29312441+fredoyetayo@users.noreply.github.com`). Never add `Co-Authored-By: Claude…`,
  `Claude-Session:` or other AI attribution lines to commits, PRs, tags or release notes.

- **Kotlin style:** official, 4 spaces, ~120 columns. Comments explain *why*; there aren't many.
- **Threading:**
  - The ViewModel runs on Main.
  - `StalkerClient` switches to IO itself.
  - Room suspend functions are main-safe.
- **Compose performance:**
  - Stable `key`s in lazy lists.
  - No focus scale animations (`focusedScale = 1f`).
  - No crossfades.
  - No blur or shadows.
  - Read fast-changing state in leaf composables.
- **User-facing failures:** map them through `ui/Errors.kt` into one plain-English sentence.
- **Tests:**
  - New protocol behaviour needs a `StalkerClientTest` case: extend `FakePortal` there.
  - Logic that can be pure Kotlin (no `android.*`) should be, and should be tested.
- **Never** skip, disable or weaken tests to get a green build.

## Roadmap

- [x] **Phase 0 — Foundation.**
  - Scaffold, CI, SessionStart hook, fake portal.
  - Stalker client (discovery, auth, re-auth, channels, create_link, watchdog).
  - Channel DB, setup screen.
  - Full-screen player with debounced zapping, overlay channel list, favourites, last-channel resume.
  - FFmpeg audio.
- [ ] **Phase 1 — Prove it on the stick.**
  - Install on the 4K Max and connect to the real portal. Fix any portal quirks.
  - Measure the start, zap and memory budgets.
  - Confirm keycodes for Guide/Recents.
  - Check MP2 audio and 576i/1080i deinterlacing.
- [ ] **Phase 2 — Guide.**
  - EPG sync (`get_epg_info`, optional XMLTV) into Room.
  - Now/next in banner and list.
  - Custom-drawn EPG grid (single focus target; never thousands of focusable cells).
  - Channel search (FTS4).
- [ ] **Phase 3 — Polish.**
  - MediaSession.
  - Baseline Profile (generated on an emulator).
  - Audio/subtitle track picker.
  - Aspect/zoom.
  - Settings screen (invert ▲/▼, buffer size, AFR).
  - Parental PIN for censored groups.
  - Account expiry display (`account_info`).
- [ ] **Phase 4 — More sources.** Xtream Codes API, M3U + XMLTV, multiple profiles.
- [ ] **Phase 5 — VOD, series, catch-up.**
  - Ministra VOD and all three series models.
  - `tv_archive`.
  - Resume positions. VOD needs real stream lengths, so it must not use `LiveDataSource`.
- [ ] **Phase 6 — Self-update** from GitHub Releases (`REQUEST_INSTALL_PACKAGES` + PackageInstaller).

**Known gaps and TODOs:**
- `do_auth` (login/password portals) isn't implemented; `get_profile` status 2 shows a message.
- The channel list is parsed as a JSON tree. Switch to streaming if a portal returns more than about 50k
  channels.
- HLS live offset tuning is untested.
