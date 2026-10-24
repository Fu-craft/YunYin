// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.download

import android.content.Context
import com.yunyin.music.core.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * One download, as persisted between process lifetimes.
 *
 * The partial file lives in the app's own cache directory; [downloadedBytes] is authoritative only when it matches
 * that file's length (checked by the writer). [url]/[etag]/[lastModified] are what make resuming safe: the same
 * record is only resumed when the newly resolved URL is identical.
 */
data class DownloadRecord(
    val track: Track,
    val quality: String = "",
    val url: String = "",
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0L,
    val etag: String? = null,
    val lastModified: String? = null,
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val message: String? = null,
    val fileName: String? = null,
    val uri: String? = null,
    val updatedAtMs: Long = 0L,
) {
    val sizeLabel: String
        get() {
            val bytes = totalBytes ?: downloadedBytes
            if (bytes <= 0L) return "—"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.1f MB", mb)
        }
}

/**
 * Small JSON persistence for download records (SharedPreferences, like the app's other light stores).
 *
 * No Room dependency is added: the set is bounded by songs the user actually downloaded, and every update writes one
 * small JSON array. Reads tolerate corrupt/partial JSON by returning an empty list rather than crashing a launch.
 */
class DownloadRecordStore(context: Context) {

    private val prefs = context.getSharedPreferences("yunyin_downloads", Context.MODE_PRIVATE)

    fun all(): List<DownloadRecord> = runCatching {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            runCatching { recordFromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun get(trackId: Long): DownloadRecord? = all().firstOrNull { it.track.id == trackId }

    fun save(record: DownloadRecord) {
        val current = all().toMutableList()
        val index = current.indexOfFirst { it.track.id == record.track.id }
        val stamped = record.copy(updatedAtMs = System.currentTimeMillis())
        if (index >= 0) current[index] = stamped else current += stamped
        write(current)
    }

    fun remove(trackId: Long) {
        val current = all().filterNot { it.track.id == trackId }
        write(current)
    }

    fun clearCompleted() {
        write(all().filterNot { it.phase == DownloadPhase.DONE })
    }

    private fun write(records: List<DownloadRecord>) {
        runCatching {
            val array = JSONArray()
            records.forEach { array.put(recordToJson(it)) }
            prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
        }
    }

    private fun recordToJson(record: DownloadRecord): JSONObject {
        val track = record.track
        return JSONObject().apply {
            put("track", JSONObject().apply {
                put("id", track.id)
                put("name", track.name)
                put("artists", JSONArray(track.artists))
                put("albumName", track.albumName)
                put("albumId", track.albumId)
                put("coverUrl", track.coverUrl)
                put("durationMs", track.durationMs)
            })
            put("quality", record.quality)
            put("url", record.url)
            put("totalBytes", record.totalBytes ?: -1L)
            put("downloadedBytes", record.downloadedBytes)
            put("etag", record.etag)
            put("lastModified", record.lastModified)
            put("phase", record.phase.name)
            put("message", record.message)
            put("fileName", record.fileName)
            put("uri", record.uri)
            put("updatedAtMs", record.updatedAtMs)
        }
    }

    private fun recordFromJson(json: JSONObject): DownloadRecord {
        val t = json.getJSONObject("track")
        val artists = t.optJSONArray("artists")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        val track = Track(
            id = t.getLong("id"),
            name = t.getString("name"),
            artists = artists,
            albumName = t.optString("albumName"),
            albumId = t.optLong("albumId"),
            coverUrl = t.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
            durationMs = t.optLong("durationMs"),
        )
        val total = json.optLong("totalBytes", -1L)
        return DownloadRecord(
            track = track,
            quality = json.optString("quality"),
            url = json.optString("url"),
            totalBytes = total.takeIf { it > 0L },
            downloadedBytes = json.optLong("downloadedBytes").coerceAtLeast(0L),
            etag = json.optString("etag").takeIf { it.isNotBlank() && it != "null" },
            lastModified = json.optString("lastModified").takeIf { it.isNotBlank() && it != "null" },
            phase = runCatching { DownloadPhase.valueOf(json.optString("phase")) }.getOrDefault(DownloadPhase.IDLE),
            message = json.optString("message").takeIf { it.isNotBlank() && it != "null" },
            fileName = json.optString("fileName").takeIf { it.isNotBlank() && it != "null" },
            uri = json.optString("uri").takeIf { it.isNotBlank() && it != "null" },
            updatedAtMs = json.optLong("updatedAtMs"),
        )
    }

    private companion object {
        const val KEY_RECORDS = "records"
    }
}
