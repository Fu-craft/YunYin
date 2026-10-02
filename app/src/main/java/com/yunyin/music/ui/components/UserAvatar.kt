package com.yunyin.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme

/**
 * A circular profile picture: a locally chosen image, a remote avatar, or a person glyph.
 *
 * ## Why this is a shared component rather than three copies
 *
 * The same picture has to look the same wherever it appears — the library header, the profile editor, and
 * the listen-together room, which now shows two of them side by side. Each place having its own copy is how
 * the fallbacks drift apart (one showing a tinted glyph, another a blank circle), and the avatar is exactly
 * the kind of thing where a blank circle reads as "broken" rather than as "no picture".
 *
 * ## Precedence
 *
 * [custom] wins over [url] whenever the user has chosen their own picture — the caller decides that, by
 * passing the bitmap only while the "follow the NetEase avatar" switch is off. Then [url], then the glyph.
 *
 * ## The fallback is deliberately tinted
 *
 * A light grey plate behind a light grey glyph has no contrast on a white sheet: it reads as an empty hole.
 * The accent tint makes the placeholder legible in both appearances, which is what makes "no picture yet"
 * look intentional instead of unfinished.
 *
 * @param url the remote avatar, when there is one.
 * @param custom an already-decoded local picture; takes precedence over [url].
 * @param ring draws a ring in the page colour around the picture. Correct when the avatar sits on a
 *   coloured surface (the library banner, the room screen); a caller on a plain sheet passes false.
 */
@Composable
fun UserAvatar(
    url: String?,
    loader: ArtworkLoader,
    modifier: Modifier = Modifier,
    custom: ImageBitmap? = null,
    ring: Boolean = true,
) {
    val palette = AppTheme.palette
    val remote = url?.takeIf { it.isNotBlank() }
    val hasPicture = custom != null || remote != null

    val base = modifier.clip(CircleShape)
    val withRing = if (ring) base.background(palette.background).padding(4.dp).clip(CircleShape) else base

    Box(
        withRing
            .background(if (hasPicture) palette.secondaryBackground else palette.accent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            custom != null -> Image(
                bitmap = custom,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            remote != null -> Artwork(
                url = remote,
                loader = loader,
                corner = AppleShapes.pill,
                requestSize = 320,
                placeholderIcon = SfIcons.Person,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Icon(
                SfIcons.Person,
                contentDescription = null,
                tint = palette.accent,
                // Proportional rather than a fixed size, so the component is usable at any diameter —
                // the room screen draws these far larger than the library header does.
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
    }
}

/**
 * A plain [UserAvatar] sized by the caller's `modifier.size(...)`; provided so callers do not have to pass
 * `size` through two parameters.
 */
@Composable
fun UserAvatar(
    url: String?,
    loader: ArtworkLoader,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    custom: ImageBitmap? = null,
    ring: Boolean = true,
) = UserAvatar(url = url, loader = loader, modifier = modifier.size(size), custom = custom, ring = ring)
