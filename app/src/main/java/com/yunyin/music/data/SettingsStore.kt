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
        private const val KEY_SEAMLESS_TRANSITION = "seamless_transition"
    }
}
