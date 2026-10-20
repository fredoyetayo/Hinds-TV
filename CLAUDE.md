# Hinds TV — guide for Claude Code

A fast, lean IPTV player for **Amazon Fire TV**, sideloaded, driven entirely by the Fire TV remote.
It replaces STB Emu for the owner's **legal** IPTV subscription, which is a **Stalker/Ministra "MAC" portal**:
a portal URL plus a MAC address registered with the provider. It also plays **M3U playlists** and single
stream links (HLS/TS), with an XMLTV guide. Besides live TV it has **Videos** (the provider's VOD: the owner's
are mostly school lessons) with Netflix-style resume, and **Radio**.

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
   - Live TV, videos and radio share the one `PlayerEngine`. Whoever plays sets `engine.owner`; every player
     listener ignores events it doesn't own. Leaving a section stops its playback first.
5. **Clean-room protocol code.** Use the protocol facts in `docs/RESEARCH.md`.
   - Never copy code from GPL clients (pvr.stalker, stalkerhek, OwnTV, stalkervod) or unlicensed ones (STB-Proxy).
   - Never guess or scan MAC addresses or device identities, and never probe portals with made-up ones.
   - The setup screen may *generate* one fresh random `00:1A:79` MAC for the user to register with their own
     provider (as STB Emu does). It's shown to the user and never auto-tried against other portals.
6. **Never log or commit credentials.** That covers portal URLs, MACs and tokens. The owner's portal details
   live only on the TV.

## Architecture (single-activity Compose app, manual DI)

```
app/src/main/java/com/hindstv/
  HindsApp.kt                  Application + AppContainer (lazy manual DI) + Coil image loader
  core/Http.kt                 the single shared OkHttpClient, Call.await()
  core/Diagnostics.kt          in-memory ring (80) of portal/player events for Settings → Diagnostics; redact()
                               strips MACs, tokens, serials, signatures (pure, tested). Never written to disk
  data/stalker/                Stalker portal client: pure Kotlin, JVM-unit-testable, no android.*
    StalkerClient.kt           discovery, handshake/get_profile, re-auth, channels, create_link, EPG, watchdog
    StalkerModels.kt           session/genre/channel models + StalkerException hierarchy
    StalkerJson.kt             tolerant JSON accessors (portals mix "1"/1/true, strings/numbers)
    PortalProfile.kt           profile (URL, MAC, STB model, optional device IDs) + Mac.normalize
  data/source/                 ChannelSource abstraction: one interface, two providers
    ChannelSource.kt           SourceProfile, SourceChannel, Programme, VideoItem/Episode/Category, RadioStation
    StalkerSource.kt           wraps StalkerClient (channels, create_link, get_epg_info, VOD online, radio)
    PlaylistSource.kt          M3U playlist or single stream link; XMLTV guide (gzip-aware); splits videos/radio
    M3uParser.kt               line-streaming extended M3U parser + live/video/radio classification (pure Kotlin)
    XmltvParser.kt             XmlPullParser-streaming XMLTV parser (pure Kotlin, kxml2 in tests)
  data/db/HindsDatabase.kt     Room v4: channels, genres, programmes, videos, radio (re-syncable caches);
                               favorite_lists + favorite_items (named lists), watch_progress (user data,
                               kept by real migrations: 2→3, 3→4)
  data/ChannelRepository.kt    sync source → DB, guide sync; UI reads only from the DB; stream URL resolution
  data/MediaRepository.kt      videos (portal: online, paged; playlist: DB), episodes, radio, resume positions
  data/ProfileStore.kt         SharedPreferences: profile, cached endpoint + session token, last channel/group,
                               recent channels (20, `Recents.kt`), last sync, recent searches, theme, start with
  player/PlayerEngine.kt       the one ExoPlayer: tuned LoadControl, OkHttp data source, TS flags, FFmpeg ext
  player/ZapController.kt      zap debouncing (lone press = instant, bursts settle 500 ms)
  player/ResumePolicy.kt       when a video counts as started/finished and where to resume (pure, tested)
  player/Tracks.kt             TrackOption/TrackChoices + TrackLabels ("English · 5.1 · Dolby Digital", tested)
  ui/AppViewModel.kt           navigation (screen), live TV: overlays, playback, zapping, setup, sync, watchdog
  ui/VideoViewModel.kt         Videos: library rows, details/episodes, video player keys, resume saving
  ui/RadioViewModel.kt         Radio: stations, playback, ICY now-playing, screen off
  ui/SearchViewModel.kt        search as you type: channels, guide programmes, videos (portal search), radio
  ui/SettingsViewModel.kt      Settings: appearance (theme), account (+ account_info expiry), lists order, ▲/▼ invert, history,
                               diagnostics; maskMac/hostOf (tested)
  ui/TrackPicker.kt            app-wide "Audio & subtitles" dialog state over the one PlayerEngine
  ui/MainActivity.kt           full-screen remote handling in dispatchKeyEvent; lifecycle → VM
  ui/RemoteKeys.kt             keycode → RemoteAction mapping
  ui/tv/                       TvScreen (video, info banner, toasts), ChannelListOverlay, GuideOverlay
  ui/videos/                   VideosScreen (library rows), DetailsPage, VideoPlayerPage
  ui/home/HomeScreen.kt        Home (opt-in start): resume hero, first favourites list, recently watched; NextUp.kt (tested)
  ui/radio/RadioScreen.kt      now playing + station grid
  ui/search/SearchScreen.kt    one box, results in rows, recent searches
  ui/tv/FavoriteDialogs.kt     "Add to list" picker (tick lists, make a new one) and rename/delete
  ui/tv/TrackPickerDialog.kt   audio tracks | subtitles (+ Off)
  ui/settings/SettingsScreen.kt  section rail (focus previews) + pane
  ui/setup/SetupScreen.kt      MAC portal (URL, MAC + Generate, advanced) or playlist/link (+ XMLTV)
  ui/components/               TvTextField (D-pad-safe input), Common (clock, logo, progress, VideoSurface),
                               Cinema (TopNav pills, PillButton, PosterCard, Artwork, LevelBars, formats),
                               PageJump (⏪⏩ page jumps in lazy lists/grids; also "focus this index")
  ui/theme/Theme.kt            HindsPalette: Dark ("Cinema", gold accent) and Light ("Daylight", blue accent) via
                               LocalHindsPalette; HindsColors.* are @Composable getters. Manrope type, focus styling
  ui/theme/ThemeMode.kt        Dark | Light | Auto (Light 07:00–19:00, re-checked on the minute tick; pure, tested)
ffmpeg-audio/                  Media3 FFmpeg *audio* decoder (MP2/AC3/E-AC3/DTS/TrueHD), prebuilt LGPL .so
tools/fake-portal/             fake Stalker portal + test streams for development without a subscription
```

**Key flows:**
- **Launch.**
  1. If a profile exists, the app plays the last channel immediately. The portal endpoint is cached, so
     there's no discovery.
  2. Channels and the guide come from the DB.
  3. Channels refresh in the background if older than 12 h; the guide if older than 4 h (it covers 2 h
     back to 12 h ahead).
- **Home** (first nav pill; start screen only if Settings → Appearance → Start with = Home, otherwise launch
  still plays the last channel). Nothing plays on Home: when it is the start screen the last channel is
  only remembered (`resumeOnForeground`) and Live picks it up. Hero = newest Continue watching item (Resume /
  Play from start), else the last channel. Wallpaper: two per theme (`ui/theme/Wallpapers.kt`, 1920×1080 WebP in
  `drawable-nodpi`, about 150–250 KB each), chosen in Appearance, under three Background scrims.
- **Full screen.**
  - ▲/▼ and CH± zap within the current group.
  - Select opens the channel list. ◀ opens the recent-channels strip (`Overlay.Recent`, `RecentStrip.kt`:
    Recently watched / first list / All tabs; Select watches, ▼ or Back hides, ⏪ last channel).
    The Live/Guide (TV) key opens the TV guide from anywhere.
  - ▶ shows the info banner.
  - ⏪ goes to the previous channel. ⏩ opens Audio & subtitles.
  - ☰ toggles favourite.
  - ⏯ stops or resumes (live TV is released, not paused).
  - Back twice exits.
- **Overlay.** Section pills on top (Home · Live · Guide · VOD · Radio · Search · Settings). Groups on the left:
  Recently watched (`__recent`, kept in `ChannelRepository`; playing from it zaps through All), the user's
  favourites lists (★), then All, then the provider's groups (focus previews a group,
  debounced); channels on the right with what's on now. Select plays, ☰ opens "Add to list" (☰ on a list
  renames/deletes it), ⏪⏩ jump 10 channels, Back closes. Footer: Refresh channels.
- **Favourites lists.** Any number of named lists; a channel can be in several. Group ids `__list:<id>`.
  Dialogs are `Dialog` windows, so focus stays inside them and Back closes them.
- **Search.** Opens with the keyboard up; 300 ms debounce; local results first, the portal's VOD search
  alongside. Picking a channel watches it full screen.
- **Guide.** One focusable Canvas for the current group: ▲▼ channel, ◀▶ programme, CH± page, Select plays,
  Back or Guide closes. `MainActivity` passes every key through while the guide is open.
- **Section bar.** `NavBarState` + `NavBackHandler`: Back from anywhere in a section's lists jumps to the
  bar first, then leaves. Keep the bar outside lazy lists so it is always composed.
- **VOD** (was "Videos"). Category rail (For you — where VOD opens: newest title in the category last watched
  from, plus a Continue watching row — then Continue watching, All VOD, provider categories; focus previews
  after 250 ms) + 3-column poster grid with paging, title filter (portal `search=`) and Newest/A–Z
  (`sortby=added|name`). "Continue watching" resumes straight into the video. Details: Resume / Play from start, episodes.
  Player: `MainActivity` sends keys to `VideoViewModel.onPlayerKey` (◀▶ ±10 s, ⏪⏩ ±30 s, accumulating;
  Select/⏯ pause, ☰ Audio & subtitles). Position saved every 10 s, on pause, Back and `onStop`. Series run on
  to the next episode. ⏪⏩ page the grid; Back from details refocuses the card (`returnFocusKey`).
- **Audio & subtitles.** `PlayerEngine.trackChoices()/selectTrack()`: a pick is a `TrackSelectionOverride`
  plus the preferred language, so the next channel keeps it. Subtitles are drawn by `SubtitleLayer` in
  `VideoSurface` (text along the bottom, bitmap DVB/PGS cues where placed); no media3-ui dependency.
- **Theme.** `AppContainer.themeMode` (saved as `theme_mode`) → `AppViewModel.lightTheme` → `HindsTheme(light)`.
  Read colours through `HindsColors` in composables; a `DrawScope` takes the palette as a parameter
  (`GuideOverlay.drawGuide`). Video areas and subtitles stay black/white in both themes. Design: `docs/design/`.
- **Settings.** Its own `Screen`; "Change account" opens Setup, whose Cancel returns to Settings. Diagnostics
  shows `Diagnostics.recent()`: `StalkerClient` logs each call's HTTP code and a short redacted body (list
  calls by size only; not the watchdog), `PlayerEngine` logs URLs played, errors and selected formats.
- **Radio.** Station grid; Stop; Screen off (black overlay, any key wakes). Back returns to Live. Plays the
  portal's `create_link` first, then the raw `cmd` URL; 15 s without audio moves on / shows an error.
- **Guide.** ⏪ ⏩ step through groups (favourites lists first), remembered in `guideGroupId`; ⏯ back to now.
- **Speed.** The portal token is kept between launches (no handshake before the first channel; an expired
  one is renewed once). Playback starts after 500 ms of media. `baseline-prof.txt` pre-compiles app code.
  The info banner shows "Tuned in x.x s" (logcat `HindsPerf` also logs start-to-first-frame).
- **Background (`onStop`).** Playback stops (videos save their position first), which frees the decoder and
  the provider connection (Amazon requirement). Live TV resumes on `onStart`.

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
Then use portal `http://10.0.2.2:8080/c/` on the emulator, with any MAC starting `00:1A:79`, or the
playlist `http://10.0.2.2:8080/playlist.m3u` (it advertises its XMLTV guide at `/epg.xml`). The portal
serves `get_epg_info`, VOD categories/lists (films, a numbered series and a season/episode series) and radio
too; videos are seekable (`vod.mp4` with Range support).

**Emulator smoke test.**
- Cloud containers have no KVM, so the emulator runs in software. Expect about 10 minutes to boot and
  seconds per key press, but it does work.
  ```bash
  sdkmanager "emulator" "system-images;android-30;android-tv;x86"
  avdmanager create avd -n hindstv -k "system-images;android-30;android-tv;x86" -d tv_1080p
  emulator -avd hindstv -no-window -no-audio -no-boot-anim -accel off -gpu swiftshader_indirect -no-snapshot &
  adb install -r app/build/outputs/apk/debug/app-debug.apk   # built with -Phinds.abis=x86
  ```
- Typing through the IME is too slow there. Seed the profile instead: write `shared_prefs/hinds.xml` via
  `adb shell run-as com.hindstv.app.debug`, either with `portal_url` and `mac`, or with `source_type=playlist`
  and `playlist_url`.
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
- [x] **v0.2.**
  - M3U playlists and single stream links, with XMLTV guide.
  - Generate MAC.
  - Guide: Stalker `get_epg_info` + XMLTV into Room, now/next in banner and list, custom-drawn guide grid.
  - "Modern minimal" redesign.
- [x] **v0.3.**
  - "Cinema" redesign (direction B) with Manrope and section pills.
  - Videos: Stalker VOD (categories, paged lists, series models a and c) and playlist videos; details;
    seekable player; Netflix-style resume and "Continue watching"; series run on.
  - Radio: Stalker radio module and playlist radio; ICY now-playing; screen off.
- [x] **v0.4.**
  - Named favourites lists (several per channel; rename/delete; zap within a list).
  - Search across channels, guide, videos and radio; recent searches.
  - Speed: session token reuse, faster start of playback, baseline profile, on-screen tune time.
- [x] **v0.5.**
  - Settings: account + expiry, order/rename/delete lists, invert ▲/▼, clear history, diagnostics, about.
  - ⏪⏩ page jumps (channel list, VOD grid, radio); Back from VOD details returns to the same card.
  - Audio & subtitle picker with our own subtitle rendering.
- [ ] **Phase 1 — Prove it on the stick.**
  - Install on the 4K Max and connect to the real portal. Fix any portal quirks.
  - Measure the start, zap and memory budgets.
  - Confirm keycodes for Guide/Recents.
  - Check MP2 audio and 576i/1080i deinterlacing.
- [ ] **Phase 2 — Guide extras.**
  - Switch search to FTS4 if LIKE gets slow on very large lists.
  - Group switcher inside the guide.
  - Per-channel `get_short_epg` fallback for portals without bulk EPG.
- [ ] **Phase 3 — Polish.**
  - MediaSession.
  - Baseline Profile (generated on an emulator).
  - Aspect/zoom.
  - More settings (buffer size, AFR).
  - Parental PIN for censored groups.
- [ ] **Phase 4 — More sources.** Xtream Codes API, multiple profiles, per-playlist user agent.
- [ ] **Phase 5 — More VOD, catch-up.**
  - Series model (b) (`type=series` categories on newer portals); video search; favourite videos.
  - `tv_archive`.
  - Radio groups and favourites.
- [ ] **Phase 6 — Self-update** from GitHub Releases (`REQUEST_INSTALL_PACKAGES` + PackageInstaller).

**Known gaps and TODOs:**
- `do_auth` (login/password portals) isn't implemented; `get_profile` status 2 shows a message.
- The channel list is parsed as a JSON tree. Switch to streaming if a portal returns more than about 50k
  channels.
- HLS live offset tuning is untested.
- Stalker `get_epg_info` `period` is treated as hours (12). The response is capped at 40 MB in case a portal
  reads it as days.
- Playlist streams use the `HindsTV/0.3` user agent. Some providers may need a VLC-like one.
- Radio module calls (`type=radio`) and VOD series drill-down are built from protocol notes and the fake
  portal only; confirm them against the real portal in Phase 1.
