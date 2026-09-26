package com.yunyin.music

import android.content.Context
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.LyricsRepository
import com.yunyin.music.data.MusicRepository
import com.yunyin.music.data.PlayHistoryStore
import com.yunyin.music.data.SearchHistoryStore
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.net.NeteaseClient
import com.yunyin.music.playback.PlayerController

/**
 * Hand-rolled DI container.
 *
 * The graph is tiny and the app is single-process, so a container with lazy
 * singletons keeps construction explicit without a DI framework.
 */
class AppContainer(val appContext: Context) {

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val client: NeteaseClient by lazy { NeteaseClient(settings) }

    val music: MusicRepository by lazy { MusicRepository(client) }

    val lyrics: LyricsRepository by lazy { LyricsRepository(appContext, client) }

    val artwork: ArtworkLoader by lazy { ArtworkLoader(appContext) }

    val searchHistory: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }

    val playHistory: PlayHistoryStore by lazy { PlayHistoryStore(appContext) }

    val player: PlayerController by lazy {
        PlayerController(appContext) { trackId ->
            music.streamUrl(trackId, settings.quality)
        }
    }

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as YunYinApp).container
    }
}
