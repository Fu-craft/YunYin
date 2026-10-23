package com.yunyin.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.local.LocalAlbum
import com.yunyin.music.data.local.LocalArtist
import com.yunyin.music.data.local.LocalGrouping
import com.yunyin.music.data.local.LocalMusicCollectionRules
import com.yunyin.music.data.local.LocalSong
import com.yunyin.music.data.local.LocalSort
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro
import java.io.File

/**
 * 本地音乐 library sheet.
 *
 * Browse modes (歌曲 / 专辑 / 艺人), an in-library search and a sort strip; every list is computed by the pure
 * [LocalMusicCollectionRules], so the same rules are exercised by unit tests. Rows drill into an album or an artist
 * rather than opening a route, and the system back gesture pops the drill before it closes the sheet.
 *
 * Skill-driven UI decisions: 44 dp segment/search/sort targets, a quiet track with one filled selected segment
 * (same idiom as 评论), cover-first rows with title + one muted metadata line (same rhythm as other track lists),
 * and explicit loading skeleton / empty / permission-error states so no state is a blank panel.
 */
@Composable
fun LocalMusicScreen(
    songs: List<LocalSong>,
    loading: Boolean,
    error: String?,
    skipped: Int,
    loader: ArtworkLoader,
    topInset: Dp = 0.dp,
    onPlayTracks: (List<LocalSong>, Int) -> Unit,
    onPlayAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var grouping by remember { mutableStateOf(LocalGrouping.Songs) }
    var sort by remember { mutableStateOf(LocalSort.Title) }
    var query by remember { mutableStateOf("") }
    var drill by remember { mutableStateOf<LocalDrill?>(null) }

    BackHandler(enabled = drill != null) { drill = null }

    val searched = remember(songs, query) { LocalMusicCollectionRules.search(songs, query) }
    val visibleSongs = remember(searched, sort) { LocalMusicCollectionRules.sortSongs(searched, sort) }
    val albums = remember(searched) { LocalMusicCollectionRules.albums(searched) }
    val artists = remember(searched) { LocalMusicCollectionRules.artists(searched) }

    Column(Modifier.fillMaxSize().padding(top = topInset).background(AppTheme.palette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (songs.isEmpty()) "" else "${songs.size} 首",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
                modifier = Modifier.weight(1f),
            )
            if (songs.isNotEmpty()) {
                PillButton(text = "播放全部", onClick = onPlayAll)
            }
        }

        if (songs.isNotEmpty() && error == null) {
            SearchField(query = query, onQueryChange = { query = it })
            Spacer(Modifier.height(10.dp))
            when (val target = drill) {
                null -> {
                    GroupingSegments(selected = grouping, onSelect = { grouping = it })
                    if (grouping == LocalGrouping.Songs) {
                        Spacer(Modifier.height(10.dp))
                        SortChips(selected = sort, onSelect = { sort = it })
                    }
                }
                is LocalDrill.Album -> DrillBack(title = target.album.name, onClick = { drill = null })
                is LocalDrill.Artist -> DrillBack(title = target.artist.name, onClick = { drill = null })
            }
            Spacer(Modifier.height(6.dp))
        }

        val phase = when {
            loading -> "loading"
            error != null -> "error"
            songs.isEmpty() -> "empty"
            else -> "list"
        }
        CrossfadeContent(
            targetState = phase,
            modifier = Modifier.fillMaxWidth().weight(1f),
            durationMillis = Motion.CrossfadeDuration,
            label = "local-music-phase",
        ) { current ->
            when (current) {
                "loading" -> LoadingSkeleton()
                "error" -> ScanError(error ?: "", onOpenSettings)
                "empty" -> EmptyState(
                    text = if (query.isBlank()) "设备上还没有本地音乐" else "没有匹配的本地歌曲",
                    icon = SfIcons.MusicNote,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    when (val target = drill) {
                        null -> when (grouping) {
                            LocalGrouping.Songs -> {
                                itemsIndexed(visibleSongs, key = { _, song -> "song-${song.id}" }) { index, song ->
                                    LocalSongRow(
                                        song = song,
                                        loader = loader,
                                        onClick = { onPlayTracks(visibleSongs, index) },
                                    )
                                }
                            }
                            LocalGrouping.Albums -> {
                                items(albums, key = { "album-${it.name}-${it.artist}" }) { album ->
                                    LocalAlbumRow(
                                        album = album,
                                        loader = loader,
                                        onClick = { drill = LocalDrill.Album(album) },
                                    )
                                }
                            }
                            LocalGrouping.Artists -> {
                                items(artists, key = { "artist-${it.name}" }) { artist ->
                                    LocalArtistRow(
                                        artist = artist,
                                        loader = loader,
                                        onClick = { drill = LocalDrill.Artist(artist) },
                                    )
                                }
                            }
                        }
                        is LocalDrill.Album -> {
                            itemsIndexed(target.album.songs, key = { _, song -> "d-${song.id}" }) { index, song ->
                                LocalSongRow(
                                    song = song,
                                    loader = loader,
                                    onClick = { onPlayTracks(target.album.songs, index) },
                                )
                            }
                        }
                        is LocalDrill.Artist -> {
                            target.artist.albums.forEach { album ->
                                item("a-header-${album.name}") { AlbumSectionHeader(album) }
                                itemsIndexed(album.songs, key = { _, song -> "a-${song.id}" }) { index, song ->
                                    LocalSongRow(
                                        song = song,
                                        loader = loader,
                                        onClick = { onPlayTracks(album.songs, index) },
                                    )
                                }
                            }
                        }
                    }
                    if (skipped > 0) {
                        item("skipped") {
                            Text(
                                text = "已跳过 $skipped 个无法读取的文件",
                                fontFamily = SFPro,
                                fontSize = 11.sp,
                                color = AppTheme.palette.tertiaryLabel,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    item("bottom") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

private sealed interface LocalDrill {
    data class Album(val album: LocalAlbum) : LocalDrill
    data class Artist(val artist: LocalArtist) : LocalDrill
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.control))
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = AppTheme.palette.accent)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.control))
            .background(AppTheme.palette.secondaryBackground)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SfIcons.MagnifyingGlass, contentDescription = null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索歌名、艺人或专辑", fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.tertiaryLabel)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.label),
                cursorBrush = SolidColor(AppTheme.palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(indication = null, interactionSource = null) { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(SfIcons.Xmark, contentDescription = "清空搜索", tint = AppTheme.palette.secondaryLabel, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun GroupingSegments(selected: LocalGrouping, onSelect: (LocalGrouping) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalGrouping.entries.forEach { option ->
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(if (option == selected) AppTheme.palette.tertiaryBackground else Color.Transparent)
                    .clickable(
                        indication = rememberControlRipple(bounded = true),
                        interactionSource = null,
                    ) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    fontFamily = SFPro,
                    fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 13.sp,
                    color = if (option == selected) AppTheme.palette.label else AppTheme.palette.secondaryLabel,
                )
            }
        }
    }
}

@Composable
private fun SortChips(selected: LocalSort, onSelect: (LocalSort) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LocalSort.entries.forEach { option ->
            Box(
                Modifier
                    .height(44.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(
                        if (option == selected) AppTheme.palette.accent.copy(alpha = 0.14f)
                        else AppTheme.palette.secondaryBackground,
                    )
                    .clickable(
                        indication = rememberControlRipple(bounded = true),
                        interactionSource = null,
                    ) { onSelect(option) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (option == selected) AppTheme.palette.accent else AppTheme.palette.secondaryLabel,
                )
            }
        }
    }
}

@Composable
private fun DrillBack(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(44.dp)
            .clickable(
                indication = rememberControlRipple(bounded = false),
                interactionSource = null,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SfIcons.ChevronLeft, contentDescription = "返回", tint = AppTheme.palette.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = AppTheme.palette.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LocalSongRow(song: LocalSong, loader: ArtworkLoader, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalArtwork(path = song.artPath, loader = loader, modifier = Modifier.size(46.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(
                    song.artist.ifBlank { LocalMusicCollectionRules.UNKNOWN_ARTIST },
                    song.album.ifBlank { LocalMusicCollectionRules.UNKNOWN_ALBUM },
                ).joinToString(" · "),
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(durationLabel(song.durationMs), fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.tertiaryLabel)
    }
}

@Composable
private fun LocalAlbumRow(album: LocalAlbum, loader: ArtworkLoader, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalArtwork(path = album.artPath, loader = loader, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                album.name,
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${album.artist} · ${album.songs.size} 首",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
            )
        }
        Icon(SfIcons.ChevronRight, contentDescription = null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LocalArtistRow(artist: LocalArtist, loader: ArtworkLoader, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalArtwork(path = artist.artPath, loader = loader, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artist.name,
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${artist.songCount} 首 · ${artist.albums.size} 张专辑",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
            )
        }
        Icon(SfIcons.ChevronRight, contentDescription = null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AlbumSectionHeader(album: LocalAlbum) {
    Text(
        text = album.name,
        fontFamily = SFPro,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = AppTheme.palette.secondaryLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun LocalArtwork(path: String?, loader: ArtworkLoader, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = path?.let { runCatching { loader.loadLocal(File(it), 200)?.asImageBitmap() }.getOrNull() }
    }
    val bitmap = image
    Box(
        modifier.clip(ContinuousRoundedRectangle(AppleShapes.control)).background(AppTheme.palette.secondaryBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(SfIcons.MusicNote, contentDescription = null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LoadingSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        repeat(6) {
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .background(AppTheme.palette.secondaryBackground),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .height(13.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.control))
                            .background(AppTheme.palette.secondaryBackground),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.42f)
                            .height(11.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.control))
                            .background(AppTheme.palette.secondaryBackground),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanError(message: String, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(message, fontFamily = SFPro, fontSize = 13.sp, lineHeight = 18.sp, color = AppTheme.palette.secondaryLabel)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .height(44.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.control))
                .clickable(
                    indication = rememberControlRipple(bounded = true),
                    interactionSource = null,
                    onClick = onOpenSettings,
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("打开系统设置授予音频读取权限", fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.accent)
        }
    }
}

private fun durationLabel(durationMs: Long): String {
    val seconds = (durationMs / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
