# 云音（YunYin）

网易云音乐第三方 Android 播放器，Kotlin + Jetpack Compose 实现，界面仿 Apple Music。
非官方客户端，与网易云音乐、Apple 均无关联；需要 Android 13（API 33）及以上。

## 功能

**播放**

- 标准 / 较高 / 极高 / 无损 / Hi-Res 五档音质切换
- 经典圆角封面与黑胶唱片两种播放器界面；暂停时封面轻微缩小
- 沉浸播放页：封面驱动流动背景、向下拖动收起、播放队列、循环与随机
- 下载到本地：经 MediaStore 写入系统音乐库，可取消与重试
- 后台播放与锁屏 / 通知栏控制（Media3 `MediaSessionService`）
- 无缝衔接播放；会员曲目 45 秒试听片段自动识别
- 本地优先收藏、分享歌曲、保存封面

**歌词**

- AMLL TTML 与网易云 YRC 逐字歌词，无逐字时回退 LRC；两路来源并发获取并整体对齐
- 逐字渐变、对唱双声部、间奏呼吸点、翻译
- 歌词校准；长按歌词行复制
- 沉浸歌词（静置隐藏界面）、横屏侧栏布局
- 状态栏歌词：推送到 Lyricon，或使用 Flyme 系统通知栏

**浏览与设置**

- 首页：精选歌单、每日推荐、为你推荐、新歌速递、排行榜、探索更多
- 搜索：关键词、最近搜索、热门搜索；可合并其它平台曲目（经自定义音源播放）
- 歌单详情（封面模糊色场、播放全部、随机播放、分享）、歌手详情
- 资料库：喜欢、我的歌单、最近播放；网页登录；资料编辑（头像 / 背景 / 昵称 / 签名）
- 自定义音源：导入 LX Music 脚本（.js 文件或链接），校验后用于解析播放地址，失败回退内置接口
- 单行液态玻璃底栏：主页 / 资料库 tab 与迷你播放器
- 应用内更新（GitHub Release）

## 截图

| 播放页 | 资料库 | 歌词页 |
|:--:|:--:|:--:|
| <img src="docs/screenshots/player.png" width="240" alt="播放页"> | <img src="docs/screenshots/library.png" width="240" alt="资料库"> | <img src="docs/screenshots/lyrics.png" width="240" alt="歌词页"> |

| 首页 | 搜索 | |
|:--:|:--:|:--:|
| <img src="docs/screenshots/home.png" width="240" alt="首页"> | <img src="docs/screenshots/search.png" width="240" alt="搜索"> | |

## 构建

需要 JDK 17+、Android SDK（`compileSdk = 37`、build-tools 37.0.0）。

`local.properties`（本地配置，不进仓库）：

```properties
sdk.dir=/path/to/Android/Sdk
# 自部署的 NeteaseCloudMusicApi 地址；留空可构建，但应用无法加载内容
api.base.url=https://your-netease-api.example.com
# 应用内更新指向的 GitHub 仓库；留空则隐藏检查更新
update.repo.owner=your-github-name
update.repo.name=your-repo-name
```

`keystore.properties`（可选，用于签名 release；缺失时产出未签名 APK）：

```properties
storeFile=my-release.jks
storePassword=****
keyAlias=mykey
keyPassword=****
```

字体：界面使用 **SF Pro**，因授权限制不随仓库分发。构建前需把字体文件放到
`app/src/main/res/font/sf_pro.ttf`，或把 `ui/theme/Type.kt` 中的 `SFPro` 换成可自由分发的字体。

```bash
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包（未配置签名则不签名）
./gradlew :app:testDebugUnitTest  # 单元测试
```

Windows 使用 `gradlew.bat`。release APK 输出在 `app/build/outputs/apk/release/`。

## 特别鸣谢

本项目使用了以下开源项目，感谢作者们的贡献：

- [NeriPlayer](https://github.com/cwuom/NeriPlayer) —— 流动背景、封面取色、音频响应管线
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) —— 液态玻璃底栏与顶栏材质
- [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui) —— 歌词渲染组件
- [accompanist-lyrics-core](https://github.com/6xingyv/accompanist-lyrics-core) —— 歌词解析
- [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) —— 逐字歌词数据
- [Lyricon](https://github.com/tomakino/lyricon) —— 状态栏歌词
- [Material Symbols](https://github.com/google/material-design-icons) —— 图标
- [quickjs-kt](https://github.com/dokar3/quickjs-kt) —— 自定义音源 JS 引擎

## 许可证

GPL-3.0，全文见 [LICENSE](LICENSE)。
