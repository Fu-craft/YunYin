// Adapter for CurrentMusicX's RecordShelfView (GNU AGPL-3.0).
//
// The renderer only reads four strings off its song model and one URL helper, so the whole data
// surface is the typealias plus these two declarations.
package com.yunyin.music.ui.components.shelf

data class ShelfSong(
    val id: Long = 0L,
    val musicSource: String = "netease",
    val name: String,
    val artists: String,
    val album: String,
    val cover: String,
)

typealias Song = ShelfSong

/** Sleeve URL at a given pixel size; NetEase covers accept a `?param=NyN` resize hint. */
fun coverRequestUrl(url: String, size: Int): String {
    if (url.isBlank()) return url
    return url.substringBefore("?param=") + "?param=${size}y$size"
}