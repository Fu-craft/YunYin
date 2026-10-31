// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.HomeUiState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.ChartRow
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.InsetSeparator
import com.yunyin.music.ui.components.PlaylistCarousel
import com.yunyin.music.ui.components.ProminentButton
import com.yunyin.music.ui.components.SectionHeader
import com.yunyin.music.ui.components.TrackCarousel
import com.yunyin.music.ui.components.TrackRow
import com.yunyin.music.ui.components.chromeContentBottomPadding
import com.yunyin.music.ui.components.formatCount
import com.yunyin.music.ui.components.glassTopBarTopPadding
import com.yunyin.music.ui.components.liquidGlassControl
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro
import com.kyant.backdrop.backdrops.layerBackdrop

/**
 * Discover tab.
 *
 * Vertical scroll is the primary navigation, as on iOS: a large search affordance, then
 * "listen now" shelves — featured playlists, daily picks, recently played, and a
 * "explore more" list. Sections collapse to nothing when they have no data, so a
 * logged-out user sees a shorter but never broken page.
 */
/**
 * Fade-in for a home shelf.
 *
 * Every shelf on this screen is conditional on the feed having loaded, so without this they all
 * appeared in a single frame once the request returned. Because each item enters composition at a
 * slightly different moment, the result is a short cascade rather than one pop.
 *
 * Placement animation is off on purpose: shelves reordering as data arrives should not slide
 * around under the user's finger.
 */
private fun LazyItemScope.shelfAppear(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(Motion.ItemAppearDuration),
    fadeOutSpec = null,
    placementSpec = null,
)

/**
 * 每日推荐 hero — the date, the count and the first cover; one tap plays the whole set.
 *
 * Apple-design pass: hierarchy comes from size and weight (12 sp eyebrow, 20 sp date with negative
 * tracking, one secondary line); the card is a single solid surface rather than translucent, because
 * a light material over the blurred album backdrop loses legibility and this is the interactive
 * element; and it scales on press so the tap is acknowledged before the transition starts.
 */
@Composable
private fun DailyRecommendationCard(
    tracks: List<Track>,
    loader: ArtworkLoader,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "daily-hero-press",
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(ContinuousRoundedRectangle(AppleShapes.card))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(
                interactionSource = interaction,
                indication = rememberControlRipple(),
                onClick = onPlayAll,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = tracks.firstOrNull()?.coverUrl,
            loader = loader,
            corner = AppleShapes.control,
            modifier = Modifier.size(112.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "每日推荐",
                fontFamily = SFPro,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
                color = AppTheme.palette.secondaryLabel,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = dailyDateLabel(),
                fontFamily = SFPro,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.7).sp,
                color = AppTheme.palette.label,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "根据你的口味生成 · ${tracks.size} 首",
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = SfIcons.Play,
            contentDescription = "播放每日推荐",
            tint = AppTheme.palette.label,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
    }
}

/** `11月1日` — the card title, so the shelf reads as "today". */
private fun dailyDateLabel(): String {
    val today = java.time.LocalDate.now()
    return "${today.monthValue}月${today.dayOfMonth}日"
}

/**
 * Cold-start skeleton that mirrors the real blocks (hero, section title, tile row) so the page does
 * not reflow when content lands. Static on purpose: a shimmer is motion for its own sake on a screen
 * that is only visible for a fraction of a second.
 */
@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedRectangle(AppleShapes.card))
                .background(AppTheme.palette.secondaryBackground)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(112.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .background(AppTheme.palette.tertiaryBackground),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                HomeSkeletonBar(width = 56.dp, height = 10.dp)
                Spacer(Modifier.height(8.dp))
                HomeSkeletonBar(width = 120.dp, height = 22.dp)
                Spacer(Modifier.height(8.dp))
                HomeSkeletonBar(width = 140.dp, height = 10.dp)
            }
        }
        HomeSkeletonBar(width = 88.dp, height = 16.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Column {
                    Box(
                        Modifier
                            .size(104.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.card))
                            .background(AppTheme.palette.tertiaryBackground),
                    )
                    Spacer(Modifier.height(8.dp))
                    HomeSkeletonBar(width = 76.dp, height = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun HomeSkeletonBar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(AppTheme.palette.tertiaryBackground),
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    loader: ArtworkLoader,
    onSearchClick: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onChartClick: (Long, String, String?) -> Unit,
    /** Starts 私人 FM; the host replaces the queue and opens the player on the first batch. */
    onStartFm: () -> Unit = {},
    /** Retries the feed after the error state; the host owns the request. */
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val feed = state.feed

    // One recorded page layer for the frosted top bar; the bar is composed outside it (below). The
    // search affordance used to be the first item of the list, so it scrolled away with the feed.
    // It is now the fixed bar's content — iOS's navigation strip — and the shelves start below it
    // and scroll up under its progressive blur.
    val topBarBackdrop = rememberGlassTopBarBackdrop()

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = glassTopBarTopPadding(),
                    bottom = chromeContentBottomPadding(),
                ),
            ) {
                // Cold start: the feed is requested when Home first appears, so the shelves are all
                // empty for a moment. Without this the page was a blank field under the search strip
                // with no sign that anything was loading.
                if (state.loading && feed.featuredPlaylists.isEmpty()) {
                    item("loading") { HomeSkeleton(modifier = Modifier.fillMaxWidth()) }
                }

                // 每日推荐 leads the page: the one shelf that answers "what should I play?" without
                // scrolling, and one tap starts listening.
                if (feed.dailySongs.isNotEmpty()) {
                    item("daily-hero") {
                        DailyRecommendationCard(
                            tracks = feed.dailySongs,
                            loader = loader,
                            onPlayAll = { onTrackClick(feed.dailySongs.first(), feed.dailySongs) },
                            modifier = shelfAppear(),
                        )
                    }
                }
                if (feed.featuredPlaylists.isNotEmpty()) {
            item("featured-header") {
                SectionHeader(
                    title = "精选歌单",
                    subtitle = "为你精选的推荐",
                    onMore = { onPlaylistClick(feed.featuredPlaylists.first()) },
                    modifier = shelfAppear(),
                )
            }
            item("featured") {
                PlaylistCarousel(
                    playlists = feed.featuredPlaylists,
                    loader = loader,
                    onPlaylistClick = onPlaylistClick,
                    modifier = shelfAppear(),
                )
            }
        }

        // 私人 FM sits inside the shelf rhythm, after the first content shelf: content leads, the entry
        // follows it (hierarchy), and because it only exists once the feed has content it never pushes
        // the cold-start loading state down.
        if (feed.featuredPlaylists.isNotEmpty() || feed.dailySongs.isNotEmpty()) {
            item("fm") { HomeFmRow(onClick = onStartFm, modifier = shelfAppear()) }
        }

        if (feed.dailySongs.isNotEmpty()) {
            item("daily-header") { SectionHeader(title = "每日推荐", modifier = shelfAppear()) }
            item("daily") {
                TrackCarousel(
                    tracks = feed.dailySongs,
                    loader = loader,
                    onTrackClick = { onTrackClick(it, feed.dailySongs) },
                    modifier = shelfAppear(),
                )
            }
        }

        if (feed.recommendedPlaylists.isNotEmpty()) {
            item("recommended-header") { SectionHeader(title = "为你推荐", modifier = shelfAppear()) }
            item("recommended") {
                PlaylistCarousel(
                    playlists = feed.recommendedPlaylists,
                    loader = loader,
                    onPlaylistClick = onPlaylistClick,
                    modifier = shelfAppear(),
                )
            }
        }

        if (feed.newSongs.isNotEmpty()) {
            // This shelf renders `/personalized/newsong`, i.e. new releases — it was
            // previously labelled 最近播放, which the data never was.
            item("new-header") { SectionHeader(title = "新歌速递", modifier = shelfAppear()) }
            item("new") {
                TrackCarousel(
                    tracks = feed.newSongs,
                    loader = loader,
                    onTrackClick = { onTrackClick(it, feed.newSongs) },
                    modifier = shelfAppear(),
                )
            }
        }

        if (feed.charts.isNotEmpty()) {
            item("charts-header") { SectionHeader(title = "排行榜", modifier = shelfAppear()) }
            // `itemsIndexed` gives the rank directly; `feed.charts.indexOf(chart)` inside the item
            // was a linear scan per row (quadratic over the list) for a value already known.
            itemsIndexed(feed.charts, key = { _, chart -> "chart-${chart.id}" }) { index, chart ->
                ChartRow(
                    index = index + 1,
                    chart = chart,
                    loader = loader,
                    onClick = { onChartClick(chart.id, chart.name, chart.coverUrl) },
                )
            }
        }

        if (feed.dailySongs.isNotEmpty()) {
            item("explore-header") { SectionHeader(title = "探索更多", modifier = shelfAppear()) }
            items(feed.dailySongs.take(8), key = { "explore-${it.id}" }) { track ->
                TrackRow(
                    track = track,
                    loader = loader,
                    onClick = { onTrackClick(track, feed.dailySongs) },
                    // No trailing ellipsis: it looked like a working "more" affordance but there is
                    // no per-row action here, so the row is simply a song.
                )
                InsetSeparator()
            }
        }

        if (state.error != null && feed.featuredPlaylists.isEmpty()) {
            item("error") {
                Column(
                    Modifier.fillMaxWidth().padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        SfIcons.WifiSlash,
                        contentDescription = null,
                        tint = AppTheme.palette.tertiaryLabel,
                        modifier = Modifier.size(38.dp),
                    )
                    Text(
                        "无法加载推荐内容",
                        fontFamily = SFPro,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.palette.label,
                    )
                    Text(
                        state.error,
                        fontFamily = SFPro,
                        fontSize = 13.sp,
                        color = AppTheme.palette.secondaryLabel,
                    )
                    Spacer(Modifier.height(6.dp))
                    // The error block used to be a dead end: a message and no way to ask again.
                    ProminentButton(text = "重新加载", onClick = onRetry)
                }
            }
        }
            }
        }

        // The fixed frosted strip: composed outside the recorded layer, so it samples the feed
        // scrolling underneath rather than itself. The search field inside it is a small
        // liquid-glass capsule on top of that strip, sampling the same recorded layer.
        GlassTopBar(
            backdrop = topBarBackdrop,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            SearchBar(onClick = onSearchClick, backdrop = topBarBackdrop)
        }
    }
}

/**
 * iOS search field: a liquid-glass capsule with the magnifier at the leading edge.
 *
 * Rendered as a tappable control here (not an input) because tapping it pushes the
 * dedicated search screen, which is how the search experience behaves on iOS. The glass is the
 * bottom chrome's material on the strip's own recorded layer — see [liquidGlassControl].
 */
@Composable
fun SearchBar(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 2dp vertical padding + the 44dp field = the strip's 48dp title row.
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .height(44.dp)
            .liquidGlassControl(backdrop, RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SfIcons.MagnifyingGlass,
            contentDescription = null,
            tint = AppTheme.palette.secondaryLabel,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = placeholder,
            fontFamily = SFPro,
            fontSize = 17.sp,
            color = AppTheme.palette.secondaryLabel,
        )
    }
}

/**
 * 私人 FM entry, integrated with the shelves rather than pinned above them.
 *
 * Reads as a shelf row (20 dp gutter, 56 dp height, 15 sp title + 12 sp subtitle) instead of a
 * banner: no filled card, no competing full-width block — just an icon, the action's name, one line
 * of what it does, and the chevron that says it navigates. Unbounded ripple so the press feedback is
 * a water ripple rather than a rectangle (design skill: shelf typography/rhythm, 44 dp+ target,
 * visible feedback without a filled surface).
 */
@Composable
private fun HomeFmRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = AppTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .height(56.dp)
            .clickable(
                indication = rememberControlRipple(bounded = false),
                interactionSource = null,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                .background(palette.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SfIcons.SpeakerWave,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "私人 FM",
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = palette.label,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = "根据你的口味持续播放",
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = palette.secondaryLabel,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Icon(
            SfIcons.ChevronRight,
            contentDescription = null,
            tint = palette.tertiaryLabel,
            modifier = Modifier.size(18.dp),
        )
    }
}
