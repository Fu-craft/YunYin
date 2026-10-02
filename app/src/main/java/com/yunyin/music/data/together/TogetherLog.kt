package com.yunyin.music.data.together

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling log of what the listen-together engine actually did.
 *
 * ## Why this exists
 *
 * Together-listen cannot be reproduced without **two signed-in NetEase accounts**, and a guest session is
 * refused outright (measured). Every symptom reported from real testing — "无法加入", "房间里没有他",
 * "歌曲不同步" — therefore has to be diagnosed from the outside, and the only way to see the inside is for
 * the app to write down what it sent and received.
 *
 * So this records one line per meaningful event: the resolved own uid, the server's answer to create/join,
 * every command published, and a summary of every poll (the room's command, who authored it, whether it was
 * judged to be this device's own, and how many members the room reports).
 *
 * ## Written to disk, not just memory
 *
 * A listen-together session is long and the app may be backgrounded or killed while the user switches to a
 * chat app to send an invite — which is exactly when the interesting events happen. An in-memory buffer
 * would be gone by the time anyone looked. So each line is also appended to
 * `filesDir/together-log.txt`, truncated when it grows past [CAPACITY], and the copy action reads that file
 * (falling back to memory) so a report survives a restart.
 *
 * This is the same reasoning as the crash log: a report nobody can retrieve is not a report.
 */
object TogetherLog {

    /** Enough to cover a join plus a few minutes of polling, and short enough to paste into a message. */
    private const val CAPACITY = 400

    private val lines = ArrayDeque<String>()

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Set by [install]; null in unit tests, where the log stays in memory. */
    private var file: File? = null

    /** True once the buffer has dropped an entry, so a reader knows it is not the whole story. */
    var truncated: Boolean = false
        private set

    /** Points the log at a file so it survives a restart. Safe to call more than once. */
    @Synchronized
    fun install(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "together-log.txt")
        // A previous run's lines are not this run's evidence, and mixing them would mislead.
        runCatching { file?.writeText("") }
    }

    @Synchronized
    fun add(message: String) {
        val line = "%s  %s".format(clock.format(Date()), message)
        if (lines.size >= CAPACITY) {
            lines.removeFirst()
            truncated = true
        }
        lines.addLast(line)
        // Best-effort: a log that cannot be written must never break the feature it is describing.
        runCatching { file?.appendText(line + "\n") }
    }

    /** The recorded lines, oldest first. */
    @Synchronized
    fun dump(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        truncated = false
        runCatching { file?.writeText("") }
    }

    /**
     * The log as one block of text, for the clipboard.
     *
     * Prefers the in-memory buffer, and falls back to the file: after a restart the buffer is empty but the
     * file still holds what happened, which is the case where a report matters most.
     */
    @Synchronized
    fun report(): String {
        val fromDisk = file?.takeIf { it.exists() }?.let { runCatching { it.readLines() }.getOrNull() }
        val body = if (lines.isNotEmpty()) lines.toList() else fromDisk.orEmpty()
        return buildString {
            append("云音 一起听日志\n")
            append("(最多保留最近 $CAPACITY 行)\n")
            append("\n")
            if (body.isEmpty()) {
                append("(还没有任何记录：请先进一次一起听房间，再回到这里复制)\n")
            } else {
                body.forEach { append(it).append('\n') }
            }
        }
    }

    /** A long id shortened for a log line: the head and tail are the identifying parts. */
    fun short(id: String?): String = when {
        id == null -> "null"
        id.length <= 12 -> id
        else -> id.take(8) + "…" + id.takeLast(4)
    }
}
