package com.yunyin.music.data.providers

import com.yunyin.music.core.NetResult
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.providers.tx.TxLyric
import com.yunyin.music.data.providers.tx.TxSearch
import com.yunyin.music.data.providers.tx.TxSongId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lyrics for a track that belongs to another platform.
 *
 * A provider track has no NetEase id, so the app's NetEase lyric path cannot serve it — that is why such a track
 * played without lyrics. This class is the provider half: it looks the track up in [ProviderTrackStore], finds its
 * platform id, and asks QQ Music's lyric endpoint directly (see [TxLyric], including the QRC decryption).
 *
 * ## Numeric id
 *
 * QQ's lyric API takes the numeric `songID`, not the `mid`. Search results carry both, so the common path uses
 * [com.yunyin.music.core.ProviderRef.songId]; a persisted row from before that field existed falls back to one
 * song-detail request ([TxSongId]).
 *
 * ## Failure is a value
 *
 * `fetch` returns null on every failure (no stored track, not this provider, no numeric id, endpoint error, no
 * decodable lyric) and writes a `provider=tx stage=lyric` block to the shared diagnostics log. The lyric repository
 * then falls back to NetEase-by-matching, and finally to "no lyrics" — never to a hang.
 */
class ProviderLyrics(
    private val store: ProviderTrackStore,
    private val txLyric: TxLyric = TxLyric(),
    private val txSongId: TxSongId = TxSongId(),
    private val log: LxSourceLog? = null,
) {

    /** Whether [trackId] is a stored QQ Music track that this path can serve. */
    fun supports(trackId: Long): Boolean = store.find(trackId)?.providerRef?.provider == TxSearch.PROVIDER

    /** The persisted track (title/artist/duration) behind a provider id, for the NetEase fallback match. */
    fun trackFor(trackId: Long): Track? = store.find(trackId)

    suspend fun fetch(trackId: Long): TxLyric.Parsed? {
        val track = store.find(trackId)
        val ref = track?.providerRef
        if (track == null || ref == null) {
            trace(trackId, "stage=lyric result=skip reason=not a stored provider track")
            return null
        }
        if (ref.provider != TxSearch.PROVIDER) {
            trace(trackId, "stage=lyric result=skip reason=provider=${ref.provider} not ported")
            return null
        }
        val numericSongId = ref.songId?.toLongOrNull()
            ?: txSongId.songIdFor(ref.providerId)
        if (numericSongId == null) {
            trace(trackId, "stage=lyric result=fail reason=no numeric song id providerId=${ref.providerId}")
            return null
        }
        return when (val result = txLyric.fetch(numericSongId)) {
            is NetResult.Err -> {
                trace(trackId, "stage=lyric result=fail songId=$numericSongId reason=${result.message}")
                null
            }
            is NetResult.Ok -> {
                val parsed = result.value
                trace(
                    trackId,
                    "stage=lyric result=ok songId=$numericSongId " +
                        "plain=${parsed.plain?.length ?: 0} words=${parsed.wordByWord?.length ?: 0} " +
                        "translation=${parsed.translation?.length ?: 0} romanization=${parsed.romanization?.length ?: 0}",
                )
                parsed
            }
        }
    }

    private fun trace(trackId: Long, detail: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching { log?.recordDiagnostic("[$timestamp] provider=${TxSearch.PROVIDER} trackId=$trackId\n  $detail") }
    }
}
