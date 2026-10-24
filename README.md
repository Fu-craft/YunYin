[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">云音 (YunYin)</h1>

<div align="center">

<h3>✨ 把多源逐字歌词、流体背景与液态玻璃做进原生 Android 的网易云播放器 🎵</h3>

<p>
  <a href="https://github.com/Fu-craft/YunYin/releases">
    <img alt="Release" src="https://img.shields.io/github/v/release/Fu-craft/YunYin?label=Release" />
  </a>
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0--only-blue" />
</p>

<p>非官方第三方客户端，与网易云音乐、Apple 均无关联。</p>

<p>Kotlin + Jetpack Compose 全原生开发，支持 Android 13 (API 33) 及以上。</p>

</div>

> [!WARNING]
> 仅供学习与研究。请只在你拥有权利或平台规则允许的范围内访问、播放与保存内容。
> 本项目不提供媒体内容、账号或密钥，也不提供规避付费 / DRM / 地区限制的方案。

> [!NOTE]
> 云音不运营云端曲库。内容全部来自你自己部署的 **NeteaseCloudMusicApi**，
> 仓库不含任何服务端点或凭据。

---

## 项目简介

云音是基于 **Kotlin + Compose + Media3** 的原生 Android 播放器，单模块实现，
主源码约 **216 个文件 / 45.3k 行**，配套 **727 个单元测试**。

不构建公共云端服务，而是把**歌词、播放、下载、本地音乐**做深，并保持一套自洽视觉语言。

---

## 项目亮点

- **逐字歌词是「竞速 + 探针」**：网易云 YRC / QQ / 酷我 / 酷狗 / AMLL TTML 并发请求，
  2.5 秒探针判定后一次性绘制，优先级 `tx > wy > amll > kw > kg`，避免半成品反复跳变。
- **逐字填充是渐变擦除**：按音节进度生成水平渐变，柔边 **100 px**，未演唱部分降透明度；
  对唱分边、翻译、间奏呼吸点。
- **交叉淡入用两个 ExoPlayer 重叠播放**：第二播放器预载下一首，`MediaSession.setPlayer()` 交接，
  40 ms 等功率淡入，时长 **3/6/9/12 秒**可选。
- **5 种流体背景，全部单趟片元着色**：云音（AGSL 五色 palette + 音频响应）、色阶流动（OkLab + 种子化参数）、
  QPlayer 像素模糊 / 经典流动 / 网格渐变（SkSL → AGSL 移植）。共用 **60 fps** 帧泵与静态帧降级。
  纹理统一走「32 px → 模糊 → 256 px」预模糊，避开 `RuntimeShader` 子采样不过滤导致的方块。
- **下载中心可续传**：自管 `.part` + sidecar（Range / etag / 进度），进程重启可继续；
  服务器忽略 Range 时回退整文件重下。
- **本地音乐是完整链路**：内嵌封面三级回退、歌曲/专辑/艺人分组、五种排序、坏文件跳过统计。

---

## 快速体验

1. 从 [Releases](https://github.com/Fu-craft/YunYin/releases/latest) 下载
2. 选包：多数手机选 `yunyin-<版本>-release.apk`（arm64，约 22.4 MB）；
   老 32 位设备选 `-armeabi-v7a`；不确定选 `-universal`（约 24.8 MB）
3. 首次启动在设置里填入你的 API 地址

---

## 构建

需要 **JDK 17+** 与 Android SDK（`compileSdk = 37`、build-tools 37.0.0）。

`local.properties`（不进仓库）：

```properties
sdk.dir=/path/to/Android/Sdk
api.base.url=https://your-netease-api.example.com
update.repo.owner=your-github-name
update.repo.name=your-repo-name
```

`keystore.properties`（可选，缺失时产出未签名 APK）：

```properties
storeFile=my-release.jks
storePassword=****
keyAlias=mykey
keyPassword=****
```

```bash
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包（三种 ABI）
./gradlew :app:testDebugUnitTest  # 单元测试
```

界面优先使用 **SF Pro**，因授权限制不分发；放入 `app/src/main/res/font/sf_pro.ttf` 即启用。

---

## 功能

**播放**：五档音质与自动降级、经典/黑胶两种播放页、双播放器交叉淡入、拖动切歌、
播放队列、循环随机、睡眠定时、45 秒试听识别、后台与通知栏控制、长按封面预览、双击收藏。

**歌词**：多源竞速逐字歌词、渐变擦除填充、对唱、翻译、呼吸点、偏移校准、长按复制、
沉浸歌词、横屏侧栏、可自选 1–4 句的分享卡（1080×1440 与 1080×1920）、
状态栏歌词（Lyricon 或 Flyme 通知栏）。

**视觉**：5 种流体背景、液态玻璃底栏与顶栏、封面↔歌词形变转场、子页滑入滑出。

**浏览**：首页（精选、日推、新歌、排行、私人 FM、歌单广场）、搜索（联想与历史）、
歌单/专辑/歌手详情与订阅关注、评论（排序、楼中楼、点赞、回复）、
资料库（喜欢、我的歌单、最近播放、本地音乐、下载中心）、喜欢同步到云端。

**下载与本地**：下载中心（进行中/已完成/失败、暂停继续重试删除、仅 Wi-Fi、并发 2、空间预检）、
断点续传、本地音乐（内嵌封面、分组、搜索、排序）。

**其他**：LX Music 自定义音源（QuickJS）、无卡片分组设置页、应用内更新、崩溃与音源诊断日志。

---

## 实现概览

- `compileSdk / targetSdk = 37`，`minSdk = 33`
- Kotlin **2.4.10**、AGP **9.3.2**、Compose **1.12.0**、Material3 **1.9.0**
- Media3 **1.11.1**（ExoPlayer + Session + HLS）、Coil **2.7.0**、OkHttp **4.12.0**
- `backdrop` **2.0.1**、`gaze-capsule`、`lyrics-core` **0.5.0**、`lyrics-ui` **1.0.19**（vendored）、`quickjs-kt` **1.0.15**
- 版本 `4.x.y`，`versionCode` 单调递增；ABI 拆分为 arm64 / armeabi-v7a / universal

```text
app/src/main/java/com/yunyin/music/
├── data/       # 网络、仓库、设置、下载、本地音乐、音源、歌词、诊断
├── playback/   # PlayerController、交叉淡入、试听识别、无缝衔接
├── lyrics/     # 歌词渲染
├── core/       # 模型与纯规则
└── ui/         # screens(28) / components(17) / background / theme
```

测试 **727 个**全绿，`tools/run_suite.py` **27/27**；CI 每次 push 跑全部单测 + debug 构建。

---

## 截图

| 播放页 | 资料库 | 歌词页 |
|:--:|:--:|:--:|
| <img src="docs/screenshots/player.png" width="240" alt="播放页"> | <img src="docs/screenshots/library.png" width="240" alt="资料库"> | <img src="docs/screenshots/lyrics.png" width="240" alt="歌词页"> |

| 首页 | 搜索 | |
|:--:|:--:|:--:|
| <img src="docs/screenshots/home.png" width="240" alt="首页"> | <img src="docs/screenshots/search.png" width="240" alt="搜索"> | |

---

## 鸣谢

- [NeriPlayer](https://github.com/cwuom/NeriPlayer) — 流动背景、封面取色、音频响应管线（GPL-3.0）
- [QPlayer](https://github.com/TIMER-err/QPlayer) — 像素模糊 / 经典流动背景（Apache-2.0）
- [AMLL](https://github.com/amll-dev/applemusic-like-lyrics) — 网格渐变算法与歌词格式（AGPL-3.0-only）
- [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) — 逐字歌词数据
- [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui) — 歌词渲染（Apache-2.0）
- [accompanist-lyrics-core](https://github.com/6xingyv/accompanist-lyrics-core) — 歌词解析（Apache-2.0）
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — 液态玻璃材质（Apache-2.0）
- [Lyricon](https://github.com/tomakino/lyricon) — 状态栏歌词（Apache-2.0）
- [quickjs-kt](https://github.com/dokar3/quickjs-kt) — 自定义音源引擎（Apache-2.0）
- [Material Symbols](https://github.com/google/material-design-icons) — 图标（Apache-2.0）

完整清单见 [NOTICE](NOTICE)。

---

## 许可证

**AGPL-3.0-only**，全文见 [LICENSE](LICENSE)。

