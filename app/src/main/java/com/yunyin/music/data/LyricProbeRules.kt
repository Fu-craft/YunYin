// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

/**
 * A lyric source, plus the priority used to break ties in the probe.
 *
 * Lower [probeRank] wins a tie. Order: QQ QRC (already word-timed on the platform) > NetEase
 * (`yrc` word timing, else `lrc`) > AMLL community TTML > Kuwo > Kugou. The QQ/NetEase sources are
 * first because they are the track's *own* catalogue and their word timing lines up with the audio
 * the app is playing; the extras are title+artist matches, i.e. the slowest and least certain lanes.
 */
enum class LyricSource(val tag: String, val probeRank: Int) {
    Tx("tx", 0),
    Netease("wy", 1),
    Amll("amll", 2),
    Kuwo("kw", 3),
    Kugou("kg", 4),
}

/** Metadata of one document produced by a probe lane. The winner rule is pure and unit-tested. */
data class LyricProbeCandidate(
    val source: LyricSource,
    val wordCount: Int,
    val lineCount: Int,
) {
    /** A document with timed words renders as karaoke, which is what the probe prefers. */
    val wordByWord: Boolean get() = wordCount > 0
}

/**
 * The probe-then-paint-once rule.
 *
 * The user's requirement: while the panel is in its loading state, check the sources for word-by-word
 * lyrics, prefer them, and **paint exactly once** with the best result — no "show the weak document
 * first and upgrade it later", because that is the mid-song source swap that was reported as broken.
 *
 * Winner: any word-by-word document wins over any line-level one. Among word-by-word documents the
 * one with the most timed words wins (tie: [LyricSource.probeRank]); among line-level documents the
 * one with the most lines wins (same tie-break). A fragment (< [MIN_LINES] lines, typically a credit
 * or placeholder) counts as nothing.
 */
object LyricProbeRules {

    /** A document with fewer than two lines is a fragment (or only a credit) and is not paint-worthy. */
    const val MIN_LINES = 2

    /**
     * Total probe budget before painting the best line-level result (or the honest empty state).
     *
     * Measured inputs from the earlier lyric work: NetEase `/lyric/new` answers in ~0.2–0.4 s; the
     * AMLL mirrors are raced and answer fast when reachable; Kuwo/Kugou are the slow lanes because
     * they need a title+artist search before the lyric fetch (each provider allows 6 s, 10 s total).
     * 2.5 s is the shortest cap that lets the fast lanes and a usually-fast AMLL mirror answer while
     * keeping the loading spinner under ~3 s — the worst case here is 2.5 s of loading instead of a
     * swap after playback has already started.
     */
    const val PROBE_CAP_MS = 2_500L

    fun isPaintable(lineCount: Int): Boolean = lineCount >= MIN_LINES

    fun winner(candidates: List<LyricProbeCandidate>): LyricProbeCandidate? {
        val usable = candidates.filter { isPaintable(it.lineCount) }
        if (usable.isEmpty()) return null
        val wordByWord = usable
            .filter { it.wordByWord }
            .sortedWith(
                compareByDescending<LyricProbeCandidate> { it.wordCount }
                    .thenBy { it.source.probeRank },
            )
        if (wordByWord.isNotEmpty()) return wordByWord.first()
        return usable
            .sortedWith(
                compareByDescending<LyricProbeCandidate> { it.lineCount }
                    .thenBy { it.source.probeRank },
            )
            .first()
    }
}
