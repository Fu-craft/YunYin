package com.yunyin.music.ui.components

/**
 * Alpha for a pinned bar title that replaces a page's large header title on scroll.
 *
 * Shared by the artist page and Settings so the two top bars cannot drift: while the header title is
 * on screen the bar title is invisible, and it fades in over a window measured from the header's own
 * height as that title slides under the strip. Read in the layer phase (`graphicsLayer { alpha }`),
 * never in composition.
 *
 * @param firstVisibleItemIndex 0 while the header is still the first item; past that the header has
 *   scrolled out entirely and the title is fully shown.
 * @param firstVisibleItemScrollOffset pixels scrolled within the first item.
 * @param fadeStartPx where the fade begins, in px (0 = as soon as the header starts moving).
 * @param fadeRampPx the fade's length, in px — the header's own measured height.
 */
internal fun scrollLinkedTitleAlpha(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    fadeStartPx: Float,
    fadeRampPx: Float,
): Float = when {
    firstVisibleItemIndex > 0 -> 1f
    fadeRampPx <= 0f -> 0f
    else -> ((firstVisibleItemScrollOffset - fadeStartPx) / fadeRampPx).coerceIn(0f, 1f)
}
