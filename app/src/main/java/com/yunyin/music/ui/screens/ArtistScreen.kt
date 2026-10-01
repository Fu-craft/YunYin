package com.yunyin.music.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.ArtistUiState
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * An artist's page: identity on top, top songs below — the playlist screen's layout with an artist's
 * data.
 *
 * Sharing the *layout* rather than being the same composable is deliberate. The playlist screen is
 * shaped by a [com.yunyin.music.ui.CollectionUiState] (playlist id for sharing, play counts, a
 * description); an artist has none of those and does have things a playlist does not (an identity
 * fetched separately from the song list). Reusing the composable would have meant threading "which
 * kind am I" through every layer, which costs more than the duplicated scaffold and reads worse.
 *
 * What *is* shared is the parts that would visibly drift if they were copied:
 *
 *  - [CoverField] — the blurred artwork, tone guard and eased fade into the page. The page's whole
 *    character lives there, and the artist header was previously a hand-rolled gradient that turned a
 *    pale portrait into a wash.
 *  - [HeaderAction] — the two headline pills. The artist page has one fewer (no shuffle on a
 *    playlist's terms), so it reuses the pill and drops the second action rather than inventing a
 *    different button.
 *  - [CollectionTrackRow] — the song rows, so an artist's list reads exactly like a playlist's.
 *  - [TopBar] — the same pinned surface, for consistency: a page reached *from* a playlist should not
 *    invent a different way of leaving.
 *
 * Two behaviours are worth stating:
 *
 *  - **The page is never blank.** The name arrives seeded from the tapped row, so the first frame
 *    already has a title; the identity call replaces the portrait and confirms the name when it lands.
 *  - **The ink is measured, not assumed.** A light portrait gets dark ink. The previous version fixed
 *    white ink and a black scrim, which is why the name was hard to read on pale artwork.
 */
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** Starts the list in a random order; the playlist's second action, restated. */
    onShufflePlay: () -> Unit = {},
    nowPlayingId: Long? = null,
    isPlaying: Boolean = false,
) {
    // As with the playlist: a small decode is plenty for a heavily blurred field.
    var backdrop by remember(state.coverUrl) { mutableStateOf<ImageBitmap?>(null) }
    // Defaults to "dark" so the first frame (before the image lands) already has white ink over the
    // placeholder field, exactly as the playlist does.
    var coverLuma by remember(state.coverUrl) { mutableFloatStateOf(0f) }
    LaunchedEffect(state.coverUrl) {
        val bitmap: Bitmap? = state.coverUrl?.let { loader.load(it, 200) }
        backdrop = bitmap?.asImageBitmap()
        coverLuma = bitmap?.let(::meanLuma) ?: 0f
    }

    val palette = AppTheme.palette
    // Measured, not assumed: a mid-grey portrait is the ambiguous case, and white ink has the better
    // worst case there.
    val coverIsDark = coverLuma < 0.55f
    val onCover = if (coverIsDark) Color.White else Color(0xFF141416)
    val inverseOnCover = if (coverIsDark) Color(0xFF141416) else Color.White

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var headerHeightPx by remember(state.id) { mutableIntStateOf(0) }

    // Derived exactly as the playlist derives it: the bar goes opaque on the frame the fade's band
    // reaches it, which is when the colour under the bar stops being artwork.
    val fadePx = with(density) { PageFade.toPx() }
    val barBottomPx = WindowInsets.systemBars.getTop(density) + with(density) { TopBarHeight.toPx() }
    val barSolidThreshold = (headerHeightPx - fadePx - barBottomPx).coerceAtLeast(0f)

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item("header") {
                ArtistHeader(
                    state = state,
                    loader = loader,
                    backdrop = backdrop,
                    coverIsDark = coverIsDark,
                    onCover = onCover,
                    inverseOnCover = inverseOnCover,
                    onPlayAll = onPlayAll,
                    onShufflePlay = onShufflePlay,
                    onHeightChanged = { headerHeightPx = it },
                )
            }

            when {
                state.loading && state.tracks.isEmpty() -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = palette.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                state.tracks.isEmpty() -> item("empty") { EmptyState("暂无热门歌曲") }

                else -> item("count") {
                    Text(
                        text = "热门歌曲",
                        fontFamily = SFPro,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = palette.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = PageInset, end = PageInset, top = 14.dp, bottom = 2.dp),
                    )
                }
            }

            // Index-based so duplicate songs still resolve to the row that was tapped.
            itemsIndexed(state.tracks, key = { index, track -> "${track.id}#$index" }) { index, track ->
                CollectionTrackRow(
                    index = index,
                    track = track,
                    loader = loader,
                    isCurrent = track.id == nowPlayingId,
                    isPlaying = isPlaying && track.id == nowPlayingId,
                    onClick = { onTrackClick(track, index) },
                )
            }

            item("bottom") { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
        }

        // Pinned last so it draws over the scrolling artwork.
        TopBar(
            onCover = onCover,
            // The opposite of the ink, so the scrim always separates the controls from whatever
            // scrolls under them regardless of which ink the artwork chose.
            scrimTint = if (coverIsDark) Color.Black else Color.White,
            listState = listState,
            solidThresholdPx = barSolidThreshold,
            onBack = onBack,
            // An artist page has nothing worth sharing that a playlist does not: leaving the row out is
            // more honest than a button that shares a half-built link.
            onShare = null,
        )
    }
}

/**
 * The artist hero: the playlist's colour field, a round portrait, the name, and the play action.
 *
 * The portrait is round while the playlist's cover is square — the one deliberate departure, because
 * an artist is a person and the round image is how every music app says so. Everything else, including
 * the spacer rhythm and the pill, is the playlist's.
 */
@Composable
private fun ArtistHeader(
    state: ArtistUiState,
    loader: ArtworkLoader,
    backdrop: ImageBitmap?,
    coverIsDark: Boolean,
    onCover: Color,
    inverseOnCover: Color,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    /** Reports the measured height so the pinned bar can time its surface to the ramp. */
    onHeightChanged: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) },
    ) {
        CoverField(
            backdrop = backdrop,
            coverIsDark = coverIsDark,
            pageBackground = AppTheme.palette.background,
            modifier = Modifier.matchParentSize(),
        )

        // Centred, because the portrait is: a centred image with a left-ranged name reads as two
        // unrelated columns rather than one stacked identity.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = PageInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Reserve exactly what the pinned bar occupies, from the same constants the bar uses.
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(TopBarHeight + BarClearance))
            Spacer(Modifier.height(8.dp))

            ArtistPortrait(state = state, loader = loader, onCover = onCover)

            Spacer(Modifier.height(20.dp))

            // The name is seeded from the tapped row and confirmed by the detail call, so it is present
            // from the first frame. The `@`-less artist handle some rows carry (`*Luna`) is kept as-is:
            // it is the printed name, and the search that resolves it uses exactly this string.
            Text(
                text = state.name.ifBlank { "未知歌手" },
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 27.sp,
                lineHeight = 33.sp,
                color = onCover,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            val count = state.tracks.size
            if (count > 0) {
                Spacer(Modifier.height(8.dp))
                // Just the tally: the section header below already names the list, so repeating
                // "热门歌曲" here would say the same word twice on one screen.
                Text(
                    text = "$count 首热门歌曲",
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = onCover.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Play leads and takes the solid fill, shuffle takes the glass one — the playlist's
                // ordering and the playlist's pills.
                HeaderAction(
                    icon = SfIcons.Play,
                    label = "播放热门",
                    fill = onCover,
                    content = inverseOnCover,
                    onClick = onPlayAll,
                    modifier = Modifier.weight(1f),
                )
                HeaderAction(
                    icon = SfIcons.Shuffle,
                    label = "随机播放",
                    fill = onCover.copy(alpha = 0.16f),
                    content = onCover,
                    border = onCover.copy(alpha = 0.22f),
                    onClick = onShufflePlay,
                    modifier = Modifier.weight(1f),
                )
            }

            // The band the fade completes in, text-free by construction.
            Spacer(Modifier.height(PageFadeMargin))
            Spacer(Modifier.height(PageFade))
        }
    }
}

/**
 * The round portrait.
 *
 * Deliberately *not* the playlist's square `Artwork`: a person's image is a circle, and the white
 * hairline ring is what keeps a dark portrait from dissolving into a dark field. A missing portrait
 * falls back to the person glyph on the theme's plate rather than an empty circle.
 */
@Composable
private fun ArtistPortrait(
    state: ArtistUiState,
    loader: ArtworkLoader,
    onCover: Color,
) {
    val palette = AppTheme.palette
    Box(
        Modifier
            .fillMaxWidth(ARTIST_PORTRAIT_FRACTION)
            .aspectRatio(1f)
            .shadow(
                elevation = 24.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.5f),
            )
            .clip(CircleShape)
            .background(palette.secondaryBackground)
            // The ring tracks the ink, so it is a light edge on dark artwork and a dark one on light.
            .border(2.dp, onCover.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val url = state.coverUrl
        var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(url) {
            bitmap = url?.let { loader.load(it, 800) }?.asImageBitmap()
        }
        val decoded = bitmap
        if (decoded != null) {
            Image(
                bitmap = decoded,
                contentDescription = "歌手头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                SfIcons.Person,
                contentDescription = null,
                tint = palette.secondaryLabel,
                modifier = Modifier.fillMaxSize(0.36f),
            )
        }
    }
}

/** Portrait width as a fraction of the page, so the round image scales with the screen. */
private const val ARTIST_PORTRAIT_FRACTION = 0.46f
