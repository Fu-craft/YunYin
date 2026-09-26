package com.yunyin.music.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.CollectionUiState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.InsetSeparator
import com.yunyin.music.ui.components.TrackRow
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/** Horizontal inset shared by the cover, the title and the back glyph. */
private val HeaderInset = 20.dp

/** Back glyph box. Its ink does not reach the box edges, so it is nudged to sit optically flush. */
private val BackGlyphSize = 26.dp

/**
 * Playlist / chart detail.
 *
 * The header reproduces the Apple Music collection layout: the artwork's own colours, heavily
 * blurred, form the backdrop, and a wash in the page background colour fades them into the list
 * so the title stays legible over any cover.
 */
@Composable
fun CollectionScreen(
    state: CollectionUiState,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A small decode is plenty: it is blurred heavily, so detail would be thrown away anyway.
    var backdrop by remember(state.coverUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(state.coverUrl) {
        val bitmap: Bitmap? = state.coverUrl?.let { loader.load(it, 200) }
        backdrop = bitmap?.asImageBitmap()
    }

    val background = AppTheme.palette.background

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 140.dp),
    ) {
        item("header") {
            Box(Modifier.fillMaxWidth()) {
                // Backdrop: the cover, blurred into a soft colour field. A wash in the page
                // background colour then ramps over it, so the header melts into the list and
                // the title always has the theme's own contrast to sit on.
                Box(Modifier.matchParentSize().clipToBounds()) {
                    val image = backdrop
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                // Overscan so the blur's soft edge is pushed outside the clip.
                                .graphicsLayer {
                                    scaleX = 1.3f
                                    scaleY = 1.3f
                                }
                                .blur(48.dp),
                        )
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    // The wash has to do two jobs at once: keep the cover's colour
                                    // alive through the header, and reach the page background at
                                    // the bottom so the header melts into the list.
                                    //
                                    // It therefore holds a low alpha across the cover and title,
                                    // then ramps hard in the last stretch. Reaching full opacity by
                                    // the title (as a naive evenly-spaced ramp does) leaves the
                                    // header looking like a flat black band.
                                    colorStops = arrayOf(
                                        0.00f to background.copy(alpha = 0.38f),
                                        0.30f to background.copy(alpha = 0.46f),
                                        0.72f to background.copy(alpha = 0.58f),
                                        0.90f to background.copy(alpha = 0.84f),
                                        1.00f to background,
                                    ),
                                ),
                            ),
                    )
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = HeaderInset)) {
                    Spacer(Modifier.statusBarsPadding())
                    Spacer(Modifier.height(6.dp))
                    Icon(
                        SfIcons.ChevronDown,
                        contentDescription = "返回",
                        tint = AppTheme.palette.label,
                        modifier = Modifier
                            // The chevron glyph is inset from its box; shift it back so the ink,
                            // not the box, lines up with the cover's left edge.
                            .offset(x = -(BackGlyphSize * ChevronInkInsetFraction))
                            .size(BackGlyphSize)
                            .clickable(onClick = onBack),
                    )
                    Spacer(Modifier.height(14.dp))
                    Artwork(
                        url = state.coverUrl,
                        loader = loader,
                        corner = AppleShapes.cardLarge,
                        requestSize = 800,
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(200.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.title,
                        fontFamily = SFPro,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        color = AppTheme.palette.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.subtitle != null) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = state.subtitle,
                            fontFamily = SFPro,
                            fontSize = 13.sp,
                            color = AppTheme.palette.secondaryLabel,
                        )
                    }
                    if (!state.loading && state.tracks.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        PlayAllRow(count = state.tracks.size, onClick = onPlayAll)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (state.loading) {
            item("loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = AppTheme.palette.accent,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        } else {
            // Index-based so duplicate songs in a playlist still resolve to the row that
            // was tapped (`indexOf` would always return the first duplicate).
            //
            // `animateItem` fades each row in as it enters composition, so the list does not pop
            // into place once loading finishes. Placement animation is left off deliberately: rows
            // reordering during a scroll should not slide, only the appearance is animated. This
            // keeps the list lazy, which the cross-fade alternative would not.
            itemsIndexed(state.tracks, key = { index, track -> "${track.id}#$index" }) { index, track ->
                TrackRow(
                    track = track,
                    loader = loader,
                    onClick = { onTrackClick(track, index) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(260),
                        fadeOutSpec = null,
                        placementSpec = null,
                    ),
                )
                InsetSeparator()
            }
        }
    }
}

/**
 * "Play all" affordance.
 *
 * Deliberately monochrome rather than the accent red: over a cover-tinted backdrop a saturated
 * red fights the artwork, and the brief asks for the quiet iOS treatment here. The count is a
 * step smaller and in the secondary label colour so the verb, not the tally, carries the row.
 */
@Composable
private fun PlayAllRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SfIcons.Play,
            contentDescription = null,
            tint = AppTheme.palette.label,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = "播放全部",
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = AppTheme.palette.label,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.secondaryLabel,
        )
    }
}

/**
 * Fraction of the back glyph's box that is empty ink padding on the left.
 *
 * Taken from the symbol's own geometry (`chevron.down` spans x 4.8..19.2 on a 24pt grid), so the
 * nudge is derived from the glyph rather than eyeballed.
 */
private const val ChevronInkInsetFraction = 4.8f / 24f
