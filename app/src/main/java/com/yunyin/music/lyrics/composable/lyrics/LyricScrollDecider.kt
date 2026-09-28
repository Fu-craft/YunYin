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
 *    usable for that, because it is also true during our own animation.
 *  - A **large jump** (a seek, or a skip to another part of the song) is placed instantly rather than
 *    animated. The caller does this by [reset]ting on a position discontinuity, which makes the next
 *    placement a first placement; a jump does not belong in this state machine, because only the caller
 *    can see that the position moved by more than playback could have.
 *  - A request that is already **in flight** is not re-issued, so a long scroll is not restarted underneath
 *    itself.
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
     * @param scrolling whether the list is currently being scrolled by us or settling from one. A
     *        request in flight must not be re-issued — restarting it every frame is what stopped a
     *        seek from ever arriving.
     */
    fun decide(
        index: Int?,
        dragging: Boolean,
        firstVisibleIndex: Int,
        scrolling: Boolean = false,
    ): Decision {
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

        // Already requested, and either the list is on it or a scroll toward it is in flight. The
        // in-flight case matters: `animateScrollToItem` suspends until it settles, but a *preempting*
        // scroll can cancel it, and without this a cancelled long scroll would be restarted from
        // wherever it had reached each time — which is what made a far target appear never to arrive.
        if (index == lastRequested && (firstVisibleIndex == index || scrolling)) return Decision.Wait

        lastRequested = index
        val animate = hasPlaced
        hasPlaced = true
        return Decision.Scroll(index, animate)
    }

    /** Forget prior state, e.g. when the lyrics document is replaced or the user seeks. */
    fun reset() {
        hasPlaced = false
        lastRequested = NONE
        heldByUser = false
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
