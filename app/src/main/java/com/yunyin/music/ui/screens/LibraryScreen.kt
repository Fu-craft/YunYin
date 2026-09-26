package com.yunyin.music.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Account
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.PlaylistCard
import com.yunyin.music.ui.components.SectionHeader
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Library tab.
 *
 * Follows the iOS grouped-list idiom: one large title, then a set of sections separated by
 * generous, *uniform* vertical space. Every tappable item is a plain row ending in a chevron —
 * no card backgrounds, no explanatory copy — so the page stays quiet and scannable.
 */
@Composable
fun LibraryScreen(
    account: Account?,
    playlists: List<Playlist>,
    playlistsLoading: Boolean,
    recentTracks: List<Track>,
    loader: ArtworkLoader,
    onSignIn: () -> Unit,
    onSettings: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onLikedSongsClick: () -> Unit,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn = account != null && !account.isAnonymous
    // NetEase returns "我喜欢的音乐" inside the playlist response; surface it separately.
    val liked = playlists.firstOrNull { it.isLikedSongs }
    val normalPlaylists = playlists.filterNot { it.isLikedSongs }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        item("status") { Spacer(Modifier.statusBarsPadding().height(4.dp)) }

        item("title") {
            Text(
                text = "资料库",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                color = AppTheme.palette.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        // ------------------------------------------------------------ account + settings
        // Both are plain rows, so they share one section and read as a single group.
        item("account-group") {
            Column {
                AccountRow(account = account, loader = loader, onClick = onSignIn)
                SettingsRow(onClick = onSettings)
            }
        }

        // ------------------------------------------------------------ my playlists
        if (signedIn) {
            item("liked") {
                LikedSongsRow(
                    trackCount = liked?.trackCount ?: 0,
                    onClick = onLikedSongsClick,
                )
            }

            if (normalPlaylists.isNotEmpty()) {
                item("my-header") {
                    SectionHeader(title = "我的歌单")
                }
                item("my-carousel") {
                    LazyRow(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(260),
                            fadeOutSpec = null,
                            placementSpec = null,
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(normalPlaylists, key = { it.id }) { playlist ->
                            PlaylistCard(
                                playlist = playlist,
                                loader = loader,
                                onClick = { onPlaylistClick(playlist) },
                            )
                        }
                    }
                }
            } else if (playlistsLoading) {
                item("my-loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = tween(260),
                                fadeOutSpec = null,
                                placementSpec = null,
                            )
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = AppTheme.palette.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------ recently played
        if (recentTracks.isNotEmpty()) {
            item("recent-header") { SectionHeader(title = "最近播放") }
            items(recentTracks, key = { "recent-${it.id}" }) { track ->
                RecentTrackRow(
                    track = track,
                    loader = loader,
                    onClick = { onTrackClick(track) },
                    // Fade rows in as they enter composition, so a newly recorded play
                    // appears rather than popping into the list.
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(260),
                        fadeOutSpec = null,
                        placementSpec = null,
                    ),
                )
            }
        }

        if (recentTracks.isEmpty() && !signedIn) {
            item("empty") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        SfIcons.MusicNote,
                        contentDescription = null,
                        tint = AppTheme.palette.tertiaryLabel,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "登录后可查看你的歌单",
                        fontFamily = SFPro,
                        fontSize = 14.sp,
                        color = AppTheme.palette.secondaryLabel,
                    )
                }
            }
        }
    }
}

/** Avatar, name and a one-line state hint, ending in a chevron. */
@Composable
private fun AccountRow(
    account: Account?,
    loader: ArtworkLoader,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Always tappable: the app starts on an anonymous session, so this row is the way
            // in to sign-in and account switching.
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.avatarUrl != null) {
            Artwork(
                url = account.avatarUrl,
                loader = loader,
                corner = AppleShapes.pill,
                requestSize = 200,
                placeholderIcon = SfIcons.Person,
                modifier = Modifier.size(52.dp),
            )
        } else {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(AppTheme.palette.secondaryBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Person,
                    contentDescription = null,
                    tint = AppTheme.palette.secondaryLabel,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.nickname ?: "未登录",
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    account == null -> "点击登录"
                    account.isAnonymous -> "游客模式"
                    else -> "${account.userId}"
                },
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = if (account == null || account.isAnonymous) {
                    AppTheme.palette.accent
                } else {
                    AppTheme.palette.secondaryLabel
                },
            )
        }
        Chevron()
    }
}

/** The liked-songs entry, styled like every other row for consistency. */
@Composable
private fun LikedSongsRow(trackCount: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.card))
                .background(AppTheme.palette.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SfIcons.HeartFill,
                contentDescription = null,
                tint = AppTheme.palette.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "我喜欢的音乐",
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                color = AppTheme.palette.label,
            )
            if (trackCount > 0) {
                Text(
                    text = "$trackCount 首",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
            }
        }
        Chevron()
    }
}

@Composable
private fun SettingsRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SfIcons.Gearshape,
            contentDescription = null,
            tint = AppTheme.palette.secondaryLabel,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = "设置",
            fontFamily = SFPro,
            fontSize = 17.sp,
            color = AppTheme.palette.label,
            modifier = Modifier.weight(1f),
        )
        Chevron()
    }
}

/**
 * One recently-played entry: artwork on the left, title and artist on the right.
 *
 * A plain list row — no card, no background — so the section reads as a list rather than a
 * strip of tiles.
 */
@Composable
private fun RecentTrackRow(
    track: Track,
    loader: ArtworkLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = track.coverUrl,
            loader = loader,
            corner = 8.dp,
            requestSize = 300,
            modifier = Modifier
                .size(48.dp)
                // Soft, low-elevation shadow: enough to lift the tile off the background
                // without drawing attention to itself.
                .shadow(2.dp, ContinuousRoundedRectangle(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name,
                fontFamily = SFPro,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
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
}

/** The trailing disclosure indicator used by every row. */
@Composable
private fun Chevron() {
    Icon(
        SfIcons.ChevronRight,
        contentDescription = null,
        tint = AppTheme.palette.tertiaryLabel,
        modifier = Modifier.size(16.dp),
    )
}

/** Vertical space between sections; one value everywhere so the rhythm is even. */
private val SectionGap = 22.dp
