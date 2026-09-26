package com.yunyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * "Up next" queue presented as an iOS sheet.
 *
 * Shows the whole queue with the playing row highlighted in the accent color; tapping a
 * row jumps to it. Sized to leave the player visible above, which is the standard iOS
 * sheet proportion.
 */
@Composable
fun QueueSheet(
    queue: List<Track>,
    currentIndex: Int,
    loader: ArtworkLoader,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // A scrim that catches a dismissal tap should not paint anything when pressed; the
            // default indication would flash a full-screen dark rectangle.
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                .background(AppTheme.palette.background)
                .clickable(
                    enabled = false,
                    indication = null,
                    interactionSource = null,
                ) { },
        ) {
            // Grabber, as on a native sheet.
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                        .background(AppTheme.palette.tertiaryLabel),
                )
            }
            Text(
                text = "播放队列",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = AppTheme.palette.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )

            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(queue, key = { _, track -> track.id }) { index, track ->
                    val isCurrent = index == currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(
                            url = track.coverUrl,
                            loader = loader,
                            corner = 8.dp,
                            requestSize = 200,
                            modifier = Modifier.size(42.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.name,
                                fontFamily = SFPro,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 15.sp,
                                color = if (isCurrent) AppTheme.palette.accent else AppTheme.palette.label,
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
                        if (isCurrent) {
                            Icon(
                                SfIcons.SpeakerWave,
                                contentDescription = "正在播放",
                                tint = AppTheme.palette.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
