package com.yunyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * iOS "material" surface.
 *
 * Compose has no backdrop blur that samples arbitrary content behind a node, so glass is
 * expressed the way iOS visually reads it over media: a translucent fill, a hairline
 * highlight border, and continuous corners. Tints come from the caller so the chrome can
 * pick up the current cover color.
 */
@Composable
fun Modifier.glassSurface(
    corner: Dp = AppleShapes.pill,
    tint: Color = AppTheme.palette.glass,
    border: Color = AppTheme.palette.glassBorder,
): Modifier = this
    .clip(ContinuousRoundedRectangle(corner))
    .background(tint)
    .border(0.5.dp, border, ContinuousRoundedRectangle(corner))

/** Circular translucent control, as used for the player's heart / ellipsis buttons. */
@Composable
fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 19.dp,
    tint: Color = AppTheme.palette.label,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AppTheme.palette.glass)
            .border(0.5.dp, AppTheme.palette.glassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Large title + optional "see all" chevron, matching Apple Music section headers. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                color = AppTheme.palette.label,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
            }
        }
        if (onMore != null) {
            Icon(
                imageVector = com.yunyin.music.ui.icons.SfIcons.ChevronRight,
                contentDescription = "更多",
                tint = AppTheme.palette.tertiaryLabel,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMore),
            )
        }
    }
}

/**
 * Standard library row: 44pt artwork, title, secondary line, optional trailing content.
 * Height and separators follow iOS list rhythm (separator inset to the text, not the edge).
 */
@Composable
fun MediaRow(
    title: String,
    subtitle: String?,
    artwork: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artwork()
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                fontFamily = SFPro,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Hairline separator inset to align with row text. */
@Composable
fun InsetSeparator(startPadding: Dp = 76.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startPadding)
            .height(0.5.dp)
            .background(AppTheme.palette.separator),
    )
}

/** Filled capsule button in the accent color (iOS prominent action). */
@Composable
fun ProminentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(if (enabled) AppTheme.palette.accent else AppTheme.palette.tertiaryBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = if (enabled) Color.White else AppTheme.palette.secondaryLabel,
        )
    }
}
