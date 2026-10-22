package com.yunyin.music.data

/** Account subscribe gating and optimistic-revert rules. */
object SubscribeRules {
    const val DEBOUNCE_MS = 500L

    fun canSubscribe(cookie: String, userId: Long, anonymous: Boolean): Boolean =
        cookie.isNotBlank() && userId > 0L && !anonymous

    /** Server result → whether the optimistic UI state must be reverted (there is no local truth here). */
    fun shouldRevert(ok: Boolean): Boolean = !ok
}
