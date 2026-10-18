package com.yunyin.music.ui.components

/*
 * The floating liquid-glass chrome — the bar and the mini player.
 *
 * PORTED FROM AM-plus-plus-main (the reference project the user asked to take this from):
 *
 *   glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt
 *       the bar: three drawn layers (material / accent mask / travelling selection), the drag
 *       physics, the squeeze, the press growth, the material and selection modifiers.
 *   glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt
 *       one tab: icon over label, the press-scale local.
 *   glass/src/main/kotlin/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt
 *       the evolved drag/spring driver: tap detection, independent pressed Y scale, and
 *       cancellation of obsolete motion/press jobs with complete release cleanup.
 *   glass/src/main/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt
 *       the press glow, including the AGSL falloff and the native press entry points.
 *   glass/src/main/kotlin/com/kyant/backdrop/catalog/utils/DragGestureInspector.kt
 *       the custom pointer loop that reports a press before touch slop.
 *   backdrop/src/commonMain/kotlin/dev/amenhancer/backdrop/VibrantBlur.kt
 *       the cached `vibrancy() + blur()` prefix, rebuilt only when the radius changes.
 *   glass/src/main/kotlin/dev/amenhancer/glass/GlassPressGeometry.kt
 *       the guarded panel press scale.
 *
 * All of it is a derivative of AndroidLiquidGlass / Backdrop 2.0.1
 * (https://github.com/Kyant0/AndroidLiquidGlass, commit 65ab177e90e5c1d8c62e70cf7755841982da65f6,
 * Apache License 2.0). See also THIRD_PARTY_NOTICES.md.
 *
 * WHAT WAS **NOT** PORTED, and why
 *
 * The reference bar's scroll-collapse machinery (`GlassBarCollapse*`, `GlassCollapseModifiers`,
 * `BottomScrim`, the compact face, the detached search capsule, the native hit test in
 * `GlassHostView`). It is not self-contained: it is driven by the Xposed host's scroll progress, by
 * `dev.amenhancer.module.model.EnhancementDefaults`, and by a native touch bridge that only exists
 * in that module. Porting it would mean inventing the host half. The three-layer bar, its physics
 * and its material — the parts that actually render the glass — are ported unchanged. The
 * `minLayerSize` / `clipCompositingStrategy` options the collapse needs are additions in the
 * AM++ patch to `DrawBackdropModifier`; our bar never asks for them, so the Maven artifact (2.0.1)
 * is enough and the library does not have to be vendored.
 *
 * WHY THE BLUR NOW RENDERS (this is the actual fix, not a rewrite for taste)
 *
 * The previous attempts asked Compose to sample itself: `rememberLayerBackdrop()` + a recorded
 * `GraphicsLayer` of the page, which Backdrop re-draws inside the glass. On the device only the
 * translucent tint appeared — the layer carrying the sampled page and its render effect never drew,
 * so the "frost" was a tint over pixel-sharp text. (The project had already measured this once: with
 * the fill transparent and the blur at 150dp the edge energy behind the bar stayed at 1.16x, i.e. no
 * smoothing.) The reference never does that: its `Backdrop` is `Backdrop`, which captures the
 * real Android view tree underneath the glass into a `RenderNode` and draws that. Platform display
 * list in, blur out.
 *
 * A pure Compose app has one window, so [Backdrop] is pointed at the window's decor view and the
 * glass is kept out of its own capture by a draw gate (`Modifier.excludedFromCapture`): while the
 * capture is running every panel in this file draws nothing, exactly as the reference's glass lives
 * in an overlay window its source view does not contain.
 *
 * THE TWO KNOBS
 *
 * [GlassBlurRadius] is the material's blur radius: it reaches `VibrantBlur`, which is the reference's
 * cached `vibrancy() + blur()` prefix. [GlassSurfaceAlpha] is the tint over the blurred backdrop and
 * is read by the colour, never written twice — the earlier rounds of "raise the alpha" did nothing
 * because the constant was not what the colour read.
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The app's top-level screens.
 *
 * The tab model and the bar that presents it belong together, so they live in the same file: a
 * separate file for the entries is the kind of split that lets one drift from the other.
 *
 * **Not every entry is a slot in the bar.** [Search] is a screen — reached from the home screen's own
 * search box (`HomeScreen.onSearchClick` → `MainActivity`'s `tab` state) — and the bar renders only
 * [Home] and [Library]. The enum stays whole because it is also the screen router: `MainActivity`
 * switches the visible screen on it, so deleting the entry would delete the search screen.
 */
enum class PlayerTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home("主页", SfIcons.House),
    Search("搜索", SfIcons.MagnifyingGlass),
    Library("资料库", SfIcons.Person),
}

/**
 * The tabs the bar actually renders, in order. [PlayerTab.Search] is deliberately absent: the home
 * screen already has a search box, so a slot for the same screen is a second door to one room.
 *
 * Kept next to the enum so a new *tab* cannot be added to the bar by editing the enum alone — it must
 * be listed here as well — while a new *screen* can still route through the enum.
 */
private val BarTabs = listOf(PlayerTab.Home, PlayerTab.Library)

/**
 * Horizontal inset of the floating chrome from the screen edges.
 *
 * Applied **once**, to the chrome's row in `MainActivity`. The stacked chrome inset the bar inside its
 * own wrapper and the mini player inside its own, which was fine while each sat on its own row; on one
 * row the two panels are inset together, so the inset belongs to the row. Insetting both panels would
 * inset the row twice (16 dp on the bar's side plus another 16 dp on the card's).
 */
internal val TabBarInset = 16.dp

/**
 * Height of the one-row chrome: the tab bar's material **and** the mini player capsule.
 *
 * The stacked chrome had two heights — a 64 dp bar above a 60 dp card — which was invisible while they
 * sat on separate rows. On one row they are two ends of the same line, so they share one value. 64 dp is
 * the ported bar's material height, so the reference's own 64 / 56 / 4 dp metrics are unchanged; the
 * mini player moved up to the bar, not the bar down to the card.
 */
private val FloatingChromeHeight = 64.dp

/**
 * The gap under the one-row chrome, on top of the navigation-bar inset.
 *
 * Lives next to [FloatingChromeHeight] because together they are the chrome's whole footprint, and the
 * scrolling screens read both through [chromeContentBottomPadding]. Moved here from `MainActivity` when
 * the chrome became one row, so a screen's bottom padding cannot drift from the panels it clears.
 */
internal val FloatingChromeBottomGap = 10.dp

/**
 * Bottom content padding for a scrolling screen that must stay clear of the floating chrome.
 *
 * These screens used to hard-code 140/150 dp, sized for the stacked chrome (a 60 dp mini player above a
 * 64 dp bar, 8 dp apart, plus the 10 dp bottom gap). The one-row chrome is only
 * [FloatingChromeHeight] + [FloatingChromeBottomGap] tall, so those literals now reserve ~70 dp of empty
 * space above the glass. Computing from the chrome's own values and the live navigation-bar inset keeps
 * the last row a comfortable [extra] above the panels, on gesture-nav and 3-button devices alike, and
 * it cannot go stale if the chrome changes again.
 */
@Composable
internal fun chromeContentBottomPadding(extra: Dp = 12.dp): Dp =
    FloatingChromeHeight +
        FloatingChromeBottomGap +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        extra

/**
 * Diameter of the artwork inside the mini player capsule.
 *
 * A circle now, as in the reference's one-row chrome, so this is its diameter rather than the side of a
 * rounded tile. 38 dp inside the 64 dp row leaves 13 dp of glass above and below, and keeps the cover
 * from eating the card's narrow width — on a row the card only gets what the tab capsule does not take,
 * so the cover is sized for the title rather than for itself.
 */
private val MiniArtworkSize = 38.dp

/**
 * Corner radius of the mini player's artwork tile.
 *
 * Public and kept for the cover transition's radius bookkeeping. The chrome draws the artwork through a
 * [CircleShape] clip as well (see [FloatingMiniPlayer]), so the circle is the shape that is actually seen.
 */
val MiniArtworkCorner = 8.dp

/**
 * Width of one tab slot.
 *
 * The bar used to divide the screen's *measured* width between its tabs (`BoxWithConstraints` +
 * `fillMaxWidth()`), which was right while it was the only panel on its row. In the one-row chrome the
 * bar wraps its content and the mini player takes what is left, so the slot width has to be a real
 * number **before** layout: the drag physics and the travelling pill both read it synchronously, and a
 * value derived from the row's width would be the row's width, not the bar's.
 *
 * The value: a 28 dp glyph over a 12 sp label needs ~48 dp of slot; 56 dp keeps that comfortable and
 * still wraps to a compact capsule (2 slots × 56 dp + the 8 dp of 4 dp gutters = 120 dp). It also makes
 * the travelling selection — 56 dp wide by the 56 dp inner height — a **circle**, which is the circular
 * highlight the reference's selected tab has.
 */
private val TabSlotWidth = 56.dp

/**
 * A true capsule. The reference uses `com.kyant.shapes.Capsule()`; a 50 % rounded rectangle is the
 * same outline, and the library's `lens()` understands `CornerBasedShape` on its own, so the
 * refraction shader gets correct corner radii from this without pulling in `gaze-capsule`'s shape.
 */
private val Capsule: Shape = RoundedCornerShape(percent = 50)

/**
 * The material's blur radius — one of the material's **two** knobs.
 *
 * It feeds the reference's [VibrantBlur] prefix (a cached `vibrancy() + blur()` render effect), which
 * is the reference's material recipe. Raising it thickens the frost; it does not change how opaque
 * the surface is. It must stay in step with `tools/verify_glass_frost.py`, which reads it from here.
 */
private val GlassBlurRadius = 4.dp

/**
 * How opaque the material is — the material's other knob.
 *
 * The reference's own tints are `0xFFFAFAFA` / `0xFF121212` at 0.4. 0.45 keeps that glass look while
 * leaving the blur in charge of the structure behind the bar; the constant is what the colour reads
 * (a literal alpha is what made three rounds of "raise it" no-ops in the earlier attempts).
 *
 * This is the knob for "I can still read what is behind the bar": lower it towards 0.3 for a more
 * transparent, more glass-like panel; raise it towards 0.95 for a solid one.
 */
private const val GlassSurfaceAlpha = 0.5f

/**
 * The two knobs above, exported for the **top-bar material** ([GlassTopBar]).
 *
 * The values are read through these accessors, never re-declared: the top bar is the bottom chrome's
 * material used on another edge, and two `4.dp`/`0.5f` literals in two files is exactly how the two
 * surfaces would drift apart. The originals stay private — this file remains the one place the
 * material is defined.
 */
internal val GlassBlurRadiusDp: Dp get() = GlassBlurRadius
internal val GlassSurfaceAlphaValue: Float get() = GlassSurfaceAlpha

/**
 * Draws nothing while [backdrop] is recording its source view.
 *
 * This is the single-window equivalent of the reference's separate overlay host: the captured frame
 * must not contain the glass that then samples it. A plain field read, so it costs nothing on the
 * frames where no capture is running.
 */

// ---------------------------------------------------------------------------- the material

/**
 * Ported from AM-plus-plus-main
 * `backdrop/src/commonMain/kotlin/dev/amenhancer/backdrop/VibrantBlur.kt`.
 *
 * An immutable `vibrancy() + blur(radiusPx)` render-effect prefix, built once per radius.
 *
 * The reference builds it against the library's `BackdropEffectScopeImpl`, which is `internal`; the
 * same effect is constructed here from the Android builders and handed to the public
 * `BackdropEffectScope.effect(...)`. The chain is the library's own order and produces the same
 * result: `vibrancy()` installs a colour-filter effect, `blur()` chains a `BlurEffect` **outside**
 * it, so the colour is stretched first and diffused second, exactly as `BackdropEffectScope
 * .vibrancy().then(.blur())` does.
 *
 * The padding is the blur radius, because the library's `blur()` raises it whenever an effect is
 * already present; the layer has to be that much larger than the panel or the blur clamps along the
 * capsule's edge.
 */
@Immutable
class VibrantBlur(val radiusPx: Float) {

    // Validated and built by the initialiser rather than in an `init` block: this file also holds the
    // bar's composables, and the project's init-order guard is file-scoped (it flags any `by`-delegated
    // property declared below the file's first `init {`). The reference's `require` is kept inside
    // `buildVibrantBlur`, so a bad radius still fails at construction with the same message.
    private val cachedEffect: RenderEffect? = buildVibrantBlur(radiusPx)

    /** Replaces `vibrancy(); blur(radiusPx)` at the start of an effect block, before `lens()`. */
    fun applyTo(scope: BackdropEffectScope) {
        val effect = cachedEffect ?: return
        scope.effect(effect)
        if (scope.padding < radiusPx) scope.padding = radiusPx
    }
}

/**
 * The reference's `vibrancy()` is a saturation lift of 1.5 on the backdrop, so a strong blur reads as
 * diffused colour instead of grey wash. The matrix is the same one the library's `colorControls`
 * builds for `saturation = 1.5`; it is written out rather than routed through the library because the
 * result has to be cached as a whole effect.
 */
private fun buildVibrantBlur(radiusPx: Float): RenderEffect? {
    require(radiusPx.isFinite() && radiusPx >= 0f) {
        "VibrantBlur requires a finite, non-negative radius but got $radiusPx"
    }
    if (!isRenderEffectSupported() || radiusPx <= 0f) return null
    val vibration = android.graphics.RenderEffect.createColorFilterEffect(
        android.graphics.ColorMatrixColorFilter(vibrantColorMatrix()),
    )
    val blur = android.graphics.RenderEffect.createBlurEffect(
        radiusPx,
        radiusPx,
        android.graphics.Shader.TileMode.CLAMP,
    )
    // createChainEffect(outer, inner): the blur is applied to the colour-filtered backdrop.
    return android.graphics.RenderEffect.createChainEffect(blur, vibration).asComposeRenderEffect()
}

private fun vibrantColorMatrix(): android.graphics.ColorMatrix {
    val saturation = 1.5f
    val invSat = 1f - saturation
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat
    val s = saturation
    return android.graphics.ColorMatrix(
        floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/**
 * Ported from AM-plus-plus-main
 * `glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt`
 * (`glassPanelMaterial`), minus the collapse-only options.
 *
 * The bar's own material: the blurred source view under the translucent tint, with the press growth
 * applied in the layer phase. `lens(24dp, 24dp)` is the reference's constant edge refraction.
 */
private fun glassPanelMaterial(
    backdrop: Backdrop,
    containerColor: Color,
    dampedDragAnimation: DampedDragAnimation,
    material: VibrantBlur,
): Modifier = Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { Capsule },
    effects = {
        material.applyTo(this)
        lens(16f.dp.toPx(), 32f.dp.toPx())
    },
    layerBlock = {
        val progress = dampedDragAnimation.pressProgress
        val scale = glassPressScale(size.width, 8f.dp.toPx(), progress)
        scaleX = scale
        scaleY = scale
    },
    onDrawSurface = { drawRect(containerColor) },
)

/**
 * Ported from AM-plus-plus-main (`glassRefractionMaterial`), minus the collapse-only options.
 *
 * The invisible row that the selection droplet refracts: same material, but its lens and highlight
 * are gated on the press so a resting bar asks the shader for nothing.
 */
private fun glassRefractionMaterial(
    backdrop: Backdrop,
    containerColor: Color,
    dampedDragAnimation: DampedDragAnimation,
    material: VibrantBlur,
): Modifier = Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { Capsule },
    effects = {
        val progress = dampedDragAnimation.pressProgress
        material.applyTo(this)
        lens(16f.dp.toPx() * progress, 32f.dp.toPx() * progress)
    },
    highlight = {
        val progress = dampedDragAnimation.pressProgress
        Highlight.Default.copy(alpha = progress)
    },
    onDrawSurface = { drawRect(containerColor) },
)

/**
 * Ported from AM-plus-plus-main (`glassSelectionMaterial`), minus the collapse-only options.
 *
 * The travelling selection: a piece of glass in its own right, sampling the source view **and** the
 * accent-tinted row (`rememberCombinedBackdrop`), so the glyph under it is the accent colour without
 * recomposing the tab. At rest it is only a faint wash; under the finger it becomes a lens.
 */
private fun glassSelectionMaterial(
    backdrop: Backdrop,
    dampedDragAnimation: DampedDragAnimation,
    isLightTheme: Boolean,
): Modifier = Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { Capsule },
    effects = {
        val progress = dampedDragAnimation.pressProgress
        lens(
            10f.dp.toPx() * progress,
            14f.dp.toPx() * progress,
            chromaticAberration = true,
        )
    },
    highlight = {
        val progress = dampedDragAnimation.pressProgress
        Highlight.Default.copy(alpha = progress)
    },
    shadow = {
        val progress = dampedDragAnimation.pressProgress
        Shadow(alpha = progress)
    },
    innerShadow = {
        val progress = dampedDragAnimation.pressProgress
        InnerShadow(radius = 8f.dp * progress, alpha = progress)
    },
    layerBlock = {
        scaleX = dampedDragAnimation.scaleX
        scaleY = dampedDragAnimation.scaleY
        val velocity = dampedDragAnimation.velocity / 10f * dampedDragAnimation.pressProgress
        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
    },
    onDrawSurface = {
        val progress = dampedDragAnimation.pressProgress
        drawRect(
            if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
            alpha = 1f - progress,
        )
        drawRect(Color.Black.copy(alpha = 0.03f * progress))
    },
)

/**
 * Ported from AM-plus-plus-main
 * `glass/src/main/kotlin/dev/amenhancer/glass/GlassPressGeometry.kt`.
 *
 * A graphics layer can run its block before it has a measured size; dividing the 8dp growth by a
 * zero or non-finite width would make the whole panel disappear for a frame.
 */
private fun glassPressScale(widthPx: Float, expansionPx: Float, progress: Float): Float {
    if (!widthPx.isFinite() || widthPx <= 0f || !expansionPx.isFinite() || expansionPx < 0f) return 1f
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    if (p == 0f) return 1f
    return (1f + expansionPx / widthPx * p).takeIf { it.isFinite() } ?: 1f
}

// ---------------------------------------------------------------------------- the public bar

/**
 * Floating tab bar — the app-facing wrapper around the reference's [LiquidBottomTabs].
 *
 * This is the only layer that knows about [PlayerTab]: the reference's component speaks in tab
 * indices (`selectedTabIndex: () -> Int`, `onTabSelected: (Int) -> Unit`), and the app speaks in
 * [PlayerTab]. The floating inset is applied by the chrome's row in `MainActivity` ([TabBarInset]) so
 * that the row — bar **and** mini player — is inset once; this wrapper adds no padding.
 *
 * The bar presents [BarTabs] only: Home and Library. [PlayerTab.Search] keeps its enum entry because it
 * still routes the screen, but it has no slot — see [BarTabs] and [PlayerTab].
 *
 * [backdrop] is the ported `Backdrop`: the real window pixels underneath the bar. Both this bar
 * and the mini player take the same instance, so they are literally one material.
 */
@Composable
fun FloatingTabBar(
    selected: PlayerTab,
    onSelect: (PlayerTab) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val tabs = BarTabs
    // Search has no slot, so there is no index to point the selection at while its screen is open. It
    // maps to Home rather than to "nothing selected": the search screen is Home's own child (the search
    // box that opens it lives there), and a no-selection state would have to hide the travelling
    // droplet and teach the drag physics a fourth resting place for a screen the user reaches from Home.
    val selectedIndex = tabs.indexOf(selected).takeIf { it >= 0 } ?: tabs.indexOf(PlayerTab.Home)
    val currentSelection = rememberUpdatedState(selectedIndex)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val selectedTabIndex = remember { { currentSelection.value } }
    val onTabSelected = remember { { index: Int -> currentOnSelect.value(tabs[index]) } }
    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White

    LiquidBottomTabs(
        selectedTabIndex = selectedTabIndex,
        onTabSelected = onTabSelected,
        // Tapping the tab that is already selected is Apple's reselect; the reference added this
        // hook for hosts whose selection means more than the index. Here it is the same callback,
        // which is a no-op for an unchanged tab.
        onSelectedTabClick = onTabSelected,
        backdrop = backdrop,
        tabsCount = tabs.size,
        modifier = modifier,
    ) {
        tabs.forEachIndexed { index, tab ->
            LiquidBottomTab(onClick = { onTabSelected(index) }) {
                // The reference's tab content: a 24dp-ish glyph in the author's black/white content
                // colour and an 11sp label. `LiquidBottomTabs` re-draws this same row tinted inside
                // the travelling selection, which is what makes the selected glyph accent-coloured.
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp),
                )
                BasicText(
                    text = tab.label,
                    style = TextStyle(color = contentColor, fontSize = 12.sp, fontFamily = SFPro),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- the bar

/**
 * Ported from AM-plus-plus-main
 * `glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt`, with the
 * collapse machinery removed (see the file header).
 *
 * The stacked drawings that make the selection read as liquid, bottom to top:
 *
 *  1. the panel — the blurred source view under the tint, then the press glow, then the content;
 *  2. an **invisible** copy of that row, recorded into a layer of its own and re-tinted with the
 *     accent colour — a mask, not a decoration. It is what the selection samples;
 *  3. the travelling selection capsule, which draws the source view *and* that mask under itself,
 *     so the selected tab's glyph turns accent-coloured without recomposing the tab.
 */
@Composable
private fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    onSelectedTabClick: ((Int) -> Unit)? = null,
    accentOverride: Color = Color.Unspecified,
    panelHeight: Dp = FloatingChromeHeight,
    panelBlur: Dp = GlassBlurRadius,
    content: @Composable RowScope.() -> Unit,
) {
    val selectedTabClick = rememberUpdatedState(onSelectedTabClick)
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (accentOverride != Color.Unspecified) {
        accentOverride
    } else if (isLightTheme) {
        Color(0xFF0088FF)
    } else {
        Color(0xFF0091FF)
    }
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(GlassSurfaceAlpha)
        else Color(0xFF121212).copy(GlassSurfaceAlpha)

    // The accent mask the selection samples. Recorded by `layerBackdrop` below.
    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val radiusPx = with(density) { panelBlur.toPx() }
    // One cached effect prefix per radius; the reference rebuilds the material only when it changes.
    val material = remember(radiusPx) { VibrantBlur(radiusPx) }
    val animationScope = rememberCoroutineScope()
    val offsetAnimation = remember { Animatable(0f) }
    val squeeze = with(density) { 4f.dp.toPx() }

    // The bar's width is its own content now, so the slot width is a constant known before layout:
    // one slot per tab (each a 12 sp label under a 28 dp glyph), plus the reference's 4 dp gutter on
    // each end. `tabWidth` is the drag/pill unit; `panelWidth` is the measured size of the material.
    val tabWidth = with(density) { TabSlotWidth.toPx() }
    val panelWidth = TabSlotWidth * tabsCount + 8.dp
    val panelWidthPx = with(density) { panelWidth.toPx() }

    Box(
        modifier = modifier.width(panelWidth),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The squeeze: the whole panel leans into a horizontal swipe and springs back. Read only
        // from draw/layer lambdas, so the lean costs a redraw rather than a recomposition.
        val panelOffset by remember(density, panelWidth) {
            derivedStateOf {
                squeezeOffset(offsetAnimation.value, panelWidthPx, squeeze)
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        // Both the drag and the glow are created once, so they must read the *live* geometry rather
        // than the values captured at the first composition. This is the reference's own pattern
        // (`liveGeometry` / `PillCentre`); without it a rotation leaves the drag scaled to the old
        // slot width and the glow parked at the old centre. The squeeze is recomputed from the glow's
        // own width inside the draw lambda, so it never forces a recomposition either.
        val liveTabWidth = rememberUpdatedState(tabWidth)
        val liveIsLtr = rememberUpdatedState(isLtr)
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                onTap = { selectedTabClick.value?.invoke(currentIndex) },
                pressedScale = 1.5f,
                pressedScaleY = 1.45f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f),
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / liveTabWidth.value *
                            if (liveIsLtr.value) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }
        // The light is placed on the selection's centre and rides it. Clipped to the capsule the
        // glass draws, so the press glow stays round on it.
        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    val panelOffset = squeezeOffset(offsetAnimation.value, size.width, squeeze)
                    Offset(
                        if (liveIsLtr.value) {
                            (dampedDragAnimation.value + 0.5f) * liveTabWidth.value + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * liveTabWidth.value +
                                panelOffset
                        },
                        size.height / 2f,
                    )
                },
                shape = Capsule,
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        // The material modifiers are independent of the animated geometry; only the press progress
        // they read changes. Remembering them keeps a recomposition from rebuilding the effects.
        val panelMaterial = remember(backdrop, containerColor, material, dampedDragAnimation) {
            glassPanelMaterial(backdrop, containerColor, dampedDragAnimation, material)
        }
        val refractionMaterial = remember(backdrop, containerColor, material, dampedDragAnimation) {
            glassRefractionMaterial(backdrop, containerColor, dampedDragAnimation, material)
        }
        val selectionBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
        val selectionMaterial = remember(selectionBackdrop, dampedDragAnimation, isLightTheme) {
            glassSelectionMaterial(selectionBackdrop, dampedDragAnimation, isLightTheme)
        }

        // 1. The panel. `drawBackdrop` draws the blurred source view, then its surface, then its
        // content; the press growth lives in the node's `layerBlock` (the layer phase).
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .then(panelMaterial)
                .then(interactiveHighlight.modifier)
                .height(panelHeight)
                .width(panelWidth)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )

        // 2. The accent mask: the same row, recorded into a layer and never drawn directly (the
        // outer alpha is 0). Its own glass pass exists only so the droplet has something to refract;
        // the recorded row keeps full strength for the tint.
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            },
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .then(refractionMaterial)
                    .then(interactiveHighlight.modifier)
                    .height(panelHeight - 8.dp)
                    .width(panelWidth)
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }

        // 3. The selection: its own piece of glass, travelling between the slots. It samples the
        // source view *and* the accent-tinted row, so the glyph under it is accent-coloured.
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .then(selectionMaterial)
                .height(panelHeight - 8.dp)
                .fillMaxWidth(1f / tabsCount),
        )
    }
}

/** The squeeze nudge, shared by the panel, the mask and the glow so they cannot disagree. */
private fun squeezeOffset(animationValue: Float, width: Float, squeeze: Float): Float {
    if (!width.isFinite() || width <= 0f) return 0f
    val fraction = (animationValue / width).fastCoerceIn(-1f, 1f)
    return squeeze * fraction.sign * EaseOut.transform(abs(fraction))
}

// ---------------------------------------------------------------------------- one tab

/**
 * Ported from AM-plus-plus-main
 * `glass/src/main/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt`.
 */
internal val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }

/**
 * One tab in the strip. `RowScope` because the slots are equal widths.
 *
 * The press scale is read through [LocalLiquidBottomTabScale] as a **lambda**, so the bar can drive it
 * from the press animation inside a `graphicsLayer` block without recomposing the tabs.
 */
@Composable
internal fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Capsule,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(shape)
            // The reference takes no indication: the press response is the travelling selection
            // growing under the finger plus the glow that follows it, and a Material ripple bounded
            // to this slot is the "small square" that appears behind a tapped tab on glass chrome.
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

// ---------------------------------------------------------------------------- press physics

/**
 * Ported from AM-plus-plus-main
 * `glass/src/main/kotlin/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt`.
 *
 * One object rather than a handful of `animate*AsState`: the bar's motion is one physical gesture,
 * and every moving value is read only from a draw/layer lambda, so animating costs a redraw rather
 * than a recomposition. The AM++ evolutions over upstream are kept: a tap is detected from the drag
 * distance (for reselection), the Y press scale is independent, and superseded motion/press jobs are
 * cancelled with a complete release cleanup.
 */
private class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    val onTap: (() -> Unit)? = null,
    val pressedScaleY: Float = pressedScale,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private var motionJob: Job? = null
    private var velocityJob: Job? = null
    private var pressJob: Job? = null
    private var releaseJob: Job? = null
    private var velocityGeneration = 0

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        var distance = 0f
        inspectDragGestures(
            onDragStart = { down ->
                distance = 0f
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                if (distance <= viewConfiguration.touchSlop) onTap?.invoke()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            distance += dragAmount.getDistance()
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        releaseJob?.cancel()
        pressJob?.cancel()
        val generation = ++velocityGeneration
        velocityJob?.cancel()
        velocityTracker.resetTracking()
        velocityJob = animationScope.launch {
            if (generation == velocityGeneration) resetVelocity()
        }
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScaleY, scaleYAnimationSpec) }
        }
    }

    fun release() {
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { abs(valueAnimation.value - valueAnimation.targetValue) }
                    .first { it < threshold }
            }
            // Invalidate queued drag-frame writes before returning to the idle shape.
            velocityGeneration++
            velocityJob?.cancelAndJoin()
            pressJob?.cancelAndJoin()
            coroutineScope {
                launch { resetVelocity() }
                launch {
                    pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec)
                    pressProgressAnimation.snapTo(0f)
                }
                launch {
                    scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec)
                    scaleXAnimation.snapTo(initialScale)
                }
                launch {
                    scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec)
                    scaleYAnimation.snapTo(initialScale)
                }
            }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        val generation = velocityGeneration
        motionJob = animationScope.launch {
            // Retarget the running spring through Animatable's own cancellation.
            // Eager cancellation on every move discards its frame time and velocity.
            if (generation == velocityGeneration) {
                valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity(generation) }
            }
        }
    }

    fun animateToValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        beginSettling()
        press()
        motionJob = animationScope.launch {
            coroutineScope {
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                // Always reset, even when a pending velocity write has not started yet.
                launch { resetVelocity() }
            }
        }
        release()
    }

    private fun beginSettling() {
        velocityGeneration++
        motionJob?.cancel()
        velocityJob?.cancel()
    }

    private suspend fun resetVelocity() {
        velocityAnimation.animateTo(0f, velocityAnimationSpec)
        velocityAnimation.snapTo(0f)
    }

    private fun updateVelocity(generation: Int) {
        if (generation != velocityGeneration) return
        velocityTracker.addPosition(
            Clock.System.now().toEpochMilliseconds(),
            Offset(value, 0f),
        )
        val targetVelocity =
            velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        velocityJob = animationScope.launch {
            if (generation == velocityGeneration) {
                velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec)
            }
        }
    }
}

// ---------------------------------------------------------------------------- the press glow

/**
 * Ported from AM-plus-plus-main
 * `glass/src/main/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt`.
 *
 * Drawn with `BlendMode.Plus`, so it *adds* light rather than painting over the glass — the
 * difference between a lit surface and a white smudge. The falloff is the reference's AGSL shader:
 * the light dies out 1.5 panel heights from the finger. The additive passes are rectangles spanning
 * the node, so they are clipped to the control's own silhouette; only the light is clipped, never the
 * children.
 */
private class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
    val shape: Shape? = null,
) {

    private val pressProgressAnimationSpec =
        spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec =
        spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader =
        if (isRuntimeShaderSupported()) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}""",
            )
        } else {
            null
        }

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                if (shape != null) {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    clipPath(Path().apply { addOutline(outline) }) { drawHighlight(progress) }
                } else {
                    drawHighlight(progress)
                }
            }
            drawContent()
        }

    /** The two additive passes, drawn in whatever clip the caller established. */
    private fun DrawScope.drawHighlight(progress: Float) {
        if (shader != null) {
            drawRect(
                Color.White.copy(0.08f * progress),
                blendMode = BlendMode.Plus,
            )
            shader.apply {
                val position = position(size, positionAnimation.value)
                setFloatUniform("size", size.width, size.height)
                setColorUniform("color", Color.White.copy(0.15f * progress))
                val falloff = size.minDimension * 1.5f
                setFloatUniform("radius", falloff)
                setFloatUniform(
                    "position",
                    position.x.fastCoerceIn(0f, size.width),
                    position.y.fastCoerceIn(0f, size.height),
                )
            }
            drawRect(
                ShaderBrush(shader.asComposeShader()),
                blendMode = BlendMode.Plus,
            )
        } else {
            drawRect(
                Color.White.copy(0.25f * progress),
                blendMode = BlendMode.Plus,
            )
        }
    }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}

// ---------------------------------------------------------------------------- the pointer loop

/**
 * Ported from AM-plus-plus-main
 * `glass/src/main/kotlin/com/kyant/backdrop/catalog/utils/DragGestureInspector.kt`.
 *
 * Press/press-release tracking that reports the press **immediately**, before the touch slop is
 * exceeded: `detectDragGestures` only reports a start once the finger has moved past the slop, which
 * leaves the press feedback late by exactly the amount that makes a surface feel unresponsive.
 */
private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)

        val down = awaitFirstDown(false)
        val drag = initialDown

        onDragStart(down)
        onDrag(drag, Offset.Zero)
        val upEvent =
            drag(
                pointerId = drag.id,
                onDrag = { onDrag(it, it.positionChange()) },
            )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}

// ---------------------------------------------------------------------------- mini player

/**
 * The material both right-hand chrome panels wear.
 *
 * [FloatingMiniPlayer] and [FloatingChromePlaceholder] alternate in **one slot** of the chrome's row,
 * so they have to be one surface: the same `drawBackdrop` pass, the same cached [VibrantBlur] prefix,
 * the same constant edge refraction and the same tint. Extracted rather than copied so a change to the
 * material cannot leave the two panels looking like two kinds of glass on the same line.
 */
private fun chromePanelMaterial(
    backdrop: Backdrop,
    containerColor: Color,
    material: VibrantBlur,
): Modifier = Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { Capsule },
    effects = {
        material.applyTo(this)
        lens(16f.dp.toPx(), 32f.dp.toPx())
    },
    onDrawSurface = { drawRect(containerColor) },
)

/**
 * Floating mini player: an instrument panel in the **same** liquid glass as the bar beside it.
 *
 * The material is the reference's panel material — [VibrantBlur] under the same translucent tint and
 * the same constant edge refraction — so the two capsules cannot drift apart, and its content is
 * drawn on top of the material exactly as the bar draws its tabs. Behaviour is unchanged: the whole
 * capsule expands the player, the play button toggles it, and it is [FloatingChromeHeight] tall — the
 * same height as the bar, because the two are one row.
 *
 * It shares its builder with [FloatingChromePlaceholder], the panel that occupies this slot while
 * nothing is loaded; see [chromePanelMaterial].
 */
@Composable
fun FloatingMiniPlayer(
    state: PlaybackUiState,
    loader: ArtworkLoader,
    backdrop: Backdrop,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    val isLightTheme = !isSystemInDarkTheme()
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(GlassSurfaceAlpha)
        else Color(0xFF121212).copy(GlassSurfaceAlpha)
    val density = LocalDensity.current
    val radiusPx = with(density) { GlassBlurRadius.toPx() }
    val material = remember(radiusPx) { VibrantBlur(radiusPx) }
    val panelMaterial = remember(backdrop, containerColor, material) {
        chromePanelMaterial(backdrop, containerColor, material)
    }
    val animationScope = rememberCoroutineScope()
    val press = remember(animationScope) { CardPress(animationScope) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FloatingChromeHeight)
            .graphicsLayer {
                scaleX = press.scaleX
                scaleY = press.scaleY
            }
            ,
    ) {
        // The material: the blurred source view under the tint. One fill only — the reference paints
        // its surface once, and a second translucent fill would compose to an opaque slab.
        Box(Modifier.matchParentSize().then(panelMaterial))

        // The whole capsule opens the player, so the gesture covers the card rather than only its
        // artwork; the play button consumes its own tap first and so never reaches this.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {
                        press.tap()
                        onExpand()
                    },
                ),
        )

        // Tighter than the stacked card's insets: on the row the card's width is what the tab capsule
        // leaves, so every dp kept out of the padding and gaps is a character of the title.
        Row(
            modifier = Modifier.matchParentSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The reference's one-row mini cover is a circle, so the artwork is clipped to one here.
            // `Artwork` still applies its own continuous-corner tile clip underneath; that outline lies
            // inside this circle, so the circle is the shape that is actually seen.
            Artwork(
                url = track.coverUrl,
                loader = loader,
                corner = MiniArtworkCorner,
                requestSize = 200,
                modifier = Modifier
                    .size(MiniArtworkSize)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // Cross-faded on track id: the capsule stays put and only its text changes, so
                // without this the previous song's title is replaced mid-frame while the new cover
                // is still loading, which reads as a glitch rather than as a track change.
                CrossfadeContent(
                    targetState = track.id,
                    modifier = Modifier.fillMaxWidth(),
                    durationMillis = 260,
                    label = "mini-track",
                ) {
                    Column {
                        Text(
                            text = track.name,
                            fontFamily = SFPro,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = AppTheme.palette.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artistLine,
                            fontFamily = SFPro,
                            fontSize = 12.sp,
                            color = AppTheme.palette.secondaryLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // 44dp rather than 38: the minimum comfortable touch target. The glyph stays 20dp, so the
            // control looks the same while becoming reliably tappable one-handed.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    // The white control ripple, not the default: the default is the theme's near-black
                    // on-surface colour and bounded, so on this glass it drew a dark clipped square.
                    .clickable(
                        indication = rememberControlRipple(bounded = false),
                        interactionSource = null,
                        onClick = onTogglePlay,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CrossfadeContent(
                    targetState = state.isPlaying,
                    durationMillis = 200,
                    label = "mini-play",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) SfIcons.Pause else SfIcons.Play,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = AppTheme.palette.label,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Alpha for the placeholder's *content* while the mini player takes the slot.
 *
 * A lambda, not a value: the swap is driven by an animation in `MainActivity` and is read in the layer
 * phase, so neither the placeholder's panel nor its label recomposes per frame (the same reason
 * [LocalLiquidBottomTabScale] is a lambda). 1 = fully shown. The material is deliberately not faded:
 * both panels paint the same backdrop, so fading the whole panel would let the sharp page through the
 * glass for the length of the swap; only the content crosses.
 */
internal val LocalChromePlaceholderContentAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * Floating chrome placeholder — the mini player's slot when nothing is loaded.
 *
 * The one-row chrome is two capsules wide whether or not a track is playing. While nothing was loaded
 * the right-hand weight had nothing in it (`AnimatedVisibility(visible = hasTrack, …)` with
 * `fill = false`), so the row read as the tab bar followed by a hole — in the user's words,
 * "为什么没有音乐播放的时候直接空白". This is the same glass at the same [FloatingChromeHeight], so the
 * row is one line in both states, and it is the *same weighted size* as the card, so starting a track
 * swaps the contents of a slot rather than pushing a panel into being.
 *
 * The material is [chromePanelMaterial] — the very builder [FloatingMiniPlayer] uses — not a second
 * variant of it, so the two panels cannot drift to different blur, tint or refraction.
 *
 * Deliberately not interactive: while nothing is playing there is no destination to open, and inventing
 * one here would be navigation the chrome does not own. The label is a static state read-out.
 */
@Composable
fun FloatingChromePlaceholder(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(GlassSurfaceAlpha)
        else Color(0xFF121212).copy(GlassSurfaceAlpha)
    val density = LocalDensity.current
    val radiusPx = with(density) { GlassBlurRadius.toPx() }
    val material = remember(radiusPx) { VibrantBlur(radiusPx) }
    val panelMaterial = remember(backdrop, containerColor, material) {
        chromePanelMaterial(backdrop, containerColor, material)
    }

    // Read in the label's `graphicsLayer`, so the swap is a layer update, not a recomposition.
    val placeholderContentAlpha = LocalChromePlaceholderContentAlpha.current

    Box(
        // The same box the mini player occupies: full width of the weighted slot and the row's height.
        modifier = modifier
            .fillMaxWidth()
            .height(FloatingChromeHeight),
    ) {
        // The material: one fill only, like the card — a second translucent fill would compose to an
        // opaque slab and no longer match the panel next to it.
        Box(Modifier.matchParentSize().then(panelMaterial))

        // A muted note and a short state label, centred in the capsule. Nothing else: this slot is a
        // read-out, not a control. Its alpha is driven by the swap so it cannot ghost through the card.
        Row(
            Modifier
                .matchParentSize()
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = placeholderContentAlpha() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = SfIcons.MusicNote,
                // Decorative: the text beside it is what a screen reader needs to hear.
                contentDescription = null,
                tint = AppTheme.palette.tertiaryLabel,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "未在播放",
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Press physics for a whole panel: it shrinks slightly while held and springs back on release.
 *
 * Same spring vocabulary as the bar's selection so the two panels move as one material, and — like the
 * bar — the values are read only from `graphicsLayer`, so holding the card never recomposes it.
 */
private class CardPress(private val animationScope: CoroutineScope) {
    private val pressSpec = spring(0.7f, 900f, 0.001f)
    private val scaleSpec = spring(0.6f, 250f, 0.001f)

    private val progressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(1f, 0.001f)
    private val scaleYAnimation = Animatable(1f, 0.001f)

    val pressProgress: Float get() = progressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value

    private fun press() {
        animationScope.launch {
            launch { progressAnimation.animateTo(1f, pressSpec) }
            launch { scaleXAnimation.animateTo(PressedScale, scaleSpec) }
            launch { scaleYAnimation.animateTo(PressedScale, scaleSpec) }
        }
    }

    private fun release() {
        animationScope.launch {
            launch { progressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(1f, scaleSpec) }
            launch { scaleYAnimation.animateTo(1f, scaleSpec) }
        }
    }

    /** A tap is a complete press/release in one go, so the card is not left in its held state. */
    fun tap() {
        press()
        animationScope.launch {
            delay(TapPressMs)
            release()
        }
    }

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { press() },
            onDragEnd = { release() },
            onDragCancel = { release() },
        ) { _, _ -> }
    }

    private companion object {
        /** Inward, and small: this is an acknowledgement, not the selection growing under a finger. */
        const val PressedScale = 0.98f
        const val TapPressMs = 110L
    }
}
