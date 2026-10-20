package com.yunyin.music.data

/**
 * Which lyric provider produced a document.
 *
 * The tags are the ones written to the shared diagnostics trail, so a report can say exactly which
 * source was pinned: `wy` (NetEase), `amll` (community TTML), `tx` (QQ Music), `kw` (Kuwo),
 * `kg` (Kugou).
 */
enum class LyricSource(val tag: String) {
    Netease("wy"),
    Amll("amll"),
    Tx("tx"),
    Kuwo("kw"),
    Kugou("kg"),
}

/** What is currently on screen for a track: the source that produced it, and its quality. */
data class PinnedLyric(val source: LyricSource, val wordByWord: Boolean)

/** Why a document was or was not painted. Tags are logged verbatim. */
enum class LyricPinDecision(val tag: String) {
    /** Nothing was displayed for this track yet, so the first usable document is pinned. */
    First("first"),

    /** The pinned document was line-level and the same source finished its word-by-word payload. */
    SameProviderUpgrade("same-provider-upgrade"),

    /** A different source offered a document after something was already displayed: never swap. */
    DifferentProviderBlocked("different-provider-blocked"),
}

/**
 * Pin rule for lyric documents.
 *
 * The user-visible bug this prevents: with several providers answering in parallel, the panel showed
 * the fast line-level document first and then jumped to another platform's word-by-word document —
 * the text is different wording/timing, so the swap reads as the app losing the lyric it was
 * showing. Multi-source must stay (it is what finds lyrics at all for many tracks), but once a
 * document is displayed it is **pinned**.
 *
 * The single exception is a *quality* upgrade that cannot be perceived as a different lyric: the
 * same source replacing its own line-level document with its own word-by-word one. Anything from a
 * different source is refused after first paint.
 *
 * Pure and unit-tested (`LyricPinRulesTest`); the repository only supplies provenance.
 */
object LyricPinRules {

    /**
     * A document with fewer than two lines is a fragment (often only a credit line, or a provider's
     * placeholder). Painting it and then replacing it is exactly the visible-swap class below, so it
     * is treated as "no document" instead.
     */
    const val MIN_LINES = 2

    /**
     * How long the first paint waits for the richer source before showing the fast one.
     *
     * Bounded and short (in the requested 400–600 ms band): the NetEase line-level document is
     * usually in hand at ~400 ms, and waiting half a second more turns the common case into "paint
     * the best document once" instead of "paint, then swap".
     */
    const val GRACE_WINDOW_MS = 500L

    /** Decide what to do with an incoming document, given what is already pinned for the track. */
    fun decide(
        pinned: PinnedLyric?,
        incomingSource: LyricSource,
        incomingWordByWord: Boolean,
    ): LyricPinDecision = when {
        pinned == null -> LyricPinDecision.First
        pinned.source == incomingSource && !pinned.wordByWord && incomingWordByWord ->
            LyricPinDecision.SameProviderUpgrade
        else -> LyricPinDecision.DifferentProviderBlocked
    }

    /** Whether a document is substantial enough to paint at all. */
    fun isPaintable(lineCount: Int): Boolean = lineCount >= MIN_LINES

    /**
     * The grace window to use before the first paint.
     *
     * Zero when the document already in hand is word-by-word: it is the best a line-level grace wait
     * could produce, so there is nothing to wait for.
     */
    fun graceWindowMs(firstDocumentIsWordByWord: Boolean): Long =
        if (firstDocumentIsWordByWord) 0L else GRACE_WINDOW_MS
}
