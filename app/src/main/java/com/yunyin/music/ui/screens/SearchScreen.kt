package com.yunyin.music.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.SearchUiState
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.InsetSeparator
import com.yunyin.music.ui.components.SectionHeader
import com.yunyin.music.ui.components.TrackRow
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Search screen.
 *
 * Three states on one surface: suggestions (hot keywords + history) before searching,
 * a spinner while in flight, and results after. The field keeps system keyboard focus so
 * the flow reads like a native search on iOS.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    loader: ArtworkLoader,
    onKeywordChange: (String) -> Unit,
    // Carries the keyword to search: the IME passes the typed text, history/hot rows pass
    // their own label (reading the live field state would search the wrong thing).
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Spacer(Modifier.statusBarsPadding().height(4.dp))

        // Search field with a cancel affordance, as in UISearchBar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(AppTheme.palette.secondaryBackground)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    SfIcons.MagnifyingGlass,
                    contentDescription = null,
                    tint = AppTheme.palette.secondaryLabel,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (state.keyword.isEmpty()) {
                        Text(
                            "搜索歌曲、歌手",
                            fontFamily = SFPro,
                            fontSize = 16.sp,
                            color = AppTheme.palette.tertiaryLabel,
                        )
                    }
                    BasicTextField(
                        value = state.keyword,
                        onValueChange = onKeywordChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = SFPro,
                            fontSize = 16.sp,
                            color = AppTheme.palette.label,
                        ),
                        cursorBrush = SolidColor(AppTheme.palette.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit(state.keyword) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.keyword.isNotEmpty()) {
                    Icon(
                        SfIcons.Xmark,
                        contentDescription = "清除",
                        tint = AppTheme.palette.secondaryLabel,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable {
                                onClear()
                            },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "取消",
                fontFamily = SFPro,
                fontSize = 16.sp,
                color = AppTheme.palette.accent,
                modifier = Modifier.clickable { onClear() },
            )
        }

        // The four states used to swap in a single frame. Cross-fading them makes "searching"
        // read as a transition into results rather than a flicker.
        val phase = when {
            state.loading -> "loading"
            state.searched && state.results.isEmpty() -> "empty"
            state.searched -> "results"
            else -> "idle"
        }

        CrossfadeContent(targetState = phase, label = "search-phase") { active ->
        when (active) {
            "loading" -> Box(
                Modifier.fillMaxSize().padding(top = 80.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                CircularProgressIndicator(
                    color = AppTheme.palette.accent,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(28.dp),
                )
            }

            "empty" -> Column(
                Modifier.fillMaxWidth().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "未找到「${state.keyword}」",
                    fontFamily = SFPro,
                    fontSize = 16.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
            }

            "results" -> LazyColumn(
                contentPadding = PaddingValues(bottom = 140.dp),
            ) {
                item("result-header") { SectionHeader(title = "歌曲") }
                items(state.results, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        loader = loader,
                        onClick = { onTrackClick(track) },
                    )
                    InsetSeparator()
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 140.dp),
            ) {
                if (state.history.isNotEmpty()) {
                    item("history-header") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "最近搜索",
                                fontFamily = SFPro,
                                fontWeight = FontWeight.Bold,
                                fontSize = 21.sp,
                                color = AppTheme.palette.label,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    items(state.history, key = { "h-${it}" }) { keyword ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSubmit(keyword) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                SfIcons.Clock,
                                contentDescription = null,
                                tint = AppTheme.palette.tertiaryLabel,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                keyword,
                                fontFamily = SFPro,
                                fontSize = 16.sp,
                                color = AppTheme.palette.label,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Separate tap target from the row: deletes rather than searches.
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable { onRemoveHistory(keyword) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    SfIcons.Xmark,
                                    contentDescription = "删除",
                                    tint = AppTheme.palette.tertiaryLabel,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        InsetSeparator(startPadding = 50.dp)
                    }
                }

                if (state.hot.isNotEmpty()) {
                    item("hot-header") { SectionHeader(title = "热门搜索") }
                    items(state.hot, key = { "hot-$it" }) { keyword ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSubmit(keyword) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                SfIcons.ArrowUpRight,
                                contentDescription = null,
                                tint = AppTheme.palette.tertiaryLabel,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                keyword,
                                fontFamily = SFPro,
                                fontSize = 16.sp,
                                color = AppTheme.palette.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        InsetSeparator(startPadding = 48.dp)
                    }
                }
            }
        }
        }
    }
}
