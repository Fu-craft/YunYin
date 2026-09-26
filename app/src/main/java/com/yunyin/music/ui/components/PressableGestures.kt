package com.yunyin.music.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Tap, long-press and vertical-drag on one surface, decided in a single detector.
 *
 * ## Why not `combinedClickable` + `detectVerticalDragGestures`
 *
 * Because the two compete. Both listen on the main pointer pass, and a long press requires the
 * pointer to stay within touch slop for the whole system long-press timeout. The drag detector is
 * watching the same movement, so a hand that drifts even slightly while holding — which is normal —
 * hands the gesture to the drag detector and the long press never fires. On the artwork that read as
 * "I held it for ages and nothing happened", and it also explains why the download never started.
 *
 * Deciding all three here removes the ambiguity: one detector owns the pointer from down to up, so
 * exactly one of tap / long press / drag can win, and which one is decided by explicit thresholds.
 *
 * ## Behaviour
 *
 *  - **long press** fires once the pointer has been held for [longPressMs] *without* exceeding touch
 *    slop. A timeout is used rather than waiting for the next pointer event, because a perfectly
 *    still finger generates none — polling for events would make the press depend on movement.
 *  - **drag** begins once vertical movement exceeds touch slop, and reports the accumulated distance;
 *    it is only armed while the press could still become a drag, so a drag that starts after a long
 *    press has already fired cannot both download and dismiss.
 *  - **tap** is a release before either threshold.
 *
 * After the long press fires, the detector keeps consuming until the finger lifts. Without that, the
 * same press would also register as a tap on release and toggle the lyrics as a side effect.
 */
fun Modifier.pressableGestures(
    longPressMs: Long = DEFAULT_LONG_PRESS_MS,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    dragDistance: () -> Float,
    setDragDistance: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
): Modifier = composed {
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downPosition = down.position
            val downTime = down.uptimeMillis

            var longPressFired = false
            var dragging = false
            var reported = 0f

            // Phase 1: decide tap vs long press vs drag.
            //
            // The wait is recomputed from the *pointer event's own clock* after each event, so a
            // stream of tiny movements cannot keep pushing the deadline out: the press still fires at
            // `longPressMs` after the finger went down, which is what makes it feel predictable.
            var elapsedMs = 0L
            while (true) {
                val remaining = (longPressMs - elapsedMs).coerceAtLeast(1L)
                val event = withTimeoutOrNull(remaining) { awaitPointerEvent() }
                // A null result means no event arrived in time: the finger is being held still, which
                // is exactly a long press.
                if (event == null) {
                    longPressFired = true
                    down.consume()
                    onLongPress()
                    break
                }

                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    // Released: a tap if neither threshold had been crossed.
                    if (!dragging && !longPressFired) onTap()
                    return@awaitEachGesture
                }
                // Elapsed time measured on the event's own clock, so the deadline is absolute
                // rather than reset by each event.
                elapsedMs = change.uptimeMillis - downTime

                val dy = change.position.y - downPosition.y
                val dx = change.position.x - downPosition.x

                if (abs(dy) > viewConfiguration.touchSlop || abs(dx) > viewConfiguration.touchSlop) {
                    dragging = true
                    break
                }
            }

            // Phase 2a: the press became a drag.
            if (dragging) {
                setDragDistance(reported)
                var lastY = downPosition.y
                var active = true
                while (active) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        active = false
                    } else {
                        val delta = change.position.y - lastY
                        lastY = change.position.y
                        reported = (reported + delta).coerceAtLeast(0f)
                        change.consume()
                        setDragDistance(reported)
                    }
                }
                onDragEnd(reported)
                return@awaitEachGesture
            }

            // Phase 2b: the long press already fired. Swallow the rest of the gesture so releasing
            // does not also register as a tap.
            if (longPressFired) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    change.consume()
                    if (!change.pressed) return@awaitEachGesture
                }
            }
        }
    }
}

/**
 * Long-press threshold used by the player's artwork.
 *
 * Shorter than the platform default (~500ms) on purpose: this gesture is not a menu, it is a single
 * action, and the default makes a download feel unresponsive. 380ms is still comfortably above a tap,
 * so the two remain unambiguous.
 */
const val DEFAULT_LONG_PRESS_MS = 380L
