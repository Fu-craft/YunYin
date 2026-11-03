# 桌面小组件实施计划

目标：主屏小组件显示当前曲目并提供播放/暂停/上一首/下一首，三档尺寸（4×2 / 2×2 / 4×1）。
选型：**RemoteViews + XML 布局**（不引入 Glance），理由：本工程已用自定义 View 与 Compose 混排，Glance 会额外引入一套渲染模型与新依赖；RemoteViews 只需 XML + Provider，样式完全可控。

## 文件清单（新建 6 个，改 2 个）

```
app/src/main/java/com/yunyin/music/widget/
  NowPlayingWidgetProvider.kt      AppWidgetProvider，处理按钮广播 + onUpdate
  WidgetUpdateScheduler.kt         节流更新（500ms），状态变化时调用
  WidgetState.kt                   从 PlayerController.state 提取的轻量快照
app/src/main/res/layout/
  widget_now_playing_4x2.xml
  widget_now_playing_2x2.xml
  widget_now_playing_4x1.xml
app/src/main/res/xml/
  widget_now_playing_info.xml      三档尺寸 + 预览图 + 更新周期 none
app/src/main/res/drawable/
  widget_bg.xml                    圆角 22dp 背景（shape）
改：
  AndroidManifest.xml              注册 receiver + meta-data
  MainActivity.kt                  播放状态变化时调用 WidgetUpdateScheduler.request()
```

## 1. WidgetState

```kotlin
data class WidgetState(
    val title: String,          // 空 = 未播放
    val artist: String,
    val coverUrl: String?,
    val playing: Boolean,
    val progressPct: Int,       // 0..100
    val hasTrack: Boolean,
)
```

从 `container.player.state.value` 映射；`title` 为空时显示「未在播放」，封面用 `SfIcons.MusicNote` 的矢量 drawable 占位。

## 2. 布局（4×2，主推）

```xml
<LinearLayout android:orientation="horizontal" ...背景=widget_bg, padding=14dp>
  <ImageView id=cover 56dp × 56dp/>
  <LinearLayout orientation="vertical" weight=1 paddingStart=12dp>
    <TextView id=title 16sp bold maxLines=1 ellipsize=end/>
    <TextView id=artist 12.5sp 次色 maxLines=1/>
    <ProgressBar id=progress style=horizontal height=2dp/>
  </LinearLayout>
  <LinearLayout orientation="horizontal">
    <ImageButton id=prev 44dp/>
    <ImageButton id=play 44dp/>
    <ImageButton id=next 44dp/>
  </LinearLayout>
</LinearLayout>
```

2×2：封面铺满 + 底部 40dp 渐变（`widget_scrim.xml`）+ 标题一行 + 中央播放键。
4×1：封面 40dp + 两行文字 + 播放键，无进度条。

## 3. Provider 要点

- `onUpdate`：读 `WidgetState` → 组装 RemoteViews → `setImageViewBitmap`（封面用 coil 同步取图或读缓存，失败则用占位 drawable）；
- 按钮：`PendingIntent.getBroadcast`，action 分别为 `ACTION_TOGGLE / ACTION_NEXT / ACTION_PREV`，`FLAG_IMMUTABLE`；
- 点封面/文字：启动 MainActivity（`showPlayer = true` 的 extra）；
- `onEnabled` 注册、`onDisabled` 反注册更新监听。

## 4. 更新节流（必须做，否则耗电）

```kotlin
object WidgetUpdateScheduler {
    private var last = 0L
    fun request(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - last < 500) return
        last = now
        // AppWidgetManager.updateAppWidget(...)
    }
}
```

- 播放状态变化 → `request()`（500ms 节流）；
- **进度条不逐帧刷新**：只在暂停/切歌/seek 后更新，或 `setProgressBar(..., false)` 交给系统；
- `updatePeriodMillis = 0`（不周期刷新）。

## 5. Manifest

```xml
<receiver android:name=".widget.NowPlayingWidgetProvider" android:exported="true">
  <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter>
  <meta-data android:name="android.appwidget.provider"
             android:resource="@xml/widget_now_playing_info"/>
</receiver>
```

## 6. 验收

1. 编译 + 742 单测绿；
2. 装到真机 → 长按桌面 → 小组件列表出现「云音」→ 三个尺寸都能添加；
3. 播放一首歌：标题/歌手/进度/按钮状态正确；点暂停、下一首生效；
4. 暂停 1 分钟观察耗电（`adb shell dumpsys batterystats`）不得有持续唤醒；
5. 报告：文件清单、版本号、sha256、APK 路径。
