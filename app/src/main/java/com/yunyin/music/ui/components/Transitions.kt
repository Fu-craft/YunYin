package com.yunyin.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Cross-fades between content states.
 *
 * Used wherever a screen swaps what it shows — loading to loaded, empty to populated, one tab to
 * another. Compose swaps such content instantly by default, which reads as a flicker; a short fade
 * makes the change legible as a change.
 *
 * Deliberately opacity-only: a slide or scale would imply a spatial relationship between the two
 * states that a "loading finished" swap does not have, and on a list that is already scrolled a
 * movement would be disorienting. (That is why the player's lyrics/artwork swap, which *does* have
 * a spatial relationship, uses its own slide+fade instead of this.)
 */
@Composable
fun <T> CrossfadeContent(
    targetState: T,
    modifier: Modifier = Modifier,
    durationMillis: Int = 220,
    label: String = "crossfade-content",
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(durationMillis)) togetherWith
                fadeOut(tween((durationMillis - 60).coerceAtLeast(1)))
        },
        label = label,
    ) { state ->
        content(state)
    }
}
