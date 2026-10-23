package com.yunyin.music.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
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
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.SheetPlacement
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.CollectionUiState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.DefaultTopBarTintIntensity
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.components.ProminentButton
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.formatCount
import com.yunyin.music.ui.components.formatDuration
import com.yunyin.music.ui.components.liquidGlassControl
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.beat.LocalBeatModeActions
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
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
    /** Enqueues the whole list for download, skipping tracks already downloaded. */
    onDownloadAll: (() -> Unit)? = null,
    /** Live download states, so a row can show its own progress while the centre is closed. */
    downloadStates: Map<Long, com.yunyin.music.data.download.TrackDownloadState> = emptyMap(),
    /** Owned-playlist actions are in this sheet, not beside the play button: two controls only. */
    onEditPlaylist: (() -> Unit)? = null,
    /** Present only when owned; host confirms then deletes and closes the page. */
    onDeletePlaylist: (() -> Unit)? = null,
    /** Present on owned playlists and the liked list; the host confirms and removes by collection kind. */
    onRemoveTrack: ((Track) -> Unit)? = null,
    subscribed: Boolean = false,
    onToggleSubscribe: (() -> Unit)? = null,
    subscribeAvailable: Boolean = false,
    /** 下一首播放 for a row; null hides the entry. */
    onPlayNext: ((Track) -> Unit)? = null,
    /** Manual refresh: still used by the first-page error state's retry. */
    onRefresh: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    // The per-row ⋯ menu: which track's actions are open, or null when no row menu is showing.
    var trackMenu by remember { mutableStateOf<Track?>(null) }
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

    // The frosted top bar's pixel source: the page content recorded with the page background painted
    // in first. The bar is composed outside this layer (see [TopBar]), so it samples the artwork and
    // rows scrolling under it rather than itself.
    val topBarBackdrop = rememberGlassTopBarBackdrop()

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
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
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
                    onMoreClick = { showMenu = true },
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
            } else if (state.tracks.isEmpty() && state.error != null) {
                item("error") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            SfIcons.WifiSlash,
                            contentDescription = null,
                            tint = AppTheme.palette.tertiaryLabel,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            "歌单加载失败",
                            fontFamily = SFPro,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            color = AppTheme.palette.label,
                        )
                        Text(
                            state.error,
                            fontFamily = SFPro,
                            fontSize = 13.sp,
                            color = AppTheme.palette.secondaryLabel,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(2.dp))
                        ProminentButton(text = "重新加载", onClick = { onRefresh?.invoke() })
                    }
                }
            } else if (state.tracks.isEmpty()) {
                item("empty") { EmptyState("这个歌单还没有歌曲") }
            } else {
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
                        onMore = { trackMenu = track },
                        downloadState = downloadStates[track.id],
                    )
                }
            }

            item("bottom") { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
            }
        }

        // Pinned last, outside the recorded layer, so it draws over the scrolling artwork and
        // samples it instead of sampling itself.
        TopBar(
            backdrop = topBarBackdrop,
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

        // The single secondary control: one sheet holds shuffle/share/edit/delete instead of four buttons.
        // The destructive row is last and coloured differently; ownership still gates whether it exists.
        if (showMenu) {
            AnimatedSheet(
                onDismissed = { showMenu = false },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
                placement = SheetPlacement.Bottom,
                scrim = true,
                sheetModifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                    .background(AppTheme.palette.background)
                    .padding(vertical = 6.dp),
            ) { close ->
                CollectionMenuRow(icon = SfIcons.Shuffle, label = "随机播放") { close { onShufflePlay() } }
                // 心动模式 needs a real playlist id. The host provides the action; a guest sees 请先登录
                // here immediately (the cookie is known without a round trip) and the row is inert.
                val beatModeActions = LocalBeatModeActions.current
                if (beatModeActions != null && state.canBeatMode) {
                    CollectionMenuRow(
                        icon = SfIcons.HeartFill,
                        label = if (beatModeActions.signedIn) "心动模式" else "心动模式（请先登录）",
                        enabled = beatModeActions.signedIn,
                    ) { close { beatModeActions.start(state.id) } }
                }
                CollectionMenuRow(SfIcons.SquareAndArrowUp, "分享歌单") { close { onShare() } }
                if (onDownloadAll != null && state.tracks.isNotEmpty()) {
                    CollectionMenuRow(SfIcons.ArrowDownCircle, "下载全部") { close { onDownloadAll() } }
                }
                // Photo-album and playlist pages share this sheet; the label states the action for the
                // current state and the row is simply absent without a real session (guest gate).
                if (onToggleSubscribe != null) {
                    CollectionMenuRow(
                        icon = SfIcons.StarFill,
                        label = CollectionSubscribeRules.label(state.subscribed),
                    ) { close { onToggleSubscribe() } }
                }
                if (onEditPlaylist != null) {
                    CollectionMenuRow(SfIcons.Pencil, "编辑歌单") { close { onEditPlaylist() } }
                }
                if (onDeletePlaylist != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.palette.separator))
                    CollectionMenuRow(SfIcons.Trash, "删除歌单", destructive = true) { close { onDeletePlaylist() } }
                }
            }
        }

        // Per-row actions. A compact bottom sheet, not an anchored popup: this codebase's menu idiom is
        // AnimatedSheet (the player's ⋯ and this page's own menu both use it), and the project avoids
        // the platform DropdownMenu because it cannot match the app's motion/material language.
        trackMenu?.let { track ->
            AnimatedSheet(
                onDismissed = { trackMenu = null },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
                placement = SheetPlacement.Bottom,
                scrim = true,
                sheetModifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                    .background(AppTheme.palette.background)
                    .padding(vertical = 6.dp),
            ) { close ->
                if (onPlayNext != null) {
                    CollectionMenuRow(SfIcons.Forward, "下一首播放") { close { onPlayNext(track) } }
                }
                if (onRemoveTrack != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.palette.separator))
                    // Same host confirmation as before: the menu only opens it.
                    CollectionMenuRow(SfIcons.Trash, "移除音乐", destructive = true) {
                        close { onRemoveTrack(track) }
                    }
                }
            }
        }
    }
}

/** One row of the collection 更多 sheet; `destructive` is the red delete entry, shown last. */
@Composable
private fun CollectionMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (destructive) AppTheme.palette.accent else AppTheme.palette.label
    val alpha = if (enabled) 1f else 0.35f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontFamily = SFPro, fontSize = 16.sp, color = tint.copy(alpha = alpha))
    }
}

/**
 * The cover-derived colour field behind a hero header: blurred artwork, a tone guard, and the fade into
 * the page background.
 *
 * Shared by the playlist and the artist page so the two headers are the *same* surface rather than two
 * implementations that drift apart. Every part here is load-bearing, and each was a visible artefact
 * before:
 *
 *  - **Overscan then blur.** The image is pushed through a 1.5x scale so the blur's soft edge lands
 *    outside the clip instead of showing as a lighter rim around the header.
 *  - **A tone guard.** Mid-grey artwork is the one case where neither ink colour has contrast, so the
 *    field is pushed toward whichever end of the scale the ink is at: extra depth under white ink,
 *    extra lift under dark ink.
 *  - **The fade's transparent end is the page colour at zero alpha, not `Color.Transparent`.**
 *    `Color.Transparent` is black with no alpha, and Compose interpolates the RGB channels as well as
 *    alpha — so a "transparent → page background" ramp spends its middle at roughly 50% of a
 *    *black-tinted* colour. Composited over the field that is darker than either end, which paints a
 *    grey band across the fade. Most of what read as a hard edge was this, not the gradient's shape.
 *  - **The curve is eased** (see [pageFadeStops]). A linear ramp arrives and leaves with a slope
 *    discontinuity — one where the flat cover colour above it stops being flat, one where the list's
 *    flat background below it begins — and the eye draws a line at each. That is the Mach band, and it
 *    is why a colour-correct linear fade still shows a seam.
 *  - **It is anchored to the bottom rather than stretched over the whole header.** Only the last
 *    [PageFade] is a gradient; above that the field is untouched cover colour, so the fade cannot wash
 *    out the artwork.
 */
@Composable
internal fun CoverField(
    backdrop: ImageBitmap?,
    coverIsDark: Boolean,
    pageBackground: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clipToBounds()) {
        if (backdrop != null) {
            Image(
                bitmap = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 1.5f
                        scaleY = 1.5f
                    }
                    .blur(56.dp),
            )
        } else {
            Box(Modifier.matchParentSize().background(AppTheme.palette.secondaryBackground))
        }

        Box(
            Modifier
                .matchParentSize()
                .background(
                    if (coverIsDark) Color.Black.copy(alpha = 0.24f)
                    else Color.White.copy(alpha = 0.34f),
                ),
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(PageFade)
                .background(Brush.verticalGradient(colorStops = pageFadeStops(pageBackground))),
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
    /** Opens the header's 更多 sheet: 随机播放 / 分享 / 编辑 / 删除. */
    onMoreClick: () -> Unit,
    /** Reports the measured height so the pinned bar can time its surface to the ramp. */
    onHeightChanged: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) },
    ) {
        // ---------------------------------------------------------------- colour field
        CoverField(
            backdrop = backdrop,
            coverIsDark = coverIsDark,
            pageBackground = AppTheme.palette.background,
            modifier = Modifier.matchParentSize(),
        )

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
                // Two controls only: play all leads (solid fill) and 更多 (glass). 收藏 lives inside
                // 更多 — a third equal-weight pill made the header read as a button bar rather than a
                // pair of actions (skill: hierarchy / one primary per band).
                HeaderAction(
                    icon = SfIcons.Play,
                    label = "播放全部",
                    fill = onCover,
                    content = inverseOnCover,
                    onClick = onPlayAll,
                    modifier = Modifier.weight(1f),
                )
                HeaderAction(
                    icon = SfIcons.Ellipsis,
                    label = "更多",
                    fill = onCover.copy(alpha = 0.16f),
                    content = onCover,
                    border = onCover.copy(alpha = 0.22f),
                    onClick = onMoreClick,
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

/** One of the two header pills. Shared with the artist page so both heroes act identically. */
@Composable
internal fun HeaderAction(
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
internal fun TopBar(
    backdrop: Backdrop,
    onCover: Color,
    scrimTint: Color,
    listState: LazyListState,
    solidThresholdPx: Float,
    onBack: () -> Unit,
    onShare: (() -> Unit)?,
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

    // The bar is the app's one progressive-blur top strip ([GlassTopBar]): the recorded page layer is
    // blurred under a colour gradient, and the gradient's density is what used to be the opaque
    // surface — 0 over artwork (pure progressive blur, which is the iOS look over a cover), ramping to
    // [DefaultTopBarTintIntensity] as the page background arrives under it.
    //
    // The strip spans the *whole* bar including the status bar inset, and its bottom edge eases to
    // transparent by itself (the shader's alpha mask), so it needs no separate fade band: the old
    // 12dp gradient under an opaque surface would now paint a page-coloured band over the very rows
    // the strip is meant to dissolve into.
    Column(modifier.fillMaxWidth()) {
        GlassTopBar(
            backdrop = backdrop,
            tintIntensity = DefaultTopBarTintIntensity * surface,
            contentHeight = TopBarHeight,
            overlay = {
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
            },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIconButton(SfIcons.ChevronDown, "返回", ink, onBack, backdrop, scrimTint)
                Spacer(Modifier.weight(1f))
                // Absent on pages with nothing worth sharing (the artist page), where an inert
                // button would be worse than the asymmetry.
                if (onShare != null) {
                    BarIconButton(SfIcons.SquareAndArrowUp, "分享", ink, onShare, backdrop, scrimTint)
                }
            }
        }
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
    backdrop: Backdrop,
    /**
     * Opaque glass tint. The bar passes the opposite of its measured ink, so the disc always sits
     * between the glyph and whatever artwork is behind it — the same contrast rule the scrim uses.
     */
    glassTint: Color,
) {
    Box(
        Modifier
            // 44dp for a comfortable touch target; the glyph stays 19dp. The glass disc is the whole
            // 44dp target, so adding the material never shrinks the tap area.
            .size(44.dp)
            .liquidGlassControl(backdrop, CircleShape, tint = glassTint)
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

/**
 * One playlist row: position, artwork, title and artist, duration.
 *
 * The number doubles as the now-playing marker — replaced by a speaker glyph while the track is
 * actually playing, and tinted with the accent while it is merely the loaded one. That is what
 * makes the current row findable in a 500-song list without adding a coloured strip down the side.
 */
@Composable
internal fun CollectionTrackRow(
    index: Int,
    track: Track,
    loader: ArtworkLoader,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    /** Row ⋯ affordance: opens the per-track actions menu. Null keeps the row without one. */
    onMore: (() -> Unit)? = null,
    /** Active/failed download state, shown inline so a list row can report progress. */
    downloadState: com.yunyin.music.data.download.TrackDownloadState? = null,
    /** Item-level modifier (e.g. `animateItem` in a lazy list); default keeps every existing call site unchanged. */
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    Row(
        modifier
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
        if (downloadState != null && downloadState.phase != com.yunyin.music.data.download.DownloadPhase.IDLE) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = downloadState.statusLabel(),
                fontFamily = SFPro,
                fontSize = 11.sp,
                color = if (downloadState.phase == com.yunyin.music.data.download.DownloadPhase.FAILED) palette.accent else palette.secondaryLabel,
                maxLines = 1,
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
        if (onMore != null) {
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(
                        indication = rememberControlRipple(bounded = false),
                        interactionSource = null,
                        onClick = onMore,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SfIcons.Ellipsis,
                    contentDescription = "更多操作",
                    tint = palette.secondaryLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
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
internal fun meanLuma(bitmap: Bitmap): Float {
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
internal val PageInset = 20.dp

/** Cover width as a fraction of the page, so the sharp artwork scales with the screen. */
private const val CoverWidthFraction = 0.56f

/** Height of the pinned bar's button row, excluding the status bar inset. */
internal val TopBarHeight = 44.dp

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

/** Clearance between the bottom of that row and the first thing in the hero (the cover). */
internal val BarClearance = 6.dp

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
internal val PageFade = 48.dp

/** Clearance between the action pills and the start of the fade. */
internal val PageFadeMargin = 8.dp

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
internal val IndexWidth = 30.dp

/**
 * Draws an artist line where **every artist is its own tap target**.
 *
 * The player prints artists joined by `" / "`, and tapping the line used to open only the first name —
 * so a collaboration (`*Luna / ゆある / ねんね`) could not reach its second artist at all.
 *
 * The line is still drawn as one `Text`, so truncation and line-breaking are unchanged; each artist is
 * given a character-offset span and a tap is resolved through the text layout's own offset mapping.
 * The separators are simply not spanned, which makes them inert.
 *
 * Spans are located by walking the separators through the *line* rather than re-joining the names, so
 * what is drawn is exactly what the model holds. `indexOf` runs from a moving cursor, so a repeated
 * name (`A / A`) still resolves to its next occurrence. If a separator cannot be found — a name that
 * itself contains `" / "` — the per-artist spans are dropped and the whole line falls back to opening
 * the first artist, which is the behaviour this replaces rather than a dead tap target.
 *
 * While a name is held it underlines. That is the only affordance the line has, and it earns its keep:
 * without it nothing on screen says the names are tappable at all, and nothing says *which* of three
 * names is about to open.
 */
/**
 * The artist index under [position], or null when the tap missed every name (a separator, or the text
 * trailing the last name).
 */
private fun artistIndexAt(
    spans: List<Pair<IntRange, Int>>,
    layout: TextLayoutResult?,
    position: Offset,
): Int? {
    val offset = (layout ?: return null).getOffsetForPosition(position)
    return spans.firstOrNull { offset in it.first.first..it.first.last }?.second
}

@Composable
internal fun ArtistLine(
    line: String,
    artists: List<String>,
    style: TextStyle,
    maxLines: Int,
    onArtistClick: ((String) -> Unit)?,
    /**
     * Minimum height of the **tap region**, not of the line's layout.
     *
     * The line is laid out at the text's own height, so it sits tight under its title (the headers
     * that use it space it 2 dp below the track name). The pointer region is instead extended below
     * the line to this height: a one-character artist name is still comfortable to hit, but the
     * 44 dp no longer becomes blank space inside the layout. Previously the minimum *was* the layout
     * height and the 15 sp line was centred in it, adding ~13 dp of empty space above the artist
     * text on top of the header's 2 dp gap — the "歌名和歌手间隔太长" regression.
     */
    minTouchHeight: Dp = 44.dp,
) {
    // Spans are computed once per (line, artists) and shared by the drawing and the hit test. The hit
    // test deliberately does **not** use string annotations: an annotation is part of the text, so
    // underlining on press would rebuild the text and restart `pointerInput` mid-gesture, losing the tap.
    val spans: List<Pair<IntRange, Int>> = remember(line, artists) { artistSpans(line, artists) }

    // Which name is under the finger, for the underline. Separate from the text so a press never
    // changes what the gesture is attached to.
    var pressed by remember(line) { mutableIntStateOf(-1) }
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(line, spans, pressed) {
        buildAnnotatedString {
            append(line)
            spans.forEach { (range, index) ->
                if (index == pressed) {
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), range.first, range.last + 1)
                }
            }
        }
    }

    val interaction = onArtistClick
    // Layout height is the text's; the tap region is a sibling that may extend below it and costs no
    // layout space. Compose hit-tests children outside their parent's layout bounds (drawing and
    // pointer hit testing are only clipped by a `clip` modifier, which nothing here applies), so the
    // taller box is reachable without the header paying for the extra height.
    val touchHeightPx = with(LocalDensity.current) { minTouchHeight.roundToPx() }
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Text(
                text = annotated,
                style = style,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout = it },
            )
            if (interaction != null) {
                // Top-aligned with the text, so a pointer position maps to the same character offset
                // the text-attached detector used to see — the per-artist hit test is unchanged.
                Box(
                    Modifier.pointerInput(line, artists, interaction) {
                        detectTapGestures(
                            onPress = { position ->
                                pressed = artistIndexAt(spans, layout, position) ?: -1
                                tryAwaitRelease()
                                pressed = -1
                            },
                            onTap = { position ->
                                artistIndexAt(spans, layout, position)
                                    ?.let { artists.getOrNull(it) }
                                    ?.let(interaction::invoke)
                            },
                        )
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val textPlaceable = measurables[0].measure(constraints.copy(minHeight = 0))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else textPlaceable.width
        val touchPlaceable = measurables.getOrNull(1)?.measure(
            Constraints.fixed(width, maxOf(textPlaceable.height, touchHeightPx)),
        )
        layout(width, textPlaceable.height) {
            textPlaceable.placeRelative(0, 0)
            touchPlaceable?.placeRelative(0, 0)
        }
    }
}

/**
 * Maps the artists onto character ranges of [line].
 *
 * Walked forward through the separators rather than re-joined, so what is drawn is exactly what the
 * model holds. `indexOf` runs from a moving cursor, so a repeated name (`A / A`) still resolves to its
 * next occurrence. Returns one span over the whole line when the mapping does not hold — a single
 * artist, or a name that itself contains `" / "` — which falls back to opening the first artist rather
 * than leaving a dead tap target.
 */
private fun artistSpans(line: String, artists: List<String>): List<Pair<IntRange, Int>> =
    ArtistSegments.segments(line, artists).map { it.range to it.index }

