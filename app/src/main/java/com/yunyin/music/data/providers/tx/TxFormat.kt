// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers.tx

/**
 * QQ Music's formatting helpers — ports of two tiny functions a source sees the *results* of, so they must match.
 *
 * ## Provenance
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0), `src/utils/common.ts`:
 *  - `sizeFormate` (line 11) — the `size` strings inside `musicInfo.types`/`_types`;
 *  - `formatPlayTime` (line 61) — the `interval` string inside `musicInfo`.
 *
 * Both are kept byte-for-byte, including `sizeFormate`'s binary units (`KiB`/`MiB`) and `formatPlayTime`'s `--/--`
 * for a zero duration, because a source may compare or display these strings.
 */
internal object TxFormat {

    private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    /** `sizeFormate`: binary units, two decimals. */
    fun sizeFormate(size: Long): String {
        if (size <= 0L) return "0 B"
        var number = 0
        var value = size.toDouble()
        while (value >= 1024.0 && number < UNITS.lastIndex) {
            value /= 1024.0
            number++
        }
        return String.format(java.util.Locale.ROOT, "%.2f %s", value, UNITS[number])
    }

    /** `formatPlayTime`: `m:ss`, or `--/--` when the duration is unknown. */
    fun formatPlayTime(seconds: Long): String {
        val minutes = seconds / 60
        val remainder = seconds % 60
        if (minutes == 0L && remainder == 0L) return "--/--"
        return "%02d:%02d".format(minutes, remainder)
    }
}
