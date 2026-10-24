package com.yunyin.music.ui.components

import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * One reused, muted ExoPlayer for the animated cover layer.
 *
 * - The player is created once with `remember` and released in `onDispose`; a track change only
 *   swaps the [MediaItem], never allocates a second decoder.
 * - It never requests audio focus and is always muted; the cover video is visual only.
 * - Pauses while the activity is not RESUMED and resumes when it is (if the stream is healthy).
 * - A first-frame timeout calls [onError] so a hanging HLS stream cannot sit black over the cover;
 *   the caller then keeps the static artwork.
 */
@Composable
fun AnimatedArtworkPlayer(
    url: String,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnError by rememberUpdatedState(onError)
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = false
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false, // never take audio focus
            )
        }
    }
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var failed by remember(url) { mutableStateOf(false) }
    var firstFrame by remember(url) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!failed) {
                    failed = true
                    currentOnError("play error reason=${error.message ?: error.errorCodeName}")
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(url) {
        failed = false
        firstFrame = false
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        delay(6_000)
        if (!firstFrame && !failed) {
            failed = true
            currentOnError("first-frame timeout")
        }
    }

    LaunchedEffect(url, resumed, failed) {
        player.playWhenReady = resumed && !failed
    }

    if (!failed) {
        AndroidView(
            factory = { ctx -> TextureView(ctx) },
            modifier = modifier.fillMaxSize(),
            update = { view -> player.setVideoTextureView(view) },
        )
    }
}
