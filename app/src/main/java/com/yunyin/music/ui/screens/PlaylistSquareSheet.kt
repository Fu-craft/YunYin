package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.net.DiscoveryPlaylist
import com.yunyin.music.ui.PlaylistSquareUiState
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/** 歌单广场: category chips + a paginated list of playlist cards. */
@Composable
fun PlaylistSquareSheet(
    state: PlaylistSquareUiState,
    loader: ArtworkLoader,
    onDismiss: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onLoadMore: () -> Unit,
    onPlaylistClick: (DiscoveryPlaylist) -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        sheetModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.82f)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(Color(0xFF1C1C1E)),
    ) { close ->
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("歌单广场", fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text("收起", fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.accent,
                    modifier = Modifier.clickable { close {} })
            }
            Spacer(Modifier.height(8.dp))
            if (state.categoriesLoading) {
                CircularProgressIndicator(color = AppTheme.palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.categories, key = { it }) { category ->
                        val selected = category == state.selected
                        Text(
                            category,
                            fontFamily = SFPro,
                            fontSize = 13.sp,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                            modifier = Modifier
                                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                                .background(if (selected) AppTheme.palette.accent else AppTheme.palette.secondaryBackground)
                                .clickable { onSelectCategory(category) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                state.playlists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "暂无歌单", fontFamily = SFPro, fontSize = 13.sp,
                        color = if (state.error != null) AppTheme.palette.accent else Color.White.copy(alpha = 0.5f))
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPlaylistClick(playlist) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(url = playlist.coverUrl, loader = loader, corner = 10.dp, requestSize = 240,
                                modifier = Modifier.size(64.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, fontFamily = SFPro, fontSize = 15.sp, color = Color.White,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${playlist.trackCount} 首" + (playlist.description?.let { " · ${it.take(24)}" } ?: ""),
                                    fontFamily = SFPro, fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    item("footer") {
                        when {
                            state.loadingMore -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AppTheme.palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            }
                            state.more -> Box(
                                Modifier.fillMaxWidth().padding(8.dp)
                                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                                    .background(AppTheme.palette.secondaryBackground)
                                    .clickable(onClick = onLoadMore)
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("加载更多", fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.accent) }
                            state.error != null -> Text(state.error, fontFamily = SFPro, fontSize = 12.sp,
                                color = AppTheme.palette.accent, modifier = Modifier.fillMaxWidth().padding(10.dp))
                            else -> Text("没有更多歌单", fontFamily = SFPro, fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth().padding(10.dp))
                        }
                    }
                }
            }
        }
    }
}
