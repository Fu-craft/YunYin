// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.download

import com.yunyin.music.core.Track
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Rewrites a FLAC file's metadata so the saved download carries its own title, artist, album, cover
 * and lyric — the same information [Mp3Tags] writes into an MP3's ID3 tag.
 *
 * FLAC is not a prefix-friendly format: the stream starts with `fLaC`, then a chain of metadata
 * blocks (each a 1-byte type/last flag and a 3-byte big-endian length), and only after the chain does
 * the audio frames begin. The only correct edit is to rebuild the chain — keep `STREAMINFO` first
 * (mandatory), drop any existing `VORBIS_COMMENT`/`PICTURE`, append ours, and let the frames follow
 * untouched. That is what [build] returns: the new prefix plus the byte offset the caller must skip
 * in the original file.
 */
internal object FlacTags {

    private const val BLOCK_STREAMINFO = 0
    private const val BLOCK_VORBIS_COMMENT = 4
    private const val BLOCK_PICTURE = 6

    /** Picture type 3 = front cover, the value every player looks for first. */
    private const val PICTURE_TYPE_FRONT_COVER = 3

    /**
     * The bytes to write instead of the file's original header, or null when [part] is not a FLAC
     * stream (then the caller copies the file verbatim).
     */
    fun build(part: File, track: Track, cover: ByteArray?, lyric: String?): Pair<ByteArray, Long>? {
        val bytes = runCatching { part.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 8) return null
        if (!(bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte())
        ) {
            return null
        }
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        var cursor = 4
        while (cursor + 4 <= bytes.size) {
            val header = bytes[cursor].toInt() and 0xff
            val type = header and 0x7f
            val last = header and 0x80 != 0
            val length = ((bytes[cursor + 1].toInt() and 0xff) shl 16) or
                ((bytes[cursor + 2].toInt() and 0xff) shl 8) or
                (bytes[cursor + 3].toInt() and 0xff)
            val start = cursor + 4
            val end = start + length
            if (end > bytes.size) return null
            if (type != BLOCK_VORBIS_COMMENT && type != BLOCK_PICTURE) {
                blocks += type to bytes.copyOfRange(start, end)
            }
            cursor = end
            if (last) break
        }
        val streamInfo = blocks.firstOrNull()?.takeIf { it.first == BLOCK_STREAMINFO } ?: return null

        val comments = vorbisComment(track, lyric)
        val out = ByteArrayOutputStream(bytes.size / 8 + 4096)
        out.write(byteArrayOf(0x66, 0x4C, 0x61, 0x43)) // fLaC
        val chain = ArrayList<Pair<Int, ByteArray>>()
        chain += streamInfo
        chain += blocks.drop(1).filter { it.first != BLOCK_STREAMINFO }
        chain += BLOCK_VORBIS_COMMENT to comments
        if (cover != null && cover.isNotEmpty()) chain += BLOCK_PICTURE to picture(cover)
        chain.forEachIndexed { index, entry ->
            val isLast = index == chain.lastIndex
            val (type, data) = entry
            out.write(if (isLast) type or 0x80 else type)
            out.write((data.size ushr 16) and 0xff)
            out.write((data.size ushr 8) and 0xff)
            out.write(data.size and 0xff)
            out.write(data)
        }
        return out.toByteArray() to cursor.toLong()
    }

    /** The `VORBIS_COMMENT` payload: a vendor string followed by `KEY=VALUE` entries. */
    private fun vorbisComment(track: Track, lyric: String?): ByteArray {
        val entries = buildList {
            track.name.takeIf { it.isNotBlank() }?.let { add("TITLE=$it") }
            track.artistLine.takeIf { it.isNotBlank() }?.let { add("ARTIST=$it") }
            track.albumName.takeIf { it.isNotBlank() }?.let { add("ALBUM=$it") }
            lyric?.takeIf { it.isNotBlank() }?.let { add("LYRICS=$it") }
            lyric?.takeIf { it.isNotBlank() }?.let { add("UNSYNCEDLYRICS=$it") }
        }
        val out = ByteArrayOutputStream()
        val vendor = "YunYin".toByteArray(Charsets.UTF_8)
        writeLittleEndian(out, vendor.size)
        out.write(vendor)
        writeLittleEndian(out, entries.size)
        entries.forEach { entry ->
            val data = entry.toByteArray(Charsets.UTF_8)
            writeLittleEndian(out, data.size)
            out.write(data)
        }
        return out.toByteArray()
    }

    /** The `PICTURE` payload: type, mime, description, dimensions (all zero = unknown), data. */
    private fun picture(cover: ByteArray): ByteArray {
        val mime = when {
            cover.size > 8 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte() -> "image/png"
            else -> "image/jpeg"
        }
        val out = ByteArrayOutputStream(cover.size + 64)
        out.write(byteArrayOf(0, 0, 0, PICTURE_TYPE_FRONT_COVER.toByte()))
        writeBigEndian(out, mime.length)
        out.write(mime.toByteArray(Charsets.US_ASCII))
        writeBigEndian(out, 0) // empty description
        out.write(byteArrayOf(0, 0, 0, 0)) // width
        out.write(byteArrayOf(0, 0, 0, 0)) // height
        out.write(byteArrayOf(0, 0, 0, 0)) // colour depth
        out.write(byteArrayOf(0, 0, 0, 0)) // palette size
        writeBigEndian(out, cover.size)
        out.write(cover)
        return out.toByteArray()
    }

    private fun writeLittleEndian(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    private fun writeBigEndian(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }
}
