package com.yunyin.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.R
import com.yunyin.music.ui.theme.SFPro

/**
 * Launch screen, shown while the first network work completes.
 *
 * It exists to cover the genuinely slow part of a cold start — resolving a session and loading the
 * home feed — so the user's first sight of the app is the brand rather than a half-built list. The
 * caller keeps it up until that work is done (with a minimum and a fail-safe maximum), so the
 * animation is a *cover*, not a fixed delay: on a fast warm start it is brief, and on a slow network
 * it stays without ever hanging forever.
 *
 * Design notes:
 *  - It shows the app's own mark, drawn by the same `ic_launcher_foreground` vector the launcher
 *    icon uses, so the icon the user tapped is continuous with what appears. The vector's own
 *    coordinates already place the mark optically centred, so it is simply scaled up.
 *  - The background is the icon's blue gradient, matching how the system splash (API 31+, on the
 *    theme's window background) hands off to this screen.
 *  - A soft radial glow behind the mark pulses very gently; it gives the static mark life without
 *    drawing attention, which is what a wait state should do. Everything is a per-frame animation of
 *    two `Animatable`s plus one infinite transition, so it costs one draw per frame and nothing else.
 *  - The reveal is a single 0..1 progress, with each element reading its own slice of it, rather than
 *    several chained coroutines — that keeps the whole thing legible as one coordinated movement and
 *    means it cannot desynchronise.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    // One progress for the whole reveal.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // A slight overshoot on the mark's scale is what makes it feel alive rather than mechanical;
        // y goes past 1 and settles, the classic "back out" curve.
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 760, easing = SplashRevealEasing),
        )
    }

    val pulse by rememberInfiniteTransition(label = "splash-pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "splash-pulse-alpha",
    )

    val p = reveal.value
    // Mark leads; the wordmark follows once the mark has mostly arrived.
    val markAlpha = (p / 0.5f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val markScale = 0.80f + 0.20f * p
    val wordT = ((p - 0.34f) / 0.66f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    Box(
        modifier
            .fillMaxSize()
            // Swallow input. A background alone does not consume touches, so without this a tap
            // during the launch screen would reach the app underneath and could start playback on a
            // screen the user cannot see yet.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // Brand backdrop: the same sky-blue-to-azure ramp as the launcher icon. The default
        // linear-gradient ends at the far corner of the drawn bounds, which matches the icon's
        // top-left-to-bottom-right direction.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(colors = SplashBrandGradient)),
        )

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(SplashMarkSize)
                    .graphicsLayer {
                        alpha = markAlpha
                        scaleX = markScale
                        scaleY = markScale
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Glow, behind the mark. Sized to the box and drawn first so the white mark sits on
                // top of it.
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension * 0.62f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.16f * pulse),
                                Color.White.copy(alpha = 0.05f * pulse),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
                // The launcher mark, verbatim.
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // No spacer: the vector's 108-unit viewport already carries ~24dp of empty space below
            // the mark (its ink is ~52% of the viewport), which is the gap to the wordmark. An
            // explicit spacer on top of that read as a disconnected pair.

            Text(
                text = "云音",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = 2.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = wordT
                    // Rises the last few dp into place, so the wordmark reads as arriving rather
                    // than appearing.
                    translationY = (1f - wordT) * 16.dp.toPx()
                },
            )
        }
    }
}

/**
 * The mark box. The vector's mark occupies about 0.52 of its 108-unit viewport vertically, so this
 * yields roughly a 118dp-tall mark on a phone — large enough to read as a launch identity, small
 * enough to leave the wordmark in the optical centre.
 */
private val SplashMarkSize = 226.dp

/** Icon blue, matching `ic_launcher_background.xml` so splash and icon are the same colour. */
private val SplashBrandGradient = listOf(
    Color(0xFF6EC6FF),
    Color(0xFF3C6BFF),
    Color(0xFF2A55F0),
)

/**
 * Reveal curve for the splash.
 *
 * Deliberately overshoots (the y control value above 1), which is what gives the mark's scale a
 * small settle at the end instead of stopping dead.
 */
private val SplashRevealEasing = CubicBezierEasing(0.22f, 1.30f, 0.36f, 1f)
