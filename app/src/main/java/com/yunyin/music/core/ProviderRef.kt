package com.yunyin.music.core

/**
 * A track that lives in another platform's catalogue and is played through a custom source, not NetEase.
 *
 * ## Why this exists
 *
 * The app's own API is NetEase-only, and NetEase gates part of its catalogue behind a membership: the app can
 * *find* those songs but only receives a ~45 s fragment. LX solves this by searching the other platforms itself
 * and asking a custom source for *that platform's* stream URL. A track found that way has no NetEase id at all —
 * it has a QQ/Kuwo/Kugou id — so the identity has to travel with the track from the search result to the queue
 * to the stream resolver.
 *
 * @param provider LX's source key (`tx`, `kw`, `kg`, `mg`); it is also the key a custom source declares.
 * @param providerId that platform's song id — QQ `mid`, Kuwo `rid`, Kugou `hash`, Migu `copyrightId`.
 * @param mediaMid the id of the *stream file* where the platform separates it from the song id (QQ's
 *   `file.media_mid`); a source that builds a URL from the file id needs it.
 * @param intervalSeconds the track length as the provider reported it, used to match the same song across
 *   platforms (LX compares within ±5 s) and to build the `interval` field a source expects.
 * @param qualities the quality tiers that platform reported sizes for, so the source is asked for a tier it can
 *   actually serve.
 */
data class ProviderRef(
    val provider: String,
    val providerId: String,
    /**
     * The platform's **numeric** song id where it has one.
     *
     * QQ Music uses a string `mid` for the song and a separate numeric id for API calls — the lyric endpoint
     * (`GetPlayLyricInfo`) takes only `songID`. The search response carries both, so the numeric id is kept here and
     * used for lyrics; when it is missing (an older persisted row) the provider fetches it from the song-detail
     * endpoint.
     */
    val songId: String? = null,
    val mediaMid: String? = null,
    val albumMid: String? = null,
    val intervalSeconds: Int = 0,
    val qualities: List<String> = emptyList(),
    /**
     * Tier → human-readable size, exactly as LX's search mapping reports it (`{"320k": "10.24 MiB"}`).
     *
     * LX hands a custom source `musicInfo.types` (`[{type,size}]`) and `musicInfo._types` (`{tier:{size}}`); a
     * source may display or compare those strings, so the size text is carried with the track instead of being
     * recomputed from bytes.
     */
    val typeSizes: Map<String, String> = emptyMap(),
)
