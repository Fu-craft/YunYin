// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Persistence for imported custom sources.
 *
 * ## Why the scripts are files and the metadata is a sidecar
 *
 * A source script is a whole JavaScript program — tens to hundreds of kilobytes — and the project's
 * preference store is `SharedPreferences`, which is the wrong place for blobs of that size: it is loaded into
 * memory at startup and rewritten wholesale on every `apply`. So the layout is
 *
 * ```
 * filesDir/lxsource/
 *   sources.json     metadata for every imported script, small and rewritten on change
 *   <id>.js          one script, written once and never edited
 *   last-error.txt   the last failure detail (see LxSourceLog)
 * ```
 *
 * The sidecar is rewritten atomically (write to `.tmp`, then rename) because a half-written `sources.json`
 * would lose every import: the scripts would still be on disk but nothing would list them.
 *
 * ## Identity
 *
 * `id` is the SHA-256 of the script text, first 16 hex characters. Importing the same file twice replaces the
 * same entry (and keeps its enabled state); importing a modified script creates a new entry. It is a content
 * hash rather than a random id so re-importing a file the user just fixed cannot leave two identical sources
 * behind. [LxImportedSource.keys] records what the script declared at import time, so listing sources never
 * needs to execute JavaScript.
 */
class LxSourceStore internal constructor(
    private val root: File,
    private val log: LxSourceLog,
) {

    /**
     * @param context only used to locate `filesDir/lxsource`. The store is a file writer, so the internal
     *   constructor takes the directory directly and a JVM test can point it at a temp directory — the same
     *   arrangement as [LxSourceLog].
     */
    constructor(context: Context, log: LxSourceLog = LxSourceLog(context)) : this(lxSourceRoot(context), log)

    private val metadataFile = File(root, METADATA_FILE)
    private val lock = Any()

    /** Everything that has been imported, newest import last. A corrupt sidecar reads as "nothing imported". */
    fun load(): List<LxImportedSource> = synchronized(lock) { loadLocked() }

    /** The script text, or null when its file has gone missing (a stale sidecar entry). */
    fun readScript(id: String): String? = synchronized(lock) {
        scriptFile(id).takeIf { it.isFile }?.readText()
    }

    fun idFor(scriptText: String): String = sha256Hex(scriptText).take(ID_LENGTH)

    fun scriptFile(id: String): File = File(root, "$id.js")

    /**
     * Writes [scriptText] and records it under its content id.
     *
     * A newly imported source is **enabled when no other enabled source claims one of its keys**, because it
     * was imported to be used; when it collides it is still stored but left disabled, and [LxImportResult.Success]
     * reports which key and which source stopped it. A re-import of the *same* content keeps the previous
     * enabled state — the user already decided that once.
     *
     * @param originUrl the download URL for a URL import, persisted for display and 重新拉取.
     * @param keepEnabledFrom an earlier entry whose enabled state (and identity) this write replaces, used by
     *   重新拉取: the new content has a new id, so the old entry is removed in the same atomic metadata write and its
     *   script file deleted only after the write succeeds. The enabled state is carried over, because updating a
     *   source must not silently switch it off.
     */
    fun addOrReplace(
        header: LxScriptHeader,
        descriptors: List<LxSourceDescriptor>,
        scriptText: String,
        originUrl: String? = null,
        keepEnabledFrom: LxImportedSource? = null,
    ): LxImportResult.Success = synchronized(lock) {
        val id = idFor(scriptText)
        val existing = loadLocked()
        val previous = existing.firstOrNull { it.id == id }
        val superseded = keepEnabledFrom?.takeIf { it.id != id }
        val keys = descriptors.map { it.key }.distinct()

        root.mkdirs()
        scriptFile(id).writeText(scriptText)

        val base = LxImportedSource(
            id = id,
            header = header,
            keys = keys,
            importedAt = System.currentTimeMillis(),
            enabled = keepEnabledFrom?.enabled ?: previous?.enabled ?: true,
            originUrl = originUrl ?: previous?.originUrl ?: superseded?.originUrl,
        )
        val others = existing.filter { it.id != id && it.id != superseded?.id }
        // A refresh carries the old enabled state over; if that state now collides it is kept off and reported.
        val conflict = findLxSourceKeyConflict(others.filter { it.enabled }, base)
        val stored = if (conflict != null) base.copy(enabled = false) else base

        writeMetadata(others + stored)
        // Only after the sidecar is consistent: a failed write must not orphan a script that is still listed.
        if (superseded != null) scriptFile(superseded.id).delete()
        LxImportResult.Success(
            source = stored,
            collision = conflict?.first,
            collidedWith = conflict?.second?.header?.name,
        )
    }

    /**
     * Flips the enabled flag, enforcing the one-source-per-key rule.
     *
     * Disabling is always allowed; enabling is refused when any of the source's keys is already claimed,
     * because the alternative — silently disabling the other script — would turn a toggle into a destructive
     * edit the user never asked for.
     */
    fun setEnabled(id: String, enabled: Boolean): LxToggleResult = synchronized(lock) {
        val sources = loadLocked()
        val target = sources.firstOrNull { it.id == id } ?: return LxToggleResult.NotFound
        if (!enabled) {
            writeMetadata(sources.map { if (it.id == id) it.copy(enabled = false) else it })
            return LxToggleResult.Ok
        }
        val conflict = findLxSourceKeyConflict(
            sources.filter { it.enabled && it.id != id },
            target,
        )
        if (conflict != null) {
            return LxToggleResult.Collision(conflict.first, conflict.second.header.name)
        }
        writeMetadata(sources.map { if (it.id == id) it.copy(enabled = true) else it })
        LxToggleResult.Ok
    }

    /** Removes the entry and its script file. The sidecar entry goes first, so a missing file never dangles. */
    fun delete(id: String): Boolean = synchronized(lock) {
        val sources = loadLocked()
        if (sources.none { it.id == id }) return false
        writeMetadata(sources.filter { it.id != id })
        scriptFile(id).delete()
        true
    }

    // ---------------------------------------------------------------- metadata

    private fun loadLocked(): List<LxImportedSource> {
        if (!metadataFile.isFile) return emptyList()
        return runCatching {
            val payload = JSONObject(metadataFile.readText())
            val array = payload.optJSONArray("sources") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::decodeSource)
            }
        }.getOrElse { error ->
            // A corrupt sidecar must not crash Settings on launch; the scripts are still on disk and the next
            // successful import rewrites the file.
            log.record("读取音源列表失败", error.stackTraceToString())
            emptyList()
        }
    }

    private fun decodeSource(json: JSONObject): LxImportedSource? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
        val header = LxScriptHeader(
            name = name,
            description = json.optString("description").takeIf { it.isNotBlank() },
            version = json.optString("version").takeIf { it.isNotBlank() },
            author = json.optString("author").takeIf { it.isNotBlank() },
            homepage = json.optString("homepage").takeIf { it.isNotBlank() },
        )
        val keysJson = json.optJSONArray("keys") ?: JSONArray()
        val keys = (0 until keysJson.length()).mapNotNull { i ->
            LxSourceKey.fromWire(keysJson.optString(i))
        }
        if (keys.isEmpty()) return null
        return LxImportedSource(
            id = id,
            header = header,
            keys = keys,
            importedAt = json.optLong("importedAt", 0L),
            enabled = json.optBoolean("enabled", false),
            originUrl = json.optString("origin").takeIf { it.isNotBlank() },
        )
    }

    private fun writeMetadata(sources: List<LxImportedSource>) {
        runCatching {
            root.mkdirs()
            val array = JSONArray()
            sources.forEach { source ->
                array.put(
                    JSONObject()
                        .put("id", source.id)
                        .put("name", source.header.name)
                        .put("description", source.header.description ?: "")
                        .put("version", source.header.version ?: "")
                        .put("author", source.header.author ?: "")
                        .put("homepage", source.header.homepage ?: "")
                        .put("keys", JSONArray(source.keys.map { it.wire }))
                        .put("importedAt", source.importedAt)
                        .put("enabled", source.enabled)
                        .put("origin", source.originUrl ?: ""),
                )
            }
            val text = JSONObject().put("version", METADATA_VERSION).put("sources", array).toString(2)
            val tmp = File(root, "$METADATA_FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(metadataFile)) {
                metadataFile.delete()
                check(tmp.renameTo(metadataFile)) { "无法替换 $METADATA_FILE" }
            }
        }.onFailure { log.record("保存音源列表失败", it.stackTraceToString()) }
    }

    private companion object {
        const val METADATA_FILE = "sources.json"
        const val METADATA_VERSION = 1
        const val ID_LENGTH = 16

        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
