package com.yunyin.music.data.playlist

/**
 * Cache-busting for playlist reads.
 *
 * The NeteaseCloudMusicApi deployment caches several result endpoints (`/user/playlist`, `/playlist/detail`,
 * `/playlist/track/all`) and only refreshes its cache when the request carries a `timestamp`. Without it, a create
 * can succeed and the following refetch still returns the **old** list — exactly "创建成功但不显示". The client
 * therefore stamps every playlist read with the current time.
 *
 * Pure so the rule is testable: a read query is the original parameters plus exactly one fresh `timestamp` value.
 */
object PlaylistRequestRules {

    const val TIMESTAMP_PARAM = "timestamp"

    /** The query with a cache-busting `timestamp`; any caller-supplied timestamp is replaced, never duplicated. */
    fun withCacheBust(query: Map<String, String>, nowMs: Long): Map<String, String> =
        LinkedHashMap(query).apply { put(TIMESTAMP_PARAM, nowMs.toString()) }

    /** Whether the query is guaranteed to bypass the server-side cache. */
    fun isCacheBusted(query: Map<String, String>): Boolean =
        query[TIMESTAMP_PARAM]?.toLongOrNull()?.let { it > 0L } == true
}
