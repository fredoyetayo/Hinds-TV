# Hinds TV

A fast, lightweight IPTV player for Amazon Fire TV, built to replace STB Emu. It connects natively to
Stalker/Ministra ("MAC") portals, and also plays M3U playlists and single stream links. Besides live TV
and the TV guide it plays your provider's **videos** (films, series, lessons) and **radio**, and remembers
where you stopped in every video. It keeps your channel list and TV guide on the device, and is controlled
entirely with the Fire TV remote.

It is a player only: it contains no channels or content. You need your own legal IPTV subscription.

## Install on your Fire TV

You only need to do this once:

1. **Settings → My Fire TV → About.** Highlight your device name and press **Select** 7 times until it says
   you are a developer.
2. **Settings → My Fire TV → Developer options:**
   - turn on **ADB debugging**;
   - under **Install unknown apps**, allow **Downloader** (install Downloader from the Amazon Appstore first).

Then install the app, either way:

- **From a computer on the same Wi-Fi.** Install Android platform-tools, download `hinds-tv.apk` from the
  latest GitHub Release, then run:
  ```
  adb connect <fire-tv-ip>:5555    # IP: Settings → My Fire TV → About → Network
  adb install -r hinds-tv.apk
  ```
  Accept the "Allow USB debugging?" prompt on the TV the first time.
- **With Downloader.** Enter
  `https://github.com/fredoyetayo/Hinds-TV/releases/latest/download/hinds-tv.apk`. This only works if the
  repository is public.

Hinds TV appears under **Apps** (Recent / My Library).

## Try it on a computer

You can run the app on Windows or a Mac in Android Studio's TV emulator:

1. Install [Android Studio](https://developer.android.com/studio) (free).
2. **Device Manager → ＋ → TV → Television (1080p) → Next**, pick an **Android TV** system image (API 30 or
   newer; on Apple Silicon Macs choose an arm64 image) → **Finish**, then press ▶ to start it.
3. From the latest GitHub Release download **`hinds-tv-emulator.apk`** (not `hinds-tv.apk`, which is for
   the Fire TV) and drag it onto the emulator window to install.
4. Open Hinds TV from the emulator's apps row.

The keyboard is the remote: arrows = D-pad, Enter = Select, Esc = Back, and the emulator's side panel
(**⋯ → Directional pad**) has Menu and media buttons. Your provider allows one stream at a time, so don't
watch on the TV and the emulator together; the emulator uses the same MAC, so your portal works there too.

## First-time setup

Choose how you connect.

**MAC portal (STB Emu-style)**
1. Enter your **Portal URL**, exactly as your provider gave it.
2. Enter the **MAC address** your provider registered. It's the one shown in STB Emu under
   *Settings → Profile → STB configuration*.
   - Or select **Generate** for a new one. Send it to your provider; it won't work until they register it.
3. Most portals need nothing else. If yours also checks the serial number, device IDs or signature, copy
   them from STB Emu into *Advanced settings*.

**Playlist or link**
1. Paste an **M3U playlist** link, or a link to a **single stream** such as an `.m3u8`.
2. Optionally add a **TV guide (XMLTV)** link. If the playlist advertises its own guide, it's used
   automatically.

Then select **Connect**. Channels and the TV guide are stored on the TV, so later starts are instant.

**Your provider allows one stream at a time.** Close STB Emu (or don't watch on another device with the
same MAC) while using Hinds TV.

## Remote control

| Button | While watching | In the channel list |
|---|---|---|
| ▲ ▼ / Channel ▲▼ | Next / previous channel | Move (Channel ▲▼ still zaps) |
| Select / ◀ | Open channel list | Play channel |
| Live (TV) button | Open TV guide | Open TV guide |
| ▶ | Show channel info | Move to channels |
| ☰ Menu | Add to a favourites list | Add to a favourites list (on a list: rename/delete it) |
| ⏪ | Previous channel | — |
| ⏯ | Stop / resume | — |
| Back | Press twice to exit | Close list |

Along the top of the channel list, Videos and Radio there are section buttons: **Live · Guide · Videos ·
Radio · Search · Settings**. However far down a list you are, **Back** jumps straight up to them; Back again
leaves the screen.

**Favourites lists**
- Press ☰ on any channel (or while watching it) and tick the lists it belongs in. Make new lists there
  ("Kids", "Sport", …). Each list appears at the top of the groups, and ▲▼ zaps within it.
- ☰ on a list in the groups column renames or deletes it.

**Search**
- One box finds channels, programmes in the TV guide (on now and later), videos and radio as you type.
  Recent searches are kept as one-press shortcuts.
- If the Fire TV keyboard shows a microphone hint, you can hold the remote's voice button and speak
  instead of typing (this depends on Fire OS, not on the app).

**VOD**
- Categories on the left (Continue watching first, then All VOD and your provider's categories), the
  chosen one as a grid on the right. **Search** filters the category by title; the order button switches
  between Newest and A–Z. Big libraries load as you scroll.
- On a video: **Resume from 12:31** or **Play from start**. Series show their episodes, with what you've
  watched.
- While playing: ◀ ▶ skip 10 s, ⏪ ⏩ skip 30 s (press repeatedly to go further), Select or ⏯ pause,
  Back returns to the video's page. Your place is saved every few seconds, when you stop, and when the TV
  goes off. Series play the next episode automatically.

**Radio**
- Pick a station; **Stop** and **Screen off** (black screen, music keeps playing; any button turns the
  picture back on).

Videos and radio use your provider's one connection, so live TV stops while they play and comes back when
you return to Live.

In the **TV guide** (⏪ ⏩ switch between groups, e.g. just one favourites list, and it remembers your
choice; ⏯ jumps back to now):
- ▲▼ moves between channels.
- ◀▶ moves between programmes.
- Channel ▲▼ pages up and down.
- Select watches the channel.
- Back or Live closes the guide.

## Development

See [`docs/RESEARCH.md`](docs/RESEARCH.md) for the device, protocol and technology research. Build with
`./gradlew assembleRelease` (Android SDK required); `./gradlew testDebugUnitTest lintDebug` runs the checks.

## Licences

The app code is © its author. It bundles the Manrope typeface (SIL Open Font License 1.1; licence text in
`app/src/main/assets/licenses/`) and FFmpeg audio decoders (LGPL-2.1, built without GPL parts) via the
Media3 FFmpeg extension (Apache-2.0); see [`ffmpeg-audio/README.md`](ffmpeg-audio/README.md) for how to
rebuild them from source. The other libraries are Apache-2.0.
