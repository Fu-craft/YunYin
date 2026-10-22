package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.net.DiscoveryPlaylist
import com.yunyin.music.ui.PlaylistSquareUiState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.components.GlassTopBar
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

/**
 * 歌单广场's pure layout rules.
 *
 * The screen itself is Compose, but the decisions that a screenshot cannot check are pure: how many
 * columns a given width should get, which category a fresh page starts on, and what a card's second
 * line should say. Kept here (and unit-tested) so the grid does not silently stretch a two-column
 * layout across a tablet, and so "no description" does not render an empty line.
 */
internal object PlaylistSquareLayoutRules {

    /** Catalogue default, from the data rules so the view model and the screen cannot drift. */
    const val DEFAULT_CATEGORY = com.yunyin.music.data.net.PlaylistSquareRules.DEFAULT_CATEGORY

    /**
     * Width at which the grid earns a third column.
     *
     * 600 dp is the app's tablet/landscape boundary elsewhere too; below it, three columns would put
     * the title and play-count badge under ~100 dp each and force every title to two clipped lines.
     */
    private const val THREE_COLUMN_MIN_WIDTH_DP = 600f

    fun columnsForWidthDp(widthDp: Float): Int =
        if (widthDp >= THREE_COLUMN_MIN_WIDTH_DP) 3 else 2

    /** Page gutter and grid rhythm, all on the app's 4/8 dp scale. */
    const val PAGE_PADDING_DP = 20f
    const val GRID_GAP_DP = 16f
    const val GRID_VERTICAL_GAP_DP = 20f

    /**
     * Widest the grid is allowed to grow.
     *
     * On a tablet the columns still fill a readable card size instead of stretching to huge tiles;
     * the extra width becomes symmetric page inset.
     */
    const val MAX_CONTENT_WIDTH_DP = 900f

    /** A chip's visual capsule is 32 dp; its touch target is the row's 44 dp. */
    const val CHIP_TOUCH_HEIGHT_DP = 44f

    /** Card type metrics. These are the line heights the reserved space is computed from. */
    const val TITLE_LINE_HEIGHT_DP = 20f
    const val TITLE_MAX_LINES = 2
    const val META_LINE_HEIGHT_DP = 16f

    /**
     * Minimum height of the title block: the tallest allowed title (two lines) at its real line
     * height. Applied as `heightIn(min = …)`, never as a fixed height, so a two-line title is not
     * clamped and a one-line title still reserves the same tile height.
     */
    fun titleMinHeightDp(): Float = TITLE_LINE_HEIGHT_DP * TITLE_MAX_LINES

    /** Minimum height of the secondary line: one real line of the 11 pt meta text. */
    fun metaMinHeightDp(): Float = META_LINE_HEIGHT_DP

    /** Horizontal inset that caps the grid at [MAX_CONTENT_WIDTH_DP] and never drops below 20 dp. */
    fun horizontalPaddingForWidthDp(widthDp: Float): Float =
        if (widthDp <= MAX_CONTENT_WIDTH_DP) PAGE_PADDING_DP
        else ((widthDp - MAX_CONTENT_WIDTH_DP) / 2f).coerceAtLeast(PAGE_PADDING_DP)

    /** The category a fresh page selects. Single-sourced in the data rules. */
    fun preferredCategory(categories: List<String>): String =
        com.yunyin.music.data.net.PlaylistSquareRules.preferredCategory(categories)

    /** Chips always show something, even before the (cached) category list has arrived. */
    fun visibleCategories(categories: List<String>): List<String> =
        com.yunyin.music.data.net.PlaylistSquareRules.visibleCategories(categories)

    /**
     * The card's second line: the curator's own blurb when the catalogue has one, otherwise the
     * track count. Empty string (not null) when neither exists, so the caller can drop the line.
     */
    fun metaLabel(description: String?, trackCount: Int): String {
        val curator = description
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        if (curator != null) return curator
        return if (trackCount > 0) "$trackCount 首" else ""
    }

    /** Play-count badge text; empty when the count is unknown, so no "0" badge is ever drawn. */
    fun playCountLabel(playCount: Long): String = if (playCount > 0) formatCount(playCount) else ""
}

/**
 * 歌单广场 — the fourth bottom-bar tab, a full-screen catalogue browse.
 *
 * Design (following the `ui-ux-pro-max` guidance for a dark, content-led music product, adapted to
 * this app's own language):
 *
 *  - **A grid, not a list.** The skill's guidance for a discovery surface is a card grid with one
 *    message per card; a cover grid is also the shape of the product (playlists are square artwork).
 *    Two columns on phones, three at ≥ 600 dp so tablets do not stretch a phone layout.
 *  - **One strong title, then categories.** A 34 pt bold in-content title gives the page a single
 *    entry point; the chips are a secondary, horizontal filter that never competes with it. The top
 *    strip therefore stays icon-only (refresh) instead of repeating the title — the skill's
 *    hierarchy rule, and the same split Home and Library already use.
 *  - **Card hierarchy**: cover (16 dp continuous corners) → two-line title → one muted line
 *    (curator blurb or track count). The play count rides on the cover as a small dark chip, so the
 *    eye gets the social signal without a third text line.
 *  - **App language, not the skill's palette.** The skill's suggested indigo/green dark palette and
 *    Poppins/Righteous type would be a second design system in the same app: this project reads
 *    [AppTheme.palette]'s semantic colours (label / secondaryLabel / secondaryBackground / accent
 *    from the album accent) and SF Pro everywhere. Contrast stays ≥ 4.5:1 through those tokens.
 *  - **States**: skeleton cards while the first page is loading (static, no shimmer — the app has no
 *    shimmer system and a new one would be motion the page does not need), a per-category empty
 *    state, an error state with retry, and a footer that distinguishes loading-more from the end.
 */
@Composable
internal fun PlaylistSquareScreen(
    state: PlaylistSquareUiState,
    loader: ArtworkLoader,
    onCategorySelected: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onPlaylistClick: (DiscoveryPlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = rememberGlassTopBarBackdrop()
    val categories = remember(state.categories) {
        PlaylistSquareLayoutRules.visibleCategories(state.categories)
    }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = PlaylistSquareLayoutRules.columnsForWidthDp(maxWidth.value)
        val pagePadding = PlaylistSquareLayoutRules.horizontalPaddingForWidthDp(maxWidth.value).dp
        val gridBottomPadding = chromeContentBottomPadding()
        val gridTopPadding = glassTopBarTopPadding()

        // Content is recorded into the backdrop layer; the top strip is composed outside it, so it
        // samples the grid scrolling underneath (the same arrangement as Home and Collection).
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = pagePadding,
                    end = pagePadding,
                    top = gridTopPadding,
                    bottom = gridBottomPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(PlaylistSquareLayoutRules.GRID_GAP_DP.dp),
                verticalArrangement = Arrangement.spacedBy(PlaylistSquareLayoutRules.GRID_VERTICAL_GAP_DP.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "square-title") {
                    // A little more air above the title than below it: the strip above already
                    // supplies its own 48 dp row, so 8 dp reads as the first content line, not a
                    // continuation of the bar.
                    Column(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
                        Text(
                            text = "歌单广场",
                            fontFamily = SFPro,
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                            color = AppTheme.palette.label,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "按分类发现歌单",
                            fontFamily = SFPro,
                            fontSize = 13.sp,
                            color = AppTheme.palette.secondaryLabel,
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "square-chips") {
                    if (state.categoriesLoading && state.categories.isEmpty()) {
                        ChipSkeletonRow()
                    } else {
                        CategoryChips(
                            categories = categories,
                            selected = state.selected,
                            onSelected = onCategorySelected,
                        )
                    }
                }

                if (state.playlists.isEmpty() && !state.loaded) {
                    // First page in flight (or about to be requested on tab entry): skeletons, never
                    // a flash of the empty state.
                    items(columns * 3, key = { "skeleton-$it" }) {
                        SkeletonCard()
                    }
                } else if (state.playlists.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "square-empty") {
                        if (state.error != null) {
                            SquareError(text = state.error, onRetry = onRefresh)
                        } else {
                            EmptyState(text = "这个分类还没有歌单")
                        }
                    }
                } else {
                    gridItems(state.playlists, key = { "playlist-${it.id}" }) { playlist ->
                        PlaylistSquareCard(
                            playlist = playlist,
                            loader = loader,
                            onClick = { onPlaylistClick(playlist) },
                            // Appended rows fade in on the app's token; placement stays instant so
                            // paging never slides the grid under the finger.
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(Motion.ItemAppearDuration),
                                fadeOutSpec = null,
                                placementSpec = null,
                            ),
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "square-footer") {
                        SquareFooter(
                            loadingMore = state.loadingMore,
                            more = state.more,
                            error = state.error,
                            onLoadMore = onLoadMore,
                            // A footer error can only be a failed append now (the first page uses
                            // SquareError above), so retrying it continues from where it stopped.
                            onRetry = onLoadMore,
                        )
                    }
                }
            }
        }

        GlassTopBar(
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                if (state.loading || state.categoriesLoading) {
                    CircularProgressIndicator(
                        color = AppTheme.palette.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                SquareIconButton(
                    icon = SfIcons.ArrowClockwise,
                    description = "刷新歌单广场",
                    backdrop = backdrop,
                    onClick = onRefresh,
                )
            }
        }
    }
}

/**
 * Horizontal, selectable category chips.
 *
 * The visual capsule stays 32 dp (the app's chip idiom), but the tappable row is 44 dp: the target is
 * the box around the capsule, so the filter is comfortable without looking heavier. A soft edge fade
 * appears only while the strip can still scroll that way, which is the affordance for the ~70
 * categories — grouping them would add a level of navigation to a one-tap filter.
 */
@Composable
private fun CategoryChips(
    categories: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val canScrollBack by remember { derivedStateOf { listState.canScrollBackward } }
    val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }
    Box(Modifier.fillMaxWidth()) {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // Extra end room so the last chip can clear the fade instead of sitting under it.
            contentPadding = PaddingValues(end = 32.dp),
        ) {
            items(categories, key = { it }) { category ->
                val isSelected = category == selected
                Box(
                    Modifier
                        .height(PlaylistSquareLayoutRules.CHIP_TOUCH_HEIGHT_DP.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                        .clickable(
                            indication = rememberControlRipple(bounded = true),
                            interactionSource = null,
                            onClick = { onSelected(category) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = category,
                        fontFamily = SFPro,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 13.sp,
                        color = if (isSelected) Color.White else AppTheme.palette.secondaryLabel,
                        modifier = Modifier
                            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                            .background(
                                if (isSelected) {
                                    AppTheme.palette.accent
                                } else {
                                    AppTheme.palette.secondaryBackground
                                },
                            )
                            // 32 dp visual height inside the 44 dp target.
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
        ChipEdgeFade(visible = canScrollBack, atStart = true)
        ChipEdgeFade(visible = canScrollForward, atStart = false)
    }
}

/** Page-coloured fade over the chip strip, so "more categories" reads without a rail or divider. */
@Composable
private fun BoxScope.ChipEdgeFade(visible: Boolean, atStart: Boolean) {
    if (!visible) return
    val background = AppTheme.palette.background
    val stops = if (atStart) {
        listOf(background, background.copy(alpha = 0f))
    } else {
        listOf(background.copy(alpha = 0f), background)
    }
    Box(
        Modifier
            .align(if (atStart) Alignment.CenterStart else Alignment.CenterEnd)
            .width(32.dp)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    colors = stops,
                    startX = 0f,
                    endX = Float.POSITIVE_INFINITY,
                ),
            ),
    )
}

/** One playlist tile: cover, title and one muted line, with the play count riding the cover. */
@Composable
private fun PlaylistSquareCard(
    playlist: DiscoveryPlaylist,
    loader: ArtworkLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = PlaylistSquareLayoutRules.metaLabel(playlist.description, playlist.trackCount)
    val badge = PlaylistSquareLayoutRules.playCountLabel(playlist.playCount)
    Column(
        modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.card))
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            ),
    ) {
        Box {
            Artwork(
                url = playlist.coverUrl,
                loader = loader,
                corner = AppleShapes.card,
                requestSize = 600,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (badge.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                        // 62 % black is the measured minimum that keeps 11 pt white legible on the
                        // brightest covers the catalogue ships, while still showing the cover through.
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        SfIcons.Play,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = badge,
                        fontFamily = SFPro,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = PlaylistSquareLayoutRules.TITLE_LINE_HEIGHT_DP.sp,
            color = AppTheme.palette.label,
            // Equal-height grid: the height is reserved through heightIn (minimum only), so one-line
            // and two-line titles produce the same tile height without clamping the text itself.
            modifier = Modifier.heightIn(min = PlaylistSquareLayoutRules.titleMinHeightDp().dp),
            maxLines = PlaylistSquareLayoutRules.TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        // Same rule for the secondary line: one real 16 dp line is reserved, the text keeps its
        // natural height (an 11 pt label fits comfortably inside 16 dp), and an empty catalogue
        // blurb still leaves the tile the same height. No fixed-height parent ever clips glyphs.
        Text(
            text = meta,
            fontFamily = SFPro,
            fontSize = 11.sp,
            lineHeight = PlaylistSquareLayoutRules.META_LINE_HEIGHT_DP.sp,
            color = AppTheme.palette.secondaryLabel,
            modifier = Modifier.heightIn(min = PlaylistSquareLayoutRules.metaMinHeightDp().dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Footer: loading-more, a 加载更多 button, or the end of the catalogue. */
@Composable
private fun SquareFooter(
    loadingMore: Boolean,
    more: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = AppTheme.palette.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "加载更多…",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
            }

            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error,
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
                Spacer(Modifier.height(6.dp))
                SquareRetryButton(text = "重试", onClick = onRetry)
            }

            more -> Text(
                text = "加载更多",
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = AppTheme.palette.accent,
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(AppTheme.palette.secondaryBackground)
                    .clickable(
                        indication = rememberControlRipple(bounded = true),
                        interactionSource = null,
                        onClick = onLoadMore,
                    )
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            )

            else -> Text(
                text = "没有更多歌单",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.tertiaryLabel,
            )
        }
    }
}

@Composable
private fun SquareError(text: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EmptyState(text = text, icon = SfIcons.WifiSlash)
        SquareRetryButton(text = "重新加载", onClick = onRetry)
    }
}

@Composable
private fun SquareRetryButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = Color.White,
        modifier = Modifier
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.accent)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

/** Small glass disc on the top strip, matching Collection's bar controls. */
@Composable
private fun SquareIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    backdrop: com.kyant.backdrop.Backdrop,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .liquidGlassControl(backdrop, CircleShape)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = AppTheme.palette.label, modifier = Modifier.size(19.dp))
    }
}

/** Static skeleton: no shimmer, because the app owns no shimmer system and this page needs none. */
@Composable
private fun SkeletonCard() {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(ContinuousRoundedRectangle(AppleShapes.card))
                .background(AppTheme.palette.secondaryBackground),
        )
        // Mirrors the real card: 8 dp gap, two 20 dp title lines (bars 16 + 8 gap = 40), 3 dp gap,
        // 14 dp secondary row. The swap from skeleton to data therefore does not change row height.
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .height(16.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.control))
                .background(AppTheme.palette.secondaryBackground),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.55f)
                .height(16.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.control))
                .background(AppTheme.palette.secondaryBackground),
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth(0.42f)
                .height(PlaylistSquareLayoutRules.META_LINE_HEIGHT_DP.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.control))
                .background(AppTheme.palette.secondaryBackground),
        )
    }
}

@Composable
private fun ChipSkeletonRow() {
    Row(
        Modifier.height(PlaylistSquareLayoutRules.CHIP_TOUCH_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(52.dp, 64.dp, 44.dp, 72.dp, 56.dp).forEach { width ->
            Box(
                Modifier
                    .width(width)
                    .height(32.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(AppTheme.palette.secondaryBackground),
            )
        }
    }
}
