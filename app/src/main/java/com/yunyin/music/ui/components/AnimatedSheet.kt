package com.yunyin.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yunyin.music.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * Where the sheet is anchored.
 *
 * Bottom: an iOS sheet rising from the bottom edge. Center: a centred modal card, which is
 * exempt from trigger-anchored origins (AUDIT.md §3) and scales 0.94 -> 1 instead.
 */
enum class SheetPlacement { Bottom, Center }

/**
 * The dismissal handle handed to an [AnimatedSheet]'s content.
 *
 * `close()` dismisses, and `close { action() }` runs [action] once the exit has finished and the caller
 * has removed the sheet (which is how a row that opens *another* sheet or a browser avoids two panels
 * overlapping). A handle type rather than a plain `(after) -> Unit` because the common case takes no
 * argument, and a function type cannot give its parameter a default.
 */
class SheetClose internal constructor(private val dismiss: (after: () -> Unit) -> Unit) {
    operator fun invoke() = dismiss({})
    operator fun invoke(after: () -> Unit) = dismiss(after)
}

/**
 * A Dialog that animates its own entrance and exit.
 *
 * `onDismissed` is called only after the exit has finished; the caller removes the sheet from
 * composition from that callback. The [SheetClose] handed to `content` works the same way: call
 * `close()` to dismiss, or `close { action() }` to run [action] once the sheet is gone.
 *
 * Back press and (when [scrim]) a scrim tap both route through `close`, so every dismissal
 * animates. [dismissible] = false makes those no-ops while a download is in flight.
 */
@Composable
fun AnimatedSheet(
    onDismissed: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    placement: SheetPlacement = SheetPlacement.Bottom,
    dismissible: Boolean = true,
    scrim: Boolean = true,
    sheetModifier: Modifier = Modifier,
    content: @Composable ColumnScope.(close: SheetClose) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var afterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Start the entrance on the first frame, so AnimatedVisibility has a frame at `false` to
    // animate away from.
    LaunchedEffect(Unit) { visible = true }

    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        visible = false
        delay(Motion.SheetExitDuration.toLong())
        val action = afterDismiss
        afterDismiss = null
        onDismissed()
        action?.invoke()
    }

    fun close(after: () -> Unit = {}) {
        if (!dismissible || closing) return
        afterDismiss = after
        closing = true
    }

    Dialog(onDismissRequest = { close() }, properties = properties) {
        Box(
            modifier = if (scrim) Modifier.fillMaxSize() else Modifier.wrapContentSize(),
            contentAlignment = if (placement == SheetPlacement.Bottom) {
                Alignment.BottomCenter
            } else {
                Alignment.Center
            },
        ) {
            if (scrim) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
                    exit = fadeOut(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(indication = null, interactionSource = null) { close() },
                    )
                }
            }
            AnimatedVisibility(
                visible = visible,
                enter = if (placement == SheetPlacement.Bottom) {
                    slideInVertically(tween(Motion.SheetEnterDuration, easing = Motion.EaseDrawer)) { it }
                } else {
                    fadeIn(tween(Motion.SmallDuration, easing = Motion.EaseOut)) +
                        scaleIn(tween(Motion.SheetEnterDuration, easing = Motion.EaseOut), initialScale = 0.94f)
                },
                exit = if (placement == SheetPlacement.Bottom) {
                    slideOutVertically(tween(Motion.SheetExitDuration, easing = Motion.EaseDrawer)) { it }
                } else {
                    fadeOut(tween(Motion.SmallDuration, easing = Motion.EaseOut)) +
                        scaleOut(tween(Motion.SheetExitDuration, easing = Motion.EaseOut), targetScale = 0.94f)
                },
            ) {
                Column(modifier = sheetModifier) {
                    content(SheetClose { after -> close(after) })
                }
            }
        }
    }
}
