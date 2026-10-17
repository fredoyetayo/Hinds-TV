# Hinds TV

A fast, lightweight IPTV player for Amazon Fire TV, built to replace STB Emu. It connects natively to
Stalker/Ministra ("MAC") portals, keeps your channel list on the device, and is controlled entirely with
the Fire TV remote.

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

## First-time setup

1. Open Hinds TV and enter your **Portal URL**, exactly as your provider gave it.
2. Enter the **MAC address** your provider registered. It's the one shown in STB Emu under
   *Settings → Profile → STB configuration*.
3. Most portals need nothing else. If yours also checks the serial number, device IDs or signature, copy
   them from STB Emu into *Advanced settings*.
4. Select **Connect**. Your channels load once and are stored on the TV, so later starts are instant.

**Your provider allows one stream at a time.** Close STB Emu (or don't watch on another device with the
same MAC) while using Hinds TV.

## Remote control

| Button | While watching | In the channel list |
|---|---|---|
| ▲ ▼ / Channel ▲▼ | Next / previous channel | Move (Channel ▲▼ still zaps) |
| Select / ◀ / Live (TV) | Open channel list | Play channel |
| ▶ | Show channel info | Move to channels |
| ☰ Menu | Add/remove favourite | Add/remove favourite |
| ⏪ | Previous channel | — |
| ⏯ | Stop / resume | — |
| Back | Press twice to exit | Close list |

## Development

See [`CLAUDE.md`](CLAUDE.md) for architecture, rules and build commands. See
[`docs/RESEARCH.md`](docs/RESEARCH.md) for the device, protocol and technology research.

## Licences

The app code is © its author. It bundles FFmpeg audio decoders (LGPL-2.1, built without GPL parts) via the
Media3 FFmpeg extension (Apache-2.0); see [`ffmpeg-audio/README.md`](ffmpeg-audio/README.md) for how to
rebuild them from source. The other libraries are Apache-2.0.
