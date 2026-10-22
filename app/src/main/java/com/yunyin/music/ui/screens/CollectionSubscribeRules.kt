package com.yunyin.music.ui.screens

/**
 * The 收藏 entry's label, once it lives inside the 更多 sheet instead of a third header pill.
 *
 * The label states the action, not the current state: an already-subscribed collection shows
 * 取消收藏, because a menu row is something to tap, not a status readout. Pure so the wording cannot
 * drift between the album and playlist pages.
 */
internal object CollectionSubscribeRules {
    fun label(subscribed: Boolean): String = if (subscribed) "取消收藏" else "收藏"

    /** The optimistic state after one tap; the server confirms it (or the host reverts on failure). */
    fun next(subscribed: Boolean): Boolean = !subscribed
}
