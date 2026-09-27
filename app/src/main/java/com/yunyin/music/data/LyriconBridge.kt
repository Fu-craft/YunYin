package com.yunyin.music.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunyin.music.core.Track
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.providerMetadataOf

/**
 * Pushes the current song, lyrics and playback state to 词幕 (Lyricon).
 *
 * Lyricon renders lyrics *outside* the player — in the status bar, on the lock screen, and in its own
 * floating window — by receiving data from installed "provider" apps. This class is that provider
 * for 云音. Nothing here draws anything: the whole feature is publishing state, which is why it is a
 * thin adapter over [LyriconMapper] and the player's state.
 *
 * ## Design notes
 *
 * **Everything degrades to a no-op when Lyricon is absent.** `LyriconFactory.createProvider` returns
 * an empty implementation when the module is not installed (and below API 27), and every
 * `RemotePlayer` call returns false rather than throwing. So the app must not depend on this for any
 * of its own behaviour, and the UI reports availability instead of silently doing nothing.
 *
 * **Registration is deferred until Lyricon is actually there.** The provider connects to Lyricon's
 * central service over a binder; registering on app start would wake that service (and hold a binding)
 * for users who do not have Lyricon installed. [register] is therefore called only from app startup
 * when the module is present, and the connection is dropped in [release].
 *
 * **Position is sent, not animated.** Lyricon keeps its own clock from the last `setPosition` plus the
 * playback state and speed, so there is no need to push a position every frame — and doing so would
 * be a binder call per frame. It is re-sent on the events that actually move the clock: play, pause,
 * seek, and track change.
 */
class LyriconBridge(private val context: Context) {

    private var provider: LyriconProvider? = null

    /** Set once [register] has been called, so repeated calls are harmless. */
    private var registrationAttempted = false

    /**
     * Whether Lyricon is installed and the provider is connected.
     *
     * Compose state rather than a plain field: the Settings row shows it and a retry can change it
     * while that row is on screen, so a plain field would leave the display stale.
     */
    var connected by mutableStateOf(false)
        private set

    /**
     * Whether the app has ever successfully registered.
     *
     * Distinguishes "Lyricon is not installed" from "installed but not currently connected", which
     * need different advice in Settings.
     */
    var available by mutableStateOf(false)
        private set

    // ---------------------------------------------------------------- lifecycle

    /**
     * Creates and registers the provider.
     *
     * Safe to call repeatedly and safe to call when Lyricon is not installed. Returns immediately on
     * the calling thread: `createProvider`/`register` are cheap, and the connection completes
     * asynchronously via [ConnectionListener].
     */
    fun register() {
        if (registrationAttempted) return
        registrationAttempted = true
        runCatching {
            val created = LyriconFactory.createProvider(
                context = context,
                providerPackageName = context.packageName,
                playerPackageName = context.packageName,
                logo = null,
                // The name shown in Lyricon's provider list. `providerMetadataOf` is the library's
                // own builder for this map; constructing the wrapper by hand would be equivalent but
                // would depend on it implementing Map, which is an implementation detail.
                metadata = providerMetadataOf("name" to APP_NAME),
            )
            provider = created
            created.service.addConnectionListener(
                object : ConnectionListener {
                    override fun onConnected(p: LyriconProvider) = applyConnectionState(true)
                    override fun onReconnected(p: LyriconProvider) = applyConnectionState(true)
                    override fun onDisconnected(p: LyriconProvider) = applyConnectionState(false)

                    /**
                     * Lyricon is not installed, or its central service never came up.
                     *
                     * Both are normal states rather than errors, so this only records that the
                     * bridge is not connected — the app's own features are unaffected either way.
                     */
                    override fun onConnectTimeout(p: LyriconProvider) {
                        applyConnectionState(false)
                    }
                },
            )
            created.register()
        }.onFailure { error ->
            // A bridge failure must never take the app down: this whole feature is optional.
            Log.w(TAG, "Lyricon provider registration failed", error)
        }
    }

    /**
     * Allows another registration attempt.
     *
     * The first attempt is guarded because registering binds Lyricon's central service; retrying on
     * every resume would hold that binding for users who are not using it. But a *failed* attempt
     * (Lyricon installed while the app was running, or its service started later) should not be
     * permanent, so Settings can ask for one more try.
     */
    fun retry() {
        if (connected) return
        registrationAttempted = false
        register()
    }

    private fun applyConnectionState(value: Boolean) {
        connected = value
        if (value) {
            available = true
            // Push whatever is already playing, so a bridge connected mid-song does not leave
            // Lyricon showing nothing until the next track change.
            onConnectedCallback?.invoke()
        }
    }

    /** Invoked when the connection is (re)established, so current state can be published. */
    var onConnectedCallback: (() -> Unit)? = null

    /** Drops the connection and frees the binder resources. */
    fun release() {
        runCatching {
            provider?.unregister()
            provider?.destroy()
        }
        provider = null
        connected = false
    }

    // ---------------------------------------------------------------- publishing

    /**
     * Publishes the current song (with its lyrics) and playback state.
     *
     * Returns without doing anything when not connected, so callers can call this unconditionally on
     * every state change without checking availability first.
     */
    fun publish(
        track: Track?,
        lyrics: SyncedLyrics?,
        isPlaying: Boolean,
        positionMs: Long,
    ) {
        val player = provider?.player ?: return
        if (!connected) return

        runCatching {
            if (track == null) {
                // Nothing playing: clear Lyricon's display rather than leaving the last song up.
                player.sendText(null)
                player.setPlaybackState(false)
                return@runCatching
            }
            player.setSong(LyriconMapper.toSong(track, lyrics))
            player.setPlaybackState(isPlaying)
            player.setPosition(positionMs.coerceAtLeast(0L))
            // Lyricon interpolates between position updates; one per second keeps it aligned without
            // a binder call per frame.
            player.setPositionUpdateInterval(POSITION_UPDATE_INTERVAL_MS)
            // Both display toggles are instructions to Lyricon about what it may show; the document
            // already carries the translation, so this only controls visibility.
            player.setDisplayTranslation(true)
        }.onFailure { error ->
            Log.w(TAG, "Lyricon publish failed", error)
        }
    }

    /** Publishes playback position only — cheaper than a full [publish] and enough for a seek. */
    fun publishPosition(positionMs: Long) {
        if (!connected) return
        runCatching { provider?.player?.setPosition(positionMs.coerceAtLeast(0L)) }
    }

    /** Publishes play/pause only. */
    fun publishPlaybackState(isPlaying: Boolean) {
        if (!connected) return
        runCatching { provider?.player?.setPlaybackState(isPlaying) }
    }

    private companion object {
        const val TAG = "YunYin/Lyricon"
        const val APP_NAME = "云音"
        const val POSITION_UPDATE_INTERVAL_MS = 1000
    }
}
