package com.yunyin.music.ui.comments

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Comment
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.CommentsTab
import com.yunyin.music.ui.CommentsUiState
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.InsetSeparator
import com.yunyin.music.ui.components.ProminentButton
import com.yunyin.music.ui.components.QueueSheetDismissRules
import com.yunyin.music.ui.components.formatCount
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Read-only comments: 热门评论 and 全部评论, each with its own loading/error state.
 *
 * No posting and no liking — this is a listening surface, not a social one. Content is rendered as
 * plain text (no HTML/linkification); long comments clamp to six lines and expand on tap. The list
 * pages with a footer action and the append rule lives in `CommentPagingRules` (id-deduped, bounded
 * by the server's `more`).
 *
 * Design notes (the `ui-ux-pro-max` guidance for an app list surface, adapted to this project):
 *  - **The comment is the hero.** Body text is 15 pt with 22 pt leading (the skill's 1.4–1.5 line
 *    height for reading text); the author line is one compact 13 pt row and the date is 11 pt, so
 *    the eye lands on the words first and the metadata never competes.
 *  - **A real segmented control**, not two labels: a 44 dp track (secondary background) with an
 *    inset selected segment (tertiary background) — the app's existing "filled capsule inside a
 *    quiet track" idiom, and a larger touch target than the two bare texts it replaces.
 *  - **Rhythm**: 14 dp vertical padding per row, hairline separators inset to the text column
 *    (66 dp = 20 dp gutter + 34 dp avatar + 12 dp gap), 8 dp between header/title/segments.
 *  - **One trailing column for the like count**, bottom-aligned with the last text line and muted at
 *    zero, instead of a floating badge.
 *  - The app's language is kept over the skill's defaults: SF Pro, `AppTheme.palette` semantic
 *    colours, `AppleShapes` (24 dp sheet corner, 16 dp control, pill), `Motion.*` tokens, and the
 *    same draggable-grabber sheet chrome as the queue sheet.
 */
@Composable
fun CommentsSheet(
    state: CommentsUiState,
    loader: ArtworkLoader,
    onDismiss: () -> Unit,
    onSelectTab: (CommentsTab) -> Unit,
    onLoadMore: () -> Unit,
    /** Retries the failed section only; the other section keeps whatever it already has. */
    onRetry: (CommentsTab) -> Unit,
) {
    // Drag-to-dismiss from the grabber + header, exactly like the queue sheet: following the finger
    // only moves a graphics layer, so it never recomposes the list.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    val density = LocalDensity.current

    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        sheetModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.78f)
            .graphicsLayer { translationY = dragOffset }
            // System bar clearance, then the app's sheet gutter; the rounded sheet itself floats
            // above the gesture bar instead of ending under it.
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            // Swallows taps on the sheet body so they do not reach the dismiss scrim behind it.
            .clickable(enabled = false, indication = null, interactionSource = null) { },
    ) { close ->
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- header (drag handle)
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                        },
                        onDragStopped = { velocity ->
                            val distancePx = with(density) {
                                QueueSheetDismissRules.DistanceDp.dp.toPx()
                            }
                            if (QueueSheetDismissRules.shouldDismiss(
                                    offsetPx = dragOffset,
                                    distancePx = distancePx,
                                    velocityPxPerMs = velocity / 1000f,
                                )
                            ) {
                                close()
                            } else {
                                dragScope.launch {
                                    androidx.compose.animation.core.Animatable(dragOffset)
                                        .animateTo(0f, Motion.SpringSettle) {
                                            dragOffset = value
                                        }
                                }
                            }
                        },
                    ),
            ) {
                Grabber()
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "评论",
                            fontFamily = SFPro,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = AppTheme.palette.label,
                        )
                        CommentCountLabel(state)
                    }
                    Box(
                        Modifier
                            .height(44.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                            .clickable(
                                indication = rememberControlRipple(bounded = true),
                                interactionSource = null,
                                onClick = { close {} },
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "收起",
                            fontFamily = SFPro,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = AppTheme.palette.accent,
                        )
                    }
                }
                CommentsSegmentedControl(
                    selected = state.tab,
                    onSelect = onSelectTab,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            // ---------------------------------------------------------------- content
            if (state.tab == CommentsTab.HOT) {
                CommentSection(
                    comments = state.hot,
                    loading = state.hotLoading,
                    error = state.hotError,
                    emptyText = "暂无热门评论",
                    emptyIcon = SfIcons.QuoteOpening,
                    loader = loader,
                    onRetry = { onRetry(CommentsTab.HOT) },
                    footer = null,
                )
            } else {
                CommentSection(
                    comments = state.all,
                    loading = state.allLoading,
                    error = state.allError,
                    emptyText = "还没有评论",
                    emptyIcon = SfIcons.QuoteOpening,
                    loader = loader,
                    onRetry = { onRetry(CommentsTab.ALL) },
                    footer = {
                        AllCommentsFooter(
                            state = state,
                            onLoadMore = onLoadMore,
                            onRetry = { onRetry(CommentsTab.ALL) },
                        )
                    },
                )
            }
        }
    }
}

/** Native-sheet grabber: 36 × 5 dp, centred, the same handle the queue sheet uses. */
@Composable
private fun Grabber() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                .background(AppTheme.palette.tertiaryLabel),
        )
    }
}

/** "N 条评论" under the title, from the total the all-comments page already returns. */
@Composable
private fun CommentCountLabel(state: CommentsUiState) {
    if (state.allTotal <= 0) return
    Text(
        text = "${formatCount(state.allTotal.toLong())} 条评论",
        fontFamily = SFPro,
        fontSize = 12.sp,
        color = AppTheme.palette.secondaryLabel,
        modifier = Modifier.padding(top = 1.dp),
    )
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
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    loader: ArtworkLoader,
    onRetry: () -> Unit,
    footer: (@Composable () -> Unit)?,
) {
    when {
        comments.isEmpty() && error != null -> SectionMessage(
            icon = SfIcons.WifiSlash,
            title = "评论加载失败",
            detail = error,
            action = "重新加载",
            onAction = onRetry,
        )
        comments.isEmpty() && loading -> SkeletonList()
        comments.isEmpty() -> SectionMessage(
            icon = emptyIcon,
            title = emptyText,
            detail = null,
            action = null,
            onAction = null,
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(comments, key = { _, comment -> comment.id }) { index, comment ->
                Column {
                    CommentRow(comment = comment, loader = loader)
                    if (index != comments.lastIndex) {
                        // Inset to the text column (gutter 20 + avatar 34 + gap 12): the rule belongs
                        // to the comment text, not to the avatar.
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
    onLoadMore: () -> Unit,
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
                    text = "加载更多…",
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

            state.allMore -> ActionPill(text = "加载更多", onClick = onLoadMore)

            state.all.isNotEmpty() -> Text(
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
 * is a small heart + number in a trailing column, bottom-aligned so it sits on the last text line
 * when the comment is short and stays out of the text's way when it is long.
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
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (expanded) "收起" else "展开",
                            fontFamily = SFPro,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = AppTheme.palette.accent,
                            modifier = Modifier
                                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                                .clickable(
                                    indication = rememberControlRipple(bounded = true),
                                    interactionSource = null,
                                ) { expanded = !expanded }
                                .padding(vertical = 2.dp),
                        )
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
