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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.HomeUiState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.ChartRow
import com.yunyin.music.ui.components.InsetSeparator
import com.yunyin.music.ui.components.PlaylistCarousel
import com.yunyin.music.ui.components.SectionHeader
import com.yunyin.music.ui.components.TrackCarousel
import com.yunyin.music.ui.components.TrackRow
import com.yunyin.music.ui.components.formatCount
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

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
    fadeInSpec = tween(260),
    fadeOutSpec = null,
    placementSpec = null,
)

@Composable
fun HomeScreen(
    state: HomeUiState,
    loader: ArtworkLoader,
    onSearchClick: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onChartClick: (Long, String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feed = state.feed

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 140.dp),
    ) {
        item("status-bar") { Spacer(Modifier.statusBarsPadding().height(4.dp)) }

        item("search") {
            SearchBar(onClick = onSearchClick)
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
                    trailing = {
                        Icon(
                            SfIcons.Ellipsis,
                            contentDescription = "更多",
                            tint = AppTheme.palette.tertiaryLabel,
                            modifier = Modifier.size(20.dp),
                        )
                    },
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
                }
            }
        }
    }
}

/**
 * iOS search field: capsule, translucent fill, magnifier at the leading edge.
 *
 * Rendered as a tappable control here (not an input) because tapping it pushes the
 * dedicated search screen, which is how the search experience behaves on iOS.
 */
@Composable
fun SearchBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground)
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
        Spacer(Modifier.width(8.dp))
        Text(
            text = placeholder,
            fontFamily = SFPro,
            fontSize = 17.sp,
            color = AppTheme.palette.secondaryLabel,
        )
    }
}
