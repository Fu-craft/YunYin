// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.SearchUiState
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.PagingAutoLoader
import com.yunyin.music.ui.components.ProminentButton
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.InsetSeparator
import com.yunyin.music.ui.components.SectionHeader
import com.yunyin.music.ui.components.TrackRow
import com.yunyin.music.ui.components.chromeContentBottomPadding
import com.yunyin.music.ui.components.glassTopBarTopPadding
import com.yunyin.music.ui.components.liquidGlassControl
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * Search screen.
 *
 * Three states on one surface: suggestions (hot keywords + history) before searching,
 * a spinner while in flight, and results after. The field keeps system keyboard focus so
 * the flow reads like a native search on iOS.
 *
 * The field lives in the screen's frosted top bar now: iOS's search screen is a fixed search
 * strip with the suggestions scrolling underneath it, and a field that scrolled away with the
 * results left the blur with nothing moving behind it. The bar is composed outside the recorded
 * page layer it samples, so it blurs the list rather than itself.
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
    /** Called when 取消 is pressed with an empty query: dismiss search back to Home. */
    onCancel: () -> Unit = {},
    onRemoveHistory: (String) -> Unit,
    onTrackClick: (Track) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    onSuggestionSong: (Track) -> Unit = onTrackClick,
    onSuggestionArtist: (Long, String) -> Unit = { _, _ -> },
    onSuggestionAlbum: (Long, String) -> Unit = { _, _ -> },
    onDismissSuggestions: () -> Unit = {},
    /** Opens the 曲风 browser; shown as a row in the idle state. */
    onOpenStyles: () -> Unit = {},
) {
    val suggestions = state.suggestions
    BackHandler(
        enabled = suggestions.songs.isNotEmpty() ||
            suggestions.artists.isNotEmpty() ||
            suggestions.albums.isNotEmpty(),
    ) { onDismissSuggestions() }
    val topBarBackdrop = rememberGlassTopBarBackdrop()
    val resultsListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissForNavigation(action: () -> Unit) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        action()
    }
    // Leaving the search surface (tab switch, back) must never leave the caret/IME behind.
    DisposableEffect(Unit) {
        onDispose {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
            // The four states used to swap in a single frame. Cross-fading them makes "searching"
            // read as a transition into results rather than a flicker.
            val phase = SearchPhaseRules.phase(
                loading = state.loading,
                searched = state.searched,
                error = state.error,
                resultCount = state.results.size + state.lyricResults.size,
            )

            CrossfadeContent(targetState = phase, label = "search-phase") { active ->
                when (active) {
                    SearchPhase.Loading -> Box(
                        Modifier.fillMaxSize().padding(top = glassTopBarTopPadding() + 40.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        CircularProgressIndicator(
                            color = AppTheme.palette.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    SearchPhase.Error -> Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = glassTopBarTopPadding())
                            .padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            SfIcons.WifiSlash,
                            contentDescription = null,
                            tint = AppTheme.palette.tertiaryLabel,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            "搜索失败",
                            fontFamily = SFPro,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            color = AppTheme.palette.label,
                        )
                        Text(
                            state.error ?: "",
                            fontFamily = SFPro,
                            fontSize = 13.sp,
                            color = AppTheme.palette.secondaryLabel,
                        )
                        Spacer(Modifier.height(2.dp))
                        ProminentButton(text = "重试", onClick = { onSubmit(state.keyword) })
                    }

                    SearchPhase.Empty -> Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = glassTopBarTopPadding())
                            .padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "未找到「${state.keyword}」",
                            fontFamily = SFPro,
                            fontSize = 16.sp,
                            color = AppTheme.palette.secondaryLabel,
                        )
                    }

                    SearchPhase.Results -> LazyColumn(
                        state = resultsListState,
                        contentPadding = PaddingValues(
                            top = glassTopBarTopPadding(),
                            bottom = chromeContentBottomPadding(),
                        ),
                    ) {
                        item("result-header") { SectionHeader(title = "歌曲") }
                        items(state.results, key = { it.id }) { track ->
                            TrackRow(
                                track = track,
                                loader = loader,
                                onClick = { dismissForNavigation { onTrackClick(track) } },
                            )
                            InsetSeparator()
                        }
                        // Both catalogues are paged together; the row disappears at the end of the list.
                        if (state.lyricResults.isNotEmpty()) {
                            item("lyric-header") { SectionHeader(title = "歌词匹配") }
                            items(state.lyricResults, key = { "lyric-${it.id}" }) { track ->
                                TrackRow(
                                    track = track,
                                    loader = loader,
                                    onClick = { dismissForNavigation { onTrackClick(track) } },
                                )
                                InsetSeparator()
                            }
                        }
                        if (state.error != null) {
                            // Append failure: the rows above stay; only the footer turns into a retry.
                            item("load-more-error") {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "加载更多失败",
                                        fontFamily = SFPro,
                                        fontSize = 12.sp,
                                        color = AppTheme.palette.secondaryLabel,
                                    )
                                    Text(
                                        "重试",
                                        fontFamily = SFPro,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = AppTheme.palette.accent,
                                        modifier = Modifier
                                            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                                            .background(AppTheme.palette.secondaryBackground)
                                            .clickable { onLoadMore() }
                                            .padding(horizontal = 18.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        } else if (state.loadingMore) {
                            item("load-more-spinner") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = AppTheme.palette.accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        } else if (!state.canLoadMore) {
                            item("load-more-end") {
                                Text(
                                    text = "没有更多结果",
                                    fontFamily = SFPro,
                                    fontSize = 12.sp,
                                    color = AppTheme.palette.tertiaryLabel,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    SearchPhase.Idle -> LazyColumn(
                        contentPadding = PaddingValues(
                            top = glassTopBarTopPadding(),
                            bottom = chromeContentBottomPadding(),
                        ),
                    ) {
                        if (!suggestions.isEmpty) {
                            item("suggest-header") { SectionHeader(title = "搜索建议") }
                            items(suggestions.songs, key = { "sg-song-${it.id}" }) { track ->
                                TrackRow(track = track, loader = loader, onClick = { dismissForNavigation { onSuggestionSong(track) } })
                                InsetSeparator()
                            }
                            items(suggestions.artists, key = { "sg-artist-${it.id}" }) { artist ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { dismissForNavigation { onSuggestionArtist(artist.id, artist.name) } }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(SfIcons.MagnifyingGlass, null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("歌手 · ${artist.name}", fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
                                }
                                InsetSeparator(startPadding = 48.dp)
                            }
                            items(suggestions.albums, key = { "sg-album-${it.id}" }) { album ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { dismissForNavigation { onSuggestionAlbum(album.id, album.name) } }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(SfIcons.ListBullet, null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("专辑 · ${album.name}", fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
                                }
                                InsetSeparator(startPadding = 48.dp)
                            }
                        }

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
                                        .padding(horizontal = 20.dp, vertical = 2.dp),
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
                                        // 44 dp target, 14 dp glyph: deleting a history row is a
                                        // destructive action that must not be a 28 dp bullseye.
                                        Modifier
                                            .size(44.dp)
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

                        item("style-entry") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onOpenStyles)
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "按曲风浏览",
                                    fontFamily = SFPro,
                                    fontSize = 16.sp,
                                    color = AppTheme.palette.label,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "流行 · 电子 · 民谣 …",
                                    fontFamily = SFPro,
                                    fontSize = 13.sp,
                                    color = AppTheme.palette.secondaryLabel,
                                )
                            }
                            InsetSeparator(startPadding = 20.dp)
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

        // Automatic pagination: the footer is informational only, and this reads the results list's
        // own scroll state so a short first page also loads the next one.
        PagingAutoLoader(
            lastVisibleItemIndex = {
                resultsListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            },
            totalItemCount = { resultsListState.layoutInfo.totalItemsCount },
            hasMore = state.canLoadMore,
            loadingMore = state.loadingMore || state.loading,
            appendError = state.error != null,
            hasItems = state.results.isNotEmpty() || state.lyricResults.isNotEmpty(),
            onLoadMore = onLoadMore,
        )

        // The search field strip: fixed, frosted, composed outside the layer it samples.
        GlassTopBar(
            backdrop = topBarBackdrop,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Field with a cancel affordance, as in UISearchBar. The field is a liquid-glass
                // capsule on the strip's recorded layer — the strip itself stays plain progressive
                // blur, so the control reads as a small glass object floating on it.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .liquidGlassControl(topBarBackdrop, RoundedCornerShape(percent = 50))
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
                // Plain text button: no filled chip. The only feedback is the app's unbounded
                // control ripple (the old default indicator drew a bounded rectangular block).
                Text(
                    text = "取消",
                    fontFamily = SFPro,
                    fontSize = 16.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier
                        .clickable(
                            indication = rememberControlRipple(bounded = false),
                            interactionSource = null,
                        ) {
                            when (SearchCancelRules.actionFor(state.keyword)) {
                                SearchCancelAction.ClearKeyword -> onClear()
                                SearchCancelAction.ReturnHome -> {
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                    dismissForNavigation { onCancel() }
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
    }
}
