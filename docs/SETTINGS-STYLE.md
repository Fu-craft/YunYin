# 设置页 UI 风格规范（ui-ux-pro-max 评估结果）

评估来源：`ui-ux-pro-max` 技能库检索（`--domain style` 命中 **Minimalism & Swiss Style**，`--domain ux` 命中排版尺度与层级相关条目）。
适用对象：`ui/screens/SettingsScreen.kt`（Compose · 云音）。目标不是重排结构，而是**换掉视觉语言**。

## 1. 风格定位

**Minimalism & Swiss Style**（技能库原始关键词：clean, spacious, functional, high contrast, geometric, essential）。

要点，按技能给出的实现清单逐条对应：

| 技能条目 | 在本页的落地 |
|---|---|
| white space（留白即结构） | 页边距 16 dp；分区间距 28 dp；卡片内行距靠内边距，不靠分割线堆叠 |
| high contrast（对比靠明度） | 主文字用前景色，副文字用 muted 色；两者明度差 ≥ 40%，不靠字号区分 |
| no box-shadow unless necessary | **不用阴影、不用描边**分层；卡片只靠底色差 |
| single accent only | 强调色只出现在**开关开启态、危险操作、可点链接**三处；标题一律不用强调色 |
| clear typography hierarchy | 三级：分区标题 13 / 行标题 16 / 副文字 12.5，行高 1.4–1.5 |
| subtle hover (200–250ms) | 按下反馈 120–200 ms；整页入场 200 ms 淡入，**不要逐行 stagger** |

## 2. 颜色（从技能配色表映射到云音调色板）

技能示例给的是暗色 OLED 方案（页面 `#0F0F23` / 卡片 `#1B1B30` / muted `#94A3B8` / 强调 `#22C55E`）。我们不改品牌色，只借用**层级关系**：

| 角色 | 取值 | 说明 |
|---|---|---|
| 页面底 | `palette.background` | 保持现状 |
| 卡片底 | `palette.secondaryBackground` | 与页面底明度差 ≥ 6%，否则卡片“消失” |
| 行标题 | `palette.label` | 100% |
| 副文字 / 取值 | `palette.secondaryLabel` | 60–72%，**不低于 55%**（低于此在暗色下不足 4.5:1） |
| 分区标题 | `palette.secondaryLabel` | 与副文字同色，但**加字距 0.4 sp** 区隔 |
| 分隔线 | `palette.separator` @ 35% | 只在行之间，末行不画 |
| 强调色 | `palette.accent` | 仅开关开启态 / 危险操作 / 链接 |

## 3. 排版

| 层级 | 字号 | 字重 | 字距 | 色 |
|---|---|---|---|---|
| 页面标题「设置」 | 32 sp | Bold | −0.6 | label |
| 分区标题 | 13 sp | SemiBold | **+0.4** | secondaryLabel |
| 行标题 | 16 sp | Medium | −0.1 | label |
| 行副标题 | 12.5 sp | Regular | +0.1 | secondaryLabel |
| 行取值（右侧） | 14 sp | Regular | 0 | secondaryLabel |

行高：标题 22 sp、副标题 18 sp。中文字号不要小于 12 sp。

## 4. 形状与间距

- 卡片圆角 **14 dp**（技能示例给 0 dp 的“直角瑞士”，但移动端列表用 14 dp 更稳，且与 App 其它卡片一致）；
- 卡片左右内缩 **16 dp**；
- 行内边距：`start 18 / end 14 / top 10 / bottom 10`；
- 行最小高度：**52 dp（无副标题）/ 64 dp（有副标题）**，保证 44×44 触控下限之上；
- 分区之间 **28 dp**，分区标题与卡片之间 **7 dp**；
- 卡片与行之间不加额外间隙（行靠内边距呼吸）。

## 5. 组件级规则

| 组件 | 规则 |
|---|---|
| 开关行 | 整行可点（不是只有开关可点）；开关距右边缘 14 dp |
| 跳转行 | 右侧 13 dp 细箭头，颜色 tertiaryLabel；**同一分区内要么都有箭头，要么都没有** |
| 取值行 | 取值靠右、与箭头间距 8 dp；长文本尾部省略 |
| 危险操作 | 文案用 accent，不加底色；点击后再弹确认 |
| 分组 | 一个分区 ≤ 5 行；超过就拆分区，不要靠分割线硬塞 |

## 6. 动效

- 按下反馈：随触摸按下立即变化（120 ms），不要等抬手；
- 页面进入：200 ms 淡入 + 4 dp 上浮，**仅整体一次**；
- 开关切换：160 ms 颜色与位移；
- 关闭“逐行错峰入场”（当前 40 行各自延迟，是“吵”的主要来源）；
- 遵循系统「移除动画」设置（`ValueAnimator.areAnimatorsEnabled()`）。

## 7. 交付前自检（技能清单的移动端版本）

- [ ] 副文字在深色底上对比度 ≥ 4.5:1（muted 不低于 55% 不透明）
- [ ] 所有可点元素 ≥ 44×44 dp，行内元素间距 ≥ 8 dp
- [ ] 没有阴影 / 描边分层，卡片靠底色区分
- [ ] 强调色只出现在三处（开关开启、危险、链接）
- [ ] 分区标题与行标题字距不同（层级不只靠字号）
- [ ] 无逐行错峰动画；尊重系统动画开关
- [ ] 中文无乱码（历史遗留：`SettingsScreen.kt` 曾有 54 处 UTF-8/GBK 损坏）
