package com.yunyin.music.data

/** Account subscribe gating and optimistic-revert rules. */
object SubscribeRules {
    const val DEBOUNCE_MS = 500L

    fun canSubscribe(cookie: String, userId: Long, anonymous: Boolean): Boolean =
        cookie.isNotBlank() && userId > 0L && !anonymous

    /** Server result → whether the optimistic UI state must be reverted (there is no local truth here). */
    fun shouldRevert(ok: Boolean): Boolean = !ok

    /** Detail response (authoritative) beats the cached list row; null detail means the cache still stands. */
    fun seedSubscribed(detail: Boolean?, cache: Boolean): Boolean = detail ?: cache

    /** Button text derives from state: an already-subscribed collection offers 取消收藏, not 收藏. */
    fun label(subscribed: Boolean): String = if (subscribed) "取消收藏" else "收藏"
}
