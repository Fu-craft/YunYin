// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.download

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Deletes a downloaded song *with* the files the download wrote beside it.
 *
 * The writer publishes three rows per song — the audio, a cover image and a `.lrc` — so deleting only
 * the audio row left two orphans in `Music/云音` for every removed track. The user cannot see them in
 * the app at all (nothing references them), yet they keep occupying storage and showing up in file
 * managers as loose images.
 *
 * Matching is by display name in the same relative path, not by scanning the folder: the trio is
 * always named `<base>.<ext>` after the same audio file, and looking them up by name cannot touch an
 * unrelated file that happens to share the directory.
 */
object DownloadCleanup {

    private val SIDECAR_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "lrc")

    /** Removes [audioUri] and its siblings; returns true when the audio row itself went away. */
    fun deleteWithSidecars(context: Context, audioUri: Uri): Boolean {
        val resolver = context.contentResolver
        val (name, folder) = readNameAndFolder(context, audioUri) ?: (null to null)
        val audioDeleted = runCatching { resolver.delete(audioUri, null, null) > 0 }.getOrDefault(false)
        if (name != null && folder != null) {
            val base = name.substringBeforeLast('.', name)
            SIDECAR_EXTENSIONS.forEach { extension ->
                runCatching {
                    resolver.delete(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(folder, "$base.$extension"),
                    )
                }
            }
        }
        return audioDeleted
    }

    private fun readNameAndFolder(context: Context, uri: Uri): Pair<String?, String?>? {
        if (uri.authority?.startsWith("media") != true) return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns._ID}=?",
                arrayOf(id.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0) to cursor.getString(1)
            }
        }.getOrNull()
    }
}
