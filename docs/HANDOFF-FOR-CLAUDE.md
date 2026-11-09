# 云音（YunYin）项目交接文档

> 面向接手的工程师 / AI 助手。读完这一份就能接手改代码、发版、推送。
> 最后更新：v4.97.9（versionCode 448），对应远端 commit `f02eb39`。

---

## 1. 这是什么

| 项 | 值 |
|---|---|
| 应用 | 云音（YunYin），Android 音乐播放器 |
| 包名 | `com.yunyin.music` |
| 技术栈 | Kotlin 2.4.10 · Jetpack Compose（Compose Multiplatform 1.12.0）· Media3/ExoPlayer |
| 构建 | AGP 9.3.2 · compileSdk 37 · minSdk 33 · targetSdk 37 |
| 许可 | **AGPL-3.0-only**（改动必须保持同许可；不要引入不兼容的代码） |
| 仓库 | `https://github.com/Fu-craft/YunYin`，默认分支 `master` |
| 本地路径 | `D:\AmllPlayer`（**注意是 D 盘，不是 C 盘**） |
| 规模 | 271 个 `.kt` / 约 56.9k 行主源码；114 个测试文件 / **753 个单测** |

---

## 2. 环境与构建

### 2.1 必须的设置

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\jbr-21.0.11-1"
```

### 2.2 三条命令

```powershell
# 改代码时：快速编译
.\gradlew.bat :app:compileReleaseKotlin --console=plain

# 提交前：完整门禁（必须全绿才允许提交/打包）
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest --console=plain

# 单测基线：753 个，failures=0 errors=0
```

### 2.3 ⚠️ 不要加 `--offline`

历史文档里写的 `--offline` **现在会失败**。原因：v4.97.0 引入了 COUI 库，它依赖更新的 androidx 产物（`androidx.compose.*:1.13.0-alpha02`、`core-ktx:1.19.1`、`graphics-shapes:1.0.1`、`dynamicanimation:1.0.0`），本地 Gradle 缓存里没有。必须联网构建。

### 2.4 工具链状态（2025-11 实测）

| 组件 | 位置 | 状态 |
|---|---|---|
| JDK 21 | `C:\Users\Administrator\.jdks\jbr-21.0.11-1` | ✅ |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`（platform 37 / build-tools 37.0.0） | ✅ |
| gh CLI | `C:\Program Files\GitHub CLI\gh.exe`，账号 `Fu-craft` | ✅ 已登录 |
| git 命令 | **PATH 里没有** | ❌ 见 §4 |
| adb 设备 | 无 | ❌ 无法真机验证，只能编译 + 单测 |

---

## 3. 发版流程（每次都要照做）

1. **改版本号** `app/build.gradle.kts`：`versionCode` +1、`versionName` 递增。
2. **打包前先删旧产物**（不删可能拿到旧包）：
   ```powershell
   Remove-Item app\build\outputs\apk\release -Recurse -Force
   ```
3. **构建 + 单测**：`.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest --console=plain`
4. **核对版本号真的写进去了**（改 gradle 偶尔会静默失败）：
   ```powershell
   Get-Content app\build\outputs\apk\release\output-metadata.json
   ```
5. **拷产物**：
   ```powershell
   Copy-Item app\build\outputs\apk\release\app-arm64-v8a-release.apk dist\yunyin-<versionName>-release.apk
   ```
6. **算 sha256**：`Get-FileHash dist\yunyin-<versionName>-release.apk -Algorithm SHA256`
7. **推送**：见 §4。

### 交付报告格式（用户明确要求）

必须包含：改了哪些文件、`versionCode` / `versionName`、单测数量与失败数、sha256、APK 路径。

---

## 4. 推送代码（没有 git 命令！）

`github.com:443` 在这台机器上**被墙**，`git push` 走不通，`git` 也不在 PATH 里。所有推送通过 GitHub REST API 完成：

```powershell
.\tools\push_files_via_api.ps1 -Message "feat(xxx): 说明 (v4.x.y)" -Files @(
  "app/src/main/java/.../Foo.kt",
  "app/build.gradle.kts"
)
```

- 脚本内部用 `gh api` 建 blob → tree → commit → 更新 ref。
- 支持删除文件：`-Deleted @("path/to/Gone.kt")`。
- **只列自己改过的文件**，绝不要 `git add -A` 式的整树提交。
- 首次遇到 `TLS handshake timeout` 直接重跑，是网络抖动。

### 本地 `.git` 是过期的

本地 `.git` 停在 `7822da3`（老提交），而远端已经到 `f02eb39`。因为一直用 API 推送，本地仓库没跟着走。**判断代码对不对，以远端为准**，别信本地 `git log`。

---

## 5. 签名密钥（丢失不可逆）

| 文件 | 位置 | 是否入库 |
|---|---|---|
| `yunyin-release.jks` / `amll-release.jks` | `D:\AmllPlayer\keystore\` | ❌ `.gitignore` 排除 |
| `keystore.properties` | `D:\AmllPlayer\` | ❌ `.gitignore` 排除 |

⚠️ **这两个东西只存在于这台 D 盘。** 一旦丢失，已安装用户只能卸载重装（签名不同的包不能覆盖安装）。
接手第一件事：把 `keystore\` + `keystore.properties` 备份到 U 盘/网盘。

`.gitignore` 还排除了：`tools/`、`plans/`、`dist/`、`*.apk`、`docs/DEVLOG.md`、`docs/HANDOFF.md`、`local.properties`。
其中 **`tools/` 里的推送脚本没有入库**，靠本地保存。

---

## 6. 代码地图

```
app/src/main/java/com/yunyin/music/
├── MainActivity.kt        # 唯一 Activity，所有页面装配、状态、导航都在这里（≈2800 行）
├── AppContainer.kt        # 手写依赖容器（player / lyrics / downloads / settings / update …）
├── audio/                 # 均衡器（EqualizerSettings / PlaybackEqualizer）
├── core/                  # 领域模型（Track / Playlist / ChartInfo）
├── data/                  # 仓库层：歌词、音源、下载、设置、更新、LX 音源脚本
│   ├── SettingsStore.kt   # SharedPreferences 封装，所有偏好都在这里
│   ├── LyricsRepository.kt / LyricTranslation.kt / LyricLineFolding.kt …
│   └── providers/         # 网易 / QQ / 酷我 / AMLL 等歌词与搜索源
├── lyrics/                # 逐字歌词渲染（KaraokeLyricsView / SyncedLineText …）
├── playback/              # PlayerController / PlaybackService（Media3）／均衡器接线
├── ui/
│   ├── components/        # 共享组件：LiquidGlass（玻璃）、Cards、Glass、QueueSheet …
│   ├── screens/           # 各页面：Home / Search / Library / Collection / PlayerScreen / Settings …
│   ├── style/UiStyle.kt   # ★ 双界面风格开关
│   └── theme/             # Motion 动效令牌、AppTheme 配色、SFPro 字体
└── widget/                # 桌面小组件（RemoteViews）
```

### 关键约定

- **文案全部中文**，不要引入英文 UI 文案。
- **动效统一走 `ui/theme/Motion.kt` 的令牌**（`Fast/Base/Slow`、`SmallDuration`、`EaseOut` 等），不要手写魔法数。
- 设置页样式规范在 `docs/SETTINGS-STYLE.md`（字号、留白、分隔线、强调色使用规则）。

---

## 7. 当前正在做的事：双界面风格（COUI）

### 7.1 设计

两套**完全独立**的实现并存，用户在 设置 → 界面风格 里切换：

| 风格 | 组件库 | 存储值 |
|---|---|---|
| 经典 | Miuix（`top.yukonga.miuix.kmp`） | `"classic"`（默认） |
| 凝光 | COUI（`io.github.itoseo:coui:0.0.1-alpha7`，MPL-2.0） | `"coui"` |

- `ui/style/UiStyle.kt`：枚举 + `LocalUiStyle`（CompositionLocal）+ `CurrentUiStyle`（全局可观察值，给共享组件用，由 `MainActivity` 的 `SideEffect` 写入）。
- 持久化：`SettingsStore.uiStyle`（字符串，默认 `classic`，老用户升级后界面不变）。

### 7.2 已转换（有 COUI 实现）

| 区域 | 文件 |
|---|---|
| 设置主页 | `SettingsScreenCoui.kt` |
| 设置子页（音源/许可/更新/日志/音效） | `SettingsSubpagesCoui.kt`、`SettingsLxSourcesCoui.kt` |
| 标题排版（首页/搜索/资料库/下载中心共用） | `components/Glass.kt` 的 `SectionHeader` |
| 列表行排版（搜索/资料库/歌手页共用） | 同上 `MediaRow` |
| 确认弹窗（下载中心 ×2、清空队列） | `components/CouiConfirmDialog.kt` |
| 播放器时间标签 | `screens/PlayerControlsCouiParts.kt` |
| 进度条光效 | `PlayerProgressBar` 的 `glow` 参数（用 COUI `rememberCouiSpotlight`） |

### 7.3 未转换，以及为什么

| 区域 | 卡点 |
|---|---|
| **封面**（`ArtworkWithGestures` + 共享元素 + 动态封面/海报） | **用户明确要求不动** |
| **歌词页**（逐字歌词/滚动/字号） | **用户明确要求不动** |
| 底栏 + 迷你播放器 | 自绘玻璃（`io.github.kyant0:backdrop`）与 COUI `CouiBackdrop` **冲突**，需先决定是否整体迁移背景系统 |
| 搜索栏 | COUI `SearchBar` 签名里就要 `CouiBackdrop`，同上 |
| 菜单/长按菜单 | COUI `Menu` 需要 `ProvideCouiMenuBackdrop`，同上 |

**这是接手后最重要的待决策项**：迁移到 `CouiBackdrop`（质感会变、动效要重调）还是保留现有玻璃（只对齐排版配色）。**没得到用户明确同意前不要擅自迁移。**

### 7.4 已知副作用

COUI 把整个项目的 `androidx.compose.ui` 顶到了 **1.13.0-alpha02**（我们用的是 Compose Multiplatform 1.12.0）。目前全项目编译与 753 个单测都正常，但这是 alpha 版本，遇到奇怪 runtime 问题时要想到这条。彻底解决需要把 CMP 升到 1.13 线，或等 COUI 出对齐 1.12 的版本。

---

## 8. 动效的现状（有过一次失败的重做，别再踩）

- **迷你播放器 → 播放器的展开**：曾经尝试移植 MeiloX 的 `MiniPlayerChromeMotion`（双段弹簧 + 速度继承），接线后用户反馈"一塌糊涂"，最终**整体回滚**到原来的 `AnimatedVisibility(slideInVertically + fadeIn)`。
- `ui/sheet/MiniPlayerChromeMotion.kt` 仍然存在（带 4 个单测钉住数值），但**没有接线**。要再启用，必须先想清楚两件事：
  1. 共享元素的两端必须在**同一次内容过渡**里（我们的胶囊在底栏、播放器在 overlay，是两个不同的过渡）；同 key 同时存在两份是官方不支持的状态。
  2. MeiloX 的 `playerVisible` 语义是"内容已可见"，不是"用户点了展开"，直接传 `showPlayer` 会让胶囊瞬移。
- 用户对动效极其敏感，**任何动效改动都要先说明改哪个数、为什么**，不要一次改一大片。

---

## 9. 已知坑（都真实踩过）

1. **PowerShell 写含中文的字符串会崩**（引号/反引号转义）。改中文注释或中文字符串时，**写 `.py` 文件再用 python 执行**，或者用编辑工具的精确替换。不要用 `pwsh -c` 里内联长中文。
2. **`String.Replace` 对 CRLF 文件可能静默失败**：先 `Replace("\r\n", "\n")` 替换完再写回，并 `Select-String` 验证。
3. **`--offline` 不可用**（见 §2.3）。
4. **C 盘空间紧张**：构建缓存会吃掉几 GB；曾因 0 字节可用导致 `mergeReleaseResources` 失败。清理：`~/.gradle/caches/build-cache-1` 可安全删除。
5. **没有真机**：`adb devices` 为空，所有 UI 结论只能靠代码 + 编译 + 单测，**不要声称"已肉眼验证"**。
6. **不要用 `git revert` 撤历史提交**（会和 `build.gradle.kts` 的版本号改动冲突）。要回退就整文件替换。
7. `tools/` 里有几百个历史调试脚本与截图，是垃圾场，不用整理也不用删。

---

## 10. 下一步建议（按优先级）

1. **等用户决定底栏/搜索/菜单的背板方案**（§7.3），这是剩余 UI 转换的唯一阻塞点。
2. 首页/歌单广场/资料库/心动模式的卡片排版对齐 COUI（标题与行距已由共享组件接管，剩下是封面墙的自绘部分）。
3. 如需继续做动效，先和用户确认目标，再按 `MiniPlayerChromeMotion` 的单测数值逐步接线，**一次只接一个通道**。
4. 备份签名密钥（§5）——这件事优先级高于任何代码工作。

---

## 11. 交接检查清单

- [ ] `$env:JAVA_HOME` 指向 JBR 21
- [ ] `.\gradlew.bat :app:compileReleaseKotlin` 通过
- [ ] `.\gradlew.bat :app:testDebugUnitTest` → 753 / 0 失败
- [ ] `keystore\` 与 `keystore.properties` 已备份
- [ ] `gh auth status` 显示已登录 `Fu-craft`
- [ ] 远端 `master` = `f02eb39`（或更新）
- [ ] 知道 `tools\push_files_via_api.ps1` 怎么用
