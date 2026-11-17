package com.yunyin.music.aura

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.yunyin.music.aura.*
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.itos.coui.search.CouiSearchBarDefaults
import com.itos.coui.search.SearchBar
import com.itos.coui.search.rememberCouiSearchBarState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Calendar
import java.util.UUID

@Composable
fun AuraTabIcon(index: Int, color: Color) {
    val icon = when (index) {
        0 -> appleMusicTabIcon(0)
        1 -> appleMusicTabIcon(1)
        else -> appleMusicTabIcon(3)
    }
    androidx.compose.foundation.Image(icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(color))
}

// Slightly tighter than the mini player's 13.dp artwork corners.
internal val ListArtworkCornerRadius = 11.dp

@Composable
fun Artwork(uri: String, modifier: Modifier = Modifier, circular: Boolean = false, cornerRadius: Dp = 16.dp) {
    val context = LocalContext.current
    val request = remember(uri, context) {
        if (uri.isBlank()) null
        else {
            val placeholderKey = findCachedArtworkMemoryEntry(SingletonImageLoader.get(context), uri)?.key
            ImageRequest.Builder(context)
                .data(uri)
                .apply { if (placeholderKey != null) placeholderMemoryCacheKey(placeholderKey) }
                .build()
        }
    }
    Box(modifier.clip(if (circular) CircleShape else RoundedCornerShape(cornerRadius))
        .background(Brush.linearGradient(listOf(MiuixTheme.colorScheme.primaryContainer, MiuixTheme.colorScheme.surfaceContainer))), contentAlignment = Alignment.Center) {
        Text("♫", fontSize = 28.sp, color = MiuixTheme.colorScheme.primary.copy(alpha = .7f))
        if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun durationText(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"

@Composable
fun SongRow(song: Song, model: AuraViewModel, onClick: () -> Unit, onLongAction: (() -> Unit)? = null) {
    val layout = LocalAuraLayout.current
    val artworkSize = if (layout.tablet) 48.dp else 44.dp
    val openActions = LocalSongActions.current
    val backdrop = LocalToolbarBackdrop.current
    val isCurrent = model.current?.key == song.key
    when (LocalAppUiStyle.current) {
        AppUiStyle.ONE -> {
            val palette = rememberActiveOneUiPalette()
            val rowBg = if (isCurrent) Color(palette.chip).copy(alpha = 0.72f) else Color.Transparent
            val titleColor = if (isCurrent) Color(palette.accentDeep) else Color(palette.text)
            val subColor = Color(palette.subText)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(rowBg)
                    .clickable(onClick = onClick)
                    .padding(vertical = 9.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(song.artwork, Modifier.size(artworkSize), cornerRadius = ListArtworkCornerRadius)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        song.title.ifBlank { tr("unknown_title", "未命名歌曲") },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.5.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = titleColor,
                    )
                    Text(
                        "${song.artist} · ${song.album}",
                        fontSize = 13.sp,
                        color = subColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(durationText(song.duration), fontSize = 12.sp, color = subColor)
                    IconButton(onClick = { openActions(SongActionRequest(song, backdrop, onLongAction)) }, modifier = Modifier.size(40.dp)) {
                        Image(AuraMoreHorizontalIcon, tr("more", "更多"), Modifier.size(20.dp), colorFilter = ColorFilter.tint(if (isCurrent) Color(palette.accentDeep) else subColor))
                    }
                }
            }
            return
        }
        AppUiStyle.MATERIAL -> {
            val scheme = rememberActiveExpressiveScheme()
            val rowBg = if (isCurrent) scheme.secondaryContainer else Color.Transparent
            val titleColor = if (isCurrent) scheme.onSecondaryContainer else scheme.onSurface
            val subColor = if (isCurrent) scheme.onSecondaryContainer.copy(alpha = 0.78f) else scheme.onSurfaceVariant
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(rowBg)
                    .clickable(onClick = onClick)
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(song.artwork, Modifier.size(artworkSize), cornerRadius = ListArtworkCornerRadius)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        song.title.ifBlank { tr("unknown_title", "未命名歌曲") },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.5.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = titleColor,
                    )
                    Text(
                        "${song.artist} · ${song.album}",
                        fontSize = 13.sp,
                        color = subColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(durationText(song.duration), fontSize = 12.sp, color = subColor)
                    IconButton(onClick = { openActions(SongActionRequest(song, backdrop, onLongAction)) }, modifier = Modifier.size(40.dp)) {
                        Image(AuraMoreHorizontalIcon, tr("more", "更多"), Modifier.size(20.dp), colorFilter = ColorFilter.tint(if (isCurrent) scheme.onSecondaryContainer else scheme.onSurfaceVariant))
                    }
                }
            }
            return
        }
        AppUiStyle.HYPER -> Unit
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 7.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.artwork, Modifier.size(artworkSize), cornerRadius = ListArtworkCornerRadius)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(song.title.ifBlank { tr("unknown_title", "未命名歌曲") }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
            Text("${song.artist} · ${song.album}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(durationText(song.duration), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            IconButton(onClick = { openActions(SongActionRequest(song, backdrop, onLongAction)) }, modifier = Modifier.size(44.dp)) {
                Image(AuraMoreHorizontalIcon, tr("more", "更多"), Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) { Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold) }

private fun LazyListScope.songRows(tracks: List<Song>, model: AuraViewModel, columns: Int,
                                   onRemove: ((Song) -> Unit)? = null,
                                   onPlay: ((Int) -> Unit)? = null) {
    items(tracks.chunked(columns), key = { it.first().key }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { song ->
                Box(Modifier.weight(1f)) {
                    SongRow(song, model, { onPlay?.invoke(tracks.indexOf(song)) ?: model.play(tracks, tracks.indexOf(song)) }, onRemove?.let { { it(song) } })
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun PermissionCard(onClick: () -> Unit) {
    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
        Text(tr("permission_title", "让 Aura 找到你的音乐"), fontWeight = FontWeight.Bold)
        Text(tr("permission_summary", "授权读取音频后，歌曲、专辑和艺术家会自动出现在这里"), style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(vertical = 10.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        GlassDialogButton(onClick, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("grant_audio", "授权读取音乐")) }
    }
}

@Composable
fun SongsPage(model: AuraViewModel, padding: PaddingValues, requestPermission: () -> Unit,
              onOpenSecondary: (Int) -> Unit = {}) {
    val songs = model.sortedSongs
    val columns = LocalAuraLayout.current.listColumns
    LazyColumn(pageContentModifier(), contentPadding = padding) {
        item("song_categories") {
            if (LocalAppUiStyle.current == AppUiStyle.ONE) {
                val palette = rememberActiveOneUiPalette()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val items = listOf(
                        tr("albums", "专辑") to 0,
                        tr("artists", "艺术家") to 1,
                        tr("playback_lists", "播放列表") to 2,
                    )
                    items.forEach { (label, target) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(palette.card))
                                .clickable { onOpenSecondary(target) }
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = Color(palette.accentDeep),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
                val scheme = rememberActiveExpressiveScheme()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val items = listOf(
                        tr("albums", "专辑") to 0,
                        tr("artists", "艺术家") to 1,
                        tr("playback_lists", "播放列表") to 2,
                    )
                    items.forEach { (label, target) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(scheme.secondaryContainer)
                                .clickable { onOpenSecondary(target) }
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = scheme.onSecondaryContainer,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Column {
                        Preference(tr("albums", "专辑"), onClick = { onOpenSecondary(0) })
                        Preference(tr("artists", "艺术家"), onClick = { onOpenSecondary(1) })
                        Preference(tr("playback_lists", "播放列表"), onClick = { onOpenSecondary(2) })
                    }
                }
            }
        }
        if (!model.hasPermission) item { PermissionCard(requestPermission) }
        else if (model.songs.isEmpty()) item { EmptyState(tr("no_songs", "没有找到歌曲"), tr("check_filters", "请检查音乐权限、文件夹与筛选设置"), tr("rescan", "重新扫描"), { model.refreshLibrary() }) }
        songRows(songs, model, columns)
    }
}

@Composable
internal fun DownloadedMusicPage(model: AuraViewModel, padding: PaddingValues) {
    val songs = model.downloadedSongs
    val columns = LocalAuraLayout.current.listColumns
    LaunchedEffect(Unit) { model.refreshDownloadedSongs() }
    LazyColumn(pageContentModifier(), contentPadding = padding) {
        if (songs.isEmpty()) item { EmptyState(tr("no_downloaded_music", "暂无已下载的音乐"), tr("downloaded_music_hint", "下载完成的网易云音乐会出现在这里")) }
        songRows(songs, model, columns)
    }
}

@Composable
fun CollectionTile(title: String, subtitle: String, art: String, modifier: Modifier = Modifier, circular: Boolean = false, foreground: Color = MiuixTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(2.dp)) {
        Artwork(art, Modifier.fillMaxWidth().aspectRatio(1f), circular)
        Text(title, color = foreground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = foreground.copy(alpha = .66f), modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun AlbumsArtistsPage(model: AuraViewModel, padding: PaddingValues, tab: Int) {
    val collections = remember(model.songs, tab) {
        if (tab == 0) model.songs.groupBy { "${it.volume}:${it.albumId}:${it.album}" }.map { (key, tracks) ->
            MusicCollection("album:$key", tracks.first().album, tracks.first().artist, tracks.sortedBy { it.track }, tracks.first().artwork)
        }.sortedBy { it.title }
        else model.songs.groupBy { "${it.volume}:${it.artistId}:${it.artist}" }.map { (key, tracks) ->
            MusicCollection("artist:$key", tracks.first().artist, "${tracks.size} ${tr("song_count", "首歌曲")}", tracks, tracks.first().artwork)
        }.sortedBy { it.title }
    }
    val layout = LocalAuraLayout.current
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (collections.isEmpty()) item { EmptyState(tr("no_collections", "暂无内容"), tr("library_content_hint", "本地音乐会自动归类到这里")) }
        items(collections.chunked(layout.gridColumns)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (layout.tablet) 12.dp else 14.dp)) {
                pair.forEach { collection -> CollectionTile(collection.title, collection.subtitle, collection.artwork, Modifier.weight(1f), tab == 1) { model.openCollection(collection) } }
                repeat(layout.gridColumns - pair.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal fun filterPlaylistSongs(songs: List<Song>, query: String): List<Song> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return songs
    return songs.filter { song ->
        terms.all { term ->
            song.title.contains(term, ignoreCase = true) ||
                song.artist.contains(term, ignoreCase = true) ||
                song.album.contains(term, ignoreCase = true) ||
                song.albumArtist.contains(term, ignoreCase = true)
        }
    }
}

internal fun shouldAutoLoadRemainingPlaylistTracks(
    searchActive: Boolean,
    hasMore: Boolean,
    loading: Boolean,
    failed: Boolean,
    accountRequiredMissing: Boolean = false,
): Boolean = searchActive && hasMore && !loading && !failed && !accountRequiredMissing

@Composable
internal fun PlaylistHeaderSearchBar(
    query: String,
    expanded: Boolean,
    onQueryChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    placeholder: String = tr("playlist_search_hint", "搜索歌单内歌曲、歌手或专辑"),
) {
    if (LocalAppUiStyle.current == AppUiStyle.ONE) {
        val palette = rememberActiveOneUiPalette()
        val focusManager = LocalFocusManager.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAuraLayout.current.pageMargin)
                .padding(top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(Color(palette.cardAlt), CircleShape)
                    .clickable { if (!expanded) onExpandedChange(true) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MiuixIcons.Regular.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color(palette.subText),
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = {
                        if (!expanded) onExpandedChange(true)
                        onQueryChange(it)
                    },
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.body1.copy(color = Color(palette.text)),
                    cursorBrush = SolidColor(Color(palette.accentDeep)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearch()
                    }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    placeholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MiuixTheme.textStyles.body1,
                                    color = Color(palette.subText),
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(MiuixIcons.Regular.Close, tr("clear", "清空"), Modifier.size(18.dp), tint = Color(palette.subText))
                    }
                }
            }
            if (expanded || query.isNotEmpty()) {
                OneUiButton(
                    onClick = {
                        focusManager.clearFocus()
                        onQueryChange("")
                        onExpandedChange(false)
                    },
                ) {
                    Text(tr("cancel", "取消"), fontSize = 13.5.sp)
                }
            }
        }
        return
    }
    if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        val focusManager = LocalFocusManager.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAuraLayout.current.pageMargin)
                .padding(top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHigh, CircleShape)
                    .clickable { if (!expanded) onExpandedChange(true) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MiuixIcons.Regular.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = {
                        if (!expanded) onExpandedChange(true)
                        onQueryChange(it)
                    },
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.body1.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearch()
                    }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    placeholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MiuixTheme.textStyles.body1,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(MiuixIcons.Regular.Close, tr("clear", "清空"), Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
            if (expanded || query.isNotEmpty()) {
                MaterialExpressiveButton(
                    onClick = {
                        focusManager.clearFocus()
                        onQueryChange("")
                        onExpandedChange(false)
                    },
                ) {
                    Text(tr("cancel", "取消"), fontSize = 13.5.sp)
                }
            }
        }
        return
    }
    val backdrop = LocalAuraCouiBackdrop.current ?: return
    val searchState = rememberCouiSearchBarState(expanded)
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val colors = CouiSearchBarDefaults.colors(dark).copy(
        foreground = scheme.onSurface,
        secondary = scheme.onSurfaceVariantSummary,
        fallbackBackground = scheme.surfaceContainerHigh,
    )
    SearchBar(
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        state = searchState,
        query = query,
        onQueryChange = onQueryChange,
        onExpandedChange = onExpandedChange,
        placeholder = placeholder,
        cancelText = tr("cancel", "取消"),
        clearDescription = tr("clear", "清空"),
        dark = dark,
        backdrop = backdrop,
        onSearch = onSearch,
        shadowProgress = LocalToolbarCollapsed.current,
        colors = colors,
    )
}

@Composable
fun CollectionPage(
    model: AuraViewModel,
    padding: PaddingValues,
    collection: MusicCollection,
    cloud: CloudPlaylistContent?,
    searchQuery: String = "",
    searchExpanded: Boolean = false,
) {
    var addToPlaylist by remember { mutableStateOf(false) }
    val revision = model.history.revision
    val localPlaylist = remember(collection.key, revision) { model.history.playlists().find { "playlist:${it.id}" == collection.key } }
    val visible = remember(model.songs) { model.songs.map { it.key }.toSet() }
    val tracks = if (localPlaylist != null) model.songs.filter { it.key in localPlaylist.songKeys }
        else cloud?.songs ?: collection.songs.filter { it.cloud || it.key in visible }
    val filtering = searchQuery.isNotBlank()
    val searchActive = filtering || searchExpanded
    LaunchedEffect(cloud, searchActive, cloud?.songs?.size, cloud?.loading, cloud?.hasMore, cloud?.failed, model.account != null) {
        if (cloud != null && shouldAutoLoadRemainingPlaylistTracks(
                searchActive = searchActive,
                hasMore = cloud.hasMore,
                loading = cloud.loading,
                failed = cloud.failed,
                accountRequiredMissing = cloud.source == "playlist" && model.account == null,
            )
        ) {
            model.loadMoreCloudSongsFor(cloud)
        }
    }
    val filteredTracks = remember(tracks, searchQuery) { filterPlaylistSongs(tracks, searchQuery) }
    val listeningRank = cloud?.source == "rank"
    val rankByKey = remember(tracks, listeningRank) {
        if (listeningRank) tracks.mapIndexed { index, song -> song.key to (index + 1) }.toMap() else emptyMap()
    }
    val columns = LocalAuraLayout.current.listColumns
    val listState = rememberLazyListState()
    LaunchedEffect(searchQuery) { listState.scrollToItem(0) }
    LazyColumn(pageContentModifier().imePadding(), state = listState, contentPadding = padding) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(collection.artwork.ifBlank { tracks.firstOrNull()?.artwork.orEmpty() }, Modifier.size(90.dp))
                Column(Modifier.padding(start = 18.dp)) {
                    val countText = if (filtering) "${filteredTracks.size} / ${cloud?.total ?: tracks.size} ${tr("song_count", "首歌曲")}"
                        else "${cloud?.total ?: tracks.size} ${tr("song_count", "首歌曲")}"
                    Text(countText, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        GlassDialogButton(
                            { model.playCollection(filteredTracks, content = if (filtering) null else cloud) },
                            enabled = filteredTracks.isNotEmpty(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) { Text(tr("play_all", "播放全部")) }
                        if (localPlaylist != null) GlassDialogButton({ addToPlaylist = true }) { Text(tr("add", "添加")) }
                        if (cloud != null) GlassDialogButton(model::refreshCloudSongs, enabled = !cloud.loading) { Text(tr("refresh", "刷新")) }
                    }
                }
            }
        }
        if (filteredTracks.isEmpty() && (cloud == null || !cloud.loading && !cloud.failed && !cloud.hasMore)) item { EmptyState(tr("no_songs", "没有找到歌曲")) }
        if (listeningRank) {
            itemsIndexed(filteredTracks, key = { _, song -> "rank-${song.key}" }) { index, song ->
                val rank = rankByKey[song.key] ?: (index + 1)
                RankSongRow(rank, song, model) { model.playCollection(filteredTracks, index, if (filtering) null else cloud) }
            }
        } else songRows(filteredTracks, model, columns, onRemove = localPlaylist?.let { playlist -> { song ->
                model.history.savePlaylists(model.history.playlists().map { if (it.id == playlist.id) it.copy(songKeys = it.songKeys - song.key) else it })
        } }, onPlay = { model.playCollection(filteredTracks, it, if (filtering) null else cloud) })
        if (cloud != null && (cloud.hasMore || cloud.loading || cloud.failed)) item("cloud_song_paging") {
            val detail = if (searchActive && cloud.total > 0) "(${cloud.songs.size}/${cloud.total})" else ""
            CloudPagingFooter(cloud.loading, cloud.failed, detail) { model.loadMoreCloudSongsFor(cloud) }
        }
    }
    if (addToPlaylist && localPlaylist != null) PlaylistSongPicker(model, localPlaylist) { addToPlaylist = false }
}

@Composable
private fun RankSongRow(rank: Int, song: Song, model: AuraViewModel, onClick: () -> Unit) {
    val openActions = LocalSongActions.current
    val backdrop = LocalToolbarBackdrop.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 7.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString(), Modifier.width(30.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontWeight = FontWeight.Bold)
        Artwork(song.artwork, Modifier.size(44.dp), cornerRadius = ListArtworkCornerRadius)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(song.title.ifBlank { tr("unknown_title", "未命名歌曲") }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                fontWeight = if (model.current?.key == song.key) FontWeight.Bold else FontWeight.Medium)
            Text("${song.artist} · ${song.album}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Text("${song.playCount} ${tr("play_count", "次")}", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        IconButton(onClick = { openActions(SongActionRequest(song, backdrop)) }, modifier = Modifier.size(44.dp)) {
            Image(AuraMoreHorizontalIcon, tr("more", "更多"), Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
        }
    }
}

@Composable
fun LocalPlaylistsPage(model: AuraViewModel, padding: PaddingValues) {
    val revision = model.history.revision
    val playlists = remember(revision) { model.history.playlists() }
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<LocalPlaylist?>(null) }
    var deletion by remember { mutableStateOf<LocalPlaylist?>(null) }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        run {
            items(playlists, key = { it.id }) { playlist ->
                val tracks = model.songs.filter { it.key in playlist.songKeys }
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    Row(Modifier.fillMaxWidth().clickable { model.openCollection(MusicCollection("playlist:${playlist.id}", playlist.name, "", tracks)) }
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(tracks.firstOrNull()?.artwork.orEmpty(), Modifier.size(50.dp), cornerRadius = ListArtworkCornerRadius)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(playlist.name, fontWeight = FontWeight.SemiBold)
                            Text("${tracks.size} ${tr("song_count", "首歌曲")}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        GlassIconDropdownMenu(DropdownEntry(listOf(
                            DropdownItem(tr("add_songs", "添加歌曲"), onClick = { editing = playlist }),
                            DropdownItem(tr("delete", "删除"), onClick = { deletion = playlist }),
                        )), LocalToolbarBackdrop.current) { Text("···", fontSize = 24.sp) }
                    }
                }
            }
            item("create_playlist") {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Preference(tr("new_playlist", "新建歌单")) { creating = true; name = "" }
                }
            }
        }
    }
    WindowDialog(creating, title = tr("new_playlist", "新建歌单"), onDismissRequest = { creating = false }) {
        AuraTextField(name, { name = it }, label = tr("playlist_name", "歌单名称"), singleLine = true, modifier = Modifier.fillMaxWidth())
        GlassDialogButton({
            model.history.savePlaylists(playlists + LocalPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList()))
            creating = false
        }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("create", "创建")) }
    }
    WindowDialog(deletion != null, title = tr("delete_playlist", "删除歌单？"), summary = tr("delete_playlist_summary", "只会删除歌单，不会删除音乐文件"), onDismissRequest = { deletion = null }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ deletion = null }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ model.history.savePlaylists(playlists.filterNot { it.id == deletion?.id }); deletion = null }, Modifier.weight(1f)) { Text(tr("delete", "删除")) }
        }
    }
    editing?.let { PlaylistSongPicker(model, it) { editing = null } }
}

@Composable
internal fun CloudPagingFooter(loading: Boolean, failed: Boolean, load: () -> Unit) =
    CloudPagingFooter(loading, failed, "", load)

@Composable
internal fun CloudPagingFooter(loading: Boolean, failed: Boolean, detail: String, load: () -> Unit) {
    if (!loading && !failed) LaunchedEffect(Unit) { load() }
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        if (failed) GlassDialogButton(load) { Text(tr("cloud_load_retry", "加载失败，点击重试")) }
        else Text(
            if (detail.isBlank()) tr("loading", "正在加载…") else "${tr("loading", "正在加载…")} $detail",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun PlaylistSongPicker(model: AuraViewModel, playlist: LocalPlaylist, dismiss: () -> Unit) {
    var selected by remember(playlist.id) { mutableStateOf(playlist.songKeys.toSet()) }
    WindowDialog(true, title = tr("add_songs", "添加歌曲"), onDismissRequest = dismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(model.songs, key = { it.key }) { song ->
                BasicComponent(title = song.title, summary = song.artist, onClick = { selected = if (song.key in selected) selected - song.key else selected + song.key },
                    endActions = { AuraCheckbox(checked = song.key in selected, onCheckedChange = { selected = if (song.key in selected) selected - song.key else selected + song.key }) })
            }
        }
        GlassDialogButton({
            model.history.savePlaylists(model.history.playlists().map { if (it.id == playlist.id) it.copy(songKeys = selected.toList()) else it })
            dismiss()
        }, Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
    }
}
