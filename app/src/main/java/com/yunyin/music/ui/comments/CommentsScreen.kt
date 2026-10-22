package com.yunyin.music.ui.comments

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Comment
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.CommentsTab
import com.yunyin.music.ui.CommentsUiState
import com.yunyin.music.ui.components.PagingAutoLoader
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.InsetSeparator
import com.yunyin.music.ui.components.ProminentButton
import com.yunyin.music.ui.components.formatCount
import com.yunyin.music.ui.components.liquidGlassControl
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Height of the glass strip's content: 48 dp title row + 8 dp gap + 44 dp segmented control. */
private val CommentsTopBarHeight = 100.dp

/** Breathing room between the bar's bottom edge and the first comment. */
private val CommentsContentTopGap = 8.dp

/**
 * Maximum content width.
 *
 * On a phone this never applies (the content is full width); on a tablet or in landscape it keeps
 * body text near a comfortable measure instead of stretching a comment across the whole screen.
 */
private val CommentsContentMaxWidth = 720.dp

/**
 * 歌曲评论 — a full-screen page (it used to be a bottom sheet).
 *
 * Structure: a [GlassTopBar] strip carrying the back disc, the track title (ellipsised) and the
 * segmented control, with the list scrolling underneath it. The segmented control therefore sticks
 * below the bar by construction — it is inside the fixed strip, not a collapsing toolbar — while the
 * rows keep the same hierarchy the 4.9.0 redesign established: content first (15 pt / 22 pt
 * leading), author + date as one compact secondary line, like count bottom-aligned with the last
 * text line, six-line clamp with an animated expand.
 *
 * Design decisions the `ui-ux-pro-max` guidance drove: a real navigation surface with a predictable
 * back action (back gesture/button returns to the player); a fixed header + content padding so the
 * strip never covers the first row; one centred content column with a maximum measure on large
 * screens; and 44 dp targets for every actionable control (back disc, segments, footer actions).
 * The skill's own palette/display type were rejected again — the app's SF Pro, `AppTheme.palette`,
 * `AppleShapes` and `Motion.*` tokens are the established language.
 *
 * Data flow is untouched: the same `/comment/hot` and `/comment/music` endpoints, the same
 * `CommentPagingRules` paging/dedupe, the same cache-bust and per-section isolation. This file is UI
 * only.
 */
@Composable
fun CommentsScreen(
    state: CommentsUiState,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onSelectTab: (CommentsTab) -> Unit,
    onLoadMore: () -> Unit,
    /** Retries the failed section only; the other section keeps whatever it already has. */
    onRetry: (CommentsTab) -> Unit,
) {
    BackHandler(onBack = onBack)

    val backdrop = rememberGlassTopBarBackdrop()
    val allListState = rememberLazyListState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Content starts below the whole fixed strip and scrolls up under its progressive blur.
    val contentTop = statusTop + CommentsTopBarHeight + CommentsContentTopGap

    Box(Modifier.fillMaxSize().background(AppTheme.palette.background)) {
        // The recorded page layer: everything that scrolls is painted into it, and the glass strip
        // is composed outside it, so the strip samples the rows rather than itself.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    // Constrain first, then fill: the other order lets fillMaxWidth fix the width to
                    // the parent before widthIn is consulted, which would ignore the max on tablets.
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = CommentsContentMaxWidth)
                        .fillMaxWidth(),
                ) {
                    if (state.tab == CommentsTab.HOT) {
                        CommentSection(
                            comments = state.hot,
                            loading = state.hotLoading,
                            error = state.hotError,
                            emptyText = "暂无热门评论",
                            loader = loader,
                            onRetry = { onRetry(CommentsTab.HOT) },
                            contentTop = contentTop,
                            contentBottom = bottomInset + 16.dp,
                            footer = null,
                        )
                    } else {
                        CommentSection(
                            comments = state.all,
                            loading = state.allLoading,
                            error = state.allError,
                            emptyText = "还没有评论",
                            loader = loader,
                            onRetry = { onRetry(CommentsTab.ALL) },
                            contentTop = contentTop,
                            contentBottom = bottomInset + 16.dp,
                            footer = {
                                AllCommentsFooter(
                                    state = state,
                                    onRetry = { onRetry(CommentsTab.ALL) },
                                )
                            },
                            listState = allListState,
                            autoLoad = onLoadMore,
                            hasMore = state.allMore,
                        )
                    }
                }
            }
        }

        GlassTopBar(
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.TopCenter),
            contentHeight = CommentsTopBarHeight,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    // Constrain first, then fill: the other order lets fillMaxWidth fix the width to
                    // the parent before widthIn is consulted, which would ignore the max on tablets.
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = CommentsContentMaxWidth)
                        .fillMaxWidth(),
                ) {
                    CommentsTitleRow(state = state, backdrop = backdrop, onBack = onBack)
                    CommentsSegmentedControl(
                        selected = state.tab,
                        onSelect = onSelectTab,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Title row: back disc, then the track name (ellipsised) with 「评论」 as its caption. */
@Composable
private fun CommentsTitleRow(
    state: CommentsUiState,
    backdrop: com.kyant.backdrop.Backdrop,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .liquidGlassControl(backdrop, CircleShape)
                .clickable(
                    indication = rememberControlRipple(bounded = true),
                    interactionSource = null,
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SfIcons.ChevronLeft,
                contentDescription = "返回",
                tint = AppTheme.palette.label,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val title = state.trackTitle.ifBlank { "评论" }
            Text(
                text = title,
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.trackTitle.isNotBlank()) {
                Text(
                    text = "评论",
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                )
            }
        }
        // Keeps the title optically centred between the disc and the screen edge.
        Spacer(Modifier.width(44.dp))
    }
}

/**
 * Two-segment control: quiet track, filled selected segment.
 *
 * The track is 44 dp tall (a comfortable tab target) and the segments are 13 pt; the selected one
 * uses the tertiary background — the same "filled capsule in a quiet track" the app uses for
 * prominent choices, so it reads as a segment rather than as two links.
 */
@Composable
private fun CommentsSegmentedControl(
    selected: CommentsTab,
    onSelect: (CommentsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(
            text = "热门评论",
            selected = selected == CommentsTab.HOT,
            onClick = { onSelect(CommentsTab.HOT) },
            modifier = Modifier.weight(1f),
        )
        Segment(
            text = "全部评论",
            selected = selected == CommentsTab.ALL,
            onClick = { onSelect(CommentsTab.ALL) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Segment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(if (selected) AppTheme.palette.tertiaryBackground else Color.Transparent)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = SFPro,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 13.sp,
            color = if (selected) AppTheme.palette.label else AppTheme.palette.secondaryLabel,
        )
    }
}

/** One section's state machine: skeletons, error+retry, empty, or the paged list. */
@Composable
private fun CommentSection(
    comments: List<Comment>,
    loading: Boolean,
    error: String?,
    emptyText: String,
    loader: ArtworkLoader,
    onRetry: () -> Unit,
    contentTop: Dp,
    contentBottom: Dp,
    footer: (@Composable () -> Unit)?,
    listState: LazyListState = rememberLazyListState(),
    autoLoad: (() -> Unit)? = null,
    hasMore: Boolean = false,
) {
    if (autoLoad != null) {
        PagingAutoLoader(
            lastVisibleItemIndex = { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
            totalItemCount = { listState.layoutInfo.totalItemsCount },
            hasMore = hasMore,
            loadingMore = loading,
            appendError = error != null,
            hasItems = comments.isNotEmpty(),
            onLoadMore = autoLoad,
        )
    }
    when {
        comments.isEmpty() && error != null -> Box(
            Modifier.fillMaxSize().padding(top = contentTop, bottom = contentBottom),
        ) {
            SectionMessage(
                icon = SfIcons.WifiSlash,
                title = "评论加载失败",
                detail = error,
                action = "重新加载",
                onAction = onRetry,
            )
        }

        comments.isEmpty() && loading -> Box(
            Modifier.fillMaxSize().padding(top = contentTop, bottom = contentBottom),
        ) {
            SkeletonList()
        }

        comments.isEmpty() -> Box(
            Modifier.fillMaxSize().padding(top = contentTop, bottom = contentBottom),
        ) {
            SectionMessage(
                icon = SfIcons.QuoteOpening,
                title = emptyText,
                detail = null,
                action = null,
                onAction = null,
            )
        }

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = contentTop, bottom = contentBottom),
        ) {
            itemsIndexed(comments, key = { _, comment -> comment.id }) { index, comment ->
                Column {
                    CommentRow(comment = comment, loader = loader)
                    if (index != comments.lastIndex) {
                        // Inset to the text column (gutter 20 + avatar 34 + gap 12): the rule
                        // belongs to the comment text, not to the avatar.
                        InsetSeparator(startPadding = 66.dp)
                    }
                }
            }
            if (footer != null) {
                item("comments-footer") { footer() }
            }
        }
    }
}

/** Centred section state with an icon, a short title, optional detail and optional retry. */
@Composable
private fun SectionMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String?,
    action: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = AppTheme.palette.secondaryLabel,
        )
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.tertiaryLabel,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            ProminentButton(text = action, onClick = onAction)
        }
    }
}

/** Three static skeleton rows: no shimmer, because the app owns no shimmer system. */
@Composable
private fun SkeletonList() {
    Column(Modifier.fillMaxSize()) {
        repeat(3) { index ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(AppTheme.palette.tertiaryBackground),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.42f)
                            .height(11.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.control))
                            .background(AppTheme.palette.secondaryBackground),
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.control))
                            .background(AppTheme.palette.secondaryBackground),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.74f)
                            .height(12.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.control))
                            .background(AppTheme.palette.secondaryBackground),
                    )
                }
            }
            if (index != 2) InsetSeparator(startPadding = 66.dp)
        }
    }
}

/** Footer for the all-comments list: paging affordance, error+retry, and the end state. */
@Composable
private fun AllCommentsFooter(
    state: CommentsUiState,
    onRetry: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.allLoading && state.all.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = AppTheme.palette.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正在加载…",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
            }

            state.allError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.allError,
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
                Spacer(Modifier.height(8.dp))
                ActionPill(text = "重试", onClick = onRetry)
            }

            state.all.isNotEmpty() && !state.allMore -> Text(
                text = "没有更多评论",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.tertiaryLabel,
            )
        }
    }
}

/** 44 dp-tall quiet capsule action used by the footer (a proper control, not raw text). */
@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = AppTheme.palette.accent,
        )
    }
}

/**
 * One comment row: content-first hierarchy.
 *
 * Author (13 pt, muted) and date (11 pt, more muted) share one compact line; the body is 15 pt with
 * 22 pt leading and clamps to six lines, expanding on tap with `animateContentSize`; the like count
 * is a small heart + number in a trailing column, bottom-aligned so it sits on the last text line.
 */
@Composable
private fun CommentRow(comment: Comment, loader: ArtworkLoader) {
    var expanded by remember(comment.id) { mutableStateOf(false) }
    var overflowed by remember(comment.id) { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CommentAvatar(comment = comment, loader = loader)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.nickname,
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatTime(comment.timeMs),
                    fontFamily = SFPro,
                    fontSize = 11.sp,
                    color = AppTheme.palette.tertiaryLabel,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(
                    Modifier
                        .weight(1f)
                        .animateContentSize(tween(Motion.SmallDuration, easing = Motion.EaseInOut)),
                ) {
                    Text(
                        text = comment.content,
                        fontFamily = SFPro,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = AppTheme.palette.label,
                        maxLines = if (expanded) Int.MAX_VALUE else COMMENT_CLAMP_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout ->
                            // Only measure the clamped state: after expanding, the layout reports no
                            // overflow and the "收起" affordance would disappear mid-toggle.
                            if (!expanded) overflowed = layout.hasVisualOverflow
                        },
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = null,
                        ) { expanded = !expanded },
                    )
                    if (overflowed) {
                        Spacer(Modifier.height(2.dp))
                        // A 44 dp row around the 12 pt label: the affordance used to be a ~20 dp
                        // text target inside a scrolling list.
                        Box(
                            Modifier
                                .height(44.dp)
                                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                                .clickable(
                                    indication = rememberControlRipple(bounded = true),
                                    interactionSource = null,
                                ) { expanded = !expanded },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (expanded) "收起" else "展开",
                                fontFamily = SFPro,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = AppTheme.palette.accent,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                LikeCount(count = comment.likedCount)
            }
        }
    }
}

/** 34 dp circular avatar: image when there is one, otherwise the nickname's initial. */
@Composable
private fun CommentAvatar(comment: Comment, loader: ArtworkLoader) {
    val size = 34.dp
    if (comment.avatarUrl.isNullOrBlank()) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(AppTheme.palette.tertiaryBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = comment.nickname.trim().take(1).ifBlank { "?" },
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
    } else {
        Artwork(
            url = comment.avatarUrl,
            loader = loader,
            corner = AppleShapes.pill,
            requestSize = 120,
            // A person glyph (not a grey note block) while the avatar loads, so the row never
            // flickers through an empty tile.
            placeholderIcon = SfIcons.Person,
            modifier = Modifier.size(size),
        )
    }
}

/** Trailing like count, vertically centred against the last body line. */
@Composable
private fun LikeCount(count: Int) {
    Row(
        Modifier.height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = SfIcons.HeartFill,
            contentDescription = null,
            tint = AppTheme.palette.tertiaryLabel,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = formatCount(count.toLong()),
            fontFamily = SFPro,
            fontSize = 11.sp,
            color = AppTheme.palette.tertiaryLabel,
        )
    }
}

/** Long comments clamp to six lines (about four CJK rows of body text in this width). */
private const val COMMENT_CLAMP_LINES = 6

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("")
}
