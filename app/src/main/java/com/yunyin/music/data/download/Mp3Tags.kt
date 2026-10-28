// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.download

import com.yunyin.music.core.Track
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes the ID3v2.3 tag a downloaded MP3 was missing.
 *
 * ## Why this exists
 *
 * The download pipeline copied the server's bytes verbatim, so the saved file carried no metadata at
 * all: every other player (file managers, car head units, the system media scanner) showed a title
 * derived from the filename and **no artwork**, even though the app itself had fetched a cover and
 * stored it as a sibling image. The cover has to be *inside* the file for anyone else to see it.
 *
 * ## What goes in
 *
 * - `TIT2` / `TPE1` / `TALB` — title, artist, album, UTF-16 with a BOM so Chinese survives.
 * - `APIC` — the front cover, JPEG or PNG as downloaded.
 * - `USLT` — the lyric text, unsynchronised, so players that read lyrics can find it.
 *
 * Frames are v2.3 (plain 32-bit sizes, not syncsafe), which is what the widest range of players
 * reads. Everything is best-effort: a missing cover or lyric simply omits that frame.
 */
internal object Mp3Tags {

    private const val ENCODING_UTF16 = 0x01
    private const val PICTURE_TYPE_FRONT_COVER = 0x03

    /** The complete tag to prepend, or an empty array when there is nothing worth writing. */
    fun build(track: Track, cover: ByteArray?, lyric: String?): ByteArray {
        val frames = ByteArrayOutputStream()
        textFrame("TIT2", track.name)?.let(frames::write)
        textFrame("TPE1", track.artistLine)?.let(frames::write)
        textFrame("TALB", track.albumName)?.let(frames::write)
        if (!lyric.isNullOrBlank()) unsynchronisedLyric(lyric)?.let(frames::write)
        if (cover != null && cover.isNotEmpty()) apic(cover)?.let(frames::write)

        val body = frames.toByteArray()
        if (body.isEmpty()) return ByteArray(0)
        val header = ByteArrayOutputStream()
        header.write("ID3".toByteArray(Charsets.US_ASCII))
        header.write(0x03) // v2.3
        header.write(0x00) // revision
        header.write(0x00) // flags: no unsynchronisation, no extended header
        header.write(syncSafe(body.size))
        return header.toByteArray() + body
    }

    /**
     * How many bytes of [file] an existing ID3v2 tag occupies, so the caller can skip it instead of
     * stacking two tags (players read the first one, but doubling the size is pointless).
     */
    fun existingTagLength(file: File): Long {
        if (file.length() < 10) return 0
        val head = ByteArray(10)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(head)
            if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
            val size = ((head[6].toInt() and 0x7f) shl 21) or
                ((head[7].toInt() and 0x7f) shl 14) or
                ((head[8].toInt() and 0x7f) shl 7) or
                (head[9].toInt() and 0x7f)
            val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
            return (10 + size + footer).toLong().coerceAtMost(file.length())
        }
    }

    private fun textFrame(id: String, value: String): ByteArray? {
        if (value.isBlank()) return null
        val payload = ByteArrayOutputStream()
        payload.write(ENCODING_UTF16)
        payload.write(bom(value))
        return frame(id, payload.toByteArray())
    }

    private fun unsynchronisedLyric(text: String): ByteArray? {
        val payload = ByteArrayOutputStream()
        payload.write(ENCODING_UTF16)
        payload.write("eng".toByteArray(Charsets.US_ASCII))
        payload.write(byteArrayOf(0, 0)) // empty content descriptor
        payload.write(bom(text))
        return frame("USLT", payload.toByteArray())
    }

    private fun apic(cover: ByteArray): ByteArray? {
        val mime = when {
            cover.size > 8 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte() -> "image/png"
            else -> "image/jpeg"
        }
        val payload = ByteArrayOutputStream()
        payload.write(0x00) // ISO-8859-1 for the mime/description strings
        payload.write(mime.toByteArray(Charsets.US_ASCII))
        payload.write(0x00)
        payload.write(PICTURE_TYPE_FRONT_COVER)
        payload.write(0x00) // empty description
        payload.write(cover)
        return frame("APIC", payload.toByteArray())
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 10)
        out.write(id.toByteArray(Charsets.US_ASCII))
        val size = payload.size
        out.write((size ushr 24) and 0xff)
        out.write((size ushr 16) and 0xff)
        out.write((size ushr 8) and 0xff)
        out.write(size and 0xff)
        out.write(0x00) // flags
        out.write(0x00)
        out.write(payload)
        return out.toByteArray()
    }

    /** UTF-16LE with a little-endian BOM, which is what encoding 0x01 expects. */
    private fun bom(value: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)

    private fun syncSafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )
}
