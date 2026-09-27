package com.yunyin.music

import android.app.Application
import com.yunyin.music.data.LyriconBridge

/** Process-wide entry point; owns the small hand-rolled dependency container. */
class YunYinApp : Application() {

    /** Wiring graph created once for the process. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogger.install(this)
        container = AppContainer(this)
        registerLyriconProvider()
    }

    /**
     * Announces this app to 词幕 (Lyricon) as a lyric source.
     *
     * Registration is a broadcast to Lyricon's central service, so when Lyricon is not installed it
     * simply reaches nobody (the bridge then reports a connect timeout and stops). That is cheap
     * enough to do unconditionally, and it is what makes the integration work the moment the user
     * installs Lyricon — no app restart or setting to find.
     *
     * Wrapped so a failure in this optional feature can never stop the app from starting.
     */
    private fun registerLyriconProvider() {
        runCatching { container.lyricon.register() }
    }

    companion object {
        lateinit var instance: YunYinApp
            private set
    }
}
