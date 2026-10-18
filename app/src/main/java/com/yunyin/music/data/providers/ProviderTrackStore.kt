package com.yunyin.music.data.providers

import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the provider-track identity available after a restart.
 *
 * [ProviderRef] lives on the in-memory [Track], but ids are what the app remembers (the media URI carries only
 * the id), and the process does not survive a restart. Without a persisted reverse mapping, a provider track that
 * came back through a restored queue, liked-song entry or play history — anything that stores an id — would look
 * like a NetEase id and be resolved against the wrong catalogue.
 *
 * ## Storage
 *
 * `filesDir/providers/tracks.json`: a JSON array, written atomically (temp file + rename) like the other stores,
 * bounded to [MAX_ENTRIES] most-recently-used entries so a long history cannot grow it without limit. A corrupt
 * file is ignored rather than fatal: this is a cache of identities, not user data.
 */
class ProviderTrackStore(private val file: File) {
    private val lock = Any()
    private var loaded = false
    private val entries = LinkedHashMap<Long, Snapshot>()

    /** Remembers a provider track so its [ProviderRef] can be recovered from its id after a restart. */
    fun remember(track: Track) {
        val ref = track.providerRef ?: return
        synchronized(lock) {
            loadLocked()
            entries.remove(track.id)
            entries[track.id] = Snapshot.of(track, ref)
            if (entries.size > MAX_ENTRIES) {
                val oldest = entries.keys.firstOrNull() ?: return
                entries.remove(oldest)
            }
            saveLocked()
        }
    }

    /** The full track for a synthetic provider id, or null when this id is not a provider track. */
    fun find(id: Long): Track? = synchronized(lock) {
        loadLocked()
        entries[id]?.toTrack()
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            saveLocked()
        }
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.isFile) return@runCatching
            val array = JSONArray(file.readText())
            for (index in 0 until array.length()) {
                val snapshot = Snapshot.from(array.optJSONObject(index) ?: continue)
                entries[snapshot.id] = snapshot
            }
        }
    }

    private fun saveLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            entries.values.forEach { array.put(it.toJson()) }
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(array.toString())
                temp.delete()
            }
        }
    }

    private class Snapshot(
        val id: Long,
        val provider: String,
        val providerId: String,
        val songId: String?,
        val mediaMid: String?,
        val albumMid: String?,
        val intervalSeconds: Int,
        val qualities: List<String>,
        val typeSizes: Map<String, String>,
        val name: String,
        val artists: String,
        val albumName: String,
        val coverUrl: String?,
        val durationMs: Long,
    ) {
        fun toTrack(): Track = Track(
            id = id,
            name = name,
            artists = artists.split(SEPARATOR).filter { it.isNotEmpty() },
            albumName = albumName,
            albumId = 0L,
            coverUrl = coverUrl,
            durationMs = durationMs,
            providerRef = ProviderRef(
                provider = provider,
                providerId = providerId,
                songId = songId,
                mediaMid = mediaMid,
                albumMid = albumMid,
                intervalSeconds = intervalSeconds,
                qualities = qualities,
                typeSizes = typeSizes,
            ),
        )

        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("provider", provider)
            .put("providerId", providerId)
            .put("songId", songId ?: JSONObject.NULL)
            .put("mediaMid", mediaMid ?: JSONObject.NULL)
            .put("albumMid", albumMid ?: JSONObject.NULL)
            .put("interval", intervalSeconds)
            .put("qualities", JSONArray(qualities))
            .put("types", JSONObject(typeSizes))
            .put("name", name)
            .put("artists", artists)
            .put("albumName", albumName)
            .put("cover", coverUrl ?: JSONObject.NULL)
            .put("duration", durationMs)

        companion object {
            fun of(track: Track, ref: ProviderRef) = Snapshot(
                id = track.id,
                provider = ref.provider,
                providerId = ref.providerId,
                songId = ref.songId,
                mediaMid = ref.mediaMid,
                albumMid = ref.albumMid,
                intervalSeconds = ref.intervalSeconds,
                qualities = ref.qualities,
                typeSizes = ref.typeSizes,
                name = track.name,
                artists = track.artists.joinToString(SEPARATOR),
                albumName = track.albumName,
                coverUrl = track.coverUrl,
                durationMs = track.durationMs,
            )

            fun from(json: JSONObject): Snapshot = Snapshot(
                id = json.optLong("id"),
                provider = json.optString("provider"),
                providerId = json.optString("providerId"),
                songId = json.optString("songId").takeIf { it.isNotBlank() },
                mediaMid = json.optString("mediaMid").takeIf { it.isNotBlank() },
                albumMid = json.optString("albumMid").takeIf { it.isNotBlank() },
                intervalSeconds = json.optInt("interval"),
                qualities = json.optJSONArray("qualities")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty(),
                typeSizes = json.optJSONObject("types")?.let { typesJson ->
                    typesJson.keys().asSequence().associateWith { key -> typesJson.optString(key) }
                }.orEmpty(),
                name = json.optString("name"),
                artists = json.optString("artists"),
                albumName = json.optString("albumName"),
                coverUrl = json.optString("cover").takeIf { it.isNotBlank() },
                durationMs = json.optLong("duration"),
            )
        }
    }

    private companion object {
        const val MAX_ENTRIES = 2_000

        /** Artists are stored joined; `\u0001` cannot appear in a platform artist name in practice. */
        const val SEPARATOR = "\u0001"
    }
}
