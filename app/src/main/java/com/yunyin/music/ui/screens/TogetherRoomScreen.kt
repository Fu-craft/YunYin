package com.yunyin.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Account
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.together.TogetherUiState
import com.yunyin.music.ui.components.UserAvatar
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * The listen-together room, as a screen of its own: two faces under one pair of headphones.
 *
 * ## What this shows, and what it deliberately does not
 *
 * Two overlapping circular avatars with a headphone band drawn across them, and the track the room is on.
 * That is the whole idea, and the reference for it puts statistics beneath — a distance, a total listening
 * time. **Those are absent because the data does not exist**: NetEase's together-listen routes return a room
 * id, its creation time and its member list, and nothing that could yield either figure. Showing invented or
 * derived numbers would be worse than showing none, so the line under the headphones is the current song,
 * which is real and is what the room is about.
 *
 * ## Why the avatars overlap
 *
 * They are the one composition that reads as "these two are listening together"; side by side they read as a
 * list. The band is drawn in the *gaps* — two short arcs from each avatar up to a shared centre — rather than
 * as one arc behind both, so it stays legible whichever way the avatars are arranged.
 *
 * ## Missing avatars
 *
 * A member with no picture (any transport other than the official one, a session without a profile) gets the
 * tinted person glyph from [UserAvatar]. With nobody in the room yet, one slot shows the user and the other
 * shows the same placeholder with a "waiting" caption, so the layout never collapses.
 */
@Composable
fun TogetherRoomScreen(
    state: TogetherUiState,
    account: Account?,
    ownAvatar: ImageBitmap?,
    loader: ArtworkLoader,
    track: Track?,
    onInvite: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette

    // The peer is the most recently updated member with something to say; with two members that is simply
    // the other one.
    val peer = state.peers.maxByOrNull { it.updatedAt } ?: state.peers.firstOrNull()

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Sized from the smaller axis so a short landscape window cannot push the captions off-screen.
        val avatarSize: Dp = minOf(maxWidth * 0.30f, maxHeight * 0.26f).coerceIn(72.dp, 168.dp)
        // The overlap: the reference's two circles touch, and the band's centre sits in the notch above.
        val overlap = avatarSize * 0.16f

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            HeadphonePair(
                avatarSize = avatarSize,
                overlap = overlap,
                ownAvatar = ownAvatar,
                ownAvatarUrl = account?.takeIf { !it.isAnonymous }?.avatarUrl,
                peerAvatarUrl = peer?.avatarUrl,
                peerName = peer?.name.orEmpty(),
                loader = loader,
            )

            Spacer(Modifier.height(28.dp))

            // The current track. Real data, and the thing the two of them are actually doing here.
            if (track != null) {
                Text(
                    text = track.name,
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp,
                    color = palette.label,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = 56.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = track.artistLine,
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = palette.secondaryLabel,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "还没有开始播放",
                    fontFamily = SFPro,
                    fontSize = 15.sp,
                    color = palette.secondaryLabel,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = if (peer == null) "正在等待对方加入" else "和 ${peer.name.ifBlank { "对方" }} 一起听",
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = palette.tertiaryLabel,
            )
        }

        // The two actions, pinned to the bottom: inviting is the reason to be on this screen at all once
        // the other person has not arrived yet.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoomButton(
                label = "邀请对方",
                icon = SfIcons.SquareAndArrowUp,
                filled = true,
                onClick = onInvite,
                modifier = Modifier.weight(1f),
            )
            RoomButton(
                label = "返回播放器",
                icon = SfIcons.ChevronDown,
                filled = false,
                onClick = onClose,
                modifier = Modifier.weight(1f),
            )
        }

        // A close affordance at the top-left, where a pushed screen's back button lives.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SfIcons.ChevronLeft, contentDescription = "返回", tint = palette.accent, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Two avatars and the headphone band that joins them.
 *
 * The band is drawn the way a real pair sits on two heads: the cups are at the **outer** edges (where ears
 * are), and one arc rises over both. An earlier version put the cups at the avatars' centres and drew the
 * arc between them, which read as a small arch in the gap rather than as something being worn.
 *
 * The canvas is taller than the avatars so the arc has room above them; the avatars sit at its bottom.
 */
@Composable
private fun HeadphonePair(
    avatarSize: Dp,
    overlap: Dp,
    ownAvatar: ImageBitmap?,
    ownAvatarUrl: String?,
    peerAvatarUrl: String?,
    peerName: String,
    loader: ArtworkLoader,
) {
    val palette = AppTheme.palette
    // Head-room for the band: half an avatar above the pair.
    val bandSpace = avatarSize * 0.52f
    // The pair's exact width. Computed rather than measured so the canvas can be sized to match: a
    // `fillMaxSize` canvas would stretch to the parent's width instead (the whole screen), putting the ear
    // cups at the screen edges.
    val pairWidth = avatarSize * 2 - overlap

    Box(Modifier.size(width = pairWidth, height = avatarSize + bandSpace)) {
        Canvas(Modifier.size(width = pairWidth, height = avatarSize + bandSpace)) {
            val w = size.width
            val h = size.height
            // The avatars occupy the lower `avatarSize` of this canvas, so their vertical centre is:
            val centreY = h - avatarSize.toPx() / 2f
            // The cups hang at the pair's outer edges, level with the ear line.
            val cupLeftX = w * 0.075f
            val cupRightX = w * 0.925f
            val band = palette.label.copy(alpha = 0.5f)
            val stroke = 2.6.dp.toPx()

            val path = Path().apply {
                moveTo(cupLeftX, centreY)
                // One arc over the top; the controls sit well above the avatars, which is what gives the
                // band its height rather than a shallow bridge between the two heads.
                cubicTo(
                    cupLeftX, centreY - avatarSize.toPx() * 0.95f,
                    cupRightX, centreY - avatarSize.toPx() * 0.95f,
                    cupRightX, centreY,
                )
            }
            drawPath(path = path, color = band, style = Stroke(width = stroke))

            // The ear cups: a slightly thicker stub just below each end of the band, which is what makes the
            // arc read as headphones rather than as a wire.
            val cupDrop = avatarSize.toPx() * 0.20f
            listOf(cupLeftX, cupRightX).forEach { x ->
                drawLine(
                    color = band,
                    start = Offset(x, centreY - cupDrop * 0.35f),
                    end = Offset(x, centreY + cupDrop),
                    strokeWidth = stroke * 2.6f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(-overlap),
        ) {
            UserAvatar(
                url = ownAvatarUrl,
                custom = ownAvatar,
                loader = loader,
                size = avatarSize,
                // No ring: on this screen the avatars are the subject, and a page-coloured ring around
                // each would cut the band where it passes behind them.
                ring = false,
            )
            UserAvatar(
                url = peerAvatarUrl,
                loader = loader,
                size = avatarSize,
                ring = false,
            )
        }
    }
}

/** A pill button on the room screen; the same shape and weight as the sheet's. */
@Composable
private fun RoomButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    Row(
        modifier
            .height(50.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(if (filled) palette.accent else palette.secondaryBackground)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Color.White else palette.label, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = if (filled) Color.White else palette.label,
        )
    }
}
