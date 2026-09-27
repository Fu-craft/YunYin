package com.yunyin.music.lyrics.composable.lyrics

/**
 * Decides when the lyrics list should move, and how.
 *
 * Extracted from the frame loop in `KaraokeLyricsView` for one reason: every bug this view has had
 * lived in exactly this decision, and inline in a `while (true)` loop over a `LazyListState` it could
 * only ever be checked by watching the screen. As a pure state machine it is unit-tested.
 *
 * The behaviour it encodes:
 *
 *  - The **first** placement is a jump, not an animation. Animating from index 0 to the current line
 *    on track start scrolls the entire list past every line above, which is the visible "lyrics rush
 *    up from the bottom" glitch.
 *  - A **deliberate drag** takes priority and is honoured until playback moves the target line on.
 *    "Deliberate" is decided by the caller from the pointer stream; `isScrollInProgress` is not
 *    usable because it is also true during our own animation.
 *  - A line that we already requested is not requested again once the list has arrived. If the list
 *    is *not* on it — because a fling or another scroll cancelled our animation — it is re-requested,
 *    which is the retry.
 */
internal class LyricScrollDecider {

    /** What the frame loop should do this frame. */
    sealed interface Decision {
        /** Do nothing. */
        data object Wait : Decision

        /** Bring [index] to the reading position, animating unless this is [firstPlacement]. */
        data class Scroll(val index: Int, val animate: Boolean) : Decision
    }

    private var hasPlaced = false
    private var lastRequested = NONE
    private var heldByUser = false

    /**
     * @param index the line to follow, or null when there is nothing to follow (lead-in, no lyrics).
     * @param dragging whether a finger is currently dragging the list.
     * @param firstVisibleIndex the list's current anchor, used to tell "already there" from "a scroll
     *        was cancelled and needs retrying".
     */
    fun decide(index: Int?, dragging: Boolean, firstVisibleIndex: Int): Decision {
        if (dragging) {
            // The user took over. Remember that, and let them be.
            heldByUser = true
            return Decision.Wait
        }
        if (index == null) return Decision.Wait

        // Held: stay where the user left it until playback actually moves the line on.
        if (heldByUser) {
            if (index == lastRequested) return Decision.Wait
            heldByUser = false
        }

        // Already requested and the list is on it — nothing to do.
        if (index == lastRequested && firstVisibleIndex == index) return Decision.Wait

        lastRequested = index
        val animate = hasPlaced
        hasPlaced = true
        return Decision.Scroll(index, animate)
    }

    /** Forget prior state, e.g. when the lyrics document is replaced. */
    fun reset() {
        hasPlaced = false
        lastRequested = NONE
        heldByUser = false
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
