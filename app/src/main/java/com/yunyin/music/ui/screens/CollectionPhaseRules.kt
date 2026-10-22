package com.yunyin.music.ui.screens

/** The collection page's mutually-exclusive presentations. */
internal enum class CollectionPhase { Loading, Content, Empty, Error }

/**
 * Error is a state, not an empty playlist.
 *
 * `CollectionUiState` had no error field, so a failed load rendered 「这个歌单还没有歌曲」. The rule
 * keeps the successful-empty case separate from the failure case; with existing content a transient
 * refresh error never replaces the list.
 */
internal object CollectionPhaseRules {
    fun phase(loading: Boolean, error: String?, trackCount: Int): CollectionPhase = when {
        loading -> CollectionPhase.Loading
        trackCount > 0 -> CollectionPhase.Content
        error != null -> CollectionPhase.Error
        else -> CollectionPhase.Empty
    }
}
