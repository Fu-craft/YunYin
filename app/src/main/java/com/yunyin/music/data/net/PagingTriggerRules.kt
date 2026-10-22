package com.yunyin.music.data.net

/**
 * When a paginated list should request its next page automatically.
 *
 * Pagination used to be a tap on a 「加载更多」 footer. These rules move that decision to scrolling:
 * the next page starts when the last visible item is within [LOOKAHEAD] items of the list's end, so
 * the list never visibly stalls at the floor. A list that fits on screen still triggers, because its
 * last visible index is already at the end — that is the "viewport not filled" case.
 *
 * Pure and unit-tested so every screen shares the same guards and none of them can silently drop one:
 * no load while a page is in flight, at the end, while an append error is showing (the footer's retry
 * is the explicit way back), or before the first page exists.
 */
object PagingTriggerRules {

    /** How close to the end the last visible item must be, in items. */
    const val LOOKAHEAD = 3

    fun shouldLoad(
        lastVisibleIndex: Int,
        totalItemCount: Int,
        hasMore: Boolean,
        loadingMore: Boolean,
        appendError: Boolean,
        hasItems: Boolean,
    ): Boolean {
        if (!hasItems || !hasMore || loadingMore || appendError) return false
        if (totalItemCount <= 0 || lastVisibleIndex < 0) return false
        return lastVisibleIndex >= totalItemCount - LOOKAHEAD
    }

    /**
     * Variant for a list whose paging footer is itself the sentinel (the artist page's sections sit
     * in one LazyColumn, so "distance to the whole list's end" is not the songs section's end).
     */
    fun shouldLoadAtFooter(
        footerVisible: Boolean,
        hasMore: Boolean,
        loadingMore: Boolean,
        appendError: Boolean,
        hasItems: Boolean,
    ): Boolean = footerVisible && hasItems && hasMore && !loadingMore && !appendError
}
