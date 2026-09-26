package com.yunyin.music.playback

import androidx.compose.runtime.staticCompositionLocalOf

/** Provides the process-wide [PlayerController] to the composition. */
val LocalPlayerController = staticCompositionLocalOf<PlayerController> {
    error("PlayerController not provided")
}
