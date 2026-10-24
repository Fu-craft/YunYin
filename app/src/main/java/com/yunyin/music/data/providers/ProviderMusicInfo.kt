// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers

import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.providers.tx.TxFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `musicInfo` object LX hands a custom source for a QQ Music track — reproduced field for field.
 *
 * ## Provenance
 *
 * The shape is defined by **lyswhut/lx-music-mobile** (GPL-3.0):
 *  - `src/utils/index.ts:82` `toOldMusicInfo` — the object passed to a user API's `getMusicUrl(songInfo, type)`,
 *    including the per-source `switch`: for `tx` it adds `strMediaMid`, `albumMid` and `songId`;
 *  - `src/utils/index.ts:28` `toNewMusicInfo` — the intermediate model, which stores the search result's
 *    `songmid` as `meta.songId`, its numeric `songId` as `meta.id`, its album mid as `meta.albumId`, and its
 *    `types`/`_types` as `meta.qualitys`/`meta._qualitys`;
 *  - `src/utils/musicSdk/tx/musicSearch.js:107-125` — the search result fields those come from (`mid`, `id`,
 *    `strMediaMid`, `albumMid`, `types`, `_types`).
 *
 * ## Field-by-field contract
 *
 * | field | LX value for `tx` |
 * |---|---|
 * | `name` / `singer` / `albumName` | track name / joined artists / album name |
 * | `source` | `"tx"` |
 * | `songmid` | the alphanumeric **mid** (`meta.songId`) |
 * | `songId` | the **numeric** id (`meta.id`) |
 * | `strMediaMid` | the stream file's media mid |
 * | `albumMid` | album mid |
 * | `albumId` | album mid (LX stores the album `mid` in `albumId` for QQ) |
 * | `interval` | `formatPlayTime` string, e.g. `"04:29"` (`--/--` when unknown) |
 * | `img` | cover URL, `""` when none |
 * | `types` | `[{type,size}]` per available tier |
 * | `_types` | `{tier:{size}}` |
 * | `typeUrl` | `{}` |
 * | `hash` | **absent** for `tx` (only `kg` sets it) |
 *
 * The earlier port sent the mid in *both* `songmid` and `songId`, and omitted `types`/`_types`/`img`/`typeUrl`/
 * `albumId`. A source that reads `songId` (the documented numeric field) therefore received a non-numeric value —
 * the kind of divergence this file exists to remove.
 */
internal object ProviderMusicInfo {

    fun forTrack(track: Track): JSONObject = musicInfo(track.providerRef, track)

    private fun musicInfo(ref: ProviderRef?, track: Track): JSONObject {
        val provider = ref?.provider.orEmpty()
        val intervalSeconds = ref?.intervalSeconds?.takeIf { it > 0 }
            ?: (track.durationMs / 1000).toInt()
        val types = JSONArray()
        val typesObject = JSONObject()
        val sizes = ref?.typeSizes.orEmpty()
        val tiers = ref?.qualities?.takeIf { it.isNotEmpty() } ?: sizes.keys.toList()
        tiers.forEach { tier ->
            val size = sizes[tier] ?: ""
            types.put(JSONObject().put("type", tier).put("size", size))
            typesObject.put(tier, JSONObject().put("size", size))
        }
        return JSONObject().apply {
            put("name", track.name)
            put("singer", track.artistLine)
            put("source", provider)
            // LX: songmid is the platform's song id as used by its own SDK (the QQ mid), NOT the numeric id.
            put("songmid", ref?.providerId ?: track.id.toString())
            put("interval", TxFormat.formatPlayTime(intervalSeconds.toLong()))
            put("albumName", track.albumName)
            put("img", track.coverUrl.orEmpty())
            put("typeUrl", JSONObject())
            put("albumId", ref?.albumMid.orEmpty())
            put("types", types)
            put("_types", typesObject)
            // LX: the per-source switch for tx adds these three fields to the object a user API receives.
            put("strMediaMid", ref?.mediaMid.orEmpty())
            put("albumMid", ref?.albumMid.orEmpty())
            // `meta.id` is the numeric id (a JSON number in LX, since it comes straight from the search JSON).
            ref?.songId?.toLongOrNull()?.let { put("songId", it) } ?: put("songId", ref?.songId.orEmpty())
        }
    }

    /**
     * The `request` payload for a `musicUrl` lookup on [provider].
     *
     * LX's source-invocation contract (`src/core/init/userApi/index.ts:88-112`): the script's `request` event
     * receives `{source, action:'musicUrl', info:{type, musicInfo}}`, so `source`/`action` sit at the top level and
     * the quality is `info.type`.
     */
    fun musicUrlRequest(provider: String, musicInfo: JSONObject, quality: String): String = JSONObject()
        .put("source", provider)
        .put("action", "musicUrl")
        .put(
            "info",
            JSONObject()
                .put("type", quality)
                .put("musicInfo", musicInfo),
        )
        .toString()
}
