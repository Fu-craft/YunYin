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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * home feed — so the first sight of the app is the brand rather than a half-built list. The caller
 * holds it until that work is done (with a minimum and a fail-safe maximum), so it is a *cover*
 * rather than a fixed delay.
 *
 * **Design.** Deliberately quiet, following the iOS rule that a launch screen should feel
 * inevitable rather than expressive:
 *
 *  - One flat, near-solid background in the icon's own blue, so the launch reads as one continuous
 *    surface from the system splash through to the app.
 *  - The mark is the launcher icon's own vector, upright and centred — continuous with the icon the
 *    user just tapped.
 *  - A single settle: the mark eases up a very small distance while fading in, then the wordmark
 *    follows underneath. No bounce, no overshoot, no looping ornament — an earlier version pulsed a
 *    glow behind the mark, which is decoration competing with a two-second wait. Motion here only
 *    explains the order of arrival.
 *  - One progress value for the whole reveal, each element reading its own slice, so it cannot
 *    desynchronise and stays legible as one coordinated movement.
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
            // Swallow input. A background alone does not consume touches, so without this a tap
            // during the launch screen would reach the app underneath and could start playback on a
            // screen the user cannot see yet.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(SplashBackground)),
        )

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(SplashMarkSize)
                    .graphicsLayer {
                        alpha = markT
                        // A one-and-a-half percent rise. Any more reads as movement for its own
                        // sake; this is only enough to make the mark feel placed rather than pasted.
                        val s = 0.985f + 0.015f * markT
                        scaleX = s
                        scaleY = s
                    },
            )

            // No spacer: the vector's 108-unit viewport carries empty space below the mark
            // (its ink stops at ~75 of 108), which is the gap to the wordmark.
            Text(
                text = "云音",
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 26.sp,
                letterSpacing = 3.sp,
                color = Color.White.copy(alpha = 0.94f),
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = wordT
                    // Rises 8dp into place, so the wordmark arrives rather than appears.
                    translationY = (1f - wordT) * 8.dp.toPx()
                },
            )
        }
    }
}

/** Smoothstep; keeps every element's slice of the progress gentle at both ends. */
private fun Float.smooth(): Float = this * this * (3f - 2f * this)

/**
 * The mark box. The vector's ink spans ~56% of its viewport, so this yields a mark of roughly
 * 112dp — present without shouting, and leaving the lockup optically centred.
 */
private val SplashMarkSize = 200.dp

/**
 * Launch backdrop: the icon's blue, held near-flat.
 *
 * A very narrow ramp rather than a pronounced gradient. Two stops this close read as one colour on
 * a phone while avoiding the banding a single flat fill can show on an OLED panel.
 */
private val SplashBackground = listOf(
    Color(0xFF4F8CEC),
    Color(0xFF3D74DE),
)

/**
 * Reveal curve: a decelerating ease-out with no overshoot.
 *
 * A launch screen is the wrong place for a spring or a bounce — the mark simply arrives. The values
 * are the classic iOS "ease out" family (fast start, long settle), which is what makes the movement
 * feel like the system's own.
 */
private val SplashEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
