package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.SheetPlacement
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro

/**
 * 本地音乐 list.
 *
 * A sheet rather than a full page: local music is a library sub-collection, and the sheet idiom keeps every state
 * explicit (scanning / permission denied / error / empty / list) without adding a route. Rows reuse the collection
 * track row so the list is visually the same as every other track list.
 */
@Composable
fun LocalMusicSheet(
    tracks: List<Track>,
    loading: Boolean,
    error: String?,
    loader: ArtworkLoader,
    onPlayTrack: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Bottom,
        sheetModifier = Modifier
            .fillMaxWidth()
            .fillMaxSize(0.92f)
            .navigationBarsPadding()
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background),
    ) { close ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("本地音乐", fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppTheme.palette.label)
                if (tracks.isNotEmpty()) {
                    Text("${tracks.size} 首", fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
                }
            }
            if (tracks.isNotEmpty()) {
                Text(
                    "播放全部",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .clickable { close { onPlayAll() } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        // Spinner → permission error / empty / list, cross-faded in place; the sheet keeps its size.
        val phase = when {
            loading -> "loading"
            error != null -> "error"
            tracks.isEmpty() -> "empty"
            else -> "list"
        }
        CrossfadeContent(
            targetState = phase,
            modifier = Modifier.fillMaxWidth().weight(1f),
            durationMillis = Motion.SmallDuration,
            label = "local-music-phase",
        ) { current ->
            when (current) {
                "loading" -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                }
                "error" -> Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(error ?: "", fontFamily = SFPro, fontSize = 13.sp, lineHeight = 18.sp, color = AppTheme.palette.secondaryLabel)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "打开系统设置授予音频读取权限",
                        fontFamily = SFPro,
                        fontSize = 14.sp,
                        color = AppTheme.palette.accent,
                        modifier = Modifier
                            .clip(ContinuousRoundedRectangle(AppleShapes.control))
                            .clickable { close { onOpenSettings() } }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                "empty" -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("设备上还没有本地音乐", fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.secondaryLabel)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(tracks) { index, track ->
                        CollectionTrackRow(
                            index = index,
                            track = track,
                            loader = loader,
                            isCurrent = false,
                            isPlaying = false,
                            onClick = { close { onPlayTrack(index) } },
                        )
                    }
                }
            }
        }
    }
}
