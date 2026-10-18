package com.yunyin.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported
import com.yunyin.music.ui.theme.AppTheme

/**
 * The frosted top bar — an iOS-style **progressive blur** strip shared by every screen that has a
 * header over scrolling content.
 *
 * This is deliberately **not** a liquid-glass surface: no capsule, no rounded ends, no rim, no
 * shadow, no `lens()` refraction and no visible glass edge. It is a full-width rectangle whose top
 * edge is the screen edge, carrying:
 *
 *  - a blur of the content scrolling underneath that **fades out toward the bottom** of the strip,
 *    so the strip dissolves into sharp content instead of ending on a seam;
 *  - a colour gradient (the page background by default) over that blur, dense at the top and gone
 *    at the bottom edge — that is what keeps the status-bar icons and the title legible over busy
 *    artwork.
 *
 * ## How the blur is masked
 *
 * The library's documented "alpha-masked progressive blur" is used: the backdrop is blurred with the
 * chrome's blur radius and an AGSL runtime shader multiplies the blur and the tint by two ramps that
 * run from the top of the strip to just above its bottom edge
 * ([TopBarAlphaMaskShader]). The shader writes a *transparent* result where both ramps have reached
 * zero, so what shows there is the untouched sharp content — that is the dissolve.
 *
 * ## The fallback exists because a shader can silently render nothing
 *
 * `isRuntimeShaderSupported()` is an SDK-level check, not a driver/compile verdict: a shader that
 * fails to build renders an empty strip, and this project has already shipped "the blur renders
 * nothing" more than once. When the runtime-shader path is not available the strip falls back to a
 * plain `blur()` plus a `Brush.verticalGradient` of the tint over it (drawn in `onDrawSurface`). That
 * cannot mask the blur itself — the blur is uniform across the strip — but the gradient thins toward
 * the bottom so the strip still reads as blurred at the top and dissolving below; with the chrome's
 * small radius the bottom transition is soft. Both paths are the same visual intent, so a device
 * without the shader gets a softer version of the same strip rather than a blank one.
 *
 * ## The recording arrangement (unchanged — this is what makes any of it render)
 *
 * The page content is recorded into a [LayerBackdrop] **whose own draw block paints the page
 * background first** ([rememberGlassTopBarBackdrop]); the bar is composed **outside** that layer.
 * Without the background fill the layer has transparent holes wherever the content does not paint
 * and a `RenderEffect` over transparency has nothing to show; with the bar inside its own layer it
 * would sample itself. Do not switch this to `ViewBackdrop`, a hand-recorded `GraphicsLayer`, or a
 * draw gate — all three were tried on the bottom chrome and failed.
 *
 * ## Usage
 *
 * Put the scrolling content in a sibling `Box(Modifier.fillMaxSize().layerBackdrop(backdrop))`,
 * give that content `top = glassTopBarTopPadding()` as content padding so it starts below the strip
 * and scrolls under it, then compose this bar last:
 *
 * ```
 * val backdrop = rememberGlassTopBarBackdrop()
 * Box(Modifier.fillMaxSize()) {
 *     Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { /* scrollable content */ }
 *     GlassTopBar(backdrop, Modifier.align(Alignment.TopCenter)) { /* title row */ }
 * }
 * ```
 */
@Composable
internal fun GlassTopBar(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /** Colour of the scrim gradient (and of the shader's tint). Defaults to the page background. */
    tint: Color = AppTheme.palette.background,
    /** Opacity of the tint at the top of the strip. The collection bar ramps this with its scroll. */
    tintIntensity: Float = DefaultTopBarTintIntensity,
    /** Height of the content row below the status-bar inset. */
    contentHeight: Dp = GlassTopBarContentHeight,
    /**
     * Drawn between the blur and the content. The collection bar uses it for the cover-derived scrim
     * that keeps its measured ink legible over artwork.
     */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    // The chrome's own blur knob: the top strip is the same frost, used on the other edge.
    val blurRadiusPx = with(density) { GlassBlurRadiusDp.toPx() }
    val shaderAvailable = isRuntimeShaderSupported()
    val intensity = tintIntensity.coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .drawPlainBackdrop(
                // `drawPlainBackdrop`, not `drawBackdrop`: no highlight, shadow or innerShadow — a
                // rect strip with any of those draws the rim the user asked us to remove.
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    blur(blurRadiusPx)
                    if (shaderAvailable) {
                        runtimeShaderEffect(
                            key = TopBarAlphaMaskKey,
                            shaderString = TopBarAlphaMaskShader,
                            uniformShaderName = "content",
                        ) {
                            // The shader's `coord` is in the render-effect layer's pixels, and the
                            // library may record that layer `padding` larger than the visible node
                            // (effects/Blur.kt raises `padding` for blur when an effect already
                            // exists, and DrawBackdropModifier draws the layer at -padding). Convert
                            // the coordinate back to visible-strip space with that padding and size
                            // the mask against the visible height — never against a padded size, or
                            // the fade finishes below the visible bottom and the clip cuts it into a
                            // hard line.
                            val layerPadding = padding
                            val visibleHeight =
                                (size.height - 2f * layerPadding).coerceAtLeast(1f)
                            setFloatUniform("size", size.width, visibleHeight)
                            setFloatUniform("padding", layerPadding)
                            setColorUniform("tint", tint)
                            setFloatUniform("tintIntensity", intensity)
                        }
                    }
                },
                // Fallback only. The shader path dissolves the blur itself, so it needs no surface
                // pass; without the shader the blur is uniform and this gradient carries the fade.
                // Its alpha reaches zero exactly at the node's bottom edge (the `onDrawSurface`
                // DrawScope is the node itself — no padding shift here), so it cannot stop early and
                // leave a band.
                onDrawSurface = if (shaderAvailable) {
                    null
                } else {
                    {
                        drawRect(
                            Brush.verticalGradient(
                                0f to tint.copy(alpha = intensity),
                                0.58f to tint.copy(alpha = intensity * 0.9f),
                                1f to tint.copy(alpha = 0f),
                            ),
                        )
                    }
                },
            ),
    ) {
        overlay?.invoke(this)
        Box(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(contentHeight),
            content = content,
        )
    }
}

/**
 * The alpha-mask applied to the blurred backdrop: the documented `AlphaMask` shader, with the two
 * ramps tuned to finish on **the same coordinate**, a margin above the strip's visible bottom edge.
 *
 * The seam this guards against: the material is drawn as a render-effect layer, and a ramp that is
 * still partially opaque where that layer is clipped by the strip's rectangle produces an abrupt
 * step — blurred content above the cut, sharp content below it ("顶栏现在为什么有断层"). Two things
 * can put the ramp past the visible bottom: a layer recorded with the blur's `padding` around it
 * (the coordinate space is the padded layer, not the node), or plain rasterisation/rounding at the
 * last row. Both are handled structurally:
 *
 *  - `y = coord.y - padding` converts from layer pixels back to visible-strip pixels, and `size.y`
 *    is the visible height (see the uniform block in [GlassTopBar]);
 *  - both ramps reach zero by **88 %** of the visible height, so the final ~12 % (≈ 7–10 dp on a
 *    phone) is fully transparent before anything can be clipped. The blur and the tint land on the
 *    same coordinate, so they cannot read as two separate bands.
 *
 * `blurAlpha` is full over the top 42 % (status bar and upper title row) and eases out from there;
 * `tintAlpha` holds the colour over the status bar and title (full until 58 %) and eases to zero on
 * the same 88 % line, so the title stays legible while the strip still dissolves into the content.
 * `mix(..., tintIntensity)` keeps the blurred artwork visible through the tint instead of painting
 * a solid slab.
 *
 * AGSL's `smoothstep` is only defined for `edge0 < edge1`, so the documented top-to-bottom ramp is
 * written as `1 - smoothstep(lower, upper, y)` — the same curve, with the documented edges in
 * ascending order.
 */
private val TopBarAlphaMaskShader = """
uniform shader content;
uniform float2 size;
uniform float padding;
layout(color) uniform half4 tint;
uniform float tintIntensity;

half4 main(float2 coord) {
    float y = coord.y - padding;
    float h = size.y;
    float blurAlpha = 1.0 - smoothstep(h * 0.42, h * 0.88, y);
    float tintAlpha = 1.0 - smoothstep(h * 0.58, h * 0.88, y);
    return mix(content.eval(coord) * blurAlpha, tint * tintAlpha, tintIntensity);
}
""".trimIndent()

/** Key under which the library caches the compiled [TopBarAlphaMaskShader]. */
private const val TopBarAlphaMaskKey = "top-bar-alpha-mask"

/** Default tint opacity at the top of the strip. */
internal const val DefaultTopBarTintIntensity = 0.62f

/**
 * Records the page content for a [GlassTopBar].
 *
 * The background **must** be painted into the layer: it is the field the blur has to work with
 * wherever the content itself does not paint. This is the single most failure-prone line in the
 * whole arrangement — see [GlassTopBar].
 */
@Composable
internal fun rememberGlassTopBarBackdrop(): LayerBackdrop {
    val background = AppTheme.palette.background
    // The record block is `remember`ed against the colour: it is a `remember` key inside
    // `rememberLayerBackdrop`, so an inline lambda instance that changed every composition would
    // rebuild the layer every frame. Keying it on the colour also makes a theme change recreate the
    // painted field instead of keeping the previous background painted over the new one.
    val onDraw: ContentDrawScope.() -> Unit = remember(background) {
        { drawRect(background); drawContent() }
    }
    return rememberLayerBackdrop(onDraw = onDraw)
}

/**
 * Top content padding for a screen whose content scrolls under a [GlassTopBar]: the strip's own
 * height plus the status-bar inset, so the first row starts just below the strip and scrolls up
 * under it.
 */
@Composable
internal fun glassTopBarTopPadding(): Dp {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return statusBar + GlassTopBarContentHeight
}

/** Height of the strip's title row, excluding the status-bar inset. A navigation-bar allowance. */
internal val GlassTopBarContentHeight = 48.dp
