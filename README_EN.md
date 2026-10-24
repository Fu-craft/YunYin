[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">YunYin (云音)</h1>

<div align="center">

<h3>✨ A native Android NetEase Cloud Music player built around word-by-word lyrics, fluid backgrounds and liquid glass 🎵</h3>

<p>
  <a href="https://github.com/Fu-craft/YunYin/releases">
    <img alt="Release" src="https://img.shields.io/github/v/release/Fu-craft/YunYin?label=Release" />
  </a>
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0--only-blue" />
</p>

<p>An unofficial third-party client. Not affiliated with NetEase Cloud Music or Apple.</p>

<p>Kotlin + Jetpack Compose, Android 13 (API 33) and above.</p>

</div>

> [!WARNING]
> For study and research only. Only access, play or save content you are entitled to.
> No media, accounts, keys, or ways to bypass payment, DRM or regional limits are provided.

> [!NOTE]
> YunYin runs no cloud catalogue. All content comes from a **NeteaseCloudMusicApi** instance you deploy;
> the repository contains no endpoint and no credential.

---

## About

A native Android player built with **Kotlin + Compose + Media3** in a single module:
about **216 files / 45.3k lines** of app source and **727 unit tests**.

It goes deep on **lyrics, playback, downloads and local music**, with one coherent visual language.

---

## Why it stands out

- **Lyrics are a race plus a probe**: NetEase YRC, QQ, Kuwo, Kugou and AMLL TTML are fetched
  concurrently; a **2.5 s** probe decides when to paint, priority `tx > wy > amll > kw > kg`,
  so the page never flickers through half-built states.
- **The karaoke fill is a gradient wipe**: per-syllable progress drives a horizontal gradient with a
  **100 px** soft edge and a dimmed unsung remainder; duet split, translation, instrumental dots.
- **Crossfade is two overlapping ExoPlayers**: the second preloads the next item,
  `MediaSession.setPlayer()` hands over, a 40 ms equal-power ticker fades over **3/6/9/12 s**.
- **Five fluid backgrounds, each a single fragment pass**: YunYin (AGSL, cover palette, audio reactive),
  OkLab flow (seeded per-track parameters), QPlayer pixi / classic / mesh (SkSL → AGSL port).
  Shared **60 fps** pump and static-frame degradation; textures go through a
  **32 px → blur → 256 px** pre-blur so `RuntimeShader` sampling cannot show texel blocks.
- **Resumable downloads**: `.part` file plus a sidecar (Range / etag / offset) survive a process restart;
  servers that ignore Range fall back to a full re-download.
- **Local music is a pipeline**: embedded artwork with three-step fallback, album/artist grouping,
  five sort modes, corrupt rows skipped and counted.

---

## Getting started

1. Download from [Releases](https://github.com/Fu-craft/YunYin/releases/latest)
2. Most phones: `yunyin-<version>-release.apk` (arm64, ~22.4 MB); 32-bit devices: `-armeabi-v7a`;
   unsure: `-universal` (~24.8 MB)
3. Set your API endpoint in Settings on first launch

---

## Build

Requires **JDK 17+** and the Android SDK (`compileSdk = 37`, build-tools 37.0.0).

`local.properties` (never committed):

```properties
sdk.dir=/path/to/Android/Sdk
api.base.url=https://your-netease-api.example.com
update.repo.owner=your-github-name
update.repo.name=your-repo-name
```

`keystore.properties` (optional; unsigned APK when absent):

```properties
storeFile=my-release.jks
storePassword=****
keyAlias=mykey
keyPassword=****
```

```bash
./gradlew :app:assembleDebug      # debug
./gradlew :app:assembleRelease    # release (three ABIs)
./gradlew :app:testDebugUnitTest  # unit tests
```

The UI prefers **SF Pro**, not redistributed; drop it at `app/src/main/res/font/sf_pro.ttf` to enable.

---

## Features

**Playback** — five quality tiers with downgrade, classic/vinyl player styles, dual-player crossfade,
drag to change track, queue, loop/shuffle, sleep timer, 45 s trial detection, background and
notification controls, long-press cover preview, double-tap like.

**Lyrics** — multi-source word-by-word lyrics, gradient wipe fill, duet, translation, breathing dots,
offset calibration, long-press copy, immersive lyrics, landscape side layout, share card with
1–4 selectable lines (1080×1440 and 1080×1920), status-bar lyrics via Lyricon or a Flyme notification.

**Visuals** — five fluid backgrounds, liquid-glass bars, cover ↔ lyrics morph, page slide transitions.

**Browsing** — home (curated, daily, new songs, charts, private FM, playlist square), search with
suggestions and history, playlist/album/artist pages with subscribe and follow, comments with sorting,
threaded replies, likes and replies, library (liked, playlists, recents, local music, downloads),
like sync to cloud.

**Downloads & local** — download centre (in progress / done / failed, pause/resume/retry/delete,
Wi-Fi only, concurrency 2, space precheck), resumable transfers, local music with embedded artwork,
grouping, search and sorting.

**Other** — LX Music custom sources via QuickJS, card-less grouped settings, in-app update,
crash and source diagnostics.

---

## Implementation notes

- `compileSdk / targetSdk = 37`, `minSdk = 33`
- Kotlin **2.4.10**, AGP **9.3.2**, Compose **1.12.0**, Material3 **1.9.0**
- Media3 **1.11.1** (ExoPlayer + Session + HLS), Coil **2.7.0**, OkHttp **4.12.0**
- `backdrop` **2.0.1**, `gaze-capsule`, `lyrics-core` **0.5.0**, `lyrics-ui` **1.0.19** (vendored),
  `quickjs-kt` **1.0.15**
- Version `4.x.y` with monotonic `versionCode`; ABI splits arm64 / armeabi-v7a / universal

```text
app/src/main/java/com/yunyin/music/
├── data/       # network, repositories, settings, downloads, local music, sources, lyrics, diagnostics
├── playback/   # PlayerController, crossfade, trial detection, gapless
├── lyrics/     # lyric rendering
├── core/       # models and pure rules
└── ui/         # screens(28) / components(17) / background / theme
```

**727** tests green, `tools/run_suite.py` **27/27**; CI runs all unit tests plus a debug build on push.

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
[Material Symbols](https://github.com/google/material-design-icons) (Apache-2.0)

Full list: [NOTICE](NOTICE).

---

## License

**AGPL-3.0-only** — see [LICENSE](LICENSE).

The project includes AGPL-3.0-only code (the `QPlayer · mesh gradient` background ported from AMLL Core),
so the whole work is distributed under AGPL-3.0-only. Source: <https://github.com/Fu-craft/YunYin>.
