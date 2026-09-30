package com.yunyin.music.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The user's own profile presentation: avatar and header background.
 *
 * Two decisions here are load-bearing.
 *
 * **The picked image is copied into app storage, not referenced by URI.** A URI from the photo picker
 * is only readable for the lifetime of the grant that came with it, so storing the string would leave
 * the header blank — or throwing — once that grant lapsed: the kind of bug that only surfaces days
 * later. A private copy has no such lifetime.
 *
 * **Each selection gets a new file name.** Reusing one name would keep the URI identical, and every
 * image cache (ours, and any platform one) would then serve the old picture for the same key, so
 * changing the avatar would appear to do nothing. A fresh name per selection makes the URI change with
 * the image.
 *
 * The store only returns URIs it can read: a chosen file that has since disappeared resolves to null, so
 * the UI falls back to the NetEase avatar or to a monogram rather than showing an empty frame.
 */
class ProfileStore(context: Context, private val settings: SettingsStore) {

    private val dir = File(context.filesDir, "profile").apply { mkdirs() }

    /** Copies [source] in as the custom avatar and switches the header over to it. */
    suspend fun setAvatar(context: Context, source: Uri): Boolean {
        val name = copyIn(context, source, AVATAR_PREFIX, settings.customAvatarFile)
        if (name == null) return false
        settings.customAvatarFile = name
        settings.useNeteaseAvatar = false
        return true
    }

    /** Copies [source] in as the custom header background. */
    suspend fun setBackground(context: Context, source: Uri): Boolean {
        val name = copyIn(context, source, BACKGROUND_PREFIX, settings.customBackgroundFile)
        if (name == null) return false
        settings.customBackgroundFile = name
        return true
    }

    /** Drops the custom avatar so the header falls back to the account's own. */
    fun clearAvatar() {
        val previous = settings.customAvatarFile
        settings.customAvatarFile = null
        settings.useNeteaseAvatar = true
        previous?.let { File(dir, it).delete() }
    }

    /** Drops the custom background so the header falls back to the artwork-derived gradient. */
    fun clearBackground() {
        val previous = settings.customBackgroundFile
        settings.customBackgroundFile = null
        previous?.let { File(dir, it).delete() }
    }

    /** The custom avatar's file, or null when none is set or the file is gone. */
    fun avatarFile(): File? = existing(settings.customAvatarFile)

    /** The custom background's file, or null when none is set or the file is gone. */
    fun backgroundFile(): File? = existing(settings.customBackgroundFile)

    private fun existing(name: String?): File? {
        if (name.isNullOrBlank()) return null
        val file = File(dir, name)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Streams the picked image into a new file, then records it and deletes the previous one.
     *
     * Returns the new file's name, or null if the image could not be read. The copy goes to a temporary
     * file first so an interrupted read cannot leave a half-written image that decodes to garbage, and
     * the previous file is only removed after the replacement is safely in place.
     */
    private suspend fun copyIn(
        context: Context,
        source: Uri,
        prefix: String,
        previous: String?,
    ): String? = withContext(Dispatchers.IO) {
        val name = "$prefix-${UUID.randomUUID()}"
        val target = File(dir, name)
        val temp = File(dir, "$name.part")

        val copied = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        }.getOrDefault(false)

        if (!copied || temp.length() == 0L) {
            temp.delete()
            return@withContext null
        }
        if (!temp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to a copy before giving up.
            val moved = runCatching {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }.isSuccess
            if (!moved) {
                temp.delete()
                return@withContext null
            }
        }
        // Only now is the old file expendable.
        previous?.takeIf { it != name }?.let { File(dir, it).delete() }
        name
    }

    private companion object {
        const val AVATAR_PREFIX = "avatar"
        const val BACKGROUND_PREFIX = "background"
    }
}
