// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.stats

/**
 * 播放统计 kill switch — **default OFF**.
 *
 * Why it is off (v4.26.5): opening 播放统计 repeatedly killed the app. The last three releases added a Java
 * crash sink that appends FATAL + stack to the copyable diagnostics log, then stage markers, a 2 s load timeout,
 * async IO and hard caps on buckets/rows. The user's report still contained **no FATAL line and no
 * `stage=stats` marker at all**, which means either the death is native/ANR (invisible to the handler) or it
 * happens before the page composes. We cannot keep shipping a feature that takes the app down, so the entry,
 * the sampler and the screen are all gated by this flag until the root cause is established.
 *
 * What stays: the screen, recorder, store and rules remain compiled and unit-tested. Nothing is deleted.
 *
 * How to re-enable safely: flip [enabled] to true again, then verify on a real device with the diagnostics file
 * copied after (a) opening the page with no data and (b) opening it with existing data. Evidence needed before
 * flipping: a clean log with the complete marker sequence `open → load-start → load-done → render` and no abort,
 * or a FATAL/native tombstone that identifies the failing stage. Until then keep this false.
 */
object StatsFeature {
    const val enabled = false
}
