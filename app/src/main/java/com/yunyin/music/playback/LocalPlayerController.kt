// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

import androidx.compose.runtime.staticCompositionLocalOf

/** Provides the process-wide [PlayerController] to the composition. */
val LocalPlayerController = staticCompositionLocalOf<PlayerController> {
    error("PlayerController not provided")
}
