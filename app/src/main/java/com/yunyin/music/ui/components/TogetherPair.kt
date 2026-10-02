package com.yunyin.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.theme.AppTheme

/**
 * The two people in a listen-together room, as shown above the album cover.
 *
 * Only meaningful while a room is active, so the caller passes null and nothing is drawn the rest of the
 * time — the strip is part of the player, not a screen of its own.
 *
 * ## Where the data comes from
 *
 * The faces are the NetEase avatars that `/listentogether/status` returns in `roomUsers[].avatarUrl`. A
 * member with no picture (or a transport that carries no avatars at all) falls back to the tinted person
 * glyph, so the strip is never blank.
 *
 * ## What is deliberately absent
 *
 * The reference design puts "相距 X 公里, 一起听了 X 小时 X 分钟" under the pair. **The together-listen API
 * exposes no such numbers** — a room carries an id, its creation time and its member list, and nothing from
 * which a distance or a cumulative listening time could be derived. Inventing them, or approximating them
 * from the current session, would be presenting made-up figures as facts, so the strip is just the pair.
 */
data class TogetherPair(
    /** The signed-in user's own avatar URL, when they have one. */
    val ownAvatarUrl: String?,
    /** A locally chosen picture for the user, which takes precedence over [ownAvatarUrl]. */
    val ownAvatar: ImageBitmap?,
    /** The other member's avatar URL, when the transport supplies one. */
    val peerAvatarUrl: String?,
)

/**
 * Two overlapping avatars under one headphone band.
 *
 * The band is drawn the way a real pair sits on two heads: the cups are at the **outer** edges (where ears
 * are) and a single arc rises over both. Drawing it between the two heads instead — which an earlier version
 * did — reads as a small arch in the gap rather than as something being worn.
 *
 * @param avatarSize the diameter of each face. Small on the player (this is an indicator, not the subject),
 *   so it stays legible without competing with the cover below it.
 */
@Composable
fun TogetherPairStrip(
    pair: TogetherPair,
    loader: ArtworkLoader,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    // The two circles overlap, which is what makes them read as a pair rather than as a list.
    val overlap = avatarSize * 0.22f
    // Head-room above the pair for the band. Enough that the arc is a visible semicircle rather than a
    // shallow lid, which is what it becomes when this is too small.
    val bandSpace = avatarSize * 0.50f
    val pairWidth = avatarSize * 2 - overlap

    Box(modifier.size(width = pairWidth, height = avatarSize + bandSpace)) {
        Canvas(Modifier.size(width = pairWidth, height = avatarSize + bandSpace)) {
            val w = size.width
            val h = size.height
            // The avatars sit at the bottom of this canvas, so their vertical centre is half an avatar up
            // from the bottom edge.
            val centreY = h - avatarSize.toPx() / 2f
            val cupLeftX = w * 0.11f
            val cupRightX = w * 0.89f
            val ink = palette.label.copy(alpha = 0.62f)
            val stroke = 2.dp.toPx()

            val arc = Path().apply {
                moveTo(cupLeftX, centreY)
                cubicTo(
                    cupLeftX, centreY - avatarSize.toPx() * 1.02f,
                    cupRightX, centreY - avatarSize.toPx() * 1.02f,
                    cupRightX, centreY,
                )
            }
            drawPath(path = arc, color = ink, style = Stroke(width = stroke))

            // The ear cups: a short thicker stub just below each end, which is what makes the arc read as
            // headphones rather than as a wire.
            val drop = avatarSize.toPx() * 0.26f
            listOf(cupLeftX, cupRightX).forEach { x ->
                drawLine(
                    color = ink,
                    start = Offset(x, centreY - drop * 0.35f),
                    end = Offset(x, centreY + drop),
                    strokeWidth = stroke * 2.4f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(-overlap),
        ) {
            UserAvatar(
                url = pair.ownAvatarUrl,
                custom = pair.ownAvatar,
                loader = loader,
                size = avatarSize,
                // No ring: at this size a page-coloured ring around each face would cut the band where it
                // passes behind them.
                ring = false,
            )
            UserAvatar(
                url = pair.peerAvatarUrl,
                loader = loader,
                size = avatarSize,
                ring = false,
            )
        }
    }
}
