package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

import org.json.JSONArray
import org.json.JSONObject

/** Exact report payloads from main.js `listenTogether` (reportPlaylist / reportCommand). */
object TogetherSeedRules {
    fun playlistParam(songIds: List<String>, userId: Long, version: Long): JSONObject {
        val ids = JSONArray().apply { songIds.forEach { put(it) } }
        return JSONObject()
            .put("commandType", "REPLACE")
            .put("version", JSONArray().put(JSONObject().put("userId", userId).put("version", version)))
            .put("anchorSongId", "")
            .put("anchorPosition", -1)
            .put("randomList", ids)
            .put("displayList", ids)
    }

    fun commandInfo(
        type: String,
        progressMs: Long,
        playing: Boolean,
        formerSongId: String,
        targetSongId: String,
        clientSeq: Long,
    ): JSONObject = JSONObject()
        .put("commandType", type)
        .put("progress", progressMs.coerceAtLeast(0L))
        .put("playStatus", if (playing) "PLAY" else "PAUSE")
        .put("formerSongId", formerSongId.ifBlank { "0" })
        .put("targetSongId", targetSongId.ifBlank { "0" })
        .put("clientSeq", clientSeq)
}
