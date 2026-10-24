// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.download

import java.net.URI

/**
 * File naming for downloads: `Music/云音/<artist> - <title>.<ext>`.
 *
 * The extension is derived from the actual response (Content-Type, then the URL path) rather than guessed, because a
 * `.flac` URL served as `audio/mpeg` really is an MP3 and the file must open in other players. Collisions are
 * resolved with the conventional `(1)`, `(2)` suffix — `MediaStore` does not dedupe display names for us.
 */
object DownloadNaming {

    /** The user-visible collection under the shared Music directory. */
    const val RELATIVE_PATH = "Music/云音"

    /** Characters Windows/Android reject in a display name, plus control chars. */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_PART_LENGTH = 80

    fun sanitize(text: String): String =
        text.replace(ILLEGAL, "_")
            .replace(WHITESPACE, " ")
            .trim()
            .trim('.')
            .take(MAX_PART_LENGTH)
            .ifBlank { "未命名" }

    /** `<artist> - <title>`, with an empty artist simply omitted. */
    fun baseName(artist: String, title: String): String {
        val a = sanitize(artist)
        val t = sanitize(title)
        return if (a == "未命名" && t == "未命名") "未命名" else "$a - $t"
    }

    fun fileName(artist: String, title: String, extension: String): String =
        "${baseName(artist, title)}.$extension"

    /** `name.ext`, `name (1).ext`, `name (2).ext` … — index 0 means no suffix. */
    fun withSuffix(base: String, extension: String, index: Int): String =
        if (index <= 0) "$base.$extension" else "$base ($index).$extension"

    /**
     * First name not already taken.
     *
     * [exists] is given the candidate display name; the caller reads the collection's current names once (a
     * MediaStore insert does not enforce uniqueness, so a second read per candidate would still race).
     */
    fun uniqueFileName(base: String, extension: String, exists: (String) -> Boolean): String {
        for (index in 0..9_999) {
            val candidate = withSuffix(base, extension, index)
            if (!exists(candidate)) return candidate
        }
        return "${base} (${System.currentTimeMillis()}).$extension"
    }

    /**
     * Extension from the response: Content-Type first, then the URL path, then the `Content-Disposition` filename.
     * Null when neither says anything — the caller refuses with a specific message instead of saving a bad-tagged file.
     */
    fun extensionFor(url: String, contentType: String?, contentDisposition: String? = null): String? {
        contentType?.lowercase()?.let { type ->
            when {
                type.contains("flac") -> return "flac"
                type.contains("mp4") || type.contains("m4a") || type.contains("aac") -> return "m4a"
                type.contains("mpeg") || type.contains("mp3") -> return "mp3"
                type.contains("ogg") || type.contains("opus") -> return "ogg"
                type.contains("wav") -> return "wav"
            }
        }
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        path.substringAfterLast('.', "").lowercase().takeIf { it in AUDIO_EXTENSIONS }?.let { return it }
        val dispositionName = contentDisposition
            ?.substringAfterLast("filename=", "")
            ?.trim('"', ' ')
            ?.substringAfterLast('.', "")
            ?.lowercase()
        return dispositionName?.takeIf { it in AUDIO_EXTENSIONS }
    }

    /** MIME type MediaStore should record for [extension]. */
    fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    private val AUDIO_EXTENSIONS = setOf("flac", "m4a", "aac", "mp3", "ogg", "opus", "wav")
}
