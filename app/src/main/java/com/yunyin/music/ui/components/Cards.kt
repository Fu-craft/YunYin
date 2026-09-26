package com.yunyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.ChartInfo
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Large playlist tile used by the "精选歌单" carousel.
 *
 * Mirrors Apple Music's editorial card: square artwork with a title/curator footer, sized
 * so roughly 1.6 cards are visible — the partial card is what signals horizontal scroll.
 */
@Composable
fun PlaylistCard(
    playlist: Playlist,
    loader: ArtworkLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 196.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .clip(ContinuousRoundedRectangle(AppleShapes.cardLarge))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(onClick = onClick),
    ) {
        Artwork(
            url = playlist.coverUrl,
            loader = loader,
            corner = 0.dp,
            requestSize = 600,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = playlist.name,
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only drawn when there is something to say. Previously a blank description fell
            // through to a fallback string, and a JSON null rendered as the literal word
            // "null".
            curatorLabel(playlist)?.let { subtitle ->
                Text(
                    text = subtitle,
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact square card for "每日推荐" / "最近播放" style carousels. */
@Composable
fun TrackCard(
    track: Track,
    loader: ArtworkLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 150.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(
            url = track.coverUrl,
            loader = loader,
            corner = AppleShapes.card,
            requestSize = 500,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            text = track.name,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = AppTheme.palette.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.artistLine,
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Horizontal carousel of [TrackCard]s. */
@Composable
fun TrackCarousel(
    tracks: List<Track>,
    loader: ArtworkLoader,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: androidx.compose.ui.unit.Dp = 150.dp,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            TrackCard(
                track = track,
                loader = loader,
                onClick = { onTrackClick(track) },
                width = cardWidth,
            )
        }
    }
}

/** Horizontal carousel of [PlaylistCard]s. */
@Composable
fun PlaylistCarousel(
    playlists: List<Playlist>,
    loader: ArtworkLoader,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            PlaylistCard(
                playlist = playlist,
                loader = loader,
                onClick = { onPlaylistClick(playlist) },
            )
        }
    }
}

/** Numbered chart row, iOS style: index on the left, artwork, then metadata. */
@Composable
fun ChartRow(
    index: Int,
    chart: ChartInfo,
    loader: ArtworkLoader,
    onClick: () -> Unit,
) {
    MediaRow(
        title = chart.name,
        subtitle = chart.updateFrequency,
        artwork = {
            Artwork(
                url = chart.coverUrl,
                loader = loader,
                corner = 10.dp,
                requestSize = 300,
                modifier = Modifier.size(56.dp),
            )
        },
        trailing = {
            Text(
                text = "$index",
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = AppTheme.palette.tertiaryLabel,
            )
        },
        onClick = onClick,
    )
}

/** Track row with an explicit play affordance used by search results. */
@Composable
fun TrackRow(
    track: Track,
    loader: ArtworkLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    MediaRow(
        title = track.name,
        subtitle = listOfNotNull(track.artistLine.takeIf { it.isNotBlank() }, track.albumName.takeIf { it.isNotBlank() })
            .joinToString(" — "),
        artwork = {
            Artwork(
                url = track.coverUrl,
                loader = loader,
                corner = 10.dp,
                requestSize = 300,
                modifier = Modifier.size(48.dp),
            )
        },
        trailing = trailing,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Empty-state block: centered glyph plus an explanatory line. */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = SfIcons.MusicNote,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(40.dp))
        Text(
            text = text,
            fontFamily = SFPro,
            fontSize = 15.sp,
            color = AppTheme.palette.secondaryLabel,
        )
    }
}

/**
 * Subtitle for a playlist card, or null when there is nothing worth showing.
 *
 * Returning null is what lets the caller omit the line entirely, rather than reserving space
 * for an empty string.
 */
private fun curatorLabel(playlist: Playlist): String? {
    playlist.description?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        ?.let { return it }
    if (playlist.playCount > 0) return "播放 ${formatCount(playlist.playCount)}"
    if (playlist.trackCount > 0) return "${playlist.trackCount} 首"
    return null
}
