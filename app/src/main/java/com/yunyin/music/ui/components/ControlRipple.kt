package com.yunyin.music.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * The tap indication used by the player's controls.
 *
 * The player's controls sit on dark translucent surfaces, and the default Material ripple is drawn
 * in the theme's *on-surface* colour. This app's `MaterialTheme` is called without a `colorScheme`,
 * so it falls back to the *light* scheme, where `onSurface` is near-black — which is why pressing a
 * control showed a dark grey block rather than a press highlight. (The default is also bounded, so it
 * clips to the control's own box, which is what made the shape read as a hard-edged rectangle.)
 *
 * Two things change, both deliberate:
 *
 *  - **White, low alpha.** The glyphs are white on a dark surface, so a press should brighten the
 *    area rather than darken it. 24% white is clearly visible without flashing.
 *  - **Shape matched to the control.** [bounded] is false for the round, background-less icon
 *    controls, where a soft circular glow suits the glyph and avoids any hard edge; it is true for
 *    the sheet's pill buttons, whose ripple should stay inside their own pill rather than spilling
 *    out of it. Only the ink changes — every touch target is untouched.
 *
 * The instance is remembered per composition and reused by every control, so the player does not
 * allocate a ripple node per button per recomposition.
 */
@Composable
fun rememberControlRipple(bounded: Boolean = false): IndicationNodeFactory = remember(bounded) {
    ripple(
        bounded = bounded,
        color = ControlRippleColor,
    )
}

/** Press indication for the player controls: white, low alpha. */
private val ControlRippleColor = Color.White.copy(alpha = 0.24f)
