package com.yunyin.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.yunyin.music.ui.theme.AppTheme

/**
 * Liquid-glass material for a **control floating on the top strip** — the search field's capsule and
 * the collection bar's circular buttons.
 *
 * This is deliberately a different surface from the strip behind it: the strip is a progressive blur
 * over the page (no capsule, no rim, no refraction), and these controls are small glass objects
 * floating on it, so they get the bottom chrome's liquid-glass recipe instead. The distinction is
 * the user's own: the strip must not look like liquid glass, but the search box and the two buttons
 * must.
 *
 * The recipe is the chrome's, in the chrome's order:
 * `vibrancy()` (colour lift) → `blur()` (diffusion) → `lens()` (edge refraction), then the
 * translucent tint. The two numbers are the chrome's exported knobs ([GlassBlurRadiusDp] and
 * [GlassSurfaceAlphaValue]) — this file must not carry a second pair. `lens()` needs a
 * `CornerBasedShape`, which is why every call site passes a capsule or a circle.
 *
 * The [backdrop] is the **strip's own recorded page layer**. The strip (and therefore these controls)
 * is composed outside that layer, so sampling it cannot include the controls themselves. Do not pass
 * a new per-control recorded layer here, and do not move the controls inside the strip's
 * `layerBackdrop` box.
 */
@Composable
internal fun Modifier.liquidGlassControl(
    backdrop: Backdrop,
    shape: Shape,
    /** Opaque tint; the glass alpha is applied here so call sites cannot drift from the chrome. */
    tint: Color = AppTheme.palette.secondaryBackground,
): Modifier {
    val containerColor = tint.copy(alpha = GlassSurfaceAlphaValue)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(GlassBlurRadiusDp.toPx())
            lens(ControlLensX.toPx(), ControlLensY.toPx())
        },
        // No drop shadow, deliberately. The strip places its children in a layer clipped to the
        // strip's rectangle (DrawBackdropModifier's `layoutLayerBlock`: clip = true, shape =
        // RectangleShape), so a control's shadow that spills past the strip's bottom edge is cut
        // into exactly the hard line the strip must not have — it showed as a grey band under the
        // search capsule. The glass still reads from the highlight rim and the refraction.
        shadow = null,
        onDrawSurface = { drawRect(containerColor) },
    )
}

/**
 * The chrome's constant edge refraction, repeated here because the frozen chrome does not export it.
 * Same values as `chromePanelMaterial`, so a control and the bottom bar bend the backdrop alike.
 */
private val ControlLensX = 16.dp
private val ControlLensY = 32.dp
