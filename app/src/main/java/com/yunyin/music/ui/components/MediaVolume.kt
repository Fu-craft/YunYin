// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.yunyin.music.ui.screens.PlayerChromeRules
import kotlin.math.roundToInt

/** State plus a throttled writer for the device's media stream. */
class MediaVolumeHandle internal constructor(
    private val audio: AudioManager,
    private val maxVolume: Int,
    initialFraction: Float,
) {
    var fraction by mutableFloatStateOf(initialFraction)
        private set

    var visible by mutableStateOf(true)
        private set

    private var dragging = false
    private var lastWriteAt = 0L
    private var lastWritten = -1
    private var lastExternal = initialFraction

    fun onFraction(value: Float, commit: Boolean) {
        val clamped = value.coerceIn(0f, 1f)
        fraction = clamped
        dragging = !commit
        val now = SystemClock.uptimeMillis()
        // Write at most every 80 ms while dragging; always write the final value.
        if (!commit && now - lastWriteAt < 80L) return
        val target = PlayerChromeRules.volumeFor(clamped, maxVolume)
        if (target != lastWritten || commit) {
            runCatching {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }.onFailure {
                visible = false
            }
            lastWritten = target
            lastWriteAt = now
        }
    }

    /** Hardware/volume-panel changes should move the thumb unless the user is dragging. */
    internal fun refreshFromDevice() {
        if (dragging) return
        val current = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            ?: return
        val next = PlayerChromeRules.volumeFraction(current, maxVolume)
        if (next != lastExternal) {
            lastExternal = next
            fraction = next
            lastWritten = current
        }
    }
}

@Composable
fun rememberMediaVolume(): MediaVolumeHandle? {
    val context = LocalContext.current
    val audio = remember(context) {
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    }
    val fixed = remember(audio) {
        runCatching { audio?.isVolumeFixed == true }.getOrDefault(false)
    }
    val max = remember(audio) {
        runCatching { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0)
    }
    if (audio == null || !PlayerChromeRules.shouldShowVolume(max, fixed)) return null
    val initial = remember(audio, max) {
        runCatching {
            PlayerChromeRules.volumeFraction(
                audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                max,
            )
        }.getOrDefault(0f)
    }
    val handle = remember(audio, max, initial) { MediaVolumeHandle(audio, max, initial) }
    DisposableEffect(context, handle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                handle.refreshFromDevice()
            }
        }
        val uri = Settings.System.getUriFor("volume_music")
        runCatching { context.contentResolver.registerContentObserver(uri, false, observer) }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    return handle
}

/**
 * 2 dp track, 10 dp thumb, 44 dp touch target. Deliberately not a Material Slider: the target
 * screenshot's track is 2 dp and the Material slider's minimum track/thickness cannot be reduced
 * without losing its measurement contract.
 */
@Composable
fun MediaVolumeSlider(handle: MediaVolumeHandle, modifier: Modifier = Modifier) {
    val thumbRadius = 5.dp
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                contentDescription = "音量"
                progressBarRangeInfo = ProgressBarRangeInfo(handle.fraction, 0f..1f)
                setProgress { target ->
                    handle.onFraction(target.coerceIn(0f, 1f), true)
                    true
                }
            }
            .pointerInput(handle) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun emit(position: Offset, commit: Boolean) {
                        if (size.width > 0) {
                            handle.onFraction((position.x / size.width).coerceIn(0f, 1f), commit)
                        }
                    }
                    emit(down.position, false)
                    down.consume()
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        pressed = change.pressed
                        if (pressed) {
                            emit(change.position, false)
                            change.consume()
                        }
                    }
                    handle.onFraction(handle.fraction, true)
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val centerY = size.height / 2f
            val trackHeight = 2.dp.toPx()
            val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = radius,
            )
            val played = size.width * handle.fraction.coerceIn(0f, 1f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(played, trackHeight),
                cornerRadius = radius,
            )
            val r = thumbRadius.toPx()
            drawCircle(
                color = Color.White,
                radius = r,
                center = Offset(played.coerceIn(r, size.width - r), centerY),
            )
        }
    }
}
