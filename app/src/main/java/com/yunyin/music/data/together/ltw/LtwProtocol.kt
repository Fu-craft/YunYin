package com.yunyin.music.data.together.ltw

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire shapes of the **NeriPlayer listen-together** service
 * (github.com/TheSmallHanCat/NeriPlayer-LTW), a Cloudflare Worker + Durable Object.
 *
 * ## Why this protocol, after the NetEase one failed
 *
 * Every earlier attempt at this feature kept the playback state **peer to peer**: each device published
 * what it was playing and each decided what to do about the other. That model has an unavoidable failure
 * mode — two devices that disagree about play/pause correct each other in a loop — and the server-side read
 * path it relied on could not even be exercised without two real accounts.
 *
 * This service is **server-authoritative**: one device is the *controller*, the server stores the room's
 * playback, computes `expectedPositionMs` from its own clock (`basePositionMs + elapsed * rate`), and
 * broadcasts the result. A listener's play/pause/next is a **request** that the server arbitrates. There is
 * therefore no "whose action is newer" question and nothing for two devices to argue about: they both read
 * the same authoritative state and move toward it. That is what makes the inversion bugs structurally
 * impossible here rather than merely fixed.
 *
 * ## Room identity
 *
 * A room id is **6 characters** from an unambiguous alphabet. Joining needs a `joinSecret`; reconnecting
 * needs the member's own `memberSecret`. Tokens live 24h and carry the room, the user and the role.
 * `userUuid` must be a real UUID — the service validates the format.
 *
 * These classes parse the JSON and nothing else; transport, retries and mapping live in [LtwTransport].
 */

/** A track as the room stores it. `channelId` must not be `local` or the server rejects the payload. */
data class LtwTrack(
    val stableKey: String,
    val channelId: String,
    val audioId: String,
    val name: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val coverUrl: String?,
)

/** The room's playback anchor. `expectedPositionMs` is derived from this against the server clock. */
data class LtwPlayback(
    val state: String,
    val basePositionMs: Long,
    val baseTimestampMs: Long,
    val playbackRate: Double,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
) {
    val playing: Boolean get() = state == "playing"
}

/** One member, as the room reports them. No avatar: the service stores only an id, a nickname and a role. */
data class LtwMember(
    val userUuid: String,
    val nickname: String,
    val role: String,
)

/** The authoritative room snapshot. */
data class LtwRoomState(
    val roomId: String,
    val version: Long,
    val controllerUserUuid: String,
    val members: List<LtwMember>,
    val queue: List<LtwTrack>,
    val currentIndex: Int,
    val track: LtwTrack?,
    val playback: LtwPlayback?,
    val roomStatus: String,
) {
    val closed: Boolean get() = roomStatus == "closed"
    val controllerOffline: Boolean get() = roomStatus == "controller_offline"
}

/** What create/join return: the room, this device's identity in it, and the credentials for next time. */
data class LtwMembership(
    val roomId: String,
    val userUuid: String,
    val nickname: String,
    val role: String,
    val token: String,
    val memberSecret: String?,
    val joinSecret: String?,
    val state: LtwRoomState,
)

/** A read of the room with the server's own clock, so the client can age the position correctly. */
data class LtwSnapshot(
    val state: LtwRoomState,
    /** The position the server says is current, at [serverNowMs]. */
    val expectedPositionMs: Long,
    val serverNowMs: Long,
)

/** The outcome of a control call. A refusal is a value, not an exception — the UI has to explain it. */
data class LtwFailure(val message: String, val httpCode: Int, val errorCode: String?) {
    /** The room is over. Distinct because the UI should stop polling, not warn and continue. */
    val noRoom: Boolean get() = httpCode == 410 || errorCode == "room closed"
    /** The controller is not reachable, so a listener cannot act right now. */
    val controllerOffline: Boolean get() = errorCode == "controller offline"
}

internal object LtwJson {

    fun track(o: JSONObject?): LtwTrack? {
        if (o == null) return null
        val key = o.optString("stableKey")
        val audioId = o.optString("audioId")
        if (key.isBlank() || audioId.isBlank()) return null
        return LtwTrack(
            stableKey = key,
            channelId = o.optString("channelId"),
            audioId = audioId,
            name = o.optString("name"),
            artist = o.optString("artist"),
            album = o.optString("album").ifBlank { null },
            durationMs = o.optLong("durationMs"),
            coverUrl = o.optString("coverUrl").ifBlank { null },
        )
    }

    fun tracks(a: JSONArray?): List<LtwTrack> =
        (0 until (a?.length() ?: 0)).mapNotNull { track(a?.optJSONObject(it)) }

    fun member(o: JSONObject?): LtwMember? {
        if (o == null) return null
        val uuid = o.optString("userUuid").ifBlank { o.optString("userId") }
        if (uuid.isBlank()) return null
        return LtwMember(uuid, o.optString("nickname"), o.optString("role"))
    }

    fun roomState(o: JSONObject?): LtwRoomState? {
        if (o == null || o.optString("roomId").isBlank()) return null
        val playback = o.optJSONObject("playback")
        return LtwRoomState(
            roomId = o.optString("roomId"),
            version = o.optLong("version"),
            controllerUserUuid = o.optString("controllerUserUuid"),
            members = (0 until (o.optJSONArray("members")?.length() ?: 0))
                .mapNotNull { member(o.optJSONArray("members")?.optJSONObject(it)) },
            queue = tracks(o.optJSONArray("queue")),
            currentIndex = o.optInt("currentIndex", -1),
            track = track(o.optJSONObject("track")),
            playback = if (playback == null) null else LtwPlayback(
                state = playback.optString("state", "paused"),
                basePositionMs = playback.optLong("basePositionMs"),
                baseTimestampMs = playback.optLong("baseTimestampMs"),
                playbackRate = playback.optDouble("playbackRate", 1.0),
                repeatMode = playback.optInt("repeatMode", 0),
                shuffleEnabled = playback.optBoolean("shuffleEnabled", false),
            ),
            roomStatus = o.optString("roomStatus", "active"),
        )
    }
}
