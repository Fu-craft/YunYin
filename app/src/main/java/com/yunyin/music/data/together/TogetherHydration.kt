package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

import com.yunyin.music.core.Track

/** Pure helper for together.js `api.songs(remoteIds)` → `queue.replace(...)`. */
object TogetherHydration {

    /** ids that must be fetched: remote order, minus what the local queue already has. */
    fun missingIds(remoteIds: List<String>, localIds: Set<String>): List<Long> =
        remoteIds.filter { it !in localIds }.mapNotNull { it.toLongOrNull() }.distinct()

    /** Remote order wins; unknown ids keep a null slot so the caller can refuse a partial queue. */
    fun ordered(remoteIds: List<String>, local: Map<String, Track>, fetched: Map<String, Track>): List<Track> =
        remoteIds.mapNotNull { local[it] ?: fetched[it] }

    /** Target is the command's song if present, otherwise the first remote item (together.js behaviour). */
    fun targetIndex(remoteIds: List<String>, targetId: String): Int =
        remoteIds.indexOf(targetId).takeIf { it >= 0 } ?: 0
}
