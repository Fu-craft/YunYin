// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers

import java.security.MessageDigest

/**
 * Maps a provider track to the `Long` id the rest of the app keys on.
 *
 * ## Why a number, and why negative
 *
 * `Track.id` is a `Long` throughout the app (liked songs, history, queue playback, the synthetic media URI), and
 * every NetEase id is a positive number. Deriving a **negative** 62-bit id from `provider:providerId` therefore
 * keeps the two id spaces disjoint without touching any call site, and stays stable across runs — the same QQ
 * song always gets the same id, so a persisted liked/history entry keeps pointing at it.
 *
 * [ProviderTrackStore] persists id → [com.yunyin.music.core.ProviderRef] so the relationship can be reversed
 * after a restart; the derivation here is only the "forward" direction.
 */
object ProviderTrackIds {

    fun idFor(provider: String, providerId: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$provider:$providerId".toByteArray(Charsets.UTF_8))
        var bits = 0L
        for (index in 0 until 8) bits = (bits shl 8) or (digest[index].toLong() and 0xff)
        // 62 bits, never zero: NetEase ids are positive, so a negative id cannot collide with one.
        val magnitude = (bits and 0x3FFF_FFFF_FFFF_FFFFL).let { if (it == 0L) 1L else it }
        return -magnitude
    }
}
