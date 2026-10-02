package com.yunyin.music.data.together

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling log of what the listen-together engine actually did.
 *
 * ## Why this exists
 *
 * Together-listen cannot be reproduced without **two signed-in NetEase accounts**, and a guest session is
 * refused by the API outright (measured). Every symptom reported from real testing — "他无法加入我的房间",
 * "我加入他的房间没有他", "显示但是歌曲没有同步" — therefore has to be diagnosed from the *outside*, and the
 * only way to see the inside is for the app to write down what it sent and received.
 *
 * So this records one line per meaningful event: the invite, the server's answer to create/join, every
 * command published, and a summary of every poll (who the peer is and what song they are on). The last
 * [CAPACITY] lines are kept in memory, so nothing is written to disk and nothing survives a restart unless
 * the user copies it out.
 *
 * ## Why not just logcat
 *
 * Because the report comes from a phone, and logcat needs a cable and a PC. The log is surfaced in Settings
 * as a copy button instead, which is the same reasoning as the crash-log row: a report nobody can retrieve
 * is not a report.
 */
object TogetherLog {

    /** Enough to cover a join plus a minute of polling, and short enough to paste into a message. */
    private const val CAPACITY = 120

    private val lines = ArrayDeque<String>()

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * True once [lines] has dropped an entry, so a reader knows the log is not the whole story.
     */
    var truncated: Boolean = false
        private set

    @Synchronized
    fun add(message: String) {
        if (lines.size >= CAPACITY) {
            lines.removeFirst()
            truncated = true
        }
        lines.addLast("%s  %s".format(clock.format(Date()), message))
    }

    /** The recorded lines, oldest first. */
    @Synchronized
    fun dump(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        truncated = false
    }

    /** The log as one block of text, for the clipboard. */
    @Synchronized
    fun report(): String = buildString {
        append("云音 一起听日志\n")
        if (truncated) append("(仅保留最近 $CAPACITY 行)\n")
        append("\n")
        lines.forEach { append(it).append('\n') }
    }

    /** A long id shortened for a log line: the room id's hash and its epoch are the identifying parts. */
    fun short(id: String?): String = when {
        id == null -> "null"
        id.length <= 12 -> id
        else -> id.take(8) + "…" + id.takeLast(4)
    }
}
