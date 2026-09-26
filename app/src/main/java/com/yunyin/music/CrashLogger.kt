package com.yunyin.music

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to a file in app storage.
 *
 * The app is normally run on hardware where logcat may not be reachable (or is truncated
 * by a restart), so the last crash is persisted at `filesDir/crash-last.txt` and can be
 * read back with `adb shell run-as com.yunyin.music cat files/crash-last.txt`, or through
 * the in-app Settings entry that surfaces the file.
 */
object CrashLogger {

    private const val FILE_NAME = "crash-last.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the last crash report, or null if none has been recorded. */
    fun lastCrash(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()

        val header = buildString {
            append("time=").append(timestamp()).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("android=").append(Build.VERSION.RELEASE)
                .append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
            append("\n")
        }
        File(context.filesDir, FILE_NAME).writeText(header + stack)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
