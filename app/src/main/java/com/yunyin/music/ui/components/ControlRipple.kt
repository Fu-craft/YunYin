package com.yunyin.music.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Press indication for dark surfaces.
 *
 * A white, low-alpha ripple, because the glyphs and labels on these surfaces are light: a press
 * should brighten the area rather than darken it. 24% white is clearly visible without flashing.
 *
 * The failure this replaces is worth stating, because it kept coming back: the default Material
 * ripple is drawn in the theme's `onSurface`, and `onSurface` is only correct for the *page*
 * background. A control sitting on a dark surface — the player's chrome, a sheet, a colour field
 * derived from artwork — therefore got whatever the page happened to be using, which on a light
 * theme is near-black: a dark grey block appearing under the finger. (The default is also bounded, so
 * it clipped to the control's own box, which is what made the shape read as a hard-edged rectangle.)
 *
 * [bounded] is false for the round, background-less icon controls, where a soft circular glow suits
 * the glyph; it is true for pills and rows, whose ripple should stay inside their own shape.
 * [color] exists for surfaces whose ink is not white — the playlist hero draws its ink from the
 * artwork, and passes the same colour here so the press matches the text. Only the ink changes;
 * every touch target is untouched.
 *
 * The instance is remembered per composition and reused by every control, so the app does not
 * allocate a ripple node per button per recomposition.
 */
@Composable
fun rememberControlRipple(
    bounded: Boolean = false,
    color: Color = ControlRippleColor,
): IndicationNodeFactory = remember(bounded, color) {
    ripple(
        bounded = bounded,
        color = color,
    )
}

/** Press indication for dark surfaces: white, low alpha. */
val ControlRippleColor = Color.White.copy(alpha = 0.24f)
