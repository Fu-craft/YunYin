[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">云音 (YunYin)</h1>

<div align="center">

<h3>✨ 多源逐字歌词、流体背景、液态玻璃，以及会动的专辑封面 🎵</h3>

<p>
  <a href="https://github.com/Fu-craft/YunYin/releases">
    <img alt="Release" src="https://img.shields.io/github/v/release/Fu-craft/YunYin?label=Release" />
  </a>
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0--only-blue" />
</p>

<p>非官方第三方客户端，与网易云音乐、Apple 均无关联。</p>

<p>Kotlin + Jetpack Compose 全原生，Android 13 (API 33) 及以上。</p>

</div>

> [!WARNING]
> 仅供学习与研究。请只在你拥有权利或平台规则允许的范围内访问、播放与保存内容。
> 本项目不提供媒体内容、账号或密钥，也不提供规避付费 / DRM / 地区限制的方案。

> [!NOTE]
> 云音不运营云端曲库。曲库来自你自己部署的 **NeteaseCloudMusicApi**；
> 仓库不含任何服务端点或凭据，端点全部由 `local.properties` 在构建时注入。

---

## 这是什么

云音是一个 **Kotlin + Compose + Media3** 的原生 Android 播放器（单模块）：
主源码 **232 个 Kotlin 文件 / 约 4.8 万行**，配套 **747 个单元测试**。

它不追求「又一个在线播放器」，而是把几件容易做糙的事做细：
**逐字歌词的时序与绘制**、**播放页的动效质感**、**下载与本地音乐链路**，
以及 4.41 起新增的 **Apple Music 动态封面**。

---

## 亮点

### 动态封面（Animated Artwork）

专辑封面不再是一张静态图，而是 Apple Music 那套方形 HLS 动图，播放页上循环播放。

- **两条来源，自动互补**：先问你配置的动态封面服务（`artwork.api.base.url`），
  没命中再走 **Apple amp-api 原生查询**——`cn / hk / tw / us / gb / jp` 六个 storefront 依次尝试，
  命中率覆盖华语专辑（例如周杰伦《最伟大的作品》走 `cn`）。
- **自己挑码率，不让播放器瞎选**：解析 master playlist，只取 **AVC (avc1)** 变体、
  最大边 ≤ 1080、同分辨率下取 ≤ 6 Mbps 的最省一档。
  HEVC 变体在部分设备上「能 prepare、但一帧都不出」，这一步把它排除在外。
  低内存机型自动降到 640。
- **缓存三层**：内存 LRU（64 条，含未命中）→ 磁盘映射（专辑 → 变体地址，7 天 TTL）→
  分片磁盘缓存（`SimpleCache`，64 MB LRU）。切歌与冷启动都不必重新解析。
- **预取下一首**：当前曲目播放时就在后台解析下一首的封面，按下一首不再等 1–2 秒。
- **播放器提升到页面级**：进歌词页再返回不会重建 ExoPlayer，封面直接接上，
  不再「先静态、后突然变动态」。
- **细节**：圆角由 `clipToOutline` 裁（TextureView 不吃 Compose 的 clip）、
  首帧到达后才淡入（无黑块闪烁）、音乐暂停时封面定格、
  进歌词页暂停解码、Apple 返回的 preview frame 作为首帧前的海报。

> 动态封面是**可选功能**：`artwork.api.base.url` 为空时不发任何请求、不建播放器，
> 设置里的开关整行隐藏。

### 歌词

- **逐字歌词是「竞速 + 探针」**：网易云 YRC / QQ / 酷我 / 酷狗 / AMLL TTML 并发请求，
  2.5 秒探针判定后一次性绘制，优先级 `tx > wy > amll > kw > kg`，避免半成品反复跳变。
- **逐字填充是渐变擦除**：按音节进度生成水平渐变，柔边 100 px，未演唱部分降透明度；
  对唱分边、翻译、间奏呼吸点。
- 沉浸歌词、横屏侧栏、偏移校准、长按复制、1–4 句的分享卡（1080×1440 / 1080×1920）、
  状态栏歌词（Lyricon 或 Flyme）。

### 播放与视觉

- **交叉淡入用两个 ExoPlayer 重叠播放**：第二播放器预载下一首，`MediaSession.setPlayer()` 交接，
  40 ms 等功率淡入，时长 3/6/9/12 秒可选。
- **5 种流体背景，全部单趟片元着色**：云音（AGSL 五色 palette + 音频响应）、
  色阶流动（OkLab + 种子化参数）、QPlayer 像素模糊 / 经典流动 / 网格渐变（SkSL → AGSL 移植）。
  共用 60 fps 帧泵与静态帧降级；纹理统一「32 px → 模糊 → 256 px」预模糊，
  避开 `RuntimeShader` 子采样不过滤导致的方块。
- 经典封面 / 黑胶唱片两种播放页、封面↔歌词形变转场、液态玻璃底栏与顶栏。

### 下载与本地

- **下载中心可续传**：自管 `.part` + sidecar（Range / etag / 进度），进程重启继续；
  服务器忽略 Range 时回退整文件重下。
- **本地音乐完整链路**：内嵌封面三级回退、歌曲/专辑/艺人分组、五种排序、坏文件跳过统计。

---

## 快速体验

1. 从 [Releases](https://github.com/Fu-craft/YunYin/releases/latest) 下载
2. 多数手机选 `yunyin-<版本>-release.apk`（arm64）；老 32 位设备选 `-armeabi-v7a`；不确定选 `-universal`
3. 首次启动在设置里填 API 地址

---

## 构建

需要 **JDK 17+** 与 Android SDK（`compileSdk = 37`、build-tools 37.0.0）。

`local.properties`（不进仓库）：

```properties
sdk.dir=/path/to/Android/Sdk

# NeteaseCloudMusicApi 端点，注入 BuildConfig.API_BASE_URL
api.base.url=https://your-netease-api.example.com

# 动态封面端点（GET {base}/api/v1/artwork/search?artist=&album=）。
# 不写这个键时使用项目默认端点；显式留空则关闭该功能。
artwork.api.base.url=https://artwork.m8tec.top

# 应用内更新检查读取的仓库
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
./gradlew :app:assembleRelease    # 发布包（arm64 / armeabi-v7a / universal）
./gradlew :app:testDebugUnitTest  # 747 个单元测试
```

界面优先使用 **SF Pro**，因授权限制不分发；放入 `app/src/main/res/font/sf_pro.ttf` 即启用。

---

## 功能一览

**播放**：五档音质与自动降级、经典/黑胶两种播放页、双播放器交叉淡入、拖动切歌、
播放队列、循环随机、睡眠定时、45 秒试听识别、后台与通知栏控制、长按封面预览、双击收藏。

**动态封面**：Apple 方形 HLS 动图、双来源互补、AVC 1080 自适应、三层缓存、下一首预取、
随播放暂停、首帧淡入、圆角裁切。

**歌词**：多源竞速逐字歌词、渐变擦除填充、对唱、翻译、呼吸点、偏移校准、长按复制、
沉浸歌词、横屏侧栏、分享卡、状态栏歌词。

**视觉**：5 种流体背景、液态玻璃底栏与顶栏、封面↔歌词形变转场、子页滑入滑出。

**浏览**：首页（精选、日推、新歌、排行、私人 FM、歌单广场）、搜索（联想与历史）、
歌单/专辑/歌手详情与订阅关注、评论（排序、楼中楼、点赞、回复）、
资料库（喜欢、我的歌单、最近播放、本地音乐、下载中心）、喜欢同步到云端。

**下载与本地**：下载中心（进行中/已完成/失败、暂停继续重试删除、仅 Wi-Fi、并发 2、空间预检）、
断点续传、本地音乐（内嵌封面、分组、搜索、排序）。

**其他**：LX Music 自定义音源（QuickJS）、无卡片分组设置页、应用内更新、崩溃与音源诊断日志。

---

## 技术栈

- `compileSdk / targetSdk = 37`，`minSdk = 33`
- Kotlin **2.4.10**、AGP **9.3.2**、Compose **1.12.0**、Material3 **1.9.0**
- Media3 **1.11.1**（ExoPlayer + Session + HLS + 分片缓存）、Coil **2.7.0**、OkHttp **4.12.0**
- `backdrop` **2.0.1**、`gaze-capsule`、`lyrics-core` **0.5.0**、`lyrics-ui` **1.0.19**（vendored）、`quickjs-kt` **1.0.15**
- 版本 `4.x.y`，`versionCode` 单调递增；ABI 拆分为 arm64 / armeabi-v7a / universal

```text
app/src/main/java/com/yunyin/music/
├── data/       # 网络、仓库、设置、下载、本地音乐、音源、歌词、诊断
│   └── net/    # 网易云客户端、动态封面（endpoint / Apple amp-api / HLS 变体 / 源链）
├── playback/   # PlayerController、交叉淡入、试听识别、无缝衔接
├── lyrics/     # 歌词渲染
├── core/       # 模型与纯规则
└── ui/         # screens(31) / components(18) / background / theme
```

CI 每次 push 跑全部单元测试 + debug 构建。

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

- [LX Music](https://github.com/lyswhut/lx-music-desktop) / [LX-Music-TAchanged](https://github.com/TAchanged) — 动态封面的 amp-api 查询、storefront 顺序与 AVC 变体选择思路（Apache-2.0 / MIT）
- [bunnykek/Apple-Music-Animated-Artwork-Fetcher](https://github.com/bunnykek/Apple-Music-Animated-Artwork-Fetcher) — Apple 动态封面的原始逆向
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
