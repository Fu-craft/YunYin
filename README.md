# 云音 (YunYin)

一个用 Kotlin + Compose 写的网易云第三方播放器：逐字歌词、流体背景、液态玻璃，以及会动的专辑封面。

<p>
  <a href="https://github.com/Fu-craft/YunYin/releases"><img alt="Release" src="https://img.shields.io/github/v/release/Fu-craft/YunYin?label=release" /></a>
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-3DDC84" />
  <img alt="License" src="https://img.shields.io/badge/license-AGPL--3.0--only-blue" />
</p>

> 非官方客户端，与网易云音乐、Apple 均无关联。曲库来自你自己部署的 NeteaseCloudMusicApi，
> 仓库不含任何服务地址或凭据。仅供学习研究，请在自己有权访问的范围内使用。

## 功能

- **逐字歌词**：网易云 / QQ / 酷我 / 酷狗 / AMLL 五源并发，2.5 秒探针后一次性绘制；渐变擦除填充、对唱分边、翻译、呼吸点、偏移校准、沉浸歌词、状态栏歌词。
- **动态封面**：Apple Music 方形动图。先查自建端点，未命中再走 Apple amp-api（含 `cn` 等 storefront），只取 AVC 变体、最高 1080；三层缓存 + 预取下一首，进歌词页自动暂停解码。
- **播放**：经典封面 / 黑胶两种播放页，双 ExoPlayer 交叉淡入（3/6/9/12 秒），队列、循环随机、睡眠定时、试听识别、通知栏与后台控制。
- **视觉**：5 种片元着色流体背景（含音频响应）、液态玻璃栏、封面↔歌词形变转场。
- **浏览**：首页、搜索、歌单/专辑/歌手、评论、资料库、喜欢同步。
- **下载与本地**：可续传下载中心、仅 Wi-Fi、并发 2；本地音乐支持内嵌封面、分组、排序。
- **其它**：LX Music 自定义音源（QuickJS）、应用内更新、崩溃与音源日志。

## 安装

从 [Releases](https://github.com/Fu-craft/YunYin/releases/latest) 下载：

| 文件 | 适用 |
|---|---|
| `yunyin-<版本>-release.apk` | 绝大多数手机（arm64） |
| `yunyin-<版本>-armeabi-v7a.apk` | 32 位设备 |
| `yunyin-<版本>-universal.apk` | 不确定时选这个 |

## 构建

需要 JDK 17+ 与 Android SDK（`compileSdk 37`、build-tools 37.0.0）。

`local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
api.base.url=https://your-netease-api.example.com
update.repo.owner=your-github-name
update.repo.name=your-repo-name
# 可选：动态封面端点。不写用项目默认值，留空则关闭该功能
artwork.api.base.url=https://artwork.m8tec.top
```

`keystore.properties` 可选，缺失时产出未签名 APK。

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease    # arm64 / armeabi-v7a / universal
./gradlew :app:testDebugUnitTest
```

## 动态封面说明

- 请求 `GET {端点}/api/v1/artwork/search?artist=&album=`，返回 `{url, url_tall, artist, album}`。
- 端点没命中时使用 Apple amp-api：抓取 `WebPlayKid` token，按 `cn → hk → tw → us → gb → jp` 搜索，
  取 `editorialVideo.motionDetailSquare`。
- 拿到 master playlist 后只保留 `avc1` 变体（HEVC 在部分设备上只出黑帧），
  同分辨率选最省码率的一档；低内存机型上限 640。
- 缓存：内存 LRU、专辑→地址（7 天）、HLS 分片 64 MB。

## 技术栈

Kotlin 2.4.10 · Compose 1.12.0 · Material3 · Media3 1.11.1（ExoPlayer / Session / HLS）· OkHttp · Coil · QuickJS · AGSL。

```text
app/src/main/java/com/yunyin/music/
├── data/       # 网络、仓库、设置、下载、本地音乐、音源、动态封面
├── playback/   # 播放控制、交叉淡入、试听
├── lyrics/     # 歌词渲染
├── core/       # 模型与纯规则
└── ui/         # 页面、组件、背景、主题
```

## 鸣谢

- [LX Music](https://github.com/lyswhut/lx-music-desktop) — 动态封面的 amp-api 查询与 AVC 变体选择思路
- [bunnykek/Apple-Music-Animated-Artwork-Fetcher](https://github.com/bunnykek/Apple-Music-Animated-Artwork-Fetcher) — Apple 动态封面逆向
- [NeriPlayer](https://github.com/cwuom/NeriPlayer) — 流动背景与音频响应
- [QPlayer](https://github.com/TIMER-err/QPlayer) — 像素模糊 / 经典流动背景
- [AMLL](https://github.com/amll-dev/applemusic-like-lyrics) 与 [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) — 歌词格式与数据
- [accompanist-lyrics-ui / core](https://github.com/6xingyv/accompanist-lyrics-ui) — 歌词渲染与解析
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)、[Lyricon](https://github.com/tomakino/lyricon)、[quickjs-kt](https://github.com/dokar3/quickjs-kt)

完整清单见 [NOTICE](NOTICE)。

## 许可证

AGPL-3.0-only，见 [LICENSE](LICENSE)。
