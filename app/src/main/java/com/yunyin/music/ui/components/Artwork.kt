package com.yunyin.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Album artwork with a placeholder while loading.
 *
 * Uses the app's continuous-corner shape so tiles match iOS artwork presentation, and
 * falls back to a tinted note glyph when a cover is missing.
 */
@Composable
fun Artwork(
    url: String?,
    loader: ArtworkLoader,
    modifier: Modifier = Modifier,
    corner: Dp = AppleShapes.card,
    requestSize: Int = 600,
    placeholderIcon: ImageVector = SfIcons.MusicNote,
    /**
     * An already-decoded bitmap to display instead of loading one.
     *
     * Needed wherever the same artwork is on screen twice at once. The player shows the cover
     * full-size and again in the lyrics header, and a *shared element* transition animates between
     * the two — so they must be the same pixels. Letting each instance load its own copy caused
     * exactly two bugs: a second fetch + JPEG decode landing in the middle of the transition (a
     * visible stutter), and a one-frame placeholder on whichever copy had not finished loading
     * (a white flash, because the placeholder is a light grey). Supplying the caller's bitmap
     * removes both.
     */
    preloaded: ImageBitmap? = null,
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url, preloaded) {
        if (preloaded != null) {
            // The caller's bitmap is authoritative; do not fetch a second copy.
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = if (url == null) null else loader.load(url, requestSize)?.asImageBitmap()
    }

    Box(
        modifier = modifier
            .clip(ContinuousRoundedRectangle(corner))
            .background(AppTheme.palette.secondaryBackground),
        contentAlignment = Alignment.Center,
    ) {
        val image = preloaded ?: bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = AppTheme.palette.tertiaryLabel,
                modifier = Modifier.fillMaxWidth(0.4f),
            )
        }
    }
}

/** Non-interactive filler used when a section is empty or still loading. */
@Composable
fun ArtworkPlaceholder(
    modifier: Modifier = Modifier,
    corner: Dp = AppleShapes.card,
    color: Color = AppTheme.palette.secondaryBackground,
) {
    Box(modifier = modifier.clip(ContinuousRoundedRectangle(corner)).background(color))
}
