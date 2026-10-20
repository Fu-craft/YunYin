package com.yunyin.music.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.AlbumInfo
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.ArtistUiState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.DefaultTopBarTintIntensity
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.glassTopBarTopPadding
import com.yunyin.music.ui.components.liquidGlassControl
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro


/**
 * 歌手详情：header + 热门歌曲 / 全部歌曲（分页）/ 专辑（横向）。
 *
 * One vertical LazyColumn owns the whole page (the album rail is a horizontal LazyRow inside one item), so there is
 * no vertical-nested-scroller. Each section carries its own loading/error/empty state: an album failure leaves the
 * header and songs untouched. The previous page only had `/artist/top/song`, which is exactly the "只能显示热门歌曲"
 * report.
 */
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
    onShufflePlay: () -> Unit = {},
    onAllTrackClick: (Track, Int) -> Unit = { _, _ -> },
    onLoadMoreSongs: () -> Unit = {},
    onLoadMoreAlbums: () -> Unit = {},
    onAlbumClick: (AlbumInfo) -> Unit = {},
    nowPlayingId: Long? = null,
    isPlaying: Boolean = false,
) {
    val palette = AppTheme.palette
    var bioExpanded by remember(state.briefDesc) { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // The frosted top bar's pixel source: the page content recorded with the page background painted
    // in first (`rememberGlassTopBarBackdrop`), which is the arrangement that makes the strip render
    // at all. The bar itself is composed outside this layer below, so it samples the header and rows
    // scrolling under it rather than sampling itself.
    val topBarBackdrop = rememberGlassTopBarBackdrop()

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // The strip floats over the content: the first row starts below it and scrolls under it.
                contentPadding = PaddingValues(top = glassTopBarTopPadding(), bottom = 32.dp),
            ) {
            item("header") {
                ArtistHeaderBlock(
                    state = state,
                    loader = loader,
                    bioExpanded = bioExpanded,
                    onToggleBio = { bioExpanded = !bioExpanded },
                    onPlayAll = onPlayAll,
                )
            }

            item("hot-title") { SectionTitle("热门歌曲") }
            if (state.hotSongs.isEmpty()) {
                item("hot-state") {
                    // Spinner → empty is a content-state change: cross-fade it instead of swapping a
                    // frame. (The loaded list stays lazy — rows fade in via `appendedRowAppear`.)
                    CrossfadeContent(
                        targetState = if (state.loading) "loading" else "empty",
                        durationMillis = Motion.CrossfadeDuration,
                        label = "hot-state",
                    ) { current ->
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            if (current == "loading") {
                                CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                            } else {
                                Text("暂无热门歌曲", fontFamily = SFPro, fontSize = 13.sp, color = palette.secondaryLabel)
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(state.hotSongs, key = { index, track -> "hot-${track.id}#$index" }) { index, track ->
                    CollectionTrackRow(
                        index = index,
                        track = track,
                        loader = loader,
                        isCurrent = track.id == nowPlayingId,
                        isPlaying = isPlaying && track.id == nowPlayingId,
                        onClick = { onTrackClick(track, index) },
                        modifier = appendedRowAppear(),
                    )
                }
            }

            item("all-title") { SectionTitle("全部歌曲") }
            if (state.songsError != null || state.allSongs.isEmpty()) {
                item("all-state") {
                    // Data-carrying target: an error message is distinct from "loading"/"empty", so
                    // the cross-fade is real rather than both branches rendering the same snapshot.
                    val phase: Any = state.songsError ?: if (state.loading) "loading" else "empty"
                    CrossfadeContent(
                        targetState = phase,
                        durationMillis = Motion.CrossfadeDuration,
                        label = "all-state",
                    ) { current ->
                        when (current) {
                            "loading" -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                            }
                            "empty" -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("暂无歌曲", fontFamily = SFPro, fontSize = 13.sp, color = palette.secondaryLabel)
                            }
                            else -> SectionMessage(current as String, isError = true)
                        }
                    }
                }
            } else {
                itemsIndexed(state.allSongs, key = { index, track -> "all-${track.id}#$index" }) { index, track ->
                    CollectionTrackRow(
                        index = index,
                        track = track,
                        loader = loader,
                        isCurrent = track.id == nowPlayingId,
                        isPlaying = isPlaying && track.id == nowPlayingId,
                        onClick = { onAllTrackClick(track, index) },
                        modifier = appendedRowAppear(),
                    )
                }
                item("all-footer") {
                    // The footer swaps kind (spinner / 加载更多 / end) as pages arrive; cross-fade it so
                    // the row under the thumb never changes in one frame.
                    val footerPhase: Any = when {
                        state.loadingSongs -> "loading"
                        state.allMore -> "more"
                        else -> "end"
                    }
                    CrossfadeContent(
                        targetState = footerPhase,
                        durationMillis = Motion.CrossfadeDuration,
                        label = "all-footer",
                    ) { current ->
                        when (current) {
                            "loading" -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                            }
                            "more" -> LoadMoreRow("加载更多歌曲", onLoadMoreSongs)
                            else -> EndRow("已显示全部歌曲")
                        }
                    }
                }
            }

            item("albums-title") { SectionTitle("专辑") }
            if (state.albumsError != null || state.albums.isEmpty()) {
                item("albums-state") {
                    val phase: Any = state.albumsError ?: if (state.loading) "loading" else "empty"
                    CrossfadeContent(
                        targetState = phase,
                        durationMillis = Motion.CrossfadeDuration,
                        label = "albums-state",
                    ) { current ->
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            when (current) {
                                "loading" -> CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                                "empty" -> Text("暂无专辑", fontFamily = SFPro, fontSize = 13.sp, color = palette.secondaryLabel)
                                else -> SectionMessage(current as String, isError = true)
                            }
                        }
                    }
                }
            } else {
                item("albums-rail") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.albums, key = { it.id }) { album ->
                            AlbumCard(
                                album,
                                loader,
                                onClick = { onAlbumClick(album) },
                                modifier = appendedRowAppear(),
                            )
                        }
                    }
                }
                item("albums-footer") {
                    val footerPhase: Any = when {
                        state.loadingAlbums -> "loading"
                        state.albumsMore -> "more"
                        else -> "end"
                    }
                    CrossfadeContent(
                        targetState = footerPhase,
                        durationMillis = Motion.CrossfadeDuration,
                        label = "albums-footer",
                    ) { current ->
                        when (current) {
                            "loading" -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                            }
                            "more" -> LoadMoreRow("加载更多专辑", onLoadMoreAlbums)
                            else -> Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            item("bottom") { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
            }
        }

        // Pinned last, outside the recorded layer, so it draws over the scrolling header and samples
        // it instead of sampling itself — the collection page's arrangement, reused verbatim.
        ArtistTopBar(
            backdrop = topBarBackdrop,
            onBack = onBack,
            onShuffle = onShufflePlay,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * Rows appended by 加载更多 fade in like Home's shelves (`HomeScreen.shelfAppear`). Placement is off on
 * purpose: content arriving must not slide around under the user's finger.
 */
private fun LazyItemScope.appendedRowAppear(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(Motion.ItemAppearDuration),
    fadeOutSpec = null,
    placementSpec = null,
)

/**
 * The artist page's pinned bar: the app's progressive-blur strip with the page's controls as liquid
 * glass, matching the collection page.
 *
 * Deliberately **icon-only**: the artist's name is already the page's large header, and a second copy
 * on the blur strip had to fight the background for legibility (the user objected to it twice). Back
 * disc on the leading edge, shuffle disc on the trailing edge, nothing in between.
 *
 * No scroll state is read here any more: it existed only for the removed bar title.
 */
@Composable
private fun ArtistTopBar(
    backdrop: Backdrop,
    onBack: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassTopBar(
        backdrop = backdrop,
        tintIntensity = DefaultTopBarTintIntensity,
        modifier = modifier,
    ) {
        // Two symmetric controls and nothing between them. The full-width strip puts them at the
        // leading/trailing edges, so the bar reads as back + shuffle rather than as a title with
        // buttons crowded around it.
        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            ArtistBarButton(
                icon = SfIcons.ChevronDown,
                description = "返回",
                backdrop = backdrop,
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            ArtistBarButton(
                icon = SfIcons.Shuffle,
                description = "随机播放",
                backdrop = backdrop,
                onClick = onShuffle,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * A control on the pinned bar: a 44dp liquid-glass disc that samples the bar's recorded page layer.
 *
 * The disc and the touch target are the same 44dp object (the collection bar's back/share discs use
 * the same arrangement), so the glass never shrinks the tap area.
 */
@Composable
private fun ArtistBarButton(
    icon: ImageVector,
    description: String,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(44.dp)
            .liquidGlassControl(backdrop, CircleShape)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = AppTheme.palette.label,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ArtistHeaderBlock(
    state: ArtistUiState,
    loader: ArtworkLoader,
    bioExpanded: Boolean,
    onToggleBio: () -> Unit,
    onPlayAll: () -> Unit,
) {
    val palette = AppTheme.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Artwork(
            url = state.coverUrl,
            loader = loader,
            corner = 64.dp,
            requestSize = 320,
            modifier = Modifier.size(128.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = state.name.ifBlank { "歌手" },
            fontFamily = SFPro,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            color = palette.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        val counts = buildList {
            state.musicSize.takeIf { it > 0 }?.let { add("单曲 $it") }
            state.albumSize.takeIf { it > 0 }?.let { add("专辑 $it") }
        }.joinToString("  ·  ")
        if (counts.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(counts, fontFamily = SFPro, fontSize = 12.sp, color = palette.secondaryLabel)
        }
        if (state.briefDesc.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            // The box animates its own height and clips: expanding flips `maxLines` 3 → MAX in one
            // frame, and without this every section below jumps instantly. Clipping makes the
            // deferred-overdraw trade-off safe — lines reveal as the box grows.
            Box(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(tween(Motion.SmallDuration, easing = Motion.EaseOut))
                    .clipToBounds(),
            ) {
                Text(
                    text = if (bioExpanded) state.briefDesc else ArtistUiRules.clampBio(state.briefDesc, 160),
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.secondaryLabel,
                    maxLines = if (bioExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .clickable(onClick = onToggleBio)
                        .padding(10.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            // Visible affordance for a tappable bio: the platform's own chevron, rotated 220 ms like
            // LibraryScreen's expandable rows. Read only in the layer phase.
            val chevron by animateFloatAsState(
                targetValue = if (bioExpanded) 1f else 0f,
                animationSpec = tween(Motion.SmallDuration, easing = Motion.EaseOut),
                label = "bio-chevron",
            )
            Icon(
                imageVector = SfIcons.ChevronDown,
                contentDescription = if (bioExpanded) "收起简介" else "展开简介",
                tint = palette.secondaryLabel,
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onToggleBio)
                    .graphicsLayer { rotationZ = chevron * 180f },
            )
        }
        if (state.error != null) {
            Spacer(Modifier.height(6.dp))
            Text(state.error, fontFamily = SFPro, fontSize = 12.sp, color = palette.accent)
        }
        Spacer(Modifier.height(14.dp))
        // The header keeps the one primary action, full width so it stays the page's most prominent
        // control. The secondary action (shuffle) moved to the pinned bar as a glass disc: it is
        // needed at any scroll depth, and the bar is where the glass controls live.
        PillAction(
            label = "播放全部",
            icon = SfIcons.Play,
            primary = true,
            onClick = onPlayAll,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PillAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    Row(
        modifier
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(if (primary) palette.accent else palette.secondaryBackground)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) Color.White else palette.label, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = if (primary) Color.White else palette.label)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        color = AppTheme.palette.label,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SectionMessage(text: String, isError: Boolean) {
    Text(
        text = if (isError) "$text（下拉重试）" else text,
        fontFamily = SFPro,
        fontSize = 13.sp,
        color = if (isError) AppTheme.palette.accent else AppTheme.palette.secondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun LoadMoreRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.control))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.accent)
    }
}

@Composable
private fun EndRow(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.tertiaryLabel)
    }
}

@Composable
private fun AlbumCard(
    album: AlbumInfo,
    loader: ArtworkLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(124.dp).clickable(onClick = onClick)) {
        Artwork(
            url = album.coverUrl,
            loader = loader,
            corner = 10.dp,
            requestSize = 300,
            modifier = Modifier.size(124.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.name,
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val year = ArtistUiRules.yearOf(album.publishTime)
        if (year != null) {
            Text(year, fontFamily = SFPro, fontSize = 11.sp, color = AppTheme.palette.secondaryLabel)
        }
    }
}
