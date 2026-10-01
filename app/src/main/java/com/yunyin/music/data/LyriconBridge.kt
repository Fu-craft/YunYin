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
 * **Position must be pushed repeatedly, and that is not an optimisation.**
 *
 * `RemotePlayer.setPosition(ms)` sends nothing over the binder: it writes a single `Long` into a
 * `SharedMemory` buffer obtained from Lyricon when the connection binds. That buffer holds no
 * timestamp and no speed, so the receiver cannot work out the rate — it can only read whatever value
 * is there. The cadence at which it does so is what `setPositionUpdateInterval` configures.
 *
 * An earlier version of this class pushed the position only on a track change, a play/pause flip or a
 * seek, assuming Lyricon interpolated its own clock. It does not — verified by decompiling the
 * provider library: `CachedRemotePlayer` caches a plain `lastPosition` and nothing advances it. The
 * visible symptom was that the highlighted line never moved past the first one.
 *
 * [publishPosition] is therefore called on every player state emission, which the controller produces
 * at 4Hz while playing. That is cheap precisely because it is a memory write, not a binder call.
 */
class LyriconBridge(private val context: Context, private val settings: SettingsStore) {

    private var provider: LyriconProvider? = null

    /** Set once [register] has been called, so repeated calls are harmless. */
    private var registrationAttempted = false

    /** Identity of the lyric document most recently sent, so it is not re-sent unchanged. */
    private var lastSongSignature: String? = null

    /**
     * Whether publishing is switched on in Settings.
     *
     * Mirrors the stored preference so the UI can read it without touching the store, and so a change
     * immediately affects [publish] rather than only after the next app start. Off means the connection
     * is dropped — see [setEnabled].
     */
    var enabled by mutableStateOf(settings.lyriconEnabled)
        private set

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
        // Switched off: do not even bind 词幕's central service.
        if (!enabled) return
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
        if (!enabled || connected) return
        registrationAttempted = false
        register()
    }

    /**
     * Turns publishing on or off from Settings.
     *
     * Off is a real disconnect, not a hidden switch: the binder to 词幕's service is dropped and the
     * resident registration removed, so the app stops touching another app's process at all. On
     * re-registers from scratch, which is why [registrationAttempted] is cleared rather than the
     * original attempt being reused.
     */
    fun applyEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        settings.lyriconEnabled = value
        // Both directions go through the lifecycle helpers, which own the registration flag:
        // `release()` clears it (so switching back on can bind) and `register()` respects `enabled`.
        if (value) retry() else release()
    }

    private fun applyConnectionState(value: Boolean) {
        connected = value
        if (value) {
            available = true
            // The read cadence is deliberately *not* set here.
            //
            // `setPositionUpdateInterval` configures how often the consumer reads the shared-memory
            // position, and the library's own default is 41ms (~24Hz) — its tuned value. An earlier
            // version of this class set it to 1000ms, which throttled the consumer to one read per
            // second and made the highlight lurch; that was a self-inflicted wound on top of the real
            // bug. The rate at which the value actually changes is our write cadence (4Hz, from the
            // player's position poll), which is the correct thing to adjust if this ever needs to be
            // smoother — not the reader's poll rate.
            //
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
        lastSongSignature = null
        registrationAttempted = false
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
                lastSongSignature = null
                player.sendText(null)
                player.setPlaybackState(false)
                return@runCatching
            }
            // The document is only re-sent when it actually changes.
            //
            // A `setSong` replaces the whole lyric set, and there is no guarantee the receiver keeps
            // its reading position across that — re-sending the identical document on every
            // play/pause is at best wasted work (it serialises every line to JSON and deflates it)
            // and at worst resets the displayed line to the top. The play state and position are
            // cheap idempotent writes and are always sent.
            val signature = songSignature(track, lyrics)
            if (signature != lastSongSignature) {
                player.setSong(LyriconMapper.toSong(track, lyrics))
                lastSongSignature = signature
            }
            player.setPlaybackState(isPlaying)
            player.setPosition(positionMs.coerceAtLeast(0L))
            // Both display toggles are instructions to Lyricon about what it may show; the document
            // already carries the translation, so this only controls visibility.
            player.setDisplayTranslation(true)
        }.onFailure { error ->
            Log.w(TAG, "Lyricon publish failed", error)
        }
    }

    /**
     * Identity of the document last sent, so an unchanged one is not re-sent.
     *
     * Based on what Lyricon would actually render: the song's identity, how many lines there are, and
     * the first and last line's times. That distinguishes a genuinely different document (a lyric
     * upgrade arriving mid-song, or a new track with the same title) without hashing every syllable.
     */
    private fun songSignature(track: Track, lyrics: SyncedLyrics?): String {
        val lines = lyrics?.lines
        return buildString {
            append(track.id).append('|').append(lines?.size ?: 0)
            lines?.firstOrNull()?.let { append('|').append(it.start) }
            lines?.lastOrNull()?.let { append('|').append(it.end) }
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
    }
}
