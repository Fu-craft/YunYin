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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
 * The liquid-glass surface used by the floating bottom chrome.
 *
 * Unlike the translucent fills elsewhere in this app, this samples and refracts **what is actually
 * behind it**: [Backdrop] holds a recorded layer of the content, and `drawBackdrop` blurs that layer,
 * bends it through a lens, and lifts its saturation so the colour of the artwork behind still reads
 * through. That is the difference between glass and a grey rectangle at 40% opacity, and it is why the
 * reference library is used here rather than another translucent fill.
 *
 * Two things about this are easy to get wrong and were:
 *
 *  - **The surface tint is required.** `drawBackdrop` on its own produces *only* the refracted
 *    backdrop — with no `onDrawSurface` the result is nearly invisible over a busy list, which is the
 *    "too transparent" the user reported. The reference passes a `containerColor` for exactly this
 *    reason; here it is the theme's own surface at an alpha that frosts without hiding.
 *  - **The blur has to be substantial.** A few dp of blur leaves the sampled content legible through
 *    the glass, which reads as a smudge rather than as frosted glass.
 *
 * @param shape the surface outline. The app's own continuous-corner shape is used so the chrome matches
 *        the rest of the UI instead of the library's default capsule.
 * @param blurRadius how much the backdrop is diffused.
 * @param refraction how far the lens bends the backdrop near the edge, which is what makes the surface
 *        read as a physical object with an edge rather than as a rectangle of frosted colour.
 * @param pressed increases the bend and the highlight, so the glass reacts to the touch that is about
 *        to move it.
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
    // The frosting. Without this the surface is only the refracted backdrop and all but disappears
    // over a bright or busy list.
    onDrawSurface = { drawRect(tint) },
)

/** Blur radius for the frosted chrome. Large enough that the content behind is a colour field. */
private val GlassBlur = 26.dp

/**
 * The frosting laid over the refracted backdrop.
 *
 * Derived from the theme's own background so the chrome belongs to the app in either appearance, and
 * heavily weighted toward opaque: at ~72% the content behind is still visible as colour and shape but
 * the surface reads as frosted glass rather than as a window. An earlier version passed no tint at all,
 * which is why the glass looked almost invisible over a busy list.
 */
@Composable
private fun glassTint(): Color = AppTheme.palette.background.copy(alpha = 0.72f)

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
 * Floating mini player: a glass capsule holding the current track.
 *
 * Glass for the same reason as the bar — it floats over the list, so the artwork and rows passing
 * beneath show through it. The whole capsule presses inward on touch, which is the affordance that
 * says *the capsule* opens the player rather than only the artwork; the play button stops the
 * propagation of its own tap by consuming it first.
 */
@Composable
fun FloatingMiniPlayer(
    state: PlaybackUiState,
    loader: ArtworkLoader,
    backdrop: Backdrop,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Called with the **capsule's** bounds in root coordinates.
     *
     * The capsule, not its artwork: the expanding container starts as the surface the user touched, and
     * its pill radius becomes the container's initial radius. Measured rather than computed because the
     * width depends on the screen.
     */
    onCoverBoundsChanged: (Rect) -> Unit = {},
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
            // The capsule's own rect: the expand transition starts from the surface that was touched.
            .onGloballyPositioned { coordinates ->
                onCoverBoundsChanged(coordinates.boundsInRoot())
            }
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
