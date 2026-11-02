// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

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
 *
 * [closeRequest] is the host-driven counterpart: an async write/save/import finishes and asks the
 * sheet to leave, instead of the host flipping its visibility state and unmounting the Dialog on the
 * spot (which is what made those popups vanish). The content stays composed for the whole exit and
 * the host only clears it from [onDismissed].
 */
@Composable
fun AnimatedSheet(
    onDismissed: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    placement: SheetPlacement = SheetPlacement.Bottom,
    dismissible: Boolean = true,
    scrim: Boolean = true,
    /** Host-driven dismissal; set true once the action that owns the sheet has completed. */
    closeRequest: Boolean = false,
    sheetModifier: Modifier = Modifier,
    content: @Composable ColumnScope.(close: SheetClose) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var afterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Exit timing, shared by the panel and the scrim so they land on the same frame.
    //
    // Measured from a frame-by-frame capture of 4.81.0: the card faded over the whole exit while the
    // scrim held full strength, so the card turned into a ghost over a still-dimmed page, and *then*
    // the dim lifted on its own a couple of frames later. Two artefacts, one cause — the two exits had
    // different lengths (294 / 360 ms) and both started at t=0.
    //
    // Now: the card keeps its surface for the first ~45 % (while the scale carries the motion) and
    // fades over the last 55 %; the scrim starts a little later and is shorter, so the page brightens
    // under a card that is already almost gone, and both land on the same final frame.
    val exitMs = Motion.duration(Motion.SheetExitDuration)
    val panelFadeMs = (exitMs * 55) / 100
    val panelFadeDelayMs = exitMs - panelFadeMs
    val scrimFadeMs = (exitMs * 45) / 100
    val scrimFadeDelayMs = exitMs - scrimFadeMs

    // Host-driven dismissal runs through the same exit as a user's close(): the Dialog stays composed
    // until [onDismissed], so the exit animates on the real content instead of snapping away.
    LaunchedEffect(closeRequest) {
        if (closeRequest) {
            closing = true
        } else if (closing) {
            // The host withdrew the request (or a newer state replaced it) before the exit finished:
            // re-enter rather than waiting for a dismissal nobody asked for.
            closing = false
            visible = true
        }
    }

    // Start the entrance on the first frame, so AnimatedVisibility has a frame at `false` to
    // animate away from.
    LaunchedEffect(Unit) { visible = true }

    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        visible = false
        // Wait for the slower of the two exits, plus a frame. Both exits are the sheet's own duration
        // (the scrim no longer outlives the panel — that mismatch left the page dimmed for ~70 ms
        // *after* the panel had gone, which reads as a grey flash on dismissal).
        delay(Motion.duration(Motion.SheetExitDuration).toLong() + 32L)
        val action = afterDismiss
        afterDismiss = null
        onDismissed()
        action?.invoke()
        closing = false
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
                    enter = fadeIn(tween(Motion.duration(Motion.ScrimDuration), easing = Motion.EaseOut)),
                    // The dim leaves *with* the panel and never after it: same end frame, slightly later
                    // start, so the page does not brighten under a card that is still legible.
                    exit = fadeOut(
                        tween(
                            durationMillis = scrimFadeMs,
                            delayMillis = scrimFadeDelayMs,
                            easing = Motion.EaseIn,
                        ),
                    ),
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
                    slideInVertically(tween(Motion.duration(Motion.SheetEnterDuration), easing = Motion.EaseDrawer)) { it }
                } else {
                    fadeIn(tween(Motion.duration(Motion.SheetEnterDuration), easing = Motion.EaseOut)) +
                        scaleIn(tween(Motion.duration(Motion.SheetEnterDuration), easing = Motion.EaseOut), initialScale = 0.94f)
                },
                // Exits accelerate: an EaseOut fade decelerates into nothing and reads as the panel
                // "dissolving in slow motion" — most obvious on the centred profile sheet, which has
                // no scrim to carry the motion. EaseIn + a late, short fade gives the dismissal a
                // direction without ever turning the card into a see-through ghost.
                exit = if (placement == SheetPlacement.Bottom) {
                    slideOutVertically(tween(exitMs, easing = Motion.EaseIn)) { it }
                } else {
                    // Centred cards get a direction instead of a dissolve: the scale and the small lift
                    // run for the whole exit, the alpha only in the last 55 %.
                    fadeOut(
                        tween(
                            durationMillis = panelFadeMs,
                            delayMillis = panelFadeDelayMs,
                            easing = Motion.EaseIn,
                        ),
                    ) + scaleOut(tween(exitMs, easing = Motion.EaseIn), targetScale = 0.96f) +
                        slideOutVertically(tween(exitMs, easing = Motion.EaseIn)) { -it / 60 }
                },
            ) {
                Column(modifier = sheetModifier) {
                    content(SheetClose { after -> close(after) })
                }
            }
            // The content stays composed through the exit, so it is still hittable for one more frame:
            // cover it while leaving, so a row cannot fire again and a second dismissal cannot stack.
            if (closing) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        indication = null,
                        interactionSource = null,
                    ) { },
                )
            }
        }
    }
}
