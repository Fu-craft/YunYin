package com.yunyin.music.ui.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
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
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only comments: 热门评论 and 全部评论, each with its own loading/error state.
 *
 * No posting and no liking — this is a listening surface, not a social one. Content is rendered as plain text (no
 * HTML/linkification); long comments clamp to four lines and expand on tap. The list pages with "加载更多" and the
 * append rule lives in `CommentPagingRules` (id-deduped, bounded by the server's `more`).
 */
@Composable
fun CommentsSheet(
    state: CommentsUiState,
    loader: ArtworkLoader,
    onDismiss: () -> Unit,
    onSelectTab: (CommentsTab) -> Unit,
    onLoadMore: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        sheetModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.72f)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(Color(0xFF1C1C1E)),
    ) { close ->
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "评论",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "收起",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier.clickable { close {} },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TabLabel("热门评论", state.tab == CommentsTab.HOT) { onSelectTab(CommentsTab.HOT) }
                TabLabel("全部评论", state.tab == CommentsTab.ALL) { onSelectTab(CommentsTab.ALL) }
            }
            Spacer(Modifier.height(6.dp))
            if (state.tab == CommentsTab.HOT) {
                CommentList(
                    comments = state.hot,
                    loading = state.hotLoading,
                    error = state.hotError,
                    empty = "暂无热门评论",
                    loader = loader,
                    footer = null,
                )
            } else {
                CommentList(
                    comments = state.all,
                    loading = state.allLoading,
                    error = state.allError,
                    empty = "还没有评论",
                    loader = loader,
                    footer = {
                        when {
                            state.allLoading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AppTheme.palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            }
                            state.allMore -> Box(
                                Modifier.fillMaxWidth().padding(10.dp)
                                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                                    .background(AppTheme.palette.secondaryBackground)
                                    .clickable(onClick = onLoadMore)
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("加载更多", fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.accent)
                            }
                            state.all.isNotEmpty() -> Text(
                                "没有更多评论",
                                fontFamily = SFPro,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 14.sp,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.45f),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun CommentList(
    comments: List<Comment>,
    loading: Boolean,
    error: String?,
    empty: String,
    loader: ArtworkLoader,
    footer: (@Composable () -> Unit)?,
) {
    when {
        error != null && comments.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error, fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.accent)
        }
        loading && comments.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppTheme.palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
        comments.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, fontFamily = SFPro, fontSize = 13.sp, color = Color.White.copy(alpha = 0.45f))
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(comments, key = { it.id }) { comment -> CommentRow(comment, loader) }
            item("footer") { footer?.invoke() }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, loader: ArtworkLoader) {
    var expanded by remember(comment.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Artwork(
            url = comment.avatarUrl,
            loader = loader,
            corner = 18.dp,
            requestSize = 96,
            modifier = Modifier.size(36.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.nickname,
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTime(comment.timeMs),
                    fontFamily = SFPro,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.3f),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = comment.content,
                fontFamily = SFPro,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (comment.likedCount > 0) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        SfIcons.HeartFill,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        comment.likedCount.toString(),
                        fontFamily = SFPro,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return ""
    return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms)) }.getOrDefault("")
}
