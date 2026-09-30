package com.yunyin.music.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.yunyin.music.ui.CollectionUiState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.components.formatCount
import com.yunyin.music.ui.components.formatDuration
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Playlist / chart / liked-songs detail.
 *
 * The page is one continuous colour field built from the artwork: the cover is drawn huge and
 * heavily blurred behind everything, a tone guard pushes it away from mid-grey, and the field
 * ramps into the page background in a text-free band just above the list. The sharp artwork sits
 * centred on top of it. That is the Apple Music collection layout, and it is why a playlist reads
 * as "belonging to" its cover rather than as a generic list that happens to have a thumbnail.
 *
 * Two decisions here are load-bearing rather than cosmetic:
 *
 *  - **The header's text colour is measured from the cover, not taken from the theme.** A light
 *    theme would otherwise put near-black text on a dark cover. The mean luminance of the decoded
 *    cover picks white or near-black ink, so both a dark and a light playlist work — which is
 *    exactly the difference between the two reference layouts this follows.
 *  - **Every colour-bearing element stays inside the un-ramped part of the field.** The ramp to the
 *    page background carries no text at all; it lives entirely in the band between the action
 *    buttons and the first row of the list. A wash that reached full page-background behind the
 *    buttons (what an evenly-spaced ramp does) would leave white labels on a white page.
 *
 * The top bar is pinned above the list rather than scrolling with the header, because this screen
 * is a full-window overlay: without it, scrolling past the artwork would leave no visible way back.
 * It crossfades onto an opaque page-background surface as the header leaves, which is the same
 * move iOS's large-title bar makes.
 */
@Composable
fun CollectionScreen(
    state: CollectionUiState,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** Runs the list in a random order; absent when there is nothing to play. */
    onShufflePlay: () -> Unit = {},
    onShare: () -> Unit = {},
    /** Id of the track the player is on, so its row can be marked. */
    nowPlayingId: Long? = null,
    isPlaying: Boolean = false,
) {
    // A small decode is plenty — it is blurred heavily, so detail would be discarded anyway.
    var backdrop by remember(state.coverUrl) { mutableStateOf<ImageBitmap?>(null) }
    // Defaults to "dark cover" so the first frame (before the image lands) already has white ink:
    // the blurred-artwork slot starts out as the dark placeholder surface.
    var coverLuma by remember(state.coverUrl) { mutableFloatStateOf(0f) }
    LaunchedEffect(state.coverUrl) {
        val bitmap: Bitmap? = state.coverUrl?.let { loader.load(it, 200) }
        backdrop = bitmap?.asImageBitmap()
        coverLuma = bitmap?.let(::meanLuma) ?: 0f
    }

    val palette = AppTheme.palette
    // Measured, not assumed: a mid-grey cover is the only genuinely ambiguous case, and white ink
    // has the better worst case there.
    val coverIsDark = coverLuma < 0.55f
    val onCover = if (coverIsDark) Color.White else Color(0xFF141416)
    val inverseOnCover = if (coverIsDark) Color(0xFF141416) else Color.White

    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // The header reports its measured height so the bar knows exactly when to take on a surface.
    var headerHeightPx by remember(state.coverUrl) { mutableIntStateOf(0) }

    /**
     * When the pinned bar stops floating on artwork and becomes an opaque bar.
     *
     * The header's last band is the fade into the page background, so the bar must go opaque the
     * moment that band reaches it — before then everything behind the bar is cover artwork with the
     * measured ink on top, and after it everything behind is the page background the list sits on.
     *
     * This is computed, not tuned. An earlier fixed 74dp made the bar turn solid while the cover was
     * still plainly on screen: a white slab appearing across the middle of the artwork. Deriving it
     * from the measured header height and the fade's own height means the switch lands on the exact
     * frame where the colour under the bar stops being the cover.
     */
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
                Header(
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

            if (state.loading) {
                item("loading") {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = palette.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            } else if (state.tracks.isEmpty()) {
                item("empty") { EmptyState("这个歌单还没有歌曲") }
            } else {
                item("count") {
                    CountRow(
                        count = state.tracks.size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = PageInset, end = PageInset, top = 14.dp, bottom = 2.dp),
                    )
                }
                // Index-based so duplicate songs in a playlist still resolve to the row that was
                // tapped (`indexOf` would always return the first duplicate).
                itemsIndexed(
                    state.tracks,
                    key = { index, track -> "${track.id}#$index" },
                ) { index, track ->
                    CollectionTrackRow(
                        index = index,
                        track = track,
                        loader = loader,
                        isCurrent = track.id == nowPlayingId,
                        isPlaying = isPlaying && track.id == nowPlayingId,
                        onClick = { onTrackClick(track, index) },
                    )
                }
            }

            item("bottom") { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
        }

        // Pinned last so it draws over the scrolling artwork.
        TopBar(
            onCover = onCover,
            // The opposite of the ink, so the scrim always separates the controls from whatever
            // scrolls under them regardless of which ink the cover chose.
            scrimTint = if (coverIsDark) Color.Black else Color.White,
            listState = listState,
            solidThresholdPx = barSolidThreshold,
            onBack = onBack,
            onShare = onShare,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * The artwork hero: blurred colour field, sharp cover, identity, and the two actions.
 *
 * Scrolling behaviour is deliberately absent here — the backdrop is a lazy-item background, so it
 * simply travels with the header and the pinned bar covers it on the way past.
 */
@Composable
private fun Header(
    state: CollectionUiState,
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
    val palette = AppTheme.palette

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) },
    ) {
        // ---------------------------------------------------------------- colour field
        Box(Modifier.matchParentSize().clipToBounds()) {
            if (backdrop != null) {
                Image(
                    bitmap = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        // Overscan so the blur's soft edge is pushed outside the clip instead of
                        // showing as a lighter rim around the header.
                        .graphicsLayer {
                            scaleX = 1.5f
                            scaleY = 1.5f
                        }
                        .blur(56.dp),
                )
            } else {
                Box(Modifier.matchParentSize().background(palette.secondaryBackground))
            }

            // Tone guard. Mid-grey artwork is the one case where neither ink colour has contrast, so
            // the field is pushed toward whichever end of the scale the ink is at: extra depth under
            // white ink, extra lift under dark ink.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        if (coverIsDark) Color.Black.copy(alpha = 0.24f)
                        else Color.White.copy(alpha = 0.34f),
                    ),
            )

            // The fade into the page background, anchored to the header's bottom edge.
            //
            // Three things here are load-bearing, and each was a visible artefact before:
            //
            //  - **The transparent end is the page colour at zero alpha, not `Color.Transparent`.**
            //    `Color.Transparent` is black with no alpha, and Compose interpolates the RGB channels
            //    as well as alpha — so a "transparent → page background" ramp spends its middle at
            //    roughly 50% of a *black-tinted* colour. Composited over the field that is darker than
            //    either end, which paints a grey band across the fade. Most of what read as a hard
            //    edge here was this, not the gradient's shape.
            //  - **The curve is eased.** A linear ramp arrives and leaves with a slope
            //    discontinuity — one where the flat cover colour above it stops being flat, one where
            //    the list's flat background below it begins — and the eye draws a line at each. That
            //    is the Mach band, and it is why a colour-correct linear fade still shows a seam.
            //  - **It is anchored to the bottom rather than stretched over the whole header.** Only
            //    the last [PageFade] is a gradient; above that the field is untouched cover colour, so
            //    the fade cannot wash out the artwork.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(PageFade)
                    .background(
                        Brush.verticalGradient(colorStops = pageFadeStops(palette.background)),
                    ),
            )
        }

        // ---------------------------------------------------------------- content
        //
        // Centred, because the cover is: a centred image with a left-ranged title reads as two
        // unrelated columns rather than one stacked identity.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = PageInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Reserve exactly what the pinned bar occupies, from the same constants the bar uses.
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(TopBarHeight + BarClearance))
            Spacer(Modifier.height(8.dp))

            Artwork(
                url = state.coverUrl,
                loader = loader,
                corner = AppleShapes.cardLarge,
                requestSize = 800,
                modifier = Modifier
                    .fillMaxWidth(CoverWidthFraction)
                    .aspectRatio(1f)
                    .shadow(
                        elevation = 24.dp,
                        shape = ContinuousRoundedRectangle(AppleShapes.cardLarge),
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.5f),
                        spotColor = Color.Black.copy(alpha = 0.5f),
                    ),
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = state.title,
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

            val description = state.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            if (description != null) {
                Spacer(Modifier.height(8.dp))
                ExpandableDescription(text = description, onCover = onCover, key = state.title)
            }

            val meta = metaLine(state)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = meta,
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
                // Play all leads: it is the action people come here for, so it takes the solid fill
                // and shuffle takes the glass one. Both are pills, as in iOS's media headers.
                HeaderAction(
                    icon = SfIcons.Play,
                    label = "播放全部",
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

            // The band the fade completes in. Text-free by construction, and given a margin above it
            // so the buttons sit on the untouched cover colour rather than inside the gradient.
            Spacer(Modifier.height(PageFadeMargin))
            Spacer(Modifier.height(PageFade))
        }
    }
}

/**
 * Description clamped to two lines with an inline disclosure.
 *
 * The toggle only exists when the text actually overflows: a short description that fits gets no
 * affordance, because a control that expands nothing is worse than no control.
 */
@Composable
private fun ExpandableDescription(text: String, onCover: Color, key: Any?) {
    var expanded by remember(key) { mutableStateOf(false) }
    var overflows by remember(key) { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            fontFamily = SFPro,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = onCover.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                // Only ever learn the clamped answer: measuring the expanded layout would clear it.
                if (!expanded) overflows = result.hasVisualOverflow
            },
        )
        if (overflows || expanded) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    // On the raw colour field, so the theme's ink would be wrong here — it is
                    // whatever the page background needs, not whatever this artwork needs.
                    indication = rememberControlRipple(
                        bounded = true,
                        color = onCover.copy(alpha = 0.18f),
                    ),
                    interactionSource = null,
                    onClick = { expanded = !expanded },
                ),
            ) {
                Text(
                    text = if (expanded) "收起" else "更多",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = onCover.copy(alpha = 0.9f),
                )
                Icon(
                    imageVector = SfIcons.ChevronDown,
                    contentDescription = null,
                    tint = onCover.copy(alpha = 0.9f),
                    modifier = Modifier
                        .size(13.dp)
                        .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                )
            }
        }
    }
}

/** One of the two header pills. */
@Composable
private fun HeaderAction(
    icon: ImageVector,
    label: String,
    fill: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: Color = Color.Transparent,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(fill)
            .then(
                if (border == Color.Transparent) Modifier
                else Modifier.border(0.5.dp, border, ContinuousRoundedRectangle(AppleShapes.pill)),
            )
            // The press ink is the pill's own content colour: on the solid pill that is dark-on-light
            // and on the glass pill light-on-dark, so one value is correct for both. Taken from the
            // artwork-derived ink rather than the theme, which is what makes it visible on either.
            .clickable(
                indication = rememberControlRipple(bounded = true, color = content.copy(alpha = 0.18f)),
                interactionSource = null,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = content,
        )
    }
}

/**
 * Pinned top bar.
 *
 * ## The surface tracks the scroll, and only this bar recomposes
 *
 * [surface] is a continuous function of the scroll position rather than a switch, which is what makes the
 * bar's appearance proportional to the gesture, exactly reversible, and free of a timer to tune. It used to
 * be a boolean that a 200ms tween faded in, which read as a pop and kept animating for 200ms after the
 * finger stopped.
 *
 * The scroll state is read **here**, not by the caller, and deliberately so: reading a value that changes on
 * every scroll frame in the page's own body would recompose the whole screen — including the 500-row list's
 * setup — once per frame. Scoped to this composable, the per-frame work is the bar's background and two
 * icons. (This is the same trap as reading an animated value in a composable body, which this codebase has
 * been bitten by before.)
 *
 * ## The ink follows the surface rather than animating separately
 *
 * The ink is *interpolated* by the same fraction, because the colour that reads on a photo is not the one
 * that reads on the page background: if the two changed on their own schedules, one of them would be
 * briefly wrong against the other.
 *
 * While transparent the bar also lays a soft scrim in [scrimTint] — the opposite of the ink, so black under
 * white ink and white under dark ink. This is not decoration: the header scrolls *behind* this bar, and
 * since the title uses the same measured ink as the bar's glyphs, the title sliding past would swallow
 * them.
 */
@Composable
private fun TopBar(
    onCover: Color,
    scrimTint: Color,
    listState: LazyListState,
    solidThresholdPx: Float,
    onBack: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette

    /**
     * How opaque the bar is: 0 while the header's fade is still clear of it (everything behind is artwork
     * carrying the measured ink), reaching 1 once the fade has travelled [TopBarSurfaceRamp] past that
     * point, by which stage what is under the bar is the page background.
     */
    val surface by remember(listState, solidThresholdPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset - solidThresholdPx)
                    .div(TopBarSurfaceRampPx)
                    .coerceIn(0f, 1f)
            }
        }
    }
    val ink = lerp(onCover, palette.label, surface)

    // The surface spans the *whole* bar including the status bar inset, and is followed by a short fade
    // band rather than a hairline.
    //
    // An earlier version padded the outer Column for the inset, which left the top ~24dp of the bar
    // unfilled: once the bar went solid, the status strip above it stayed a different colour and read
    // as a separate white band sitting on top of the app. Insets belong inside the filled area, not
    // around it.
    //
    // The band replaces a 0.5dp `separator` line under the bar. Two reasons it had to go: it read as a
    // hard dark rule across the page (the user reported it as a black line), and a one-pixel rule is the
    // wrong tool for this edge anyway — what belongs where content slides under floating chrome is a
    // short gradient, so a row dissolves under the bar instead of being sliced by a line.
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().background(palette.background.copy(alpha = surface))) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            // Both stops are the same colour; only the alpha differs. Fading to
                            // `Color.Transparent` instead would interpolate its black RGB through the
                            // middle of the scrim — the same grey-band defect the page fade documents.
                            0f to scrimTint.copy(alpha = TopBarScrim * (1f - surface)),
                            1f to scrimTint.copy(alpha = 0f),
                        ),
                    ),
            )
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(TopBarHeight)) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BarIconButton(SfIcons.ChevronDown, "返回", ink, onBack)
                        Spacer(Modifier.weight(1f))
                        BarIconButton(SfIcons.SquareAndArrowUp, "分享", ink, onShare)
                    }
                }
            }
        }

        // The scroll edge: rows fade out as they pass under the bar instead of being cut off by it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(TopBarEdgeFade)
                .background(
                    Brush.verticalGradient(
                        0f to palette.background.copy(alpha = surface),
                        1f to palette.background.copy(alpha = 0f),
                    ),
                ),
        )
    }
}

/**
 * Translucent circular bar/hero control.
 *
 * The fill and the ink both come from the measured cover, so the button is legible over the photo
 * rather than assuming the theme's own contrast.
 */
@Composable
private fun BarIconButton(
    icon: ImageVector,
    description: String,
    ink: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            // 44dp for a comfortable touch target; the glyph stays 19dp.
            .size(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(ink.copy(alpha = 0.16f))
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = ink, modifier = Modifier.size(19.dp))
    }
}

/** "N 首歌曲" section header above the list. */
@Composable
private fun CountRow(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = "$count 首歌曲",
        fontFamily = SFPro,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = AppTheme.palette.label,
        modifier = modifier,
    )
}

/**
 * One playlist row: position, artwork, title and artist, duration.
 *
 * The number doubles as the now-playing marker — replaced by a speaker glyph while the track is
 * actually playing, and tinted with the accent while it is merely the loaded one. That is what
 * makes the current row findable in a 500-song list without adding a coloured strip down the side.
 */
@Composable
private fun CollectionTrackRow(
    index: Int,
    track: Track,
    loader: ArtworkLoader,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val palette = AppTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = PageInset, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(IndexWidth), contentAlignment = Alignment.CenterStart) {
            if (isPlaying) {
                Icon(
                    imageVector = SfIcons.SpeakerWave,
                    contentDescription = "正在播放",
                    tint = palette.accent,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text(
                    text = "${index + 1}",
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = if (isCurrent) palette.accent else palette.tertiaryLabel,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Artwork(
            url = track.coverUrl,
            loader = loader,
            corner = 10.dp,
            requestSize = 300,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = track.name,
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = if (isCurrent) palette.accent else palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistLine,
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.durationMs > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatDuration(track.durationMs),
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = palette.tertiaryLabel,
                maxLines = 1,
            )
        }
    }
}

/**
 * "N 首 · 播放 X" for the header.
 *
 * Built from whatever is actually known: the loaded list is authoritative for the count, the
 * playlist's own tally is the stand-in while it loads, and a chart has no play count at all so the
 * segment is dropped rather than shown as a zero.
 */
private fun metaLine(state: CollectionUiState): String {
    val count = if (state.tracks.isNotEmpty()) "${state.tracks.size} 首" else state.subtitle
    val plays = state.playCount.takeIf { it > 0 }?.let { "播放 ${formatCount(it)}" }
    return listOfNotNull(count?.takeIf { it.isNotBlank() }, plays).joinToString(" · ")
}

/**
 * Mean perceptual lightness of [bitmap], 0..1, sampled on a coarse grid.
 *
 * Deliberately not a `Palette` extraction: only the light/dark decision is needed, and a 24-step
 * grid gives it without pulling in quantisation or a background thread.
 */
private fun meanLuma(bitmap: Bitmap): Float {
    val step = (minOf(bitmap.width, bitmap.height) / 24).coerceAtLeast(1)
    var sum = 0.0
    var samples = 0
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val color = bitmap.getPixel(x, y)
            val r = (color shr 16 and 0xFF) / 255.0
            val g = (color shr 8 and 0xFF) / 255.0
            val b = (color and 0xFF) / 255.0
            sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            samples++
            x += step
        }
        y += step
    }
    return if (samples == 0) 0f else (sum / samples).toFloat()
}

/** Horizontal inset shared by the hero, the title and the rows, so the column lines up. */
private val PageInset = 20.dp

/** Cover width as a fraction of the page, so the sharp artwork scales with the screen. */
private const val CoverWidthFraction = 0.56f

/** Height of the pinned bar's button row, excluding the status bar inset. */
private val TopBarHeight = 44.dp

/**
 * How far the scroll has to travel past the threshold for the bar's surface to reach full opacity.
 *
 * The surface tracks the scroll rather than a timer (see [TopBar]), and this is the distance over which it
 * fades in: about the bar's own height, which is long enough to read as a transition and short enough that
 * a quick flick is not left with a half-transparent bar.
 */
private val TopBarSurfaceRamp = 44.dp

/** The ramp in pixels, for the scroll-offset arithmetic. */
private val TopBarSurfaceRampPx = TopBarSurfaceRamp.value

/**
 * Height of the fade at the bar's lower edge.
 *
 * Replaces the 0.5dp separator that used to sit there: a gradient dissolves the row passing underneath,
 * where a hairline sliced it. Short, because it only has to cover the few dp in which a row's top edge
 * would otherwise meet the bar's fill.
 */
private val TopBarEdgeFade = 12.dp

/** Clearance between the bottom of that row and the first thing in the hero (the cover). */
private val BarClearance = 6.dp

/** Peak alpha of the bar's scrim while it floats over artwork. */
private const val TopBarScrim = 0.34f

/**
 * Height of the mask that dissolves the artwork into the page background.
 *
 * This is the whole transition between the cover area and the list, so it doubles as the gap the
 * user sees under the action buttons — which is why it is not larger. It is a real gradient at this
 * size (144px on a 3x screen) and, because the curve is eased rather than linear, it reads as the
 * cover dissolving rather than as a band with two edges.
 */
private val PageFade = 48.dp

/** Clearance between the action pills and the start of the fade. */
private val PageFadeMargin = 8.dp

/**
 * Stops in the fade. More than two is what makes the curve possible: a gradient interpolates
 * straight between adjacent stops, so the ease is expressed by sampling it.
 */
private const val PageFadeSteps = 9

/**
 * Stops for a fully transparent → [color] fade.
 *
 * Every stop carries [color]'s own RGB with only the alpha varying. Ramping `Color.Transparent`
 * instead would interpolate black through the middle of the gradient, which darkens it well below
 * either end and paints a grey band across the transition.
 */
private fun pageFadeStops(color: Color): Array<Pair<Float, Color>> =
    Array(PageFadeSteps) { index ->
        val t = index / (PageFadeSteps - 1).toFloat()
        t to color.copy(alpha = smootherStep(t))
    }

/**
 * `6t⁵ − 15t⁴ + 10t³`.
 *
 * Its first and second derivatives are zero at both ends, so the ramp leaves the flat cover colour
 * and arrives at the flat page background without a slope discontinuity. A linear ramp has one at
 * each end, and the visual system exaggerates it into a line — the Mach band, which is what keeps a
 * colour-correct linear fade looking like a band anyway.
 */
private fun smootherStep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

/** Width reserved for the row position / now-playing marker. Fits three digits. */
private val IndexWidth = 30.dp
