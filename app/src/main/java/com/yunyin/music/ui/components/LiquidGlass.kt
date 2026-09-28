package com.yunyin.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * The three top-level destinations.
 *
 * Moved here from the old `TabBar.kt` when that was replaced by [FloatingTabBar]: the tab model and
 * the bar that presents it belong together, and a separate file for three entries was the kind of
 * split that lets one drift from the other.
 */
enum class PlayerTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home("主页", SfIcons.House),
    Search("搜索", SfIcons.MagnifyingGlass),
    Library("资料库", SfIcons.Person),
}

/**
 * The frosted-glass material used by the floating bottom chrome.
 *
 * ## Gaussian blur is the material, the fill only tempers it
 *
 * This has been round the houses, so the target is worth stating plainly. The previous revision made the
 * fill fully opaque, which fixed "too transparent" but threw away the blur: an opaque fill is painted
 * *over* the refracted backdrop, so nothing of what is behind the bar survives. The ask now is a
 * **Gaussian-blur material**, which is a different thing from both:
 *
 *  - A *transparent* panel (the 0.34 revision) lets recognisable content — rows, artwork edges — read
 *    through it. That is what was rejected as "too transparent".
 *  - A *blurred material* (this revision) diffuses what is behind into an unreadable colour field, then
 *    tempers it with a moderately translucent fill. You see the *colour* of the artwork behind the bar,
 *    never its detail. That is the iOS material look, and it is why the two earlier extremes both missed.
 *
 * So the two knobs are deliberately split, and both matter:
 *
 *  - [GlassBlur] is strong (see its note) — the blur is what turns detail into colour. A weak blur with a
 *    heavy fill reads as a smudged translucent panel; a strong blur with a moderate fill reads as frosted
 *    glass.
 *  - [MaterialFillAlpha] sits around 0.7 *and carries the sheen with it*. The fill must not be opaque or
 *    it hides the blur again; the sheen gradient therefore keeps the same alpha, or an opaque sheen would
 *    do exactly that.
 *
 * `vibrancy()` (a saturation lift in the library) is what keeps the diffused colour from turning washed
 * grey — without it a strong blur desaturates the artwork behind and the material looks flat.
 *
 * The library's draw order is: refracted backdrop, then this fill, then the content; the highlight and
 * inner shadow nodes then draw themselves over the top, each clipping to [shape] on its own. The fill is
 * clipped to [shape] here because the library does not clip `onDrawSurface`.
 *
 * @param shape the surface outline. The app's own continuous-corner shape is used so the chrome matches
 *        the rest of the UI instead of the library's default capsule.
 * @param blurRadius how much the backdrop is diffused. This *is* the material, so it is heavy.
 * @param refraction how far the lens bends the backdrop near the edge, which gives the surface a lit rim
 *        rather than a flat cut.
 * @param pressed increases the bend and the highlight, so the glass reacts to the touch that is about to
 *        move it.
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: () -> Shape,
    tint: Color,
    pressed: Boolean = false,
    blurRadius: Dp = GlassBlur,
    refraction: Dp = 18.dp,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = shape,
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        // At rest the bend is one third of the pressed amount: calm when idle, visibly reactive when
        // touched. The full lens at rest made the chrome look warped for no reason.
        val amount = if (pressed) 1f else 0.34f
        lens(refraction.toPx() * amount, refraction.toPx() * amount)
    },
    highlight = { Highlight.Default.copy(alpha = if (pressed) 1f else 0.5f) },
    shadow = { Shadow.Default },
    innerShadow = { InnerShadow(radius = 6.dp, alpha = if (pressed) 1f else 0.45f) },
    // The material: a translucent wash over the refracted backdrop.
    //
    // Two details here are load-bearing:
    //
    //  - **It has to be clipped to `shape`.** The library clips only what *it* draws (the highlight and
    //    the inner shadow call `clipOutline` themselves); `onDrawSurface` is handed a bare draw scope, so
    //    an unclipped `drawRect` spills a square rect into the squircle's corner cut-outs. That is a real
    //    bug, not a style choice.
    //  - **The sheen must keep the fill's alpha.** Each stop is built from the *already translucent*
    //    base and lerped toward an equally translucent white/black, so the gradient shades the material
    //    without making it opaque. Lerping toward plain `Color.White` would raise the alpha at the top and
    //    paint the blur out again at that edge.
    onDrawSurface = {
        val path = Path()
        path.addOutline(shape().createOutline(size, layoutDirection, this))
        clipPath(path) {
            val base = tint.copy(alpha = MaterialFillAlpha)
            drawRect(
                Brush.verticalGradient(
                    0f to lerp(base, Color.White.copy(alpha = MaterialFillAlpha), 0.07f),
                    0.5f to base,
                    1f to lerp(base, Color.Black.copy(alpha = MaterialFillAlpha), 0.07f),
                )
            )
        }
    },
)

/**
 * Blur radius for the backdrop under the chrome — the material itself.
 *
 * Heavy on purpose: the blur is what converts the content behind the bar from *recognisable* into a
 * colour field, which is the whole difference between frosted glass and a translucent panel. 30dp is
 * roughly half the bar's own height, so nothing finer than a broad colour transition survives it.
 */
private val GlassBlur = 30.dp

/**
 * Opacity of the material's fill.
 *
 * The band matters more than the exact value: high enough that nothing behind is recognisable (the failing
 * of the 0.34 revision), low enough that the blur still reads (the failing of the opaque revision). Around
 * 0.7 is the iOS "regular material" zone. The theme supplies the colour, so the chrome belongs to the app
 * in either appearance.
 */
private const val MaterialFillAlpha = 0.70f

/**
 * The material's base colour.
 *
 * The theme's own secondary background, left opaque here: the translucency is a property of the material
 * ([MaterialFillAlpha]), not of the colour, so the two do not have to be kept in step at the call sites.
 */
@Composable
private fun glassTint(): Color = AppTheme.palette.secondaryBackground

/**
 * Floating tab bar: a glass pill with an accent selection sliding between tabs.
 *
 * **Floating, not docked.** It is inset from the screen edges with content scrolling underneath, which
 * is what makes the glass worth having — a docked bar has only the page background behind it and would
 * look the same as a plain fill. The inset also keeps the bar clear of the gesture area.
 *
 * The selection is one pill that **moves**, not a background per tab, so switching reads as an object
 * travelling rather than two elements fading.
 */
@Composable
fun FloatingTabBar(
    selected: PlayerTab,
    onSelect: (PlayerTab) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val tabs = PlayerTab.entries
    val accent = AppTheme.palette.accent
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)

    val fraction by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "tab-fraction",
    )

    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = TabBarInset)) {
        // Equal slots, so the selection's travel is a plain fraction of the inner width.
        val inner = maxWidth - TabBarPadding * 2
        val slot = inner / tabs.size
        val pillLeft by animateDpAsState(
            targetValue = TabBarPadding + slot * fraction,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
            label = "tab-pill-left",
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(TabBarHeight)
                .liquidGlass(
                    backdrop = backdrop,
                    shape = { ContinuousRoundedRectangle(TabBarCorner) },
                    tint = glassTint(),
                ),
        ) {
            Box(
                Modifier
                    .offset(x = pillLeft, y = TabBarPadding)
                    .width(slot - TabBarGap)
                    .height(TabBarHeight - TabBarPadding * 2)
                    .clip(ContinuousRoundedRectangle(TabBarCorner))
                    .background(accent.copy(alpha = 0.18f)),
            )
            Row(
                Modifier.fillMaxWidth().height(TabBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    TabItem(
                        tab = tab,
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One tab: glyph over label, tinted by selection, with the glyph growing slightly when chosen. */
@Composable
private fun TabItem(
    tab: PlayerTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) AppTheme.palette.accent else AppTheme.palette.secondaryLabel,
        label = "tab-tint",
    )
    val glyphScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "tab-glyph",
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            // **Unbounded** ripple, deliberately.
            //
            // `bounded = true` clips the indication to this Column's rectangle, and a rectangle of
            // ripple inside the glass pill is the "small square" that appears behind a tapped tab. This
            // is the same class of bug as the player controls' earlier black rectangle, with the
            // clipping rather than the colour being the visible part. Unbounded gives a soft circular
            // glow that suits a glass surface.
            .clickable(
                indication = rememberControlRipple(bounded = false),
                interactionSource = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier
                .size(23.dp)
                .graphicsLayer {
                    scaleX = glyphScale
                    scaleY = glyphScale
                },
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            fontFamily = SFPro,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
        )
    }
}

/**
 * Floating mini player: the same frosted material as the bar.
 *
 * They stack, so they have to match — a blur material above a solid one would read as two different
 * surfaces. What is behind the capsule is diffused into colour rather than left recognisable, so the rows
 * passing beneath tint it without showing through. The whole capsule presses inward on touch, which is the
 * affordance that says *the capsule* opens the player rather than only the artwork; the play button stops
 * the propagation of its own tap by consuming it first.
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "mini-press",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TabBarInset)
            .height(MiniPlayerHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(
                backdrop = backdrop,
                shape = { ContinuousRoundedRectangle(MiniPlayerCorner) },
                tint = glassTint(),
                pressed = pressed,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onExpand,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = track.coverUrl,
            loader = loader,
            corner = MiniArtworkCorner,
            requestSize = 200,
            modifier = Modifier.size(MiniArtworkSize),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Cross-faded on track id: the capsule stays put and only its text changes, so without
            // this the previous song's title is replaced mid-frame while the new cover is still
            // loading, which reads as a glitch rather than as a track change.
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
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                // The white control ripple, not the default: the default is the theme's near-black
                // on-surface colour and bounded, so on this dark glass it drew a dark clipped square —
                // the "black square" this app has hit before, here on the glass chrome.
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

/** Horizontal inset of the floating chrome from the screen edges. */
private val TabBarInset = 16.dp

/** Padding between the bar's glass edge and its content. */
private val TabBarPadding = 5.dp

/** Gap between the selection pill and its slot edges. */
private val TabBarGap = 4.dp

/** Height of the floating tab bar. Sized for a comfortable target with its labels. */
private val TabBarHeight = 58.dp

/** Corner radius of the tab bar's glass; a squircle, matching the app's other surfaces. */
private val TabBarCorner = 26.dp

/** Corner radius of the mini player's glass. */
private val MiniPlayerCorner = 20.dp

/** Height of the mini player capsule. */
private val MiniPlayerHeight = 60.dp

/** Artwork size inside the capsule. */
private val MiniArtworkSize = 42.dp

/**
 * Corner radius of the mini player's artwork.
 *
 * Public because the cover transition interpolates from it to the player cover's radius, and the two
 * values have to agree with what is actually drawn or the corner would jump at either end.
 */
val MiniArtworkCorner = 8.dp
