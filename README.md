# 云音

一个仿 Apple Music 的网易云音乐第三方 Android 播放器。
**Kotlin + Jetpack Compose**，界面遵循 Apple Human Interface Guidelines，
具备**逐字歌词**与**封面驱动的流动背景**。

> ⚠️ 非官方客户端，仅用于个人学习与技术研究。音乐内容、商标及相关权利归各自权利人所有。
> 使用前请自行确认符合当地法律与服务条款。

---

## 功能

- **逐字歌词** —— 支持 AMLL TTML 与网易云 YRC 两路逐字时间轴，含逐字渐变填充、逐字跳动、
  和声/伴奏轨、间奏呼吸点、翻译与注音；两路来源自动择优合并，并做时间轴对齐。
- **流动背景** —— 从专辑封面提取主色，经 AGSL 着色器渲染成随音乐律动的流体色场；
  配合音频响应（RMS + 节拍检测）驱动，非模糊、非单纯渐变。
- **沉浸歌词** —— 静置数秒自动隐藏界面，歌词铺满全屏；横竖屏分别适配，
  横屏为「左栏控制 + 右侧歌词」。
- **Apple 风格界面** —— SF Pro 字体、SF Symbols 同款矢量图标、iOS 系统色板、
  HIG 字号阶梯、连续圆角（squircle）、毛玻璃 chrome、底部 Sheet 队列、迷你播放器。
- **歌单详情** —— 封面放大加模糊作为整页色场，正文颜色由封面亮度测得（暗封面白字、
  亮封面深字），顶部返回/分享条常驻并随滚动过渡为不透明；列表标注序号与正在播放行。
- **收藏与分享** —— 播放页爱心直接收藏/取消收藏（乐观更新，失败回滚）；长按封面保存
  封面图到相册，长按歌词行复制歌词。
- **词幕状态栏歌词** —— 可选集成：安装「词幕」后，当前歌曲、逐字歌词与播放状态会推送给它，
  由它渲染到状态栏／锁屏。未安装时不启用，应用行为不受影响（见下方「词幕」）。
- **开屏动画** —— 冷启动期间展示品牌开屏，待会话与首页数据就绪后交叉溶解揭开。
- **后台播放** —— Media3 ExoPlayer + MediaSessionService，支持锁屏与通知控制。
- **网页登录** —— 应用内嵌浏览器登录，自动获取登录态，无需手动复制 Cookie。

---

## 截图

> 待补充。把图片放到 `docs/screenshots/` 后在此引用即可。

---

## 技术栈

| 项 | 版本 / 说明 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose |
| 播放 | Media3 ExoPlayer + MediaSessionService |
| 背景 | AGSL（`android.graphics.RuntimeShader`，需 API 33+） |
| 图像取色 | AndroidX Palette |
| 状态栏歌词 | 词幕 Lyricon Provider（可选，见「词幕」） |
| minSdk / targetSdk | 33 / 37 |
| 构建 | Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.4.10 |

---

## 构建

```bash
./gradlew :app:assembleDebug       # 调试包
./gradlew :app:assembleRelease     # 发布包
./gradlew :app:testDebugUnitTest   # 单元测试
```

需要 JDK 17+ 与 Android SDK。若环境中 `java` 不在 PATH，设置 `JAVA_HOME` 指向你的 JDK 即可。

### 配置（两项都在本地，不进仓库）

项目通过两个 **git-ignored** 的配置文件读取本地配置：

**`local.properties`** —— 除 SDK 路径外，增加接口地址：

```properties
api.base.url=https://your-netease-api.example.com
```

该值在编译期注入 `BuildConfig.API_BASE_URL`，**源码中不含任何服务器地址**。
留空时应用能正常构建与启动，只是在调用接口时提示「未配置接口地址」。

**`keystore.properties`** —— 签名（不配置则 release 包不签名，但构建不会失败）：

```properties
storeFile=my-release.jks
storePassword=****
keyAlias=mykey
keyPassword=****
```

生成自己的密钥：

```bash
keytool -genkeypair -v -keystore my-release.jks -alias mykey \
        -keyalg RSA -keysize 2048 -validity 10000
```

> ⚠️ **绝不要提交 `keystore.properties` 或 `.jks`**（已在 `.gitignore` 中）。
> 私钥泄露意味着任何人都能发布 Android 会当作你签名的更新。

### 字体

界面使用 SF Pro，因许可限制**不包含在本仓库**。
请自行获取后放入 `app/src/main/res/font/sf_pro.ttf`；
或把 `ui/theme/Type.kt` 中的 `SFPro` 换成 Inter / Noto Sans 等可自由分发的字体。

---

## 接口

应用需要一个 [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi)
服务端，地址在应用内配置（仓库不附带任何服务端地址）。

已接入的主要接口：

| 用途 | 接口 |
|---|---|
| 游客会话 | `POST /register/anonimous` |
| 登录态 | `/login/status` |
| 精选歌单 | `/personalized?limit=` |
| 推荐歌单 | `/recommend/resource` |
| 每日推荐 | `/recommend/songs`（需登录） |
| 新歌速递 | `/personalized/newsong?limit=` |
| 排行榜 | `/toplist` |
| 歌单详情 | `/playlist/detail`、`/playlist/track/all` |
| 用户歌单 | `/user/playlist?uid=` |
| 我喜欢的音乐 | `/likelist?uid=` |
| 收藏 / 取消收藏 | `/like?id=&like=` |
| 搜索 | `/search?keywords=&type=1`、`/search/hot` |
| 歌曲详情 | `/song/detail?ids=` |
| 歌词 | `/lyric/new?id=` |
| 播放地址 | `/song/url/v1?id=&level=` |
| 歌手热门 | `/artist/top/song` |

封面支持 `?param=500y500` 直接取缩略图，应用按需请求尺寸。

**逐字歌词（YRC）格式**：`[行开始ms,行时长ms](字开始ms,字时长ms,?)字…`

---

## 逐字歌词

两路来源，按「有逐字优先」并发竞速合并，统一解析为一种 `SyncedLyrics`：

1. **AMLL TTML DB**（首选，Apple Music 同源逐字歌词）
   `amll-ttml-db` 的 `ncm-lyrics/{网易云歌曲ID}.ttml`，按网易云 ID 直取，
   含逐音节时间、对唱左右声道、翻译。多镜像并发竞速，取第一个可用结果。
2. **网易云 `/lyric/new`**：`yrc`（逐字）优先，否则 `lrc`（逐行）；
   翻译优先取 `ytlrc`（对齐逐字），其次 `tlyric`（对齐逐行）。

两路来源若时间轴不一致（同一首歌的 Apple Music 版本前奏更短），
会以网易首行时间为准做整体平移对齐。

播放位置以**每帧插值**提供，而非低频轮询——否则逐字时间轴会抖。

---

## 流动背景

不是模糊。组成：

1. **封面取色** —— AndroidX Palette 取 5 个角色色（base / accent / light / dark / bridge）并做 HSL 柔化。
2. **色场** —— AGSL 着色器把五个柔和色场按反平方权重混合；
   色场被映射到一条色带再拉伸，这是斜向大色带的来源（铺满全屏只会得到一片柔糊）。
3. **流动** —— 色场中心随时间做轨道运动，另由音频响应的 beat/motion 波位移采样坐标。
4. **音频响应** —— 从 ExoPlayer 音频管线 `TeeAudioProcessor` 取 PCM，
   经 RMS → 快/慢 EMA → 自适应噪声底 → 节拍检测，再非对称平滑后驱动 uniforms。

毛玻璃控制栏由**同一份背景场**渲染两层实现：一层模糊、一层清晰，
清晰层在面板边界渐隐 —— 两层颜色逐像素一致，交界处只改变锐度，因此不会出现色阶。

> **来源声明**：本部分的着色器与 painter 移植自 **NeriPlayer**（GPL-3.0）。详见下方「许可与致谢」。

---

## 词幕（状态栏歌词）

可选集成。安装 [词幕 Lyricon](https://github.com/proify/lyricon) 后，应用会把当前歌曲、
歌词与播放状态推送过去，由词幕渲染到状态栏、锁屏与悬浮窗。

接入的是词幕官方的 **Provider** 接口（`io.github.proify.lyricon:provider`，Apache-2.0）：

- `AndroidManifest.xml` 中声明 `lyricon_module` 等 meta-data，词幕据此把本应用列为歌词来源。
- `LyriconBridge` 注册 provider，并通过 `RemotePlayer` 推送 `Song`（含逐字 `words` 与翻译）
  与播放状态／进度。
- `LyriconMapper` 负责把本项目的歌词文档转成词幕的模型，并强制满足其文档约定：
  时间统一毫秒、行时间单调递增、逐字范围落在所属行内、空行不发布。

**未安装词幕时整个功能是空操作**：provider 返回空实现，应用其余部分完全不受影响；
设置页有「状态栏歌词」一行显示当前状态（已连接／未连接／未安装）并可重试。

---

## 播放

`Media3 ExoPlayer` + `MediaSessionService`（后台播放、锁屏与通知控制）。

网易云返回的是**短时效签名 URL**，因此队列项用 `netease://track/{id}` 占位，
由 `ResolvingDataSource` 在真正加载时才换真链——队列因此保持稳定，
ExoPlayer 可正常处理切歌与重试。

会员曲（`fee=1`）在免费会话下只返回 45 秒试听片段，应用会识别并提示，
同时避免把片段当作完整歌曲循环。

---

## 项目结构

```
app/src/main/java/com/yunyin/music/
├── ui/             Compose 界面：播放器、歌词页、歌单、资料库、主题与图标
│   ├── background/ 流动背景（AGSL + Palette）
│   └── screens/    各页面
├── lyrics/         逐字歌词渲染器（vendored，见许可与致谢）
├── data/           接口、解析、缓存
├── playback/       ExoPlayer 封装与音频响应
└── core/           模型与工具
```

---

## 许可与致谢

### 本项目代码的来源

| 部分 | 来源 | 许可证 |
|---|---|---|
| 流动背景（着色器、painter、调色板、音频响应） | [NeriPlayer](https://github.com/cwuom/NeriPlayer) | **GPL-3.0** |
| 逐字歌词渲染器（`lyrics/`） | `accompanist-lyrics-ui` / `-core` | Apache-2.0 |
| 词幕接入（`LyriconBridge` / `LyriconMapper`） | 本项目代码，依赖 [词幕 Lyricon](https://github.com/proify/lyricon) 的 Provider 接口 | Apache-2.0 |
| 其余界面 / 数据 / 网络 / 播放 | 本项目 | 见下 |

### 许可证

由于流动背景与音频响应源自 **GPL-3.0** 的 NeriPlayer，
按 GPL-3.0 的分发条款，**整个组合作品需以 GPL-3.0 分发**。
本仓库因此以 **GPL-3.0** 发布，全文见 [LICENSE](LICENSE)。

若希望改用 MIT / Apache-2.0 等宽松许可，需先将上述 GPL 来源的部分
**独立重写**（而非修改注释）。

### 第三方

- **SF Pro** —— Apple 的字体，**不包含在本仓库**，请自行获取或替换（见「构建」）。
- **SF Symbols 同款图标** —— 项目内为手绘矢量，命名对齐 SF Symbols，未使用 Apple 资产。
- **NeteaseCloudMusicApi** —— 服务端由使用者自行部署。

---

## 免责声明

本项目为个人学习与技术研究项目，**非网易云音乐或 Apple 官方客户端**，
与二者无任何关联。「网易云音乐」「Apple Music」「SF Pro」等名称与商标
归各自权利人所有。

请勿将本项目用于商业用途。使用者需自行承担因使用本软件产生的一切责任。
