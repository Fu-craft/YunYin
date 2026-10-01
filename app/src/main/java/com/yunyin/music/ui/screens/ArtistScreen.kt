package com.yunyin.music.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.graphics.Brush
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
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.ArtistUiState
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * An artist's page: identity on top, top songs below — the playlist screen's layout with an artist's data.
 *
 * Sharing the *layout* rather than being the same composable is deliberate. The playlist screen is shaped
 * by a [com.yunyin.music.ui.CollectionUiState] (playlist id for sharing, play counts, a description); an
 * artist has none of those and does have things a playlist does not (an identity fetched separately from
 * the song list). Reusing the composable would have meant threading "which kind am I" through every layer,
 * which costs more than the duplicated scaffold and reads worse.
 *
 * The song list is not duplicated, though: each row is the playlist's own [CollectionTrackRow], so an
 * artist's songs read exactly like a playlist's — position number, now-playing marker, duration.
 *
 * Two behaviours are worth stating:
 *
 *  - **The page is never blank.** The name arrives seeded from the tapped row, so the first frame already
 *    has a title; the identity call replaces the cover and confirms the name when it lands.
 *  - **The top bar is the same pinned surface as the playlist's**, for one reason: consistency. A page
 *    reached *from* a playlist should not invent a different way of leaving.
 */
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayAll: () -> Unit,
    nowPlayingId: Long? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // As with the playlist: a small decode is plenty for a heavily blurred field.
    var backdrop by remember(state.coverUrl) { mutableStateOf<ImageBitmap?>(null) }
    var coverLuma by remember(state.coverUrl) { mutableFloatStateOf(0f) }
    LaunchedEffect(state.coverUrl) {
        val bitmap: Bitmap? = state.coverUrl?.let { loader.load(it, 200) }
        backdrop = bitmap?.asImageBitmap()
        coverLuma = bitmap?.let(::meanLuma) ?: 0f
    }

    val palette = AppTheme.palette
    val coverIsDark = coverLuma < 0.55f
    val onCover = if (coverIsDark) Color.White else Color(0xFF141416)

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var headerHeightPx by remember(state.id) { mutableIntStateOf(0) }

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
                    onPlayAll = onPlayAll,
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

                else -> item("section") {
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

        TopBar(
            onCover = onCover,
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
 * The artist hero: blurred colour field, round portrait, name, and one action.
 *
 * The portrait is round rather than the playlist's square cover — the one deliberate departure from the
 * playlist layout, because an artist is a person and the round image is how every music app says so.
 */
@Composable
private fun ArtistHeader(
    state: ArtistUiState,
    loader: ArtworkLoader,
    backdrop: ImageBitmap?,
    onPlayAll: () -> Unit,
    onHeightChanged: (Int) -> Unit,
) {
    val headerHeightPx = remember { mutableIntStateOf(0) }
    // Reports the header's measured height, exactly as the playlist header does: the pinned bar derives
    // its threshold from it, and a constant 0 would make the bar go solid while the hero was still on
    // screen.
    SideEffect { onHeightChanged(headerHeightPx.intValue) }

    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { headerHeightPx.intValue = it.height },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp),
        ) {
            if (backdrop != null) {
                Image(
                    bitmap = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppTheme.palette.background),
                )
                // The same dissolve mask as the playlist hero: artwork melts into the page, so the list
                // below is not sliced by an edge.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(PageFade)
                        .background(
                            Brush.verticalGradient(
                                0f to AppTheme.palette.background.copy(alpha = 0f),
                                1f to AppTheme.palette.background,
                            ),
                        ),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    AppTheme.palette.accent.copy(alpha = 0.55f),
                                    AppTheme.palette.background,
                                ),
                            ),
                        ),
                )
            }

            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = PageInset, end = PageInset, top = TopBarHeight + BarClearance),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val size = 132.dp
                Box(
                    Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(AppTheme.palette.secondaryBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = state.coverUrl
                    if (image != null) {
                        // Loaded directly: a portrait decodes once and is then covered by its own bitmap.
                        var bitmap by remember(image) { mutableStateOf<ImageBitmap?>(null) }
                        LaunchedEffect(image) {
                            bitmap = loader.load(image, 400)?.asImageBitmap()
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
                            ArtistPortraitPlaceholder(size)
                        }
                    } else {
                        ArtistPortraitPlaceholder(size)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = state.name.ifBlank { "未知歌手" },
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = AppTheme.palette.label,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageInset),
            horizontalArrangement = Arrangement.Center,
        ) {
            PillButton(label = "播放热门", filled = true, onClick = onPlayAll)
        }
    }
}

/** The stand-in portrait: the person glyph on the secondary plate. */
@Composable
private fun ArtistPortraitPlaceholder(size: androidx.compose.ui.unit.Dp) {
    Icon(
        SfIcons.Person,
        contentDescription = null,
        tint = AppTheme.palette.secondaryLabel,
        modifier = Modifier.size(size * 0.4f),
    )
}

/** The playlist's pill action, restated for the artist's single action. */
@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val palette = AppTheme.palette
    val bg = if (filled) palette.accent else palette.secondaryBackground
    val ink = if (filled) Color.White else palette.label
    Box(
        Modifier
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(bg)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = ink,
        )
    }
}
