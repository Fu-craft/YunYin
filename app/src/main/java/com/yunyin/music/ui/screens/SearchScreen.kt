package com.yunyin.music.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.SearchUiState
import com.yunyin.music.ui.components.CrossfadeContent
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
) {
    val suggestions = state.suggestions
    BackHandler(
        enabled = suggestions.songs.isNotEmpty() ||
            suggestions.artists.isNotEmpty() ||
            suggestions.albums.isNotEmpty(),
    ) { onDismissSuggestions() }
    val topBarBackdrop = rememberGlassTopBarBackdrop()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Box(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
            // The four states used to swap in a single frame. Cross-fading them makes "searching"
            // read as a transition into results rather than a flicker.
            val phase = when {
                state.loading -> "loading"
                state.searched && state.results.isEmpty() && state.lyricResults.isEmpty() -> "empty"
                state.searched -> "results"
                else -> "idle"
            }

            CrossfadeContent(targetState = phase, label = "search-phase") { active ->
                when (active) {
                    "loading" -> Box(
                        Modifier.fillMaxSize().padding(top = glassTopBarTopPadding() + 40.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        CircularProgressIndicator(
                            color = AppTheme.palette.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    "empty" -> Column(
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

                    "results" -> LazyColumn(
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
                                onClick = { onTrackClick(track) },
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
                                    onClick = { onTrackClick(track) },
                                )
                                InsetSeparator()
                            }
                        }
                        if (state.canLoadMore || state.loadingMore) {
                            item("load-more") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !state.loadingMore) { onLoadMore() }
                                        .padding(vertical = 18.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator(
                                            color = AppTheme.palette.accent,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Text(
                                            "加载更多",
                                            fontFamily = SFPro,
                                            fontSize = 15.sp,
                                            color = AppTheme.palette.accent,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> LazyColumn(
                        contentPadding = PaddingValues(
                            top = glassTopBarTopPadding(),
                            bottom = chromeContentBottomPadding(),
                        ),
                    ) {
                        if (!suggestions.isEmpty) {
                            item("suggest-header") { SectionHeader(title = "搜索建议") }
                            items(suggestions.songs, key = { "sg-song-${it.id}" }) { track ->
                                TrackRow(track = track, loader = loader, onClick = { onSuggestionSong(track) })
                                InsetSeparator()
                            }
                            items(suggestions.artists, key = { "sg-artist-${it.id}" }) { artist ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onSuggestionArtist(artist.id, artist.name) }
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
                                    Modifier.fillMaxWidth().clickable { onSuggestionAlbum(album.id, album.name) }
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
                                    onCancel()
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
    }
}
