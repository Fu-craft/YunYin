package com.yunyin.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Library tab ("我的").
 *
 * Rebuilt to the reference layout: one large title with a circular action button beside it, a
 * standalone account card, then a single grouped card holding the destination rows. Three things
 * about that structure are deliberate and worth stating, because they are what make it read as an
 * iOS grouped list rather than as a pile of rows:
 *
 *  - **Cards, not a flat list.** Each group is one rounded surface on the page background, so the
 *    grouping is carried by the shape instead of by separators. There are consequently no separator
 *    lines at all — the reference has none, and spacing alone does the work.
 *  - **One icon per row, one chevron per row.** Every row is the same height with the same leading
 *    icon size and a trailing disclosure indicator, so the column of labels lines up and the eye can
 *    scan it vertically.
 *  - **Every row goes somewhere.** The reference's rows are all navigational; a row that only looked
 *    like one would be worse than not having it, so the two list rows expand in place to reveal
 *    their content rather than pretending to be pages this app does not have.
 *
 * [AppTheme]'s palette is used rather than the reference's fixed greys, so the screen follows the
 * system appearance instead of being permanently light.
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
    val liked = playlists.firstOrNull { it.isLikedSongs }
    val normalPlaylists = playlists.filterNot { it.isLikedSongs }

    // Which expandable row is open. Only one at a time: the card is a single surface, so two open
    // sections inside it would read as one long ungrouped list.
    var expanded by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        item("top") { Spacer(Modifier.statusBarsPadding().height(8.dp)) }

        // ------------------------------------------------------------ title + action
        item("title") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "我的",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    color = AppTheme.palette.label,
                    modifier = Modifier.weight(1f),
                )
                // The reference's circular button. Here it opens settings, which is the real
                // destination this app has; the tinted circle is kept for the same reason it works
                // there — a bare glyph next to a 34pt title reads as too small to be a button.
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                        .background(AppTheme.palette.accent.copy(alpha = 0.16f))
                        .clickable(
                            indication = rememberControlRipple(bounded = true),
                            interactionSource = null,
                            onClick = onSettings,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        SfIcons.Gearshape,
                        contentDescription = "设置",
                        tint = AppTheme.palette.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // ------------------------------------------------------------ account card
        item("account") {
            AccountCard(account = account, loader = loader, onClick = onSignIn)
        }

        // ------------------------------------------------------------ grouped rows
        item("rows") {
            GroupedCard {
                // 喜欢 — goes straight to the liked-songs list.
                if (signedIn) {
                    LibraryRow(
                        icon = SfIcons.Heart,
                        label = "喜欢",
                        trailing = liked?.trackCount?.takeIf { it > 0 }?.let { "$it 首" },
                        onClick = onLikedSongsClick,
                    )
                }

                // 我的歌单 — expands to the carousel in place.
                LibraryRow(
                    icon = SfIcons.ListBullet,
                    label = "我的歌单",
                    trailing = if (normalPlaylists.isNotEmpty()) "${normalPlaylists.size}" else null,
                    expandable = true,
                    expanded = expanded == "playlists",
                    onClick = {
                        expanded = if (expanded == "playlists") null else "playlists"
                    },
                )
                ExpandableSection(visible = expanded == "playlists") {
                    when {
                        normalPlaylists.isNotEmpty() -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
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

                        playlistsLoading -> Box(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = AppTheme.palette.accent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        else -> EmptyHint(
                            if (signedIn) "还没有歌单" else "登录后可同步你的歌单",
                        )
                    }
                }

                // 最近播放 — expands to the list in place.
                LibraryRow(
                    icon = SfIcons.Clock,
                    label = "最近播放",
                    trailing = if (recentTracks.isNotEmpty()) "${recentTracks.size}" else null,
                    expandable = true,
                    expanded = expanded == "recent",
                    onClick = { expanded = if (expanded == "recent") null else "recent" },
                )
                ExpandableSection(visible = expanded == "recent") {
                    if (recentTracks.isEmpty()) {
                        EmptyHint("还没有播放记录")
                    } else {
                        Column(Modifier.padding(bottom = 4.dp)) {
                            recentTracks.forEach { track ->
                                RecentTrackRow(
                                    track = track,
                                    loader = loader,
                                    onClick = { onTrackClick(track) },
                                )
                            }
                        }
                    }
                }

                // 设置 — the same destination as the title button, listed because the reference has
                // a row here and users scan the list before the header.
                LibraryRow(
                    icon = SfIcons.Gearshape,
                    label = "设置",
                    onClick = onSettings,
                )
            }
        }
    }
}

/**
 * The account card: avatar, identity, one line of state, and a disclosure chevron.
 *
 * Always tappable. The app starts on an anonymous session, so if this row were disabled for guests
 * there would be no way in to sign-in at all.
 */
@Composable
private fun AccountCard(
    account: Account?,
    loader: ArtworkLoader,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(ContinuousRoundedRectangle(CardCorner))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.avatarUrl != null) {
            Artwork(
                url = account.avatarUrl,
                loader = loader,
                corner = AppleShapes.pill,
                requestSize = 200,
                placeholderIcon = SfIcons.Person,
                modifier = Modifier.size(56.dp),
            )
        } else {
            // A tinted circle with the glyph, as in the reference: reads as a placeholder avatar
            // rather than as a missing image.
            Box(
                Modifier
                    .size(56.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(AppTheme.palette.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Person,
                    contentDescription = null,
                    tint = AppTheme.palette.accent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    account == null -> "点击登录"
                    account.isAnonymous -> "点击登录"
                    else -> account.nickname.ifBlank { "已登录" }
                },
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    account == null || account.isAnonymous -> "登录后可体验更多功能"
                    else -> "已同步你的歌单"
                },
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Chevron()
    }
}

/** One rounded surface holding a set of rows, so the grouping is carried by the shape. */
@Composable
private fun GroupedCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(ContinuousRoundedRectangle(CardCorner))
            .background(AppTheme.palette.secondaryBackground)
            .padding(vertical = 6.dp),
    ) {
        content()
    }
}

/**
 * A single row: leading icon, label, optional value, and a chevron.
 *
 * Expandable rows rotate their chevron a quarter turn when open, which is the standard iOS cue that
 * the row unfolds rather than navigates.
 */
@Composable
private fun LibraryRow(
    icon: ImageVector,
    label: String,
    trailing: String? = null,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(220),
        label = "row-chevron",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTheme.palette.label,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            color = AppTheme.palette.label,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                fontFamily = SFPro,
                fontSize = 15.sp,
                color = AppTheme.palette.secondaryLabel,
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = SfIcons.ChevronRight,
            contentDescription = null,
            tint = AppTheme.palette.tertiaryLabel,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (expandable) rotation else 0f },
        )
    }
}

/** Reveals a row's content in place, growing and fading so the card does not jump open. */
@Composable
private fun ExpandableSection(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(260)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(220)) + fadeOut(tween(140)),
    ) {
        Box(Modifier.padding(bottom = 6.dp)) { content() }
    }
}

/** A short line of grey text for an expanded section with nothing in it. */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontSize = 14.sp,
        color = AppTheme.palette.secondaryLabel,
        modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
    )
}

/**
 * One recently-played entry: artwork on the left, title and artist on the right.
 *
 * A plain row with no card of its own, since it already sits inside the grouped card.
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
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = track.coverUrl,
            loader = loader,
            corner = 8.dp,
            requestSize = 300,
            modifier = Modifier
                .size(46.dp)
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

/** Corner radius of the cards; the reference's cards are generously rounded. */
private val CardCorner = 20.dp

/** Vertical space between cards. One value, so the page rhythm is even. */
private val CardGap = 14.dp
