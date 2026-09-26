package com.yunyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Compact now-playing bar above the tab bar.
 *
 * Mirrors the iOS mini player: leading artwork, title with the artist beneath, and a
 * play/pause control. It ignores [PlaybackUiState.durationMs] for its own progress line
 * and instead paints a 2pt accent underlay, which reads as progress without competing
 * with the tab bar.
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    loader: ArtworkLoader,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.card))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(onClick = onExpand)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = track.coverUrl,
            loader = loader,
            corner = 10.dp,
            requestSize = 200,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Cross-faded on track id: the bar stays put and only its text changes, so without
            // this the previous song's title is replaced mid-frame while the cover is still
            // loading, which reads as a glitch rather than a track change.
            CrossfadeContent(
                targetState = track.id,
                modifier = Modifier.fillMaxWidth(),
                durationMillis = 260,
                label = "mini-track",
            ) {
                Column {
                    Text(
                        text = track.name,
                        fontFamily = SFPro,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = AppTheme.palette.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artistLine,
                        fontFamily = SFPro,
                        fontSize = 12.sp,
                        color = AppTheme.palette.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            // The play/pause glyph used to swap in a single frame; scaling + fading it makes the
            // state change feel like a control being pressed rather than the icon being replaced.
            CrossfadeContent(
                targetState = state.isPlaying,
                durationMillis = 200,
                label = "mini-play",
            ) { playing ->
                Icon(
                    imageVector = if (playing) SfIcons.Pause else SfIcons.Play,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = AppTheme.palette.label,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
