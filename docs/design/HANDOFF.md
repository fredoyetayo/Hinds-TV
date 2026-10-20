# Hinds TV · design handoff (Dark and Light)

This folder is the design for the next Hinds TV UI: every screen in a **Dark** theme (today's "Cinema"
palette) and a new **Light** theme, switched in **Settings → Appearance**. It was designed against this repo
and keeps every rule in `CLAUDE.md`.

- The live design canvas (Fred's, private): https://claude.ai/artifact/P8ji2eaDzmfAUhAaY9Tbjt
- `screens/*.dc.html`: one file per screen, the source of truth for layout, spacing and copy.
- `screens/hinds-theme.css`: both colour sets as CSS variables (`.hinds` = dark, `.hinds.light` = light).

## How to read the screen files

- Each file is a **1920 × 1080 px** frame. The Fire TV lays out at **960 × 540 dp**, so **divide every px value
  by 2** to get dp or sp (`font-size: 28px` → 14 sp, `left: 96px` → 48 dp, `border-radius: 24px` → 12 dp).
- Colours are `var(--token)`; the token table below maps each one to `HindsColors`.
- `{{name}}`, `<sc-for>` and `<sc-if>` are the design tool's template syntax. The sample data for each screen is
  in the `<script>` block at the bottom of the file (that is where channel rows, focus index and so on live).
- The `theme` prop (`dark` | `light`) switches the root class; the light boards on the canvas are the same files
  with `theme="light"`.
- Images use `/_blob/…` URLs that only resolve on the canvas. They are stock photos standing in for portal
  artwork. **All channel names, logos, programmes and titles are invented**; the app shows the provider's.
- Channel logos are drawn as text wordmarks in a tile (`--plate`). In the app that tile is `ChannelLogo`
  (provider logo, initials when there is none). Live thumbnails are not used anywhere: portals don't provide
  them and the one-stream rule rules out previews.

## Rules the design keeps (from CLAUDE.md)

- No blur, no shadows, no focus scaling (`focusedScale = 1f`), no crossfades. Focus is the existing pattern:
  **Focus fill + OnFocus content** for list rows, pills and buttons (`focusColors`), a **3 dp Focus ring** for
  cards and tiles (`focusRing`). Nothing in the design relies on anything else.
- Gradients appear only as scrims (the same `Brush.verticalGradient` idea as `InfoBanner`).
- Manrope, weights 400 / 500 / 600 / 700 (the bundled four).
- Launch still plays the last channel immediately. Home is opt-in (see Appearance).
- Every screen is D-pad first. Back keeps its current meaning: first to the section bar, then out.

## 1. Theme: Dark, Light, Automatic

### Tokens

| Token (`hinds-theme.css`) | `HindsColors` | Dark (today) | Light (new) |
|---|---|---|---|
| `--bg` | `Background` | `#0B0B0D` | `#F3F2EE` |
| `--panel` | `Panel` | `#F20B0B0D` | `#F7F6F5F1` |
| `--surface` | `Surface` | `#141418` | `#FFFFFF` |
| `--raised` | `SurfaceRaised` | `#1F1F25` | `#E6E7EB` |
| `--hairline` | `Hairline` | `#2A2A31` | `#D8D9DF` |
| `--focus` | `Focus` | `#F5F5F7` | `#14161B` |
| `--on-focus` | `OnFocus` | `#0B0B0D` | `#FFFFFF` |
| `--accent` | `Accent` | `#F2B33D` | `#2F4FC4` |
| `--on-accent` | `OnAccent` | `#0B0B0D` | `#FFFFFF` |
| `--text` | `Text` | `#F5F5F7` | `#14161B` |
| `--muted` | `Muted` | `#A1A1AA` | `#555A66` |
| `--faint` | `Faint` | `#8A8A94` ¹ | `#6B707C` |
| `--error` | `Error` | `#FF6B5B` | `#C8372D` |
| `--live` | **new** `Live` (live dot) | `#FF5A4E` | `#D93A2F` |
| `--chip` | **new** `Chip` (quiet button fill) | white 10 % | `#14161B` 6 % |
| `--chip-line` | **new** `ChipLine` (outlines, key caps) | white 14 % | `#14161B` 14 % |
| `--plate` | **new** `Plate` (logo tile) | `#1F1F25` | `#FFFFFF` |
| `--track` | **new** `Track` (progress track) | white 18 % | `#14161B` 14 % |
| `--dim` | **new** `Dim` (behind dialogs) | black 60 % | `#14161B` 45 % |

¹ Today's `Faint` is `#6E6E78`, which is under 4.5:1 on the background for small labels. The design uses
`#8A8A94`; please change it.

Every light colour passes 4.5:1 for text against its background (Accent on `--bg` is about 6:1).

### Where the theme has to reach

- `ui/theme/Theme.kt`: replace `object HindsColors` with a palette (suggested shape below) and use
  `lightColorScheme(...)` from tv-material for light.
- `Cinema.kt:139`: `PillButton`'s `Color.White.copy(alpha = 0.12f)` becomes `HindsColors.Chip`.
- `Common.kt`: `ProgressLine`'s track becomes `Track`. **Keep** the black picture areas and white-on-black
  subtitles (`TvScreen`, `VideoPlayerPage`, `RadioScreen` screen-off, `SubtitleLayer`): they are the video, not UI.
- `Cinema.kt` `TILE_COLOURS` (artwork without a poster) needs a light set, for example
  `#E4DDF0 #D9E6EF #DCEBE2 #F0E6D6 #F1DEDC #DDE3EE #EDE7D5 #DAE8E4` with `Muted` initials.
- `GuideOverlay.drawGuide` reads `HindsColors` inside a `DrawScope`. Read the palette in the composable and pass
  it in.

Suggested shape (keeps most call sites as they are):

```kotlin
@Immutable data class HindsPalette(val background: Color, val panel: Color, /* … every token … */)
val DarkPalette = HindsPalette(/* table, Dark column */)
val LightPalette = HindsPalette(/* table, Light column */)
val LocalHindsPalette = staticCompositionLocalOf { DarkPalette }

object HindsColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.background
    // … one getter per token
}
```

`staticCompositionLocalOf` recomposes everything when the theme changes, which is what we want: the switch is
rare, instant and has no animation.

### The setting

- `enum class ThemeMode { Dark, Light, Auto }`, stored in `ProfileStore` (`theme_mode`, default `Dark`).
- **Automatic** is Light from 07:00 to 19:00 local time and Dark otherwise. Re-check it on the existing
  once-a-minute `minute` tick in `AppViewModel` (no new timer, no location permission).
- Also in `ProfileStore`: `wallpaper_dark`, `wallpaper_light` (index) and `start_with` (`last` | `home`,
  default `last`).
- Wallpapers: the APK must stay under 10 MB. Bundle **one wallpaper per theme** as a 1920 × 1080 WebP
  (about 150 KB each) to start; Coil decodes it at screen size and only Home shows it. More can come later.

## 2. Type, spacing, shapes

| Canvas px | dp / sp | Used for |
|---|---|---|
| 104 | 52 sp Bold | Hero title (Home, VOD) |
| 88 | 44 sp Bold | VOD details title |
| 56–60 | 28–30 sp Bold | Guide programme, radio station, player title |
| 44–48 | 22–24 sp Bold | Screen titles, channel name in banner |
| 32 | 16 sp Bold | Row titles (`titleMedium`) |
| 28 | 14 sp Bold/SemiBold | Card titles, buttons, list rows |
| 26 | 13 sp SemiBold | Nav pills, meta lines |
| 24 | 12 sp Medium | Secondary lines (`bodySmall`) |
| 22 | 11 sp Bold, +0.14 em | Eyebrows and labels (`labelSmall`, uppercase) |

- Side margins 96 px (48 dp); top 40–48 px (20–24 dp).
- Radius: buttons and pills are fully round; rows 24 px (12 dp); cards 20 px (10 dp); dialogs 28 px (14 dp).
- Buttons 72 px tall (36 dp); list rows 64–96 px (32–48 dp).

## 3. Navigation

- Section pills: **Home · Live · Guide · VOD · Radio · Search · Settings** (adds `Home` to `NavSection`).
- Capsule on `Surface`; the current section is filled with `Hairline`; focus is the Focus fill.
- On full-screen sections the pills are centred, with the clock (22 sp SemiBold) and date (12 sp, Muted)
  top-left. In the channel-list overlay they stay top-left over the panel, as today.

## 4. Screens

File names are in `screens/`. Sizes in dp.

**Home** (`Home.dc.html`, new `ui/home/HomeScreen.kt`)
- Wallpaper with three scrims: from the left (Background 86 % → 50 % at 38 % → 0 at 62 %), from the bottom
  (98 % → 90 % at 36 % → 0 at 60 %) and a short one under the clock.
- Hero = the most recent unfinished item in `watch_progress`. It shows an eyebrow ("Continue watching"), the
  title, meta, a 240 dp progress line with "48m left", a 2-line synopsis, **Resume from 1:06:12** (initial
  focus) and **Play from start**. The 320 × 180 dp artwork sits on the right, with "Next up …" under it.
- **My Channels**: the first favourites list as five logo tiles (163 × 75 dp). Each has its number top-left, a
  progress line at the bottom, and the name plus what's on now under the tile.
- **Recently watched**: the last four channels as 32 dp chips, plus **All channels**. This needs a list of recent
  channel ids in `ProfileStore` (today it keeps only the last one; keep about 20).

**Live · info banner** (`LiveBanner.dc.html`): the current banner, resized. It has a 112 × 72 dp logo, the number
in Accent, the name at 24 sp, NOW (Accent) and NEXT (Faint) lines, a 520 dp progress line, the clock and
"Tuned in 0.8 s", and a one-line key hint along the bottom.

**Live · channel list** (`ChannelList.dc.html`): the same overlay structure (panel 820 dp, soft edge into the video).
- Groups gain **Recently watched** at the top (`__recent`), then the ★ lists, then All channels and the
  provider's groups.
- Rows are 46 dp, each with a 60 × 34 dp logo, the number, name, what's on now plus a progress line, and a ★
  for favourites. The playing channel shows "WATCHING" and its number in Accent.
- The footer shows key hints.

**Live · TV guide** (`Guide.dc.html`)
- The header has `⏪ ★ My Channels ⏩`, the selected programme at 28 sp, the channel, time and genre, and a
  2-line description.
- Grid: a 192 dp channel column, then a 2-hour window of 664 dp (5.53 dp per minute), with 42 dp rows.
- Cells on now are `SurfaceRaised`, later ones `Surface`, the focused one Focus.
- A 1.5 dp Accent "now" line runs down the grid, with a time tag at the top.

**Live · recent channels** (`QuickSwitch.dc.html`, new `Overlay.Recent`): a bottom panel over the picture.
- Tabs: Recently watched, My Channels, All channels.
- 156 × 88 dp logo tiles with a ring on focus. The playing channel is marked "WATCHING".
- Top-right shows "BACK · Last channel 412 Kino Classic". ⏪ already goes to the previous channel; this just
  makes it visible.

**Live · add to favourites** (`AddToList.dc.html`): a 520 dp dialog. It has 38 dp checkbox rows with counts, the
New list field with Create, and Done. Same behaviour as `ListPickerDialog`.

**Live · audio & subtitles** (`Tracks.dc.html`): a 640 dp dialog with two radio columns (Audio, Subtitles with Off)
and the "used for the next channel too" line.

**Live · can't play** (`LiveError.dc.html`)
- A 560 dp card with a label in Error, the `Errors.kt` sentence at 17 sp, the retry hint, and **Try again** and
  **Channel list** buttons.
- Also shown: the "Press Back again to exit" toast, top centre.

**VOD · for you** (`VodHome.dc.html`, new first rail entry "For you")
- Hero = the newest title in a category you have watched (falls back to newest overall), with **Play** and
  **Episodes**.
- Then a **Continue watching** row of 204 dp 16:9 cards with progress lines and "S2 · E4" badges.

**VOD · library** (`VodLibrary.dc.html`): today's rail and 3-column grid, with the selected category in
`SurfaceRaised` and Accent. The search field and the **Newest ▾** sort pill sit on the title row; the focused
card gets the 3 dp ring. Lesson categories are shown because that's most of the real library.

**VOD · details** (`VodDetails.dc.html`): today's page at the new sizes, plus a **Season 1** chip. Episode cards
are 204 dp, with a "✓ Watched" badge and dimmed art, a progress line, or the duration.

**VOD · player** (`VodPlayer.dc.html`): today's controls, plus a 14 dp scrubber knob in Focus and a 260 dp Up next
card. It keeps `vod_hint`.

**Radio** (`Radio.dc.html`)
- Now playing: a 112 dp logo tile, the **ON AIR** pill, the number, the station name at 30 sp, the ICY title,
  the static `LevelBars`, and **Stop** and **Screen off**.
- Stations: a 5-column grid of 74 dp tiles. The playing station reads "On air" in Accent.

**Search** (`Search.dc.html`)
- A 44 dp pill field showing the query, with **Clear** beside it.
- Results: Channels and Radio share the first row, then On TV (time badge: "On now" in Accent), then VOD
  posters.

**Settings · Appearance** (`SettingsAppearance.dc.html`, new first section). The canvas version works: pick a
theme and the whole screen re-themes.
- **Theme**: three 186 dp option cards (Dark, Light, Automatic). Each has a mini preview, a caption and a check
  when selected; selecting applies straight away.
- **Home wallpaper**: thumbnails of the current theme's set.
- **Start Hinds TV with**: Last channel (fastest), the default, or Home.

**Settings · Account** (`SettingsAccount.dc.html`): the same fields as today in a `Surface` card, with 38 dp rows
and hairline dividers, plus **Change account** and **Refresh channels**.

**Setup** (`Setup.dc.html`)
- A 360 dp brand panel (wallpaper, Accent app tile, "Hinds TV", tagline, about line).
- The form keeps today's fields and copy, with the selected mode in `SurfaceRaised` and Accent.
- **Connect** is the Accent primary; the focused field gets the ring.

New strings: `nav_home`, `settings_appearance`, `appearance_theme`, `theme_dark`, `theme_light`, `theme_auto`,
`theme_dark_hint` ("Best for evenings and dark rooms"), `theme_light_hint` ("Best for bright, sunny rooms"),
`theme_auto_hint` ("Light 07:00–19:00, dark after"), `appearance_wallpaper`, `appearance_start`,
`start_last_channel` ("Last channel (fastest)"), `start_home`, `group_recent` ("Recently watched"),
`vod_for_you` ("For you"), `next_up` ("Next up").

## 5. Behaviour changes Fred needs to confirm

1. **Home** is a new section and the first nav pill. Start-up still goes to the last channel unless
   *Start with Home* is chosen.
2. **◀ in full screen** opens the recent-channels strip (today ◀ and Select both open the channel list; Select
   keeps doing that).
3. **Recently watched** becomes the first group in the channel list and the guide's group switcher.
4. **VOD** opens on **For you** instead of the first category.

## 6. Suggested order

1. Palette, `ThemeMode` and **Settings → Appearance** only. Check nothing else moved, and measure start-up and
   zapping on the stick (release build) to confirm no regression.
2. Light `TILE_COLOURS`, `Chip` and `Track` clean-up, and the `Faint` contrast fix.
3. `Home` nav section, recent-channels list in `ProfileStore`, Home screen.
4. Channel list *Recently watched* group, then the recent-channels strip (after Fred confirms the ◀ change).
5. VOD *For you*.
6. Remaining size and spacing polish screen by screen, against these files.
