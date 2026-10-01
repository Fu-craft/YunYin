package com.yunyin.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Account
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Library tab ("我的").
 *
 * Two parts, in the order the reference presents them: a **profile header** (banner, identity sheet,
 * avatar straddling the seam) and then a single grouped card of destination rows.
 *
 * The profile header replaced a title row plus a standalone account card. That structure was the reason
 * the page could not show a personalised background: a title row above a card leaves no room for a
 * full-bleed image, and the card is an opaque box with nothing to put a picture behind. A banner the
 * avatar sits on gives the user's own image a place to be, which is what was asked for.
 *
 * Three things about the row list are deliberate and are what make it read as an iOS grouped list rather
 * than as a pile of rows:
 *
 *  - **Cards, not a flat list.** Each group is one rounded surface on the page background, so the
 *    grouping is carried by the shape instead of by separators. There are consequently no separator
 *    lines at all — the reference has none, and spacing alone does the work.
 *  - **One icon per row, one chevron per row.** Every row is the same height with the same leading
 *    icon size and a trailing disclosure indicator, so the column of labels lines up and the eye can
 *    scan it vertically.
 *  - **Every row goes somewhere, and the two list rows open their own page.** They used to unfold inside
 *    the card, which made the page's length depend on what was open (so the card below jumped), and it
 *    meant a long "最近播放" and a long playlist collection both turned the profile page into a scroller.
 *    They are now subpages that slide in from the trailing edge over the list — the standard push, with a
 *    matching slide-out — so each collection gets the full height and the profile page keeps its shape.
 *    The operation is no longer than before: one tap to open, the same back gesture to leave.
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
    signature: String = "",
    /**
     * The name shown on the profile header.
     *
     * Resolved by the caller (a local override when the user set one, otherwise the account's nickname), so
     * this screen does not have to know which of the two it is displaying.
     */
    displayName: String = "",
    /**
     * Songs in the liked list, or null while it is not yet known.
     *
     * The merged total (local likes plus the account's cloud likes), which is exactly the number the user
     * sees when they open the list. Null is passed through rather than defaulted to zero so a half-known
     * count is never presented as the total — that was the bug where one freshly liked song was shown as
     * "1 首" beside a 563-song list.
     */
    likedCount: Int? = null,
    /** The user's chosen header image, already decoded; null falls back to an accent gradient. */
    headerBackground: ImageBitmap? = null,
    /** The user's chosen avatar, already decoded; null falls back to the account's own. */
    headerAvatar: ImageBitmap? = null,
    onSignIn: () -> Unit,
    onSettings: () -> Unit,
    onEditProfile: () -> Unit = {},
    onPlaylistClick: (Playlist) -> Unit,
    onLikedSongsClick: () -> Unit,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn = account != null && !account.isAnonymous
    val liked = playlists.firstOrNull { it.isLikedSongs }
    val normalPlaylists = playlists.filterNot { it.isLikedSongs }

    // Which subpage is open, if any. One at a time, and held here rather than by each row so only one
    // layer can ever be on top.
    var openPage by remember { mutableStateOf<LibraryPage?>(null) }

    // The system back gesture has to close the subpage rather than escape the whole tab: without this a
    // pushed page is a dead end for anyone using the gesture instead of the on-screen back chip.
    BackHandler(enabled = openPage != null) { openPage = null }

    Box(modifier.fillMaxSize()) {
    LibraryContent(
        account = account,
        playlists = playlists,
        playlistsLoading = playlistsLoading,
        recentTracks = recentTracks,
        loader = loader,
        signature = signature,
        displayName = displayName,
        likedCount = likedCount,
        headerBackground = headerBackground,
        headerAvatar = headerAvatar,
        liked = liked,
        normalPlaylists = normalPlaylists,
        onSignIn = onSignIn,
        onSettings = onSettings,
        onEditProfile = onEditProfile,
        onPlaylistClick = onPlaylistClick,
        onLikedSongsClick = onLikedSongsClick,
        onTrackClick = onTrackClick,
        onOpenPage = { openPage = it },
    )

    // The subpages themselves.
    //
    // An `AnimatedVisibility` per page, so each animates its own presence: only the entering page
    // animates in, and on back the leaving page animates out with its content still composed (the route
    // is not cleared until the transition has finished, which is the same trap that made the collection
    // page's exit invisible).
    AnimatedVisibility(
        visible = openPage == LibraryPage.Playlists,
        enter = slideInHorizontally(tween(LIB_PAGE_MS)) { it } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(LIB_PAGE_EXIT_MS)) { it } + fadeOut(tween(160)),
    ) {
        PlaylistsPage(
            playlists = normalPlaylists,
            loading = playlistsLoading,
            signedIn = signedIn,
            loader = loader,
            onBack = { openPage = null },
            onPlaylistClick = onPlaylistClick,
        )
    }

    AnimatedVisibility(
        visible = openPage == LibraryPage.Recent,
        enter = slideInHorizontally(tween(LIB_PAGE_MS)) { it } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(LIB_PAGE_EXIT_MS)) { it } + fadeOut(tween(160)),
    ) {
        RecentPage(
            tracks = recentTracks,
            loader = loader,
            onBack = { openPage = null },
            onTrackClick = onTrackClick,
        )
    }
    }
}

/** The two collections that used to unfold inside the profile card. */
private enum class LibraryPage { Playlists, Recent }

/** Slide-in duration for a library subpage; matches the app's other page pushes. */
private const val LIB_PAGE_MS = 320

/** Exit is slightly quicker than entry, as everywhere else in this app. */
private const val LIB_PAGE_EXIT_MS = 260

/** The profile page's own content, separated so the subpages can be layered over it. */
@Composable
private fun LibraryContent(
    account: Account?,
    playlists: List<Playlist>,
    playlistsLoading: Boolean,
    recentTracks: List<Track>,
    loader: ArtworkLoader,
    signature: String,
    displayName: String,
    likedCount: Int?,
    headerBackground: ImageBitmap?,
    headerAvatar: ImageBitmap?,
    liked: Playlist?,
    normalPlaylists: List<Playlist>,
    onSignIn: () -> Unit,
    onSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onLikedSongsClick: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onOpenPage: (LibraryPage) -> Unit,
) {
    val signedIn = account != null && !account.isAnonymous

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // No status-bar spacer here: the profile header is the first thing on the page and runs under the
        // status bar on purpose, so the banner reads as full-bleed rather than as an inset image.
        contentPadding = PaddingValues(bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        // ------------------------------------------------------------ profile header
        item("header") {
            ProfileHeader(
                account = account,
                signature = signature,
                displayName = displayName,
                background = headerBackground,
                customAvatar = headerAvatar,
                loader = loader,
                onSignIn = onSignIn,
                onEditProfile = onEditProfile,
                onSettings = onSettings,
            )
        }

        // ------------------------------------------------------------ grouped rows
        item("rows") {
            GroupedCard {
                // 喜欢 — always available. Liking is a local action now, so a guest has a real list here
                // too; hiding the row for guests (as it was) would leave the heart with nowhere to show.
                LibraryRow(
                    icon = SfIcons.Heart,
                    label = "喜欢",
                    // The row shows the merged total — the same number the list itself will report.
                    // While that total is unknown (`null`) it falls back to the cloud playlist's own
                    // count, which is the closest thing already known; the local count is deliberately
                    // not used as a fallback, because on its own it reads as "1 首" no matter how large
                    // the real list is.
                    trailing = when {
                        likedCount != null && likedCount > 0 -> "$likedCount 首"
                        liked?.trackCount?.takeIf { it > 0 } != null -> "${liked.trackCount} 首"
                        else -> null
                    },
                    onClick = onLikedSongsClick,
                )

                // 我的歌单 — opens its own page rather than unfolding here.
                LibraryRow(
                    icon = SfIcons.ListBullet,
                    label = "我的歌单",
                    trailing = if (normalPlaylists.isNotEmpty()) "${normalPlaylists.size}" else null,
                    onClick = { onOpenPage(LibraryPage.Playlists) },
                )

                // 最近播放 — opens its own page for the same reason.
                LibraryRow(
                    icon = SfIcons.Clock,
                    label = "最近播放",
                    trailing = if (recentTracks.isNotEmpty()) "${recentTracks.size}" else null,
                    onClick = { onOpenPage(LibraryPage.Recent) },
                )

                // 设置 is reached from the header's gear, so it is not repeated here.
            }
        }
    }
}

/**
 * The profile header, in the reference's shape: a tall banner with the account's identity on a sheet
 * that overlaps it, and a circular avatar sitting on the seam between the two.
 *
 * Three details make it read as the reference rather than as a stack of boxes:
 *
 *  - **The banner runs under the status bar and the sheet overlaps it.** The avatar is centred on the
 *    seam, so the header reads as one object with the identity card lifted onto it — not as an image
 *    with a card below it.
 *  - **The banner is the user's own image when they have chosen one**, falling back to a gradient
 *    derived from the app's accent. A guest therefore still gets a finished header instead of an empty
 *    grey block, and the fallback is never a broken image.
 *  - **The icons on the banner are white with a scrim behind them.** The banner can be any image, so
 *    fixed ink would disappear over a light one; the scrim guarantees contrast without dimming the
 *    picture as a whole.
 *
 * The guest state is not a variant of the signed-in one: with no account there is no nickname to show
 * and nothing to customise, so the header says so and offers the one action that matters (sign in).
 */
@Composable
private fun ProfileHeader(
    account: Account?,
    signature: String,
    displayName: String,
    background: ImageBitmap?,
    customAvatar: ImageBitmap?,
    loader: ArtworkLoader,
    onSignIn: () -> Unit,
    onEditProfile: () -> Unit,
    onSettings: () -> Unit,
) {
    val signedIn = account != null && !account.isAnonymous

    // The header is laid out as a column of two bands, not as one fixed-height box with an avatar
    // positioned by arithmetic.
    //
    // That distinction is the whole fix for the name being drawn underneath the avatar: the previous
    // version placed the avatar at `HeaderHeight - SheetHeight`, but the identity sheet's height is
    // decided by its *content* (a two-line signature is taller than a one-line one), so subtracting a
    // constant put the avatar in the wrong place as soon as the content was not the assumed size.
    // Here the sheet reserves exactly half the avatar's height at its top and the avatar is offset up by
    // that same half, so the two meet at the seam whatever the content is: no constant to get wrong.
    Column(Modifier.fillMaxWidth()) {
        // ---------------------------------------------------------------- banner
        Box(
            Modifier
                .fillMaxWidth()
                .height(BannerHeight),
        ) {
            ProfileBanner(background)

            // A scrim behind the banner icons only. Full-height dimming would wash out the picture the
            // user chose; a short gradient at the top keeps the glyphs legible over any image.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.34f),
                            1f to Color.Transparent,
                        )
                    ),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BannerIcon(SfIcons.Gearshape, "设置", onSettings)
                Spacer(Modifier.weight(1f))
                if (signedIn) {
                    BannerIcon(SfIcons.Pencil, "编辑资料", onEditProfile)
                }
            }
        }

        // ---------------------------------------------------------------- identity sheet
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(topRounded(SheetCorner))
                    .background(AppTheme.palette.background)
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Reserves the lower half of the avatar, so the identity starts exactly where the avatar
                // ends.
                Spacer(Modifier.height(AvatarSize / 2))

                // `signedIn` already means a non-null, non-anonymous account, so the remaining branch can
                // read `account` directly — the compiler establishes that, and an `account?.` here would be
                // a safe call it flags as unnecessary.
                Text(
                    // The caller-resolved display name: the user's local override when they set one,
                    // otherwise the account's nickname. For a guest this is empty, so the fallback below
                    // is what shows.
                    text = (displayName.ifBlank { account?.nickname.orEmpty() })
                        .ifBlank { if (signedIn) "云音用户" else "未登录" },
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = AppTheme.palette.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        !signedIn -> "登录后可同步歌单、头像与喜欢"
                        signature.isNotBlank() -> signature
                        else -> "@${account.userId}"
                    },
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(16.dp))

                if (signedIn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProfileAction(SfIcons.Pencil, "编辑资料", onEditProfile)
                        ProfileAction(SfIcons.Gearshape, "设置", onSettings)
                    }
                } else {
                    // One primary action for a guest: there is nothing else meaningful to offer.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                            .background(AppTheme.palette.accent)
                            .clickable(
                                indication = rememberControlRipple(bounded = true),
                                interactionSource = null,
                                onClick = onSignIn,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "登录网易云音乐",
                            fontFamily = SFPro,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Color.White,
                        )
                    }
                }
            }

            // Straddles the seam: offset up by exactly half its height, which is the space the sheet
            // reserved for it above.
            Avatar(
                account = account,
                custom = customAvatar,
                loader = loader,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = -(AvatarSize / 2))
                    .size(AvatarSize),
            )
        }
    }
}

/**
 * The banner image: the user's choice when set, otherwise a gradient from the app's accent.
 *
 * A custom image is cropped to fill rather than letterboxed, so any aspect ratio the user picks still
 * fills the band without distortion.
 */
@Composable
private fun ProfileBanner(background: ImageBitmap?) {
    val palette = AppTheme.palette
    if (background != null) {
        Image(
            bitmap = background,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            palette.accent.copy(alpha = 0.85f),
                            palette.accent.copy(alpha = 0.45f),
                            palette.secondaryBackground,
                        )
                    )
                ),
        )
    }
}

/** A circular profile picture: the custom image, the NetEase avatar, or a monogram/person glyph. */
@Composable
private fun Avatar(
    account: Account?,
    custom: ImageBitmap?,
    loader: ArtworkLoader,
    modifier: Modifier = Modifier,
) {
    val signedIn = account != null && !account.isAnonymous
    val palette = AppTheme.palette
    val hasPicture = custom != null || (signedIn && !account.avatarUrl.isNullOrBlank())
    Box(
        modifier
            .clip(CircleShape)
            // A ring in the page colour, which is what separates the avatar from the banner behind it.
            .background(palette.background)
            .padding(4.dp)
            .clip(CircleShape)
            // The plate behind the picture. Neutral when there is an image to show; accent-tinted when
            // there is not, because a light grey plate with a light grey glyph on a white sheet has no
            // contrast at all — it reads as an empty hole rather than as an avatar. The tint makes the
            // placeholder legible in both appearances, which is what the reference's artwork does for it.
            .background(if (hasPicture) palette.secondaryBackground else palette.accent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        val avatarUrl = account?.avatarUrl
        when {
            // The user's own picture wins whenever they have chosen one; the caller only supplies it when
            // the "follow NetEase avatar" switch is off.
            custom != null -> Image(
                bitmap = custom,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            signedIn && !avatarUrl.isNullOrBlank() -> Artwork(
                url = avatarUrl,
                loader = loader,
                corner = AppleShapes.pill,
                requestSize = 320,
                placeholderIcon = SfIcons.Person,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Icon(
                SfIcons.Person,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(AvatarSize * 0.42f),
            )
        }
    }
}

/** A white glyph on the banner, with its own hit area. */
@Composable
private fun BannerIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                indication = rememberControlRipple(bounded = false),
                interactionSource = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** One filled chip under the name, matching the reference's button row. */
@Composable
private fun ProfileAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AppTheme.palette.label, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = AppTheme.palette.label,
        )
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

/**
 * The subtitle line for an empty collection on one of the subpages.
 *
 * Centred and with no leading inset, because it stands alone on its own page rather than sitting under a
 * row's icon.
 */
@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontFamily = SFPro,
            fontSize = 15.sp,
            color = AppTheme.palette.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A library subpage: the app's standard push, with a back chip instead of a system bar.
 *
 * Full-bleed with an opaque background on purpose — it is layered *over* the profile page, so without a
 * background the two would show through each other during the slide. Its own `navigationBarsPadding` keeps
 * the last row clear of the gesture area; the content padding below does the same for the floating chrome.
 *
 * `content` is a `ColumnScope` lambda so the page can claim the space left under the header with
 * `weight(1f)`. A `fillMaxSize()` list would instead measure against the column's *full* height and push
 * its last rows off the bottom — the header's height is not subtracted from a plain `fillMaxSize`.
 */
@Composable
private fun LibrarySubpage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(AppTheme.palette.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(
                        indication = rememberControlRipple(bounded = false),
                        interactionSource = null,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = AppTheme.palette.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content()
    }
}

/**
 * 我的歌单 as its own page.
 *
 * A list rather than the carousel the row used to open: this page exists to show *all* of them, and a
 * horizontal carousel makes a large collection tedious to scan. The count can be in the hundreds, so the
 * list is lazy.
 */
@Composable
private fun PlaylistsPage(
    playlists: List<Playlist>,
    loading: Boolean,
    signedIn: Boolean,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
) {
    LibrarySubpage(title = "我的歌单", onBack = onBack) {
        when {
            playlists.isNotEmpty() -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 150.dp),
            ) {
                itemsIndexed(playlists, key = { _, p -> p.id }) { index, playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        loader = loader,
                        onClick = { onPlaylistClick(playlist) },
                    )
                    if (index != playlists.lastIndex) {
                        RowDivider()
                    }
                }
            }

            loading -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                CircularProgressIndicator(
                    color = AppTheme.palette.accent,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.padding(top = 40.dp).size(24.dp),
                )
            }

            else -> EmptyHint(if (signedIn) "还没有歌单" else "登录后可同步你的歌单")
        }
    }
}

/** 最近播放 as its own page. */
@Composable
private fun RecentPage(
    tracks: List<Track>,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (Track) -> Unit,
) {
    LibrarySubpage(title = "最近播放", onBack = onBack) {
        if (tracks.isEmpty()) {
            EmptyHint("还没有播放记录")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 150.dp),
            ) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                    RecentTrackRow(
                        track = track,
                        loader = loader,
                        onClick = { onTrackClick(track) },
                    )
                    if (index != tracks.lastIndex) {
                        RowDivider()
                    }
                }
            }
        }
    }
}

/** A hairline between rows, inset to line up with the row's text rather than its artwork. */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp)
            .height(0.5.dp)
            .background(AppTheme.palette.separator),
    )
}

/** A playlist as a full-width row: artwork, name, track count, and a chevron. */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    loader: ArtworkLoader,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = playlist.coverUrl,
            loader = loader,
            corner = 10.dp,
            requestSize = 300,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                fontFamily = SFPro,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (playlist.trackCount > 0) {
                Text(
                    text = "${playlist.trackCount} 首",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            SfIcons.ChevronRight,
            contentDescription = null,
            tint = AppTheme.palette.tertiaryLabel,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * One recently-played entry: artwork on the left, title and artist on the right.
 *
 * Now used full-width on its own page, so it carries the page's horizontal inset itself.
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

/** Corner radius of the cards; the reference's cards are generously rounded. */
private val CardCorner = 20.dp

/** Vertical space between cards. One value, so the page rhythm is even. */
private val CardGap = 14.dp

/**
 * Height of the banner band. The identity sheet is *not* given a height: it sizes to its content and the
 * header grows with it, so a two-line signature cannot overflow into the list below.
 */
private val BannerHeight = 180.dp

/** Diameter of the header avatar, including its ring. The sheet reserves half of this at its top. */
private val AvatarSize = 104.dp

/** Corner radius of the header sheet's top edge. */
private val SheetCorner = 24.dp

/**
 * A rounded rectangle with only its top corners rounded.
 *
 * The header sheet needs this: it is the top half of a card whose bottom half is the page, so rounding
 * the bottom corners would cut notches out of content that is not there. Built from the shape library's
 * per-corner constructor so it keeps the same continuous-curvature corners as the rest of the app rather
 * than switching to a plain circular one.
 */
private fun topRounded(corner: Dp) =
    ContinuousRoundedRectangle(corner.value, corner.value, 0f, 0f)
