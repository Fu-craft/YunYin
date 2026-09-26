package com.yunyin.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * home feed — so the first sight of the app is the brand rather than a half-built list. The caller
 * holds it until that work is done (with a minimum and a fail-safe maximum), so it is a *cover*
 * rather than a fixed delay.
 *
 * **Design.** The launcher icon is a pastel note on a white face, so the launch screen continues
 * that: a white ground with the same mark, centred, and the wordmark beneath. This is Apple's own
 * launch-screen idiom — the icon, larger, on its own background — and it makes the launch read as
 * one continuous surface from the system splash through to the app.
 *
 * Only three things move, and each explains the arrival order rather than decorating it:
 * the mark fades in, the wordmark follows once the mark has settled, and the wordmark rises the
 * last few dp into place. No bounce, no overshoot, no looping ornament: a launch screen is the wrong
 * place for anything that draws attention to itself.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 620, easing = SplashEase),
        )
    }

    val p = reveal.value
    // The mark leads; the wordmark arrives once the mark has settled.
    val markT = (p / 0.62f).coerceIn(0f, 1f).smooth()
    val wordT = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f).smooth()

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
            // The mark is its own drawable rather than the launcher foreground, whose viewport is
            // the 108-unit icon canvas: using that here would leave the note at ~57% of the box and
            // make it awkward to size. `ic_brand_note` is cropped to the note's own ink.
            Image(
                painter = painterResource(R.drawable.ic_brand_note),
                contentDescription = null,
                modifier = Modifier
                    .size(SplashMarkWidth, SplashMarkWidth * BrandNoteAspect)
                    .graphicsLayer {
                        alpha = markT
                        // A one-and-a-half percent rise: enough that the mark reads as placed rather
                        // than pasted, not enough to be movement for its own sake.
                        val s = 0.985f + 0.015f * markT
                        scaleX = s
                        scaleY = s
                    },
            )

            Text(
                text = "云音",
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 26.sp,
                letterSpacing = 3.sp,
                color = SplashWordmark,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 28.dp)
                    .graphicsLayer {
                        alpha = wordT
                        translationY = (1f - wordT) * 8.dp.toPx()
                    },
            )
        }
    }
}

/** Smoothstep; keeps each element's slice of the progress gentle at both ends. */
private fun Float.smooth(): Float = this * this * (3f - 2f * this)

/** The mark's width on the launch screen. Its height follows from the note's own aspect. */
private val SplashMarkWidth = 132.dp

/** The note's height/width ratio, matching the launcher icon's geometry. */
private const val BrandNoteAspect = 1.183f

/**
 * Launch ground: white, matching the icon's face.
 *
 * A hair off pure white so the mark's soft edges sit on a surface rather than in a void, and so the
 * handover from the platform splash (which uses the same colour) has no step.
 */
private val SplashBackground = Color(0xFFFCFCFD)

/**
 * The wordmark takes the note's own cool tone rather than pure black, so it reads as part of the
 * lockup instead of as separate text.
 */
private val SplashWordmark = Color(0xFF5A6480)

/**
 * Reveal curve: a decelerating ease-out with no overshoot.
 *
 * A launch screen is the wrong place for a spring or a bounce — the mark simply arrives. These are
 * the classic iOS "ease out" values (fast start, long settle), which is what makes the movement feel
 * like the system's own.
 */
private val SplashEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
