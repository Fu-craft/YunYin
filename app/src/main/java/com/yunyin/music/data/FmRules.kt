// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.Track

/** Pure 私人 FM queue rules and album-subscribed seeding. */
object FmRules {
    const val REFILL_THRESHOLD = 2
    const val BATCH_HINT = 4

    /** Refill when fewer than [REFILL_THRESHOLD] items remain after the current one. */
    fun shouldRefill(currentIndex: Int, queueSize: Int): Boolean =
        currentIndex < 0 || queueSize <= 0 || queueSize - currentIndex - 1 < REFILL_THRESHOLD

    /** Append without duplicating ids already in the radio queue. */
    fun appendUnique(existing: List<Track>, incoming: List<Track>): List<Track> {
        val seen = existing.mapTo(HashSet()) { it.id }
        return existing + incoming.filter { it.id > 0L && seen.add(it.id) }
    }

    /** Album page: use the dynamic endpoint's authoritative value; a failed read assumes not subscribed. */
    fun albumSubscribedSeeded(readSucceeded: Boolean, isSub: Boolean): Boolean =
        readSucceeded && isSub

    /** A radio session is identified by its source; starting any other queue leaves FM mode. */
    data class Session(val active: Boolean, val startedAtMs: Long)
    fun start(nowMs: Long) = Session(active = true, startedAtMs = nowMs)
    fun stop() = Session(active = false, startedAtMs = 0L)
}
