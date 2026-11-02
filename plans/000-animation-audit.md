# 动效审计（improve-animations · Phase 1–3）

审计对象：云音 Compose 客户端，`app/src/main/java`（不含测试）。
本文件只做**发现与计划**，不改源码（技能硬性规则）。

## Phase 1 · Recon

| 项 | 实测 |
|---|---|
| 框架 | Jetpack Compose（Animation API，无第三方动效库） |
| 动效调用点 | **206 处**，分布在 **32 个文件** |
| 原语用量 | `tween` 83、`spring` 26、`animateFloatAsState` 24、`AnimatedVisibility` 16、`Animatable` 16、`animateColorAsState` 4、`rememberInfiniteTransition` 3、`infiniteRepeatable` 2、`AnimatedContent` 2、`Crossfade` 1 |
| 时长取值 | 60 / 100 / 160 / 180 / 220 / 250 / 300 / 320 / 420 / 600 / 1000 / 1400 / 1800 ms（**无统一 token**） |
| 既有约定 | `Motion` 常量对象（`ItemAppearDuration`、`EaseOut`、`SheetEnterDuration` …），但只有部分调用点使用 |
| 产品性格 | 消费级音乐应用，动效应"贴身、可打断、克制" |
| 高频面 | 播放页控件、标签栏切换、列表行、歌词滚动 → 每天数十到数百次 |
| 低频面 | sheets、设置页、报告页 → 每天几次 |

## Phase 2–3 · 已核实发现（按 leverage 排序）

| # | 严重度 | 类别 | 位置 | 现象 | 修复摘要 |
|---|---|---|---|---|---|
| 1 | **HIGH** | 目的与频率 | `SettingsScreen.kt`（每行 `appear.animateTo`） | 设置页 40+ 行**逐行错峰淡入**（6 dp 上浮）。低频页面上做高频动效，页面看起来"一直在动"，且首屏被拖慢 | 删除逐行动画，只保留整页一次 200 ms 淡入 |
| 2 | **HIGH** | 缓动与时长 | 全局 83 处 `tween`，其中多处以 `tween(300)` 硬编码 | 30+ 个不同时长共存，同类交互手感不一致；缺失统一 token | 收敛为 `Motion.Fast(150)` / `Motion.Base(220)` / `Motion.Slow(320)`，UI 交互禁止 >320 ms |
| 3 | MEDIUM | 可打断性 | `PlayerScreen.kt` 演示态切换（`AnimatedContent`） | 已刻意用交叉淡入 + `sizeTransform = null` 避免黑闪（**属既定设计，不报**）；但其周边控件的 `AnimatedVisibility` 各自独立时长，进出不同步 | 统一到同一 spec，进出同时长 |
| 4 | MEDIUM | 物理性 | `HomeScreen.kt:107`、`QueueSheet.kt:222`、`ArtistScreen.kt:329`、`PlaylistSquareScreen.kt:275` | 行入场使用 `tween(Motion.ItemAppearDuration)` 但**未指定 easing**，实际是 LinearEasing——列表滑入显得"机械" | 显式 `easing = Motion.EaseOut`，时长 ≤ 180 ms |
| 5 | MEDIUM | 无障碍 | 全局 | 未见 `ValueAnimator.areAnimatorsEnabled()` 或 reduced-motion 分支（除播放页个别处） | 在 `Motion` 层提供 `scale(duration)`，系统关闭动画时归零 |
| 6 | LOW | 性能 | `LyricsView.kt:339-343` | 占位符用 1400 ms 无限往复（`RepeatMode.Reverse`）呼吸；长时间停留持续请求帧 | 静止 3 s 后停止该动画，或改为静态骨架 |
| 7 | LOW | 连贯性 | `QueueSheet` / `PlayerStyleSheet` / `UpdateSheet` | 各 sheet 入场时长/曲线不统一 | 统一 `Motion.SheetEnterDuration` + `EaseOut`，退出 0.7× 入场 |

## 遗漏机会（additive）

1. **切歌时的身份变化**：播放页标题已有 `Crossfade`，但封面容器没有随切歌做 120 ms 的位移+淡出，切歌读起来像"图片被替换"。
2. **列表项按下反馈**：多数行只有 ripple，没有按下缩放；44 dp 目标上做 0.97 缩放 120 ms 能立刻"贴手"。
3. **收藏按钮**：点赞只有颜色变化，缺少 1.0→1.12→1.0 的弹簧（`dampingRatio 0.6`，`stiffness 900`），这是唯一"值得庆祝"的高频动作。
4. **空状态**：无内容时直接出现文字，缺少 160 ms 淡入 + 8 dp 上浮。

## 结论

206 处调用里，**真正需要动的是前 3 项**：设置页的逐行动效（删）、时长 token（收敛）、列表入场 easing（补）。
第 3 项（播放页交叉淡入）已经过专门调试，**不应再改**。

## 待你选择

按技能流程，接下来应为选中的发现各写一份计划（`plans/NNN-*.md`，含精确 easing 数值、file:line、验证方法），然后由任意执行方实现。默认建议执行 **#1 #2 #4** 三项。
