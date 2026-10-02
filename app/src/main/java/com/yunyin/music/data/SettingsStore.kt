package com.yunyin.music.data

import android.content.Context
import android.content.SharedPreferences
import com.yunyin.music.core.Account

/**
 * Local preferences and session state.
 *
 * Backed by [SharedPreferences] rather than DataStore: the payload is a handful of scalars
 * plus one cookie string, so the synchronous read at startup is simpler and avoids an async
 * dependency in the DI container.
 *
 * Note there is deliberately no server address, proxy or client-IP preference. The service
 * endpoint is fixed at build time and shipped with the app; surfacing network configuration only
 * asks the user to reason about infrastructure that is not theirs to manage.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("amll_settings", Context.MODE_PRIVATE)

    /** Cookie jar for the NetEase API, persisted as a single header value. */
    var cookie: String
        get() = prefs.getString(KEY_COOKIE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    /** Preferred audio quality flag understood by `/song/url/v1`. */
    var quality: String
        get() = prefs.getString(KEY_QUALITY, "exhigh") ?: "exhigh"
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    /**
     * Whether the app publishes to 词幕 (Lyricon) at all.
     *
     * Defaults on, matching the behaviour before the switch existed: on a device where 词幕 is
     * installed, publishing is what the user installed it for. It is opt-out because the feature is
     * invisible otherwise — nothing in 云音's own UI shows the status-bar lyric, so someone who does
     * not want it would have no way to discover it was happening.
     *
     * Off means the binder connection is dropped, not merely hidden.
     */
    var lyriconEnabled: Boolean
        get() = prefs.getBoolean(KEY_LYRICON_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LYRICON_ENABLED, value).apply()

    /**
     * This installation's identity inside a listen-together room.
     *
     * Deliberately **not** the NetEase account id. Two reasons, and both bit before:
     *
     *  - The transport ignores messages whose sender id equals its own, so two devices sharing one
     *    account could never see each other — which made the feature impossible to test with a single
     *    account, and would also break a legitimate "same account on phone and tablet" case.
     *  - A room is a conversation between *devices*, not accounts. The account only supplies a
     *    nickname to show.
     *
     * Generated once per installation and persisted, so the peer sees a stable member across
     * restarts. Never sent anywhere but the room topic.
     */
    val togetherMemberId: String
        get() {
            prefs.getString(KEY_TOGETHER_MEMBER_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_TOGETHER_MEMBER_ID, generated).apply()
            return generated
        }

    /**
     * Whether to post the Flyme status-bar lyric notification.
     *
     * Opt-out rather than opt-in: on a ROM that supports it the user installed the app expecting the
     * feature, and a permanent notification is the documented mechanism — there is no way to show a
     * status-bar lyric without one. Defaults to on, and the toggle exists so it can be turned off
     * where a permanent notification is unwelcome.
     */
    var statusBarLyrics: Boolean
        get() = prefs.getBoolean(KEY_STATUS_BAR_LYRICS, true)
        set(value) = prefs.edit().putBoolean(KEY_STATUS_BAR_LYRICS, value).apply()

    /**
     * Whether the head of each stream is tapered, so a track begins rather than switching on.
     *
     * Defaults on. It is a small, safe improvement: the taper changes amplitude only and cannot affect the
     * media position, so it cannot desync the lyrics or the progress bar. (Silence trimming was removed
     * from this feature — it broke seeking; see `SeamlessAudioProcessor`.)
     */
    var seamlessTransition: Boolean
        get() = prefs.getBoolean(KEY_SEAMLESS_TRANSITION, true)
        set(value) = prefs.edit().putBoolean(KEY_SEAMLESS_TRANSITION, value).apply()

    /**
     * Which player presentation to draw: the classic rounded cover, or the rotating vinyl record.
     *
     * Stored as the `PlayerStyle.level` string rather than an ordinal, so reordering or renaming the enum
     * cannot silently change what a user has chosen. An unrecognised value falls back to the default at
     * construction (`PlayerStyle.from`), which is what keeps a downgrade from failing to open the player.
     */
    var playerStyle: String
        get() = prefs.getString(KEY_PLAYER_STYLE, "classic") ?: "classic"
        set(value) = prefs.edit().putString(KEY_PLAYER_STYLE, value).apply()

    // ------------------------------------------------------------ updates

    /**
     * When the update check last ran, in wall-clock milliseconds; 0 when it never has.
     *
     * Persisted rather than held in memory because the point of the record is to survive the restarts that
     * a "once a day" rule is about. GitHub's anonymous API allows 60 requests per hour per address, so an
     * automatic check on every launch would be both wasteful and eventually refused.
     */
    var updateLastCheckMs: Long
        get() = prefs.getLong(KEY_UPDATE_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_LAST_CHECK, value).apply()

    /**
     * The version the user chose to skip, so a dismissed update is not re-offered.
     *
     * Only the *version name*, not a flag: once a newer one is published, the comparison stops matching and
     * the offer returns by itself.
     */
    var updateSkippedVersion: String
        get() = prefs.getString(KEY_UPDATE_SKIPPED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UPDATE_SKIPPED, value).apply()

    // ------------------------------------------------------------ profile

    /**
     * Free-text signature shown under the nickname on the profile header.
     *
     * Empty by default: the header falls back to the account's id rather than showing a blank line.
     */
    var profileSignature: String
        get() = prefs.getString(KEY_PROFILE_SIGNATURE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROFILE_SIGNATURE, value).apply()

    /**
     * The user's own nickname, overriding the account's.
     *
     * Empty means "follow NetEase" — the same arrangement as the avatar switch, because both are the same
     * question: is this field mine or the account's? A local override is the only option here: NetEase's
     * profile-update endpoints are behind risk control (the same wall that forced sign-in into an embedded
     * browser), so writing the nickname back to the cloud is not reliably possible.
     */
    var customNickname: String
        get() = prefs.getString(KEY_CUSTOM_NICKNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_NICKNAME, value).apply()

    /**
     * Whether the displayed nickname follows the account, mirroring [useNeteaseAvatar].
     *
     * Defaults on: someone who just signed in expects to see their account's name, and the override exists
     * for the case where they would rather show something else.
     */
    var useNeteaseName: Boolean
        get() = prefs.getBoolean(KEY_USE_NETEASE_NAME, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_NETEASE_NAME, value).apply()

    /**
     * Whether the avatar follows the NetEase account's own avatar.
     *
     * Defaults on, because that is what someone who just signed in expects to see. Turning it off keeps
     * whatever custom image was picked, so the switch is a choice between two stored states rather than
     * a destructive toggle.
     */
    var useNeteaseAvatar: Boolean
        get() = prefs.getBoolean(KEY_USE_NETEASE_AVATAR, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_NETEASE_AVATAR, value).apply()

    /**
     * File name (not a path) of the custom avatar/background inside the app's private profile directory.
     *
     * A name rather than a full path so the store survives the app's data directory moving, and a copied
     * file rather than the picker's URI because a `content://` URI from the photo picker is only
     * guaranteed for the lifetime of the grant — persisting that string would leave the header blank once
     * the grant expired.
     */
    var customAvatarFile: String?
        get() = prefs.getString(KEY_CUSTOM_AVATAR, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_AVATAR, value).apply()

    var customBackgroundFile: String?
        get() = prefs.getString(KEY_CUSTOM_BACKGROUND, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_BACKGROUND, value).apply()

    /**
     * The signed-in account's own details, mirrored locally.
     *
     * NetEase does not expose a profile "signature" through the endpoints this app calls, so a signature
     * the user types is stored here and treated as the local override.
     */
    var account: Account?
        get() {
            val id = prefs.getLong(KEY_USER_ID, 0L)
            if (id == 0L) return null
            return Account(
                userId = id,
                nickname = prefs.getString(KEY_NICKNAME, "") ?: "",
                avatarUrl = prefs.getString(KEY_AVATAR, null),
                isAnonymous = prefs.getBoolean(KEY_ANON, false),
            )
        }
        set(value) = prefs.edit().apply {
            if (value == null) {
                remove(KEY_USER_ID); remove(KEY_NICKNAME); remove(KEY_AVATAR); remove(KEY_ANON)
            } else {
                putLong(KEY_USER_ID, value.userId)
                putString(KEY_NICKNAME, value.nickname)
                putString(KEY_AVATAR, value.avatarUrl)
                putBoolean(KEY_ANON, value.isAnonymous)
            }
        }.apply()

    fun clearSession() {
        prefs.edit().remove(KEY_COOKIE).remove(KEY_USER_ID).remove(KEY_NICKNAME)
            .remove(KEY_AVATAR).remove(KEY_ANON).apply()
    }

    // ------------------------------------------------------------ per-song lyric offset

    /**
     * Manual lyric timing offset for one track, in milliseconds.
     *
     * Positive shifts the lyrics later (they were running early). This is the manual escape hatch
     * for a track whose lyric timeline genuinely does not match the streamed audio: automatic
     * alignment fixes a *global* offset between two sources, but cannot help when the lyric itself
     * is simply mistimed, or when the audio being streamed is a different edit again.
     *
     * Stored as one JSON object rather than a preference per track so the key space stays bounded;
     * only tracks the user actually adjusted are kept.
     */
    fun lyricOffsetMs(trackId: Long): Long =
        lyricOffsets()[trackId.toString()] ?: 0L

    fun setLyricOffsetMs(trackId: Long, offsetMs: Long) {
        val map = lyricOffsets().toMutableMap()
        if (offsetMs == 0L) map.remove(trackId.toString()) else map[trackId.toString()] = offsetMs
        val json = org.json.JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }
        prefs.edit().putString(KEY_LYRIC_OFFSETS, json.toString()).apply()
    }

    private fun lyricOffsets(): Map<String, Long> = runCatching {
        val raw = prefs.getString(KEY_LYRIC_OFFSETS, null) ?: return emptyMap()
        val json = org.json.JSONObject(raw)
        json.keys().asSequence().associateWith { json.optLong(it) }
    }.getOrDefault(emptyMap())

    companion object {
        /**
         * NeteaseCloudMusicApi endpoint the app talks to.
         *
         * Injected at build time from `local.properties` (`api.base.url`) via
         * `BuildConfig.API_BASE_URL`, so no server address is committed to the repository. An empty
         * value means this build has no endpoint configured: the app surfaces that instead of
         * silently sending the user's requests to a server they did not choose.
         */
        val BASE_URL: String = com.yunyin.music.BuildConfig.API_BASE_URL

        /** True when this build was configured with an API endpoint. */
        val hasBaseUrl: Boolean get() = BASE_URL.isNotBlank()

        private const val KEY_COOKIE = "cookie"
        private const val KEY_QUALITY = "quality"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_ANON = "anonymous"
        private const val KEY_LYRIC_OFFSETS = "lyric_offsets"
        private const val KEY_STATUS_BAR_LYRICS = "status_bar_lyrics"
        private const val KEY_LYRICON_ENABLED = "lyricon_enabled"
        private const val KEY_TOGETHER_MEMBER_ID = "together_member_id"
        private const val KEY_SEAMLESS_TRANSITION = "seamless_transition"
        private const val KEY_PLAYER_STYLE = "player_style"
        private const val KEY_PROFILE_SIGNATURE = "profile_signature"
        private const val KEY_CUSTOM_NICKNAME = "custom_nickname"
        private const val KEY_USE_NETEASE_NAME = "use_netease_name"
        private const val KEY_USE_NETEASE_AVATAR = "use_netease_avatar"
        private const val KEY_CUSTOM_AVATAR = "custom_avatar_file"
        private const val KEY_CUSTOM_BACKGROUND = "custom_background_file"
        private const val KEY_UPDATE_LAST_CHECK = "update_last_check"
        private const val KEY_UPDATE_SKIPPED = "update_skipped_version"
    }
}
