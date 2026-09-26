package com.yunyin.music.ui.screens

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * It covers the genuinely slow part of a cold start — resolving a session and loading the home feed
 * — so the first sight of the app is the brand rather than a half-built list. The caller holds it
 * until that work is done (with a minimum and a fail-safe maximum), so it is a *cover* rather than a
 * fixed delay.
 *
 * **Design.** The icon is a sky-blue mark on a white face, so the launch continues that: the same
 * white ground, the same mark, the wordmark beneath. That is Apple's own launch idiom — the icon,
 * larger, on its own background — and it makes the launch one continuous surface from the platform
 * splash through to the app.
 *
 * **Motion.** Two beats, then an idle.
 *
 *  1. *Arrival.* The mark settles in: it eases down from 1.05 to its final size while fading up, so
 *     it reads as coming to rest rather than being switched on. A scale that starts above 1 and
 *     relaxes to 1 is the same settle iOS uses when a view is presented.
 *  2. *The wordmark.* It fades in *and its letter-spacing tightens* from wide to its final value.
 *     That tracking-in is what makes a word feel placed by a designer rather than typed — and it is
 *     the one moment of the sequence that rewards a second look.
 *  3. *Idle.* Once settled, the whole lockup breathes very slightly. A splash that sits perfectly
 *     still for a multi-second wait reads as frozen, so this is the "still working" signal — but it
 *     is an opacity breath of a few percent, not a spinner or a pulse that draws the eye.
 *
 * Nothing overshoots, nothing bounces, and nothing loops so fast that it competes with the wait.
 * `ValueAnimator.areAnimatorsEnabled()` is honoured: with system animations off, the final state is
 * shown immediately, since a launch screen that animates for someone who has disabled motion is a
 * bug rather than a flourish.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val animatorsOn = remember { ValueAnimator.areAnimatorsEnabled() }

    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!animatorsOn) {
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        reveal.animateTo(1f, tween(durationMillis = 780, easing = SplashEase))
    }

    // Idle breath. An infinite transition is created unconditionally (composition must not branch
    // on hooks) but its value is ignored when it should not run, so reduced-motion users get a
    // still screen.
    val breath by rememberInfiniteTransition(label = "splash-breath").animateFloat(
        initialValue = 1f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "splash-breath-alpha",
    )

    val p = reveal.value
    val markT = (p / 0.55f).coerceIn(0f, 1f).smooth()
    val wordT = ((p - 0.40f) / 0.60f).coerceIn(0f, 1f).smooth()

    // The idle only starts once the entrance is done, and only when motion is allowed.
    val settled = p >= 1f
    val idle = if (animatorsOn && settled) BreathFloor + (1f - BreathFloor) * breath else 1f

    Box(
        modifier
            .fillMaxSize()
            .background(SplashBackground)
            // Swallow input. A background alone does not consume touches, so without this a tap
            // during the launch screen would reach the app underneath and could start playback on a
            // screen the user cannot see yet.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The mark is its own drawable, cropped to the mark's ink, so it can be sized directly.
            // The launcher foreground cannot be used here: its viewport is the 108-unit icon canvas,
            // so the mark would sit at ~57% of the box with dead space all round.
            Image(
                painter = painterResource(R.drawable.ic_brand_note),
                contentDescription = null,
                modifier = Modifier
                    .size(SplashMarkSize)
                    .graphicsLayer {
                        alpha = markT * idle
                        // Settles from 5% larger. Scaling down into place reads as arriving; scaling
                        // up would read as growing, which is not what a mark does.
                        val s = 1f + (1f - markT) * 0.05f
                        scaleX = s
                        scaleY = s
                    },
            )

            Text(
                text = "云音",
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 26.sp,
                // Tightens from wide to its final tracking as it fades in. Animated as a real value
                // so the text re-lays-out each frame, rather than being scaled — scaling would
                // stretch the glyphs instead of the spacing between them.
                letterSpacing = (WordmarkTracking + (1f - wordT) * 7f).sp,
                color = SplashWordmark,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .graphicsLayer {
                        alpha = wordT * idle
                        translationY = (1f - wordT) * 10.dp.toPx()
                    },
            )
        }
    }
}

/** Smoothstep; keeps each element's slice of the progress gentle at both ends. */
private fun Float.smooth(): Float = this * this * (3f - 2f * this)

/**
 * The mark's box on the launch screen.
 *
 * The brand drawable is square (its viewport is cropped to the mark's ink with a little padding), so
 * one dimension is enough.
 */
private val SplashMarkSize = 132.dp

/** How far the idle breath dips, so it is felt rather than noticed. */
private const val BreathFloor = 0.90f

/** Final letter-spacing of the wordmark; it animates in from [WordmarkTracking] + 7sp. */
private const val WordmarkTracking = 2.5f

/**
 * Launch ground: white, matching the icon's face.
 *
 * A hair off pure white so the mark's edges sit on a surface rather than in a void, and so the
 * handover from the platform splash (which uses the same colour) has no step.
 */
private val SplashBackground = Color(0xFFFCFCFD)

/**
 * The wordmark takes the mark's own blue, so it reads as part of the lockup rather than as separate
 * text — and it clears 3.3:1 against the white ground, which is what a graphic this size needs.
 */
private val SplashWordmark = Color(0xFF1E93E0)

/**
 * Reveal curve: a decelerating ease-out with no overshoot.
 *
 * A launch screen is the wrong place for a spring or a bounce — the mark simply arrives. These are
 * the classic iOS "ease out" values (fast start, long settle), which is what makes the movement feel
 * like the system's own.
 */
private val SplashEase = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
