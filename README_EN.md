[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">YunYin (云音)</h1>

<div align="center">

<h3>✨ A native Android NetEase Cloud Music player built around multi-source word-by-word lyrics, fluid backgrounds and liquid glass 🎵</h3>

<p>
  <a href="https://github.com/Fu-craft/YunYin/releases">
    <img alt="Downloads" src="https://img.shields.io/github/downloads/Fu-craft/YunYin/total?style=social" />
  </a>
  <a href="https://github.com/Fu-craft/YunYin/releases">
    <img alt="Release" src="https://img.shields.io/github/v/release/Fu-craft/YunYin?label=Release" />
  </a>
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0--only-blue" />
</p>

<p>
  An unofficial third-party client. Not affiliated with NetEase Cloud Music or Apple.
</p>

<p>
  Fully native Kotlin + Jetpack Compose, Android 13 (API 33) and above.
  Built around four ideas: word-by-word lyrics, playback feel, visual consistency, and recoverable runtime.
</p>

🛠️ <strong>Active development</strong>

</div>

> [!WARNING]
> For study and research only. Only access, play or save content you are entitled to.
> This project ships no media, no accounts, no keys, and no way to bypass payment, DRM or regional limits.

> [!NOTE]
> YunYin operates no cloud catalogue and no media distribution service.
> All content comes from a **NeteaseCloudMusicApi** instance you deploy yourself.
> The repository contains no API endpoint, private key or credential.

---

## Start here

- Just want to try it: [Getting started](#getting-started)
- Want to know what it does: [Why it stands out](#why-it-stands-out) and [Key features](#key-features)
- Want to build it: [Build](#build)
- Want the engineering trade-offs: [Implementation notes](#implementation-notes)

```text
YunYin
├── Lyrics: multi-source race + probe pre-fetch + per-syllable fill + duet + translation
├── Playback: dual-ExoPlayer crossfade, gapless, five quality tiers, trial detection
├── Visuals: liquid glass, 5 fluid backgrounds, morph transitions, card-less grouped lists
├── Local: download centre (resumable), local music (embedded artwork / grouping / search)
└── Recoverable: crash stacks into diagnostics, four-state error handling, CI gates
```

## About

YunYin is a native Android audio player built with **Kotlin + Jetpack Compose + Media3**
in a single module (`:app`), about **216 files / 45.3k lines** of app source and
**106 test files / 727 unit tests**.

It runs no cloud service. Instead, on top of your own NetEase API deployment, it goes deep on
**lyrics, playback, downloads and local music**, while keeping one coherent visual language.

- **You own the endpoint**: the API base URL is injected at build time (`api.base.url`); the repo contains none.
- **Lyrics first**: word-by-word is the default experience, not an add-on; line-level LRC is the fallback.
- **Local first**: downloads, local music, likes, playlists and settings stay on the device.
- **One design system**: glass materials, radii, motion curves and type scale come from shared tokens.
- **Diagnosable**: crash stacks, ANR hints and source-resolution events land in one copyable log.

---

## Why it stands out

- **Lyrics are a race plus a probe, not a serial fallback**:
  `LyricSourceRace` fetches NetEase YRC, QQ, Kuwo, Kugou and AMLL TTML mirrors concurrently;
  `LyricProbeRules` uses a **2.5 s probe** to decide whether waiting for better word timing is worth it,
  with priority `tx > wy > amll > kw > kg`. The page paints once after the probe, so lyrics never
  flicker through half-built states.

- **The karaoke fill is a gradient wipe, not a hard cut**:
  `KaraokeLineText` derives per-syllable progress into a horizontal gradient brush with a
  **100 px** soft edge (matching Apple Music's Android value), dimming the unsung remainder;
  duet lines are split by singer and instrumental gaps show breathing dots.

- **Crossfade is two overlapping ExoPlayers**:
  on a track change the second player preloads the next item, `MediaSession.setPlayer()` hands over,
  and a **40 ms** equal-power ticker drives the fade over **3 / 6 / 9 / 12 s**.
  It is real overlap, not pause-and-resume; the "gapless" switch can disable it.

- **Five fluid backgrounds, every one a single fragment pass**:
  - **YunYin**: AGSL `RuntimeShader`, five-colour cover palette, volume/beat reactive
  - **OkLab flow**: four-colour **OkLab** interpolation with per-track seeded flow parameters
  - **QPlayer · pixi / classic**: line-by-line SkSL → AGSL port (Apache-2.0)
  - **QPlayer · mesh gradient**: bicubic Hermite mesh, 24 subdivisions (AGPL-3.0, from AMLL Core)
  All styles share a **60 fps** frame pump, static-frame degradation and silent shader fallback,
  plus a pre-blur pipeline (32 px → blurred → 256 px) because Android's `RuntimeShader`
  child sampler does not honour filter quality and would otherwise show texel blocks.

- **The download centre can actually resume**:
  no `DownloadManager`; it manages `.part` files plus a JSON sidecar (URL / total / etag / offset)
  and uses `Range` requests. If the server ignores Range (HTTP 200) it falls back to a full re-download.
  Concurrency **2**, Wi-Fi-only by default, **50 MB** free-space precheck, and a completed file is
  published to `Music/云音` via MediaStore and picked up by local music automatically.

- **Local music is a full pipeline**:
  embedded artwork via `MediaMetadataRetriever` → sibling `cover|folder|albumart` → placeholder;
  song / album / artist browsing with track-number and year ordering; search across title, artist
  and album; five sort modes; corrupt rows are skipped with a running "skipped N" count.

- **The lyric card lets you choose the lines**:
  pick **1–4 lines** (song order preserved), then get **1080×1440** and **1080×1920** variants.
  The backdrop is a cover-derived gradient with downsampled texture, vignette and IGN dithering;
  the title auto-shrinks to stay on **one line**.

- **Diagnostics are a closed loop**:
  `CrashLogger` appends the **full stack trace** into the diagnostics log (64 KiB cap, never throws),
  alongside `stage=…` events from lyric probing and source resolution, in the same copyable file.

- **Quality gates live in CI**:
  `tools/run_suite.py` runs **27 structural checks (27/27)**; GitHub Actions runs
  **all 727 unit tests + a debug build** on every push to master.

---

## Getting started

1. Download from [GitHub Releases](https://github.com/Fu-craft/YunYin/releases/latest)
2. Which APK?
   - Most phones: **`yunyin-<version>-release.apk`** (arm64-v8a, ~22.4 MB)
   - Old 32-bit devices: `yunyin-<version>-armeabi-v7a.apk`
   - Unsure: `yunyin-<version>-universal.apk` (~24.8 MB, all ABIs)
3. On first launch, set your own API endpoint in Settings.

> [!IMPORTANT]
> The in-app updater always downloads the **arm64 primary asset**.
> Extra assets deliberately omit the `release` token so the updater cannot pick them.

---

## Build

Requires **JDK 17+** and the Android SDK (`compileSdk = 37`, build-tools 37.0.0).

`local.properties` (never committed):

```properties
sdk.dir=/path/to/Android/Sdk
api.base.url=https://your-netease-api.example.com
update.repo.owner=your-github-name
update.repo.name=your-repo-name
# optional: self-hosted animated-artwork endpoint; blank hides the feature and sends no request
artwork.api.base.url=
```

`keystore.properties` (optional; unsigned output when absent):

```properties
storeFile=my-release.jks
storePassword=****
keyAlias=mykey
keyPassword=****
```

```bash
./gradlew :app:assembleDebug      # debug
./gradlew :app:assembleRelease    # release (three ABIs)
./gradlew :app:testDebugUnitTest  # 727 unit tests
```

The UI prefers **SF Pro**; it is not redistributed for licensing reasons. Drop the font at
`app/src/main/res/font/sf_pro.ttf` to enable it, otherwise the system sans-serif is used.

---

## Key features

**Playback** — five quality tiers with automatic downgrade; classic and vinyl player styles;
dual-player crossfade (3/6/9/12 s) with an independent gapless switch; drag-to-change-track,
queue, loop/shuffle, sleep timer; 45 s trial detection for members-only tracks; background
playback with Media3 `MediaSessionService`; long-press cover preview, double-tap like, swipe to switch.

**Lyrics** — concurrent multi-source fetch with a 2.5 s probe; per-syllable gradient wipe (100 px edge);
duet split, translation, instrumental breathing dots; offset calibration, long-press copy, tap-to-seek;
immersive lyrics and landscape side layout; shareable lyric card with 1–4 selectable lines;
status-bar lyrics through **Lyricon** or a **Flyme** notification ticker.

**Visuals** — five fluid backgrounds (YunYin / OkLab flow / QPlayer pixi / QPlayer classic /
QPlayer mesh); consistent gating (dynamic off ⇒ one static frame, audio off ⇒ no audio term,
power save ⇒ no frame pump); liquid-glass bars; cover ↔ lyrics morph transition; page slide/fade.

**Browsing** — home with curated playlists, daily recommendations, new songs, charts, **private FM**
and playlist square; search with suggestions and history; playlist, album and artist pages with
subscribe/follow and a combined follow + play-all row; comments with sort, threaded replies, likes;
library with liked songs, playlists, recents, **local music** and **download centre**; like sync to cloud.

**Local** — resumable download centre with pause/retry/delete, Wi-Fi-only and concurrency 2;
full-screen local music with embedded artwork, album/artist grouping, search and five sorts.

**Other** — LX Music custom-source scripts via QuickJS; six-group card-less settings; in-app update;
copyable crash and source diagnostics.

> [!NOTE]
> Removed or paused features are stated, never left as dead entries:
> **Listen together** (removed), **import playlist** (removed), **playback statistics** (entry hidden,
> sampler stopped), **animated cover** (implemented, hidden unless an endpoint is configured; off by default).

---

## Implementation notes

- `compileSdk = 37` / `targetSdk = 37` / `minSdk = 33`
- Kotlin **2.4.10**, AGP **9.3.2**, Compose **1.12.0**, Material3 **1.9.0**
- Media3 **1.11.1** (ExoPlayer + Session + HLS), Coil **2.7.0**, OkHttp **4.12.0**
- `backdrop` **2.0.1** (glass), `gaze-capsule` (continuous corners)
- `accompanist lyrics-core` **0.5.0** (Apache-2.0, parsing); `lyrics-ui` **1.0.19** (Apache-2.0, **vendored and modified**)
- `quickjs-kt` **1.0.15** (custom sources)
- Version: `4.x.y`, `versionCode` monotonic, bumped together
- **ABI splits**: `arm64-v8a`, `armeabi-v7a`, `universal` (arm64 primary, ~2.4 MB saved)

```text
app/src/main/java/com/yunyin/music/
├── data/       # network, repositories, settings, downloads, local music, sources, lyrics, diagnostics
├── playback/   # PlayerController, crossfade, trial detection, gapless
├── lyrics/     # lyric rendering (vendored KaraokeLineText et al.)
├── core/       # models and pure rules
└── ui/         # screens(28) / components(17) / background / theme
```

---

## Credits

[NeriPlayer](https://github.com/cwuom/NeriPlayer) (GPL-3.0) ·
[QPlayer](https://github.com/TIMER-err/QPlayer) (Apache-2.0) ·
[AMLL](https://github.com/amll-dev/applemusic-like-lyrics) (AGPL-3.0-only) ·
[AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) ·
[accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui) (Apache-2.0) ·
[accompanist-lyrics-core](https://github.com/6xingyv/accompanist-lyrics-core) (Apache-2.0) ·
[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0) ·
[Lyricon](https://github.com/tomakino/lyricon) (Apache-2.0) ·
[quickjs-kt](https://github.com/dokar3/quickjs-kt) (Apache-2.0) ·
[Material Symbols](https://github.com/google/material-design-icons) (Apache-2.0) ·
[Lucide](https://lucide.dev) (ISC)

Full list: [NOTICE](NOTICE).

---

## License

**GNU Affero General Public License v3.0 only (AGPL-3.0-only)** — see [LICENSE](LICENSE).

YunYin links against **AGPL-3.0-only** code: the `QPlayer · mesh gradient` background is ported
from **AMLL Core** (relayed through QPlayer, SPDX headers preserved, `© AMLL Contributors`).
Because it forms one combined work with the app, the whole project is distributed under AGPL-3.0-only.

Source is available at <https://github.com/Fu-craft/YunYin> (AGPL §13); releases also bundle
`assets/LICENSE-AGPL-3.0.txt` and `assets/NOTICE.txt`.
