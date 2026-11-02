// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.lxsource.LxSourceLog

/**
 * Apple-Music-style overlapping crossfade with two ExoPlayers.
 *
 * The session keeps one [MediaSession]; the **audible** player is [audible]. On the boundary the secondary is
 * preloaded, faded in while the audible fades out (equal-power, 40 ms ticks), then the session is switched with the
 * supported public [MediaSession.setPlayer] call and the old player is released. The session (and therefore
 * `PlayerController`'s `MediaController`, notification, lock screen, lyrics) stays connected across the swap.
 *
 * Every step is guarded: any uncertainty ([CrossfadeRules]) aborts the overlap and restores the normal single-player
 * path. Nothing here may pause or stall the audible player while preloading.
 */
class CrossfadeCoordinator(
    private val session: MediaSession,
    initial: ExoPlayer,
    initialProbe: TrackEnergyProbe,
    private val createPlayer: (TrackEnergyProbe) -> ExoPlayer,
    private val settings: SettingsStore,
    private val log: LxSourceLog?,
    /** Called whenever the audible player changes (handover), so listeners can re-attach per-session effects. */
    private val onAudibleChanged: (ExoPlayer) -> Unit = {},
) : Player.Listener {

    private enum class State { IDLE, PRELOAD, FADE }

    private var audible: ExoPlayer = initial

    /** Energy meter attached to the audible player; feeds the smart-transition analysis. */
    private var audibleProbe: TrackEnergyProbe = initialProbe
    private var secondary: ExoPlayer? = null
    private var secondaryProbe: TrackEnergyProbe? = null

    /** Plan chosen when the overlap starts; null means "use the fixed slider duration". */
    private var fadePlan: MusicTransitionPlan? = null
    private var state = State.IDLE
    private var fadeStartedAt = 0L
    private var deadlinePassed = false
    private var lastPosition = 0L
    private var fadeFromIndex = -1

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() = tick()
    }

    /**
     * The transition clock.
     *
     * This is the piece that was missing: `evaluate()` used to run only from `Player.Listener.onEvents`,
     * and ExoPlayer emits **no events during steady playback** — so the one-second window in which the
     * preload has to start was never observed, no overlap was ever armed, and tracks simply ended. The
     * upstream service drives its transition from a 40 ms loop for exactly this reason; 100 ms is enough
     * for a decision that only needs to notice "the end is near", and leaves the 40 ms fade tick to
     * `ticker` alone.
     */
    private val poller = object : Runnable {
        override fun run() {
            runCatching { evaluate() }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Preload deadline; a named runnable so [abort] can cancel it without clearing the poller. */
    private val deadline = Runnable {
        deadlinePassed = true
        runCatching { evaluate() }
    }

    init {
        audible.addListener(this)
        handler.post(poller)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        runCatching { evaluate() }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (state != State.IDLE && !audible.playWhenReady) abort("paused")
    }

    /** Overlap length: the smart plan's duration while one is active, otherwise the slider's value. */
    private fun fadeMs(): Long {
        if (!settings.seamlessTransition) return 0L
        return fadePlan?.durationMs ?: CrossfadeRules.durationMs(settings.crossfadeSeconds)
    }

    private fun remainingMs(): Long {
        val duration = audible.duration
        if (duration <= 0L) return Long.MAX_VALUE
        return (duration - audible.currentPosition).coerceAtLeast(0L)
    }

    private fun evaluate() {
        when (state) {
            State.IDLE -> maybePreload()
            State.PRELOAD -> {
                if (deadlinePassed && secondary?.playbackState != Player.STATE_READY) {
                    abort("secondary-not-ready")
                    return
                }
                // The plan has to exist *before* the start decision, or the gate would use the slider's
                // seconds and a longer smart overlap would begin too late — the outgoing track then ends
                // mid-fade and the transition cuts. Computed once the secondary is ready (the incoming
                // probe needs the preloaded audio to have produced samples).
                if (settings.smartTransition && fadePlan == null &&
                    secondary?.playbackState == Player.STATE_READY
                ) {
                    fadePlan = smartMusicTransition(audibleProbe.profile(), secondaryProbe?.profile())
                    trace("plan", "provisional=${fadePlan?.durationMs} remaining=${remainingMs()}")
                }
                if (CrossfadeRules.shouldStartFade(
                        preloadReady = secondary?.playbackState == Player.STATE_READY,
                        fadeStarted = state == State.FADE,
                        remainingMs = remainingMs(),
                        fadeMs = fadeMs(),
                        playing = audible.playWhenReady,
                    )
                ) {
                    startFade()
                }
            }
            State.FADE -> {
                if (audible.currentMediaItemIndex != fadeFromIndex ||
                    CrossfadeRules.shouldAbort(
                        playing = audible.playWhenReady,
                        repeatMode = audible.repeatMode,
                        previousPositionMs = lastPosition,
                        positionMs = audible.currentPosition,
                    )
                ) {
                    abort("fade-cancelled")
                    return
                }
                lastPosition = audible.currentPosition
            }
        }
    }

    private fun maybePreload() {
        if (!settings.seamlessTransition) return
        val fade = fadeMs()
        if (fade <= 0L) return
        val index = audible.currentMediaItemIndex
        val next = CrossfadeRules.nextIndex(index, audible.mediaItemCount, audible.repeatMode)
        if (!CrossfadeRules.shouldStartPreload(
                enabled = settings.seamlessTransition,
                playing = audible.playWhenReady,
                secondaryAvailable = secondary != null,
                stateIsIdle = state == State.IDLE,
                remainingMs = remainingMs(),
                fadeMs = fade,
                nextIndex = next,
                repeatMode = audible.repeatMode,
            ) || next == null
        ) return
        runCatching {
            val items = (0 until audible.mediaItemCount).map { audible.getMediaItemAt(it) }
            // One probe per player: the meter of the outgoing track answers "how much tail is left",
            // the meter of the incoming one answers "how it starts". Enabled only in smart mode.
            val nextProbe = TrackEnergyProbe().apply { enabled = settings.smartTransition }
            val player = createPlayer(nextProbe)
            player.setMediaItems(items, next, 0L)
            player.volume = 0f
            player.playWhenReady = true
            player.prepare()
            secondary = player
            secondaryProbe = nextProbe
            state = State.PRELOAD
            deadlinePassed = false
            lastPosition = audible.currentPosition
            fadeFromIndex = index
            trace("preload", "next=$next audible=$index")
            handler.postDelayed(
                deadline,
                CrossfadeRules.PRELOAD_DEADLINE_MS,
            )
        }.onFailure {
            trace("abort", "preload-exception=${it.message}")
            abort("preload-exception")
        }
    }

    private fun startFade() {
        val next = secondary ?: return abort("secondary-gone")
        val fade = fadeMs()
        if (fade <= 0L) return abort("no-fade")
        runCatching {
            state = State.FADE
            fadeStartedAt = SystemClock.elapsedRealtime()
            fadeFromIndex = audible.currentMediaItemIndex
            lastPosition = audible.currentPosition
            // Ported smart transition: the two profiles decide the duration and a gain correction for the
            // incoming side. Fixed mode keeps the slider's seconds and unity gain.
            val outgoingProfile = audibleProbe.profile()
            val incomingProfile = secondaryProbe?.profile()
            val requested = fadePlan ?: if (settings.smartTransition) {
                smartMusicTransition(outgoingProfile, incomingProfile)
            } else {
                null
            }
            // Clamp to what is actually left of the outgoing track (upstream's `min(duration, remaining - 40)`).
            // Without it a 7.5 s plan starting at 6 s of remaining lets the old track run out mid-fade,
            // which is heard as the song stopping dead rather than crossfading.
            val remaining = remainingMs()
            fadePlan = requested?.copy(
                durationMs = requested.durationMs.coerceAtMost((remaining - 40L).coerceAtLeast(200L)),
            )
            next.volume = 0f
            next.playWhenReady = true
            // The trace carries the inputs as well as the answer, so "smart did not seem to do anything"
            // is diagnosable from the log instead of by ear: `plan=5.0/1.0` with null profiles means the
            // analysis bailed out, not that the two tracks were judged alike.
            trace(
                "fade",
                "fadeMs=$fade plan=${fadePlan?.let { "${it.durationMs}/${it.incomingGain}" } ?: "fixed"} " +
                    "out=rms=${outgoingProfile?.rms} tail=${outgoingProfile?.tailRatio} beat=${outgoingProfile?.beatPeriodMs} " +
                    "in=rms=${incomingProfile?.rms} lead=${incomingProfile?.leadingSilenceMs} index=$fadeFromIndex",
            )
            handler.post(ticker)
        }.onFailure {
            trace("abort", "fade-exception=${it.message}")
            abort("fade-exception")
        }
    }

    private fun tick() {
        if (state != State.FADE) return
        val next = secondary ?: return abort("secondary-gone")
        runCatching {
            val fade = fadeMs().coerceAtLeast(1L)
            val progress = ((SystemClock.elapsedRealtime() - fadeStartedAt).toFloat() / fade).coerceIn(0f, 1f)
            // Equal-power with the plan's incoming-gain correction (unity when the plan has none).
            val (out, incoming) = musicTransitionGains(progress, fadePlan?.incomingGain ?: 1f)
            audible.volume = out
            next.volume = incoming
            lastPosition = audible.currentPosition
            if (audible.playbackState == Player.STATE_ENDED || CrossfadeRules.isFinished(progress)) {
                // `ended` first: if the outgoing track runs out before the ramp finishes (a plan clamped
                // at the last moment, a seek near the end), handing over immediately keeps the incoming
                // side audible instead of leaving the user in silence.
                handover()
            } else {
                handler.postDelayed(ticker, 40L)
            }
        }.onFailure {
            trace("abort", "tick-exception=${it.message}")
            abort("tick-exception")
        }
    }

    private fun handover() {
        val old = audible
        val next = secondary ?: return abort("secondary-gone")
        runCatching {
            next.volume = 1f
            // Supported public route: keep the session and its listeners, swap only the player it controls.
            session.setPlayer(next)
            old.removeListener(this)
            audible = next
            audibleProbe = secondaryProbe ?: audibleProbe
            audibleProbe.enabled = settings.smartTransition
            audible.addListener(this)
            secondary = null
            secondaryProbe = null
            fadePlan = null
            state = State.IDLE
            old.stop()
            old.clearMediaItems()
            old.release()
            runCatching { onAudibleChanged(audible) }
            trace("handover", "index=${audible.currentMediaItemIndex} items=${audible.mediaItemCount}")
        }.onFailure {
            trace("abort", "handover-exception=${it.message}")
            abort("handover-exception")
        }
    }

    private fun abort(reason: String) {
        if (state == State.IDLE && secondary == null) {
            runCatching { audible.volume = 1f }
            return
        }
        state = State.IDLE
        handler.removeCallbacks(ticker)
        // Only the preload deadline is cancelled here; clearing *all* callbacks (as this used to) also
        // removed the poller, which left the coordinator unable to arm another overlap until the next
        // player event — i.e. until something unrelated happened.
        handler.removeCallbacks(deadline)
        secondary?.let {
            runCatching {
                it.stop()
                it.clearMediaItems()
                it.release()
            }
        }
        secondary = null
        secondaryProbe = null
        fadePlan = null
        runCatching { audible.volume = 1f }
        trace("abort", reason)
    }

    /** Releases both players/session participation; called from the service's onDestroy. */
    fun release() {
        runCatching { handler.removeCallbacks(poller) }
        runCatching { abort("release") }
        runCatching { audible.removeListener(this) }
    }

    private fun trace(phase: String, detail: String) {
        runCatching {
            log?.record(
                "解析",
                "stage=crossfade phase=$phase $detail enabled=${settings.seamlessTransition} " +
                    "seconds=${settings.crossfadeSeconds}",
            )
        }
    }

    private companion object {
        /** How often the coordinator checks whether it is time to preload or fade (see [poller]). */
        const val POLL_MS = 100L
    }
}
