package com.yunyin.music.data.lxsource

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The retrievable record of the last custom-source failure.
 *
 * ## Why a file in the app's own storage
 *
 * A source runs JavaScript on-device, and when one fails the useful part is a JS stack trace that never
 * reaches the screen. The app already solved the same problem for crashes (`CrashLogger`, `crash-last.txt`):
 * app-private files are unreadable without adb on an unrooted device, and a *launch-time* failure makes adb
 * the only other route, which is no help to whoever is holding the phone. So the detail lands in
 * `filesDir/lxsource/last-error.txt`, next to the scripts it describes, and the sheet shows the short reason.
 *
 * Deliberately not a general log: one file, overwritten each time, so it cannot grow without bound.
 */
class LxSourceLog internal constructor(private val file: File) {

    /** Per-lookup blocks, appending; the import failure file above stays a single "last error". */
    private val diagnosticsFile = File(file.parentFile, DIAGNOSTICS_FILE)

    /**
     * @param context only used to locate `filesDir/lxsource`; the component itself is a file writer, which is
     *   why the internal constructor takes the file directly and a JVM test can point it at a temp directory.
     */
    constructor(context: Context) : this(File(lxSourceRoot(context), LAST_ERROR_FILE))

    /** Records a failure detail. Never throws: logging must not be the thing that breaks an import. */
    fun record(stage: String, detail: String) {
        // `runCatching` around the logcat call as well as the file write: under a JVM unit test the Android
        // `Log` stub throws "not mocked", and a *logging* call must never be the reason a caller fails.
        runCatching { Log.w(TAG, "$stage: $detail") }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("stage=$stage\n$detail\n")
        }
    }

    /**
     * Appends one diagnostic block (see [LxLookupTrace.format]).
     *
     * Appending rather than overwriting is the point: the useful question is "what did the last few lookups do",
     * and a single overwritten line could not answer it. The file is rotated to [MAX_DIAGNOSTICS_BYTES] so it
     * still cannot grow without bound.
     */
    fun recordDiagnostic(block: String) {
        runCatching { Log.i(TAG, block.lineSequence().firstOrNull().orEmpty()) }
        synchronized(this) {
            runCatching {
                file.parentFile?.mkdirs()
                diagnosticsFile.appendText(block + "\n")
                if (diagnosticsFile.length() > MAX_DIAGNOSTICS_BYTES) {
                    val keep = diagnosticsFile.readText().takeLast((MAX_DIAGNOSTICS_BYTES * 3 / 4).toInt())
                    val firstLineBreak = keep.indexOf('\n')
                    diagnosticsFile.writeText(if (firstLineBreak >= 0) keep.substring(firstLineBreak + 1) else keep)
                }
            }
        }
    }

    /**
     * Everything the copy affordance should put on the clipboard: the lookup trail (oldest first) followed by
     * the last import/initialisation error, which is a different kind of failure and labelled as such.
     */
    fun readLast(): String? {
        val diagnostics = runCatching {
            diagnosticsFile.takeIf { it.isFile && it.length() > 0L }?.readText()
        }.getOrNull()
        val lastError = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        if (diagnostics.isNullOrBlank() && lastError.isNullOrBlank()) return null
        return buildString {
            if (!diagnostics.isNullOrBlank()) {
                appendLine("===== 音源解析诊断（越靠下越新）=====")
                append(diagnostics)
            }
            if (!lastError.isNullOrBlank()) {
                if (!diagnostics.isNullOrBlank()) appendLine()
                appendLine("===== 最近一次音源导入/初始化错误 =====")
                append(lastError)
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
        runCatching { diagnosticsFile.delete() }
    }

    private companion object {
        const val TAG = "YunYin/LxSource"
        const val LAST_ERROR_FILE = "last-error.txt"
        const val DIAGNOSTICS_FILE = "diagnostics.log"
        const val MAX_DIAGNOSTICS_BYTES = 64L * 1024L
    }
}

/**
 * Where imported scripts live: `filesDir/lxsource/`.
 *
 * A directory of its own rather than the files root, because the content is user-supplied executable code and
 * the three kinds of file in it (scripts, the metadata sidecar, the last error) belong together and nowhere
 * else. See [LxSourceStore] for why the scripts are files rather than preferences.
 */
fun lxSourceRoot(context: Context): File = File(context.filesDir, "lxsource")
