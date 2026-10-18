package com.yunyin.music.ui.playlist

import androidx.compose.runtime.compositionLocalOf
import com.yunyin.music.core.Track

/**
 * Playlist actions reachable from surfaces that do not otherwise know about playlists (the player's action sheet).
 *
 * Same pattern as the download controller: a composition local keeps [com.yunyin.music.ui.screens.PlayerScreen]'s
 * parameter list (and the frozen chrome around it) untouched, while the sheet gets a real action instead of a
 * decorative row.
 */
class PlaylistActions(
    val onAddToPlaylist: (Track) -> Unit,
)

val LocalPlaylistActions = compositionLocalOf<PlaylistActions?> { null }
