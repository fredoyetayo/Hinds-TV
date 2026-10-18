# Hinds TV — Feasibility & Technical Research

_Researched 17 Oct 2026. Version numbers and platform facts were current on that date; re-check versions when scaffolding._

Goal: a fast, lean, sideloaded IPTV player for **Amazon Fire TV Stick 4K**, driven entirely by the Fire TV remote, replacing STB Emu for a **legal** subscription delivered as a Stalker/Ministra ("MAC") portal.

---

## 1. Verdict

**Yes, it is buildable, and it can be much faster than STB Emu.** STB Emu is slow mainly because of how it is built, not because of the hardware. A native app that talks to the portal's JSON API, caches everything locally and keeps one tuned video player will feel far quicker on the same stick.

Main caveats:
- **Platform future.** New Fire TV sticks (from Oct 2025) run Vega OS, which **cannot** run Android apps. Your July 2025 stick is Fire OS 8 (Android), so it's fine. A future replacement stick may not be (see §3).
- **Provider limits.** A native app can't fix a slow provider server or poor Wi-Fi. If streams buffer in every app, the cause is upstream.
- **Portal variation.** Stalker portals differ by version and reseller panel. Testing against the real portal early is essential.

---

## 2. The device

**Owner's device (confirmed 17 Oct 2026): Fire TV Stick 4K Max (2nd Gen), Fire OS 8 — `AFTKRT`.** For reference, the 4K stick that was also on sale in July 2025 (UK Prime Day was 8–11 Jul 2025) is listed alongside it.

| | Fire TV Stick 4K (2nd Gen, 2023) | Fire TV Stick 4K Max (2nd Gen, 2023) |
|---|---|---|
| `Build.MODEL` | **AFTKM** | **AFTKRT** |
| SoC | MediaTek MT8696D, 4× Cortex-A55 @ 1.7 GHz | MT8696T, 4× A55 @ 2.0 GHz |
| GPU | PowerVR GE9215 @ 650 MHz (GLES 3.2) | GE9215 @ 850 MHz |
| RAM / storage | 2 GB / 8 GB | 2 GB / 16 GB |
| Userspace ABI | **32-bit (armeabi-v7a)** | **32-bit** |
| Wi-Fi | Wi-Fi 6 | Wi-Fi 6E |
| OS | **Fire OS 8 = Android 11 (API 30)** | Fire OS 8 = Android 11 |

The Vega OS sticks (4K Select, Oct 2025, and 4K 3rd Gen, Sep 2026) were not on sale in July 2025. The "Fire TV Stick 4K Plus" (UK, Dec 2025) is a rename of the 2023 4K stick.

### Media capabilities (AFTKM / AFTKRT, Amazon spec page)
- **Video (hardware):**
  - H.264: 4K60
  - HEVC Main10: 4K60
  - VP9 Profile 2
  - AV1: 4K60
  - **MPEG-2: up to 1080p60**
  - MPEG-4 Part 2: 1080p30
- **HDR:** HDR10, HDR10+, HLG, Dolby Vision.
- **Audio:**
  - Decoded on the device: AAC (LC/HE/xHE), AC3, E-AC3, Atmos, AC4, FLAC, Opus, Vorbis, PCM.
  - Decoded in software: MP3.
  - Passthrough: TrueHD, DTS.
- **MPEG-1 Layer II (MP2) audio is not listed.** MP2 is very common in IPTV MPEG-TS streams, so we need a software decoder for it (Media3's FFmpeg audio extension).
- Deinterlacing of 1080i/576i on the 2023 stick is **unverified**. Test it on the device.

### Amazon performance targets (KPI guidelines, updated Jul 2026)
| Metric | Target |
|---|---|
| Cold start to first frame | < 2 s |
| Warm start to first frame | < 1 s |
| Foreground memory (PSS) while playing ≤1080p | < 300 MB |
| Foreground memory (PSS) while playing 4K | < 400 MB |
| Background memory | < 100 MB (apps above this are killed first) |
| Flash writes | < 50 MB/hour (never buffer video to disk) |

Free RAM and `dalvik.vm.heapgrowthlimit` on the stick are **not publicly documented**. Measure them with `adb shell getprop dalvik.vm.heapgrowthlimit` and `ActivityManager.getMemoryClass()`.

---

## 3. Platform risk: Vega OS

- **What it is.** Vega OS is Amazon's own Linux-based OS. Its apps are React Native for Vega or web apps. **It cannot install Android APKs.**
- **Devices:** Fire TV Stick 4K Select (2025), Fire TV Stick HD (2026) and Fire TV Stick 4K 3rd Gen (2026).
- **Amazon's stated direction:** "Starting with Fire TV Stick 4K Select, all future Fire TV Sticks will run on Vega."
- **Existing devices:** Amazon told heise it has no plans to move existing Fire OS devices to Vega.
- **How to tell which OS a device runs:** Settings → My Fire TV → About. "Fire OS 8.x" means Android, which is good. "OS 1.x" or "OS 2.x" means Vega.

**Mitigation:** we build a standard Android TV app. Besides Fire OS 8 sticks, the Fire TV Cube and Fire OS smart TVs, it will also run on Google TV and Android TV boxes (Google TV Streamer, onn 4K, Nvidia Shield). If the stick ever dies, a Google TV box is the natural replacement.

---

## 4. Sideloading and Amazon's piracy blocking

### Install steps (Fire OS 8)
1. Settings → My Fire TV → About. Highlight the device name and press Select 7 times until "You are now a developer" appears.
2. Settings → My Fire TV → Developer options → turn on **ADB debugging**.
3. In **Install unknown apps**, allow the installer app (e.g. Downloader). This is a per-app setting.
4. Install the APK, using either:
   - **Downloader:** enter the APK's URL (e.g. a GitHub Releases link), or
   - **adb from a computer on the same network:** `adb connect <ip>:5555`, accept the prompt on the TV, then `adb install -r app.apk`.

### Amazon's piracy crackdown
- **Oct–Dec 2025:** Amazon started blocking sideloaded apps on the ACE anti-piracy list. It warned users first, then disabled the apps.
- **Since Jan 2026:** flagged apps are blocked at install time, reportedly **by package name**.
- **Sideloading itself stays allowed.** Amazon calls it "a valuable tool for developers".
- **Effect on us: none expected.** It's a self-built player, with no bundled content, used with a legal subscription. Use a **unique package name** (`uk.co.fredwebstudio.hindstv` or similar) and never reuse a known IPTV app's package name.

### Fire OS 8 install requirements
- APKs signed with **v1 only will not install**. Use v2 or newer; AGP does this by default.
- Use **one permanent release keystore.** Otherwise updates fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Fire OS 8.1.8.2 had a bug, reported Aug 2026, that hid Downloader from "Install unknown apps". The fix began rolling out 16 Sep 2026.
- After **4 hours with no remote input**, Fire TV shows an "Are you still watching?" prompt. Users can turn it off in Settings. Worth noting for long live-TV sessions.

---

## 5. Why STB Emu is slow and crashes

STB Emu emulates an Infomir MAG 250/270/322 set-top box. It **loads the provider's MAG web portal (HTML/JS written for the MAG's QtWebKit browser) in a browser engine** and emulates the MAG JavaScript API (`gSTB`, player objects). Video is handed to a separate player.

Why that design performs badly on a 2 GB stick:
- **No local storage of data.** Every list page and guide view is a fresh `load.php` request, about 14 items per page, so scrolling 10,000 channels means hundreds of round trips. Favourites live on the server.
- **Heavy browser layer.** Single-threaded legacy JavaScript runs inside a full browser engine, and every `gSTB` call crosses a JS↔native bridge.
- **Extra GPU work.** Video sits under a transparent browser layer, adding compositing load. The hand-off between portal and player is also a common failure point.
- **Ads.** The free version includes ad SDKs.
- **User reports:** freezes after 10–40 minutes, lost favourites, broken backup/restore on Android 11+, and volume resets.

STB Emu is still maintained (v2.0.24, updated 11 Oct 2026), so the problems come from its design, not neglect.

**Our approach:** call the portal's JSON API directly, store channels and EPG in a local database, sync in the background, render natively, and resolve stream URLs only when playing.

---

## 6. Stalker / Ministra portal API (summary for implementation)

> Reimplement from these protocol facts. **Do not copy code** from pvr.stalker (GPL-2.0+), OwnTV, stalkerhek or stalkervod (GPL-3.0), or STB-Proxy (no licence). Protocol facts are fine to reimplement.

### 6.1 Endpoint discovery
- A portal URL ending in `/c/` → API at `<base>/server/load.php`, Referer `<base>/c/`.
  - `http://host:8080/c/` → `http://host:8080/server/load.php`
  - `http://host/stalker_portal/c/` → `http://host/stalker_portal/server/load.php`
- Otherwise probe in order: `{root}/portal.php`, `{root}/stalker_portal/server/load.php`, `{root}/server/load.php`. Keep the first one where the handshake succeeds.
- Most robust method: fetch `/c/xpcom.common.js` and parse `this.ajax_loader = …php`.
- `portal.php` usually means an Xtream-UI/XUI panel emulating a MAG portal. Many "Stalker" subscriptions are these panels.
- Follow redirects and carry cookies to the new host.
- An HTML response means the wrong endpoint or user agent.

### 6.2 Headers (every call)
```
GET {endpoint}?type=…&action=…&JsHttpRequest=1-xml
User-Agent: Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3
X-User-Agent: Model: MAG250; Link: WiFi
Referer: http://host/stalker_portal/c/
Cookie: mac=00%3A1A%3A79%3AXX%3AXX%3AXX; stb_lang=en; timezone=Europe%2FLondon
Authorization: Bearer <token>        (all calls except handshake)
```
Make the user agent, `stb_type` and model configurable per profile.

### 6.3 Authentication
1. **Handshake:** `type=stb&action=handshake&token=&prehash=0` → `{"js":{"token":"…"}}`
2. **get_profile:** `type=stb&action=get_profile`
   - **Parameters sent:**
     - `stb_type=MAG250`, `sn`, `device_id`, `device_id2`, `signature`
     - `hd=1`, `num_banks=1`, `image_version=216`, `hw_version=1.7-BD-00`
     - `ver=…`, `not_valid_token=0`, `auth_second_step=0`
   - **Response fields** (`js`): `id`, `status`, `msg`, `block_msg`, `watchdog_timeout` (default 120), `timeslot` (default 90).
   - **Status values:** `0` = OK, `2` = call `do_auth` next, anything else = blocked or error.
3. **do_auth** (login/password portals only): `type=stb&action=do_auth&login=&password=&device_id=&device_id2=`, then call get_profile again with `auth_second_step=1`.
4. **Device identity.** Serial number, device IDs and signature are **user-supplied settings** (as in pvr.stalker), copied from the user's existing STB Emu profile.
   - We do **not** generate or scan identities.
   - Most portals ignore the serial number. Some bind the device IDs, and a wrong `stb_type` causes a "device conflict" `block_msg`.
5. **Keep-alive:** `type=watchdog&action=get_events&init=0&cur_play_type=1&event_active_id=0`, sent every `timeslot` seconds.
6. **Account info:** `type=account_info&action=get_main_info`. The expiry date can appear as `end_date`, `exp_date`, `expire_billing_date`, `tariff_expired_date` or even `phone`.
7. **Detecting a dead token:**
   - The plain-text body `Authorization failed.`
   - `{"js":false}` or an empty `js`
   - HTTP 401

   When one of these happens, handshake again **once** and retry the call. Never re-handshake more than once a minute. **403 usually means throttling or too many connections, not a logout**, so back off.

### 6.4 Live TV
- **Genres:** `type=itv&action=get_genres` → `[{id,title,alias}]`
- **Channels:**
  - `type=itv&action=get_all_channels` returns the whole list in one response. Some portals cap or deny it.
  - Fallback: `type=itv&action=get_ordered_list&genre=*&fav=0&sortby=number&p=N` → `{total_items, max_page_items, data[]}`
- **Channel fields:** `id, name, number, cmd, logo, tv_genre_id, xmltv_id, use_http_tmp_link, use_load_balancing, enable_tv_archive | archive, tv_archive_duration`
- **Playable URL:** `type=itv&action=create_link&cmd=<urlencoded cmd>&series=&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0` → `js.cmd`, e.g. `ffmpeg http://h:p/play/live.php?mac=…&stream=123&extension=ts&play_token=…`
  - Strip any prefix before the first space (`ffmpeg `, `ffrt2 `, `auto `).
  - Some portals return `js.url` or a bare string instead.
  - **Call create_link at play time on every zap**, as STB Emu does. Cache the result for a few seconds at most and fall back to the raw `cmd`.
  - A `cmd` containing `localhost` or `127.0.0.1` → rewrite it to the portal host.

### 6.5 EPG
- **Bulk:** `type=itv&action=get_epg_info&period=N` → `js.data{"<ch_id>":[{id,name,descr,start_timestamp,stop_timestamp}]}`
  - Download fully to disk before parsing; portals drop idle keep-alive sockets.
  - Whether `period` is in hours or days is unverified.
- **Now/next:** `type=itv&action=get_short_epg&ch_id=&size=`
- **Day table:** `type=epg&action=get_simple_data_table&ch_id=&date=YYYY-MM-DD&p=`. Rows include `mark_archive`, which catch-up needs.
- **XMLTV** is not standard on Stalker. Support an optional XMLTV URL that overrides the provider's EPG.

### 6.6 VOD and series
- **Categories:** `type=vod&action=get_categories` (also `type=series` on newer portals)
- **Lists:** `type=vod&action=get_ordered_list&category=&sortby=added&p=` (optional `&search=`)
- **Playing:** `type=vod&action=create_link&cmd=<cmd>`. Add `series=<ep>` only for episodes; `series=0` on a film gives `nothing_to_play`.
  - The response is either a URL in `js.cmd`/`js.url`, or `js.id` + `js.play_token`, which becomes `{base}/play/movie.php?mac=&stream=<id>.mp4&play_token=&type=vod`.
- **Three series models exist; support all of them:**
  - (a) A VOD item with `series:[1..N]`.
  - (b) `type=series&action=get_ordered_list&movie_id=` returns seasons, each with `series[]`.
  - (c) Ministra drill-down by `movie_id/season_id/episode_id`, played with `cmd=/media/file_<id>.mpg`.

- **Hinds TV implementation notes (v0.3):** lists are requested with `category=<id>&genre=*&sortby=added&fav=0&p=N`;
  item fields used: `id, name|o_name, description, year, time` (minutes), `genres_str, screenshot_uri, cmd,
  series[], is_series`. Seasons: `movie_id=<id>&season_id=0&episode_id=0`; episodes: `season_id=<sid>`
  (fields `id, name, series_number, cmd`; missing `cmd` → `/media/file_<id>.mpg`).

### 6.6a Radio
- **Stations:** `type=radio&action=get_ordered_list&p=N` (paged like itv) → `id, name, number, cmd, logo`.
- **Playing:** the `cmd` is normally the stream URL itself (strip the player prefix). When it isn't a usable
  URL (e.g. `localhost` or a `/media/…` path), ask `type=radio&action=create_link&cmd=…`.
- Unverified against a real portal; a portal without the module returns an empty list or an error, which
  must not break the channel sync.

### 6.7 Catch-up
- A channel supports catch-up when `enable_tv_archive` or `archive` is set.
- Take the programme id from `get_simple_data_table` rows where `mark_archive=1`.
- Call `type=tv_archive&action=create_link&cmd=auto%20/media/<programme_id>.mpg`. Archive links are short-lived, so fetch a fresh one each time.

### 6.8 Quirks to design for
- **One stream per MAC.** STB Emu and Hinds TV must not use the same MAC at the same time. Fully release the old stream before calling create_link for the next channel. Never preload neighbouring channels.
- **Rate limits on fast zapping.** 13 opens in 3.6 s produced HTTP 429 for the whole burst. Fix: tune a lone key press immediately, and when presses arrive less than 1.5 s apart, wait about 500 ms (cancelled by the next press). XUI panels can lock the account for about 2 minutes (HTTP 458) after a burst.
- **Redirects.** Stream URLs often 302 to tokenised edge hosts; follow cross-host redirects.
- **Formats:** mostly MPEG-TS over HTTP, plus HLS, and occasionally udpxy.
- **Version differences.** Ministra versions (4.x to 5.6.1) and reseller panels differ in endpoint path, create_link response shape, series model, field names and get_all_channels availability.
- **Timeouts.** Use 10–30 s timeouts and cache everything on disk.
- **Responses** are always `{"js": …}`. Lists come as `{total_items, max_page_items, cur_page, data:[…]}`. Errors appear in `js.error` or as plain text.

---

## 7. Other formats (planned)

Many legal providers offer these too, and they are simpler and often faster than Stalker.

- **Xtream Codes API**
  - **Login:** `{s}/player_api.php?username=U&password=P` → `user_info` (`exp_date`, `max_connections`, `active_cons`…)
  - **Actions:** `get_live_categories`, `get_live_streams`, `get_vod_categories`, `get_vod_streams`, `get_vod_info`, `get_series`, `get_series_info`, `get_short_epg` (titles base64-encoded)
  - **Stream URLs:**
    - Live: `{s}/live/U/P/{id}.ts`
    - Films: `{s}/movie/U/P/{id}.{ext}`
    - Episodes: `{s}/series/U/P/{id}.{ext}`
    - Catch-up: `{s}/timeshift/U/P/{mins}/{YYYY-MM-DD:HH-MM}/{id}.ts`
  - **EPG:** `xmltv.php?username=&password=`
- **M3U + XMLTV**
  - Header: `#EXTM3U url-tvg="…"`
  - Entries: `#EXTINF:-1 tvg-id tvg-name tvg-logo tvg-chno group-title catchup catchup-days catchup-source,Name`
  - Guide: standard XMLTV.

---

## 8. Recommended stack (lean, no bloat)

| Area | Choice | Why |
|---|---|---|
| Language | Kotlin 2.4.x | Standard; no runtime cost |
| Build | AGP 9.4.x, Gradle 9.6+, version catalog | Current stable; AGP 9 has Kotlin built in |
| SDK levels | minSdk 28 (Fire OS 7+), target/compile 36 | Covers older sticks; target is irrelevant for sideloading |
| ABI | **armeabi-v7a only** | Stick userspace is 32-bit; halves native size |
| UI | Jetpack Compose (foundation) + **tv-material 1.1.0**; no phone Material3 | Leanback is deprecated; tv-foundation's TvLazy* layouts were removed; use standard Lazy lists |
| EPG grid | **Custom-drawn** (single focus target, Canvas + cached `TextMeasurer`, our own focused-cell state) | Thousands of focusable composables is the #1 jank trap |
| Player | **Media3 ExoPlayer 1.11.x**, `media3-datasource-okhttp`, **one shared player instance**, **SurfaceView** | Hardware decode; reusing codecs speeds up zapping; Media3 recommends SurfaceView over TextureView |
| Extra audio codecs | **Self-built** Media3 FFmpeg *audio* extension (mp2/mp3, ac3, eac3, dca, aac), about 1–1.5 MB | Fills the MP2 gap. Prebuilts are GPL and pinned to old Media3 versions. LibVLC would add about 40 MB |
| Networking | OkHttp 5.x (one shared client) | Warm connection pool shared by API, EPG, logos and video; no Cronet or Play Services on Fire OS |
| JSON | kotlinx.serialization 1.11 (`decodeToSequence` streaming) | Streams huge channel arrays without loading them whole |
| XML (XMLTV) | `XmlPullParser` over `GZIPInputStream`, streamed from disk | Never DOM or whole-file parsing |
| Database | Room 2.8.x, platform SQLite, FTS4 for search | No bundled SQLite needed (saves 1.25 MB) |
| Images | Coil 3 (shares OkHttp), explicit sizes, small memory cache | Logo memory under control |
| DI | Manual (`AppContainer` + `lazy`) | No Hilt or Koin startup cost |
| Background sync | WorkManager 2.12 (on-demand init) plus a freshness check at launch | The stick kills background apps aggressively |
| Excluded | Ads, analytics, Firebase, crash SaaS, Play Services, LibVLC, mpv | Keeps it lean; Play Services don't exist on Fire OS anyway |

**Target APK size: under 10 MB.**

### Zapping and playback tuning
- **Player instance.** Keep a single ExoPlayer. Zap with `setMediaItem()` + `prepare()`; never release and rebuild.
- **Buffering.** Use `DefaultLoadControl` tuned for live, e.g. `setBufferDurationsMs(15000, 30000, 500–1000, 2000–3000)`, and cap target buffer bytes at about 16–32 MB. The default allows about 131 MB of video buffer.
- **Progressive TS** (the fastest start):
  - Use a TS-only extractors factory to skip format sniffing.
  - Set `FLAG_ALLOW_NON_IDR_KEYFRAMES`.
  - Report `LENGTH_UNSET` for live URLs so a fake `Content-Length` doesn't trigger a seek to the end before playback starts.
- **HLS:** use a lower `LiveConfiguration.targetOffsetMs`. On `BEHIND_LIVE_WINDOW`, call `seekToDefaultPosition()` + `prepare()`.
- **Frame-rate switching:** off, or seamless-only, while zapping. An HDMI resync costs seconds of black screen.
- **Tunneling mode:** opt-in only.
- **Amazon multimedia requirements:**
  - Release decoders in `onStop()`.
  - Keep the screen on during playback.
  - Use MediaSession.
  - Handle PLAY_PAUSE even on live streams.
  - Implement `onTrimMemory`.

### UI performance rules
- **Builds and profiles:**
  - Judge performance only on **release** builds with R8 (`isMinifyEnabled`, `isShrinkResources`).
  - Ship a **Baseline Profile** plus `profileinstaller`. Generate it on an emulator; it carries over to the stick.
- **Compose state:**
  - Give list items stable `key`s.
  - Mark models `@Immutable`.
  - Read fast-changing state in leaf composables or in the draw phase.
  - Update the EPG "now" line once a minute.
- **During scrolling:**
  - Pause non-render work while `isScrollInProgress`.
  - No crossfades in lists.
  - Avoid large alpha, shadow or blur layers; the GE9215 GPU is weak.
- **Focus:**
  - Use `focusRestorer` on rows.
  - Never call `requestFocus()` before the node is attached.
  - Handle key-repeat bursts by throttling.

### Data rules
- **Channel lists:** load a light projection of all channels (id, number, name, logo, group) — 50k rows is about 5 MB. That's simpler and better for D-pad than Paging.
- **EPG queries:** load by window (row range × time range), with a small LRU cache.
- **EPG import:**
  - Insert into an unindexed staging table in batches of 1–5k rows.
  - Then build the index on `(channel_id, start)` and swap the table in one transaction.
  - Store epoch seconds. Use WAL with `synchronous=NORMAL`.
  - Keep only about −1/+3 days, and skip channels that aren't in the playlist.
- **App startup:** open straight to the last-watched channel. Start the stream in parallel with the first frame of UI.

---

## 9. Remote control

**Owner's remote (photo, 17 Oct 2026): Alexa Voice Remote Enhanced (UK).** Its buttons, top to bottom:
- Power and Alexa.
- D-pad ring with Select.
- Back, Home, Menu (☰).
- ⏪ ⏯ ⏩.
- Volume ±, a **Live/Guide (TV icon)** button, **Channel ▲/▼**.
- Mute, Settings (⚙), Recents.
- Prime Video, Netflix, Apps and Disney+ shortcut buttons.

| Button | KeyEvent | Planned use |
|---|---|---|
| D-pad ▲ ▼ ◀ ▶ | `DPAD_UP/DOWN/LEFT/RIGHT` (19–22) | Navigation. In full-screen: ▲/▼ change channel, ◀ opens the channel list, ▶ opens the info panel |
| Select | `DPAD_CENTER` (23) | Select. In full-screen: mini-guide / info |
| Back | `BACK` (4) | Back. In full-screen: show the channel list or exit to the guide; never quit straight out |
| Menu (☰) | `MENU` (82) | Context menu (favourite, catch-up, audio track, subtitles) |
| ⏯ / ⏪ / ⏩ | `MEDIA_PLAY_PAUSE` (85) / `MEDIA_REWIND` (89) / `MEDIA_FAST_FORWARD` (90) | VOD and catch-up control. On live: last-channel toggle and page up/down in lists |
| Channel ▲/▼ (Enhanced remote) | `CHANNEL_UP/DOWN` (166/167) | Zapping |
| Home, Alexa, volume, power, app buttons | n/a | **Can't be intercepted** by apps |

Guide, Recents, Settings and Mute are not in Amazon's keycode table. Log `onKeyDown` on the device to see what, if anything, arrives. There are **no number keys**, so number entry needs an on-screen pad.

---

## 10. Challenges and risks

| # | Challenge | Impact | Mitigation |
|---|---|---|---|
| 1 | Future Fire TV sticks run Vega OS (no APKs) | Medium (long term) | Standard Android TV app that runs on any Google TV / Android TV box |
| 2 | Portal differences between versions and panels | High | Endpoint probing, tolerant parsing, configurable identity; test against the real portal early |
| 3 | Device-ID binding on some portals | Medium | Copy SN / device IDs / signature / model from the existing STB Emu profile |
| 4 | One connection per account | High | Strict single-stream lifecycle; stop STB Emu using the same MAC |
| 5 | Rate limits on fast zapping | Medium | Debounce bursts; back off on 403/429/458 |
| 6 | MP2 audio has no hardware decoder | High (no sound on many channels) | FFmpeg audio extension |
| 7 | Interlaced channels | Low–Medium | Test hardware deinterlacing on the device |
| 8 | 2 GB RAM, weak GPU | Medium | Streaming parsers, windowed EPG, custom-drawn grid, capped buffers, small image cache |
| 9 | Cloud dev container can't reach the TV over the LAN | Process | CI builds the APK and publishes it to GitHub Releases; install with Downloader. Or run Claude Code on a local PC for `adb` |
| 10 | Provider or Wi-Fi slowness | Not fixable in the app | Fast failure, clear errors, auto-retry; suggest the Amazon Ethernet adapter |
| 11 | "Are you still watching?" after 4 hours idle | Minor | Document the Fire TV setting |
| 12 | Licensing | Low | Clean-room protocol implementation; build our own FFmpeg (LGPL decoders only) |

---

## 11. Build and install workflow

1. **Build:** Gradle command line with the Android cmdline-tools (no Android Studio needed). This works in the Claude Code cloud container: Google's SDK repo and Maven are reachable.
   - Maven Central has rate-limited this container (HTTP 429) before. Add Google's mirror `https://maven-central.storage-download.googleapis.com/maven2/` to `settings.gradle.kts`.
2. **CI (GitHub Actions):** `setup-java` → `setup-gradle` → decode the release keystore from a secret → `assembleRelease` → attach the APK to a GitHub Release.
3. **Install on the stick:** in Downloader, enter `https://github.com/<owner>/<repo>/releases/latest/download/hinds-tv.apk`.
   - Works only if the repo or release is public. For a private repo, use adb from a local machine, or another host.
4. **Later:** in-app self-update from GitHub Releases (`REQUEST_INSTALL_PACKAGES` + PackageInstaller). On API 30 the user confirms each install.

---

## 12. Proposed roadmap

| Phase | Scope |
|---|---|
| **0 — Foundation** | Project scaffold, performance budgets and rules (CLAUDE.md), CI building a signed APK, an app shell that installs on the stick, D-pad navigation, plays a test stream. Proves the build→sideload loop end to end |
| **1 — Live TV core** | Stalker client (discovery, auth, watchdog, channels, create_link); local DB; channel list by group; full-screen player; fast zapping; favourites; resume last channel on launch |
| **2 — Guide** | EPG sync; custom TV guide grid; now/next mini-guide overlay; search |
| **3 — More sources** | Xtream Codes and M3U/XMLTV; multiple profiles; catch-up |
| **4 — VOD and series** | Films and series browsing with all three series models; resume positions |
| **5 — Polish** | Self-updater, parental PIN, audio/subtitle track picker, settings backup, tunneling and AFR options |

---

## 13. Owner's answers (17 Oct 2026)

1. **Device:** Fire TV Stick 4K Max, Fire OS 8.
2. **Provider:** a Stalker portal URL plus a MAC address. The provider's setup instructions:
   1. Install STB Emu and create a profile.
   2. Enter the portal URL.
   3. Send the provider the MAC shown under *STB Configuration*. The provider then registers that MAC.

   So Hinds TV must use **the same MAC** STB Emu generated (prefix `00:1A:79`).
   - The portal URL is a bare `https://host/`, so endpoint discovery matters: `portal.php`, `server/load.php`
     and `stalker_portal/server/load.php` are probed in turn.
   - The provider didn't mention M3U or Xtream.
3. **STB Emu identity settings:** not reported. The defaults are assumed (MAG250, no custom serial or device
   IDs). They can be entered under Advanced settings if the portal rejects the device.
4. **Remote:** the Enhanced remote, with Channel ▲/▼ and a Live/Guide button (see §9).

---

## Sources

**Fire TV device, OS and sideloading**
- Amazon Fire TV device specs: https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html
- Fire OS 8 notes: https://developer.amazon.com/docs/fire-tv/fire-os-8.html
- Fire OS overview: https://developer.amazon.com/docs/fire-tv/fire-os-overview.html
- Remote input: https://developer.amazon.com/docs/fire-tv/remote-input.html
- KPIs: https://developer.amazon.com/docs/app-testing/measure-kpis-fire-tv-apps.html
- Multimedia app requirements: https://developer.amazon.com/docs/fire-tv/multimedia-app-requirements.html
- ADB: https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html
- Vega OS announcement: https://developer.amazon.com/apps-and-games/blogs/2025/09/announcing-vega-os
- AFTVnews, all future sticks on Vega: https://www.aftvnews.com/amazon-confirms-all-future-fire-tv-sticks-will-run-vega-os-no-more-android-or-sideloading-on-new-models/
- heise, no Vega migration for existing devices: https://heise.de/-10964878
- Piracy blocking:
  - https://www.aftvnews.com/amazon-to-start-blocking-sideloaded-piracy-apps-on-fire-tvs/
  - https://www.aftvnews.com/fire-tvs-now-block-piracy-apps-on-installation-instead-of-at-launch/
  - https://www.cordbusters.co.uk/amazon-blocking-dodgy-iptv-all-fire-tv/

**Stalker portal clients and docs**
- Kodi pvr.stalker: https://github.com/kodi-pvr/pvr.stalker
- stalkerhek: https://github.com/erkexzcx/stalkerhek
- STB-Proxy: https://github.com/Chris230291/STB-Proxy
- stalkervod: https://github.com/rickeylohia/plugin.video.stalkervod
- StalkerWeb: https://github.com/rangoDJ/stalkerweb
- OwnTV: https://github.com/ahXN00/OwnTV
- OwnTV_Core: https://github.com/ahXN00/OwnTV_Core
- STB Emu docs: https://docs.stbemu.com/print.html
- Infomir gSTB API: https://soft.infomir.com/stbapi/JS/v343/gSTB.html
- OTT Navigator changelog: https://ottnav.github.io/changelog.html

**Android libraries and tooling**
- Media3 releases: https://developer.android.com/jetpack/androidx/releases/media3
- Media3 supported formats: https://developer.android.com/media/media3/exoplayer/supported-formats
- Media3 surfaces: https://developer.android.com/media/media3/ui/surface
- Media3 live streaming: https://developer.android.com/media/media3/exoplayer/live-streaming
- Media3 network stacks: https://developer.android.com/media/media3/exoplayer/network-stacks
- Media3 FFmpeg extension: https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md
- Compose for TV: https://developer.android.com/jetpack/androidx/releases/tv
- Compose for TV lists: https://developer.android.com/training/tv/playback/compose/lists
- Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- AGP release notes: https://developer.android.com/build/releases/gradle-plugin
