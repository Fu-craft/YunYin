package com.yunyin.music

import android.app.Application

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
    }

    companion object {
        lateinit var instance: YunYinApp
            private set
    }
}
