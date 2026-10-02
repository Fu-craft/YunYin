package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.ui.PlayerStyle
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * "播放器界面" picker: the classic cover, or the vinyl record.
 *
 * The same shape as the quality picker — a self-drawn bottom dialog, each option a row, the selected one
 * marked by both its colour and a checkmark, and a cancel line at the bottom. It is written this way rather
 * than as a segmented control because each option needs a sentence of explanation ("唱片随播放旋转"),
 * which two inline words cannot carry.
 *
 * A preview glyph per row is what makes the choice legible without trying it: a rounded square and a
 * circle-with-a-hole are recognisable as the two players at a glance.
 */
@Composable
fun PlayerStyleSheet(
    current: PlayerStyle,
    onSelect: (PlayerStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    // A fixed dark surface, matching the quality sheet and the app's other bottom panels.
    val surface = Color(0xFF1C1C1E)
    val label = Color.White
    val secondary = Color.White.copy(alpha = 0.55f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(indication = null, interactionSource = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                    .background(surface)
                    // Swallow taps so tapping inside does not dismiss.
                    .clickable(enabled = false, indication = null, interactionSource = null) { }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = "播放器界面",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )

                PlayerStyle.entries.forEach { style ->
                    PlayerStyleRow(
                        style = style,
                        selected = style == current,
                        onClick = { onSelect(style) },
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "取消",
                    fontFamily = SFPro,
                    fontSize = 16.sp,
                    color = secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedRectangle(12.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerStyleRow(
    style: PlayerStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = Color.White
    val secondary = Color.White.copy(alpha = 0.55f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A small glyph of the shape itself: a rounded square for the classic cover, a disc for vinyl.
        StyleGlyph(
            style = style,
            tint = if (selected) AppTheme.palette.accent else Color.White.copy(alpha = 0.75f),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = style.label,
                fontFamily = SFPro,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 16.sp,
                color = if (selected) AppTheme.palette.accent else label,
            )
            Text(
                text = style.detail,
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = secondary,
            )
        }
        if (selected) {
            Icon(
                SfIcons.Checkmark,
                contentDescription = "已选择",
                tint = AppTheme.palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The two shapes, drawn small: enough to tell the styles apart at a glance. */
@Composable
private fun StyleGlyph(style: PlayerStyle, tint: Color) {
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        when (style) {
            PlayerStyle.Classic -> Box(
                Modifier
                    .size(22.dp)
                    .clip(ContinuousRoundedRectangle(6.dp))
                    .background(tint),
            )

            PlayerStyle.Vinyl -> Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1C1C1E)),
                )
            }
        }
    }
}
