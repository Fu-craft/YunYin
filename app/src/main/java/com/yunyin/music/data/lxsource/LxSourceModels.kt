// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

/**
 * The model of an imported LX Music (洛雪音乐) 自定义源.
 *
 * ## Where this comes from
 *
 * A custom source is a **JavaScript file** whose header comment names it and whose script builds the
 * `globalThis.lx` host API — the authoritative description is LX Music's own
 * [自定义源脚本编写说明](https://lxmusic.toside.cn/desktop/custom-source). This file is a transcription of the
 * protocol's *data* shapes, not a port of LX's implementation: the header fields, the six source keys, the
 * two action sets and the four quality tiers are the wire contract a script is written against.
 *
 * ## Why a source key is an enum and not a string
 *
 * Two scripts claiming `wy` is ambiguous, and the whole collision rule exists to make that unambiguous. If
 * the key stayed a `String`, "is this the same source key" would compare with `==` in one place and
 * `equals(ignoreCase = true)` in another, and the rule would hold only where someone remembered. The enum
 * is the single place that answers it, and [fromWire] is the only way in from a script.
 */

/** The six source keys a script may declare. The wire spelling is the protocol's; the label is for the UI. */
enum class LxSourceKey(val wire: String, val label: String) {
    KW("kw", "酷我"),
    KG("kg", "酷狗"),
    TX("tx", "QQ音乐"),
    WY("wy", "网易云"),
    MG("mg", "咪咕"),
    LOCAL("local", "本地"),

    ;

    /** Whether this key is LX's special `local` source, whose actions and qualities differ. */
    val isLocal: Boolean get() = this == LOCAL

    companion object {
        /** Indexed by wire name. */
        private val byWire: Map<String, LxSourceKey> = entries.associateBy { it.wire }

        /** The keys in protocol order, for message text and UI. */
        val wireList: List<String> = entries.map { it.wire }

        /** `null` when the script named a key the protocol does not define. */
        fun fromWire(value: String?): LxSourceKey? = value?.let { byWire[it.trim()] }
    }
}

/** The protocol's four quality tiers. Anything else is a script error, not a tier to ignore. */
object LxQuality {
    const val Q128 = "128k"
    const val Q320 = "320k"
    const val QFLAC = "flac"
    const val QFLAC24 = "flac24bit"

    val all: List<String> = listOf(Q128, Q320, QFLAC, QFLAC24)

    fun isValid(value: String): Boolean = value in all
}

/** The `musicUrl` action; the only one a non-`local` source may declare. */
const val LX_ACTION_MUSIC_URL = "musicUrl"

/** The extra actions LX's `local` source may declare. */
const val LX_ACTION_LYRIC = "lyric"
const val LX_ACTION_PIC = "pic"

val LX_LOCAL_ACTIONS: Set<String> = setOf(LX_ACTION_MUSIC_URL, LX_ACTION_LYRIC, LX_ACTION_PIC)

/**
 * The header a script must carry, parsed from its leading block comment.
 *
 * [name] is required by this app even though LX itself documents it as "目前非必须" for a *source entry*: the
 * imported script has to be identifiable in a list, and a nameless file would be shown by filename, which is
 * not what the author wrote.
 */
data class LxScriptHeader(
    val name: String,
    val description: String? = null,
    val version: String? = null,
    val author: String? = null,
    val homepage: String? = null,
)

/** One source a script declares in its `inited` payload, already validated against the protocol. */
data class LxSourceDescriptor(
    val key: LxSourceKey,
    /** The script's own name for this source; optional in the protocol, so nullable rather than invented. */
    val name: String?,
    val actions: List<String>,
    val qualities: List<String>,
)

/**
 * One imported script, as persisted.
 *
 * [id] is the SHA-256 of the script text (first 16 hex characters), so importing the same file twice updates
 * the same entry instead of growing the list; a changed script is a different id, and the collision rule is
 * what keeps the old and new version from being enabled at once.
 *
 * [keys] are the source keys the script claimed in `inited` at import time. They are persisted rather than
 * re-derived on every list read: a script is only executed at import, and a list that needed the JS engine to
 * render would make Settings depend on it.
 */
data class LxImportedSource(
    val id: String,
    val header: LxScriptHeader,
    val keys: List<LxSourceKey>,
    val importedAt: Long,
    val enabled: Boolean,
    /**
     * Where the script was downloaded from, when it was imported by URL.
     *
     * Persisted because a URL-sourced script is updatable: the list shows its origin, and 重新拉取 re-downloads from
     * it. `null` for a local-file import — the file picker's URI is not a stable origin.
     */
    val originUrl: String? = null,
) {
    /** "wy / tx" — the keys a user sees beside the name. */
    val keysLabel: String get() = keys.joinToString(" / ") { it.wire }

    /** "v1.0.0 · 作者" or just one of them; empty when the header carried neither. */
    val versionAuthorLabel: String
        get() = listOfNotNull(
            header.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
            header.author?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}

/** The outcome of an import attempt. A failure carries a user-facing line and the detail for the log. */
sealed interface LxImportResult {

    /**
     * The script ran and sent a valid `inited` event.
     *
     * [collision] is the key that stopped it from being enabled beside an existing source; a freshly imported
     * source is enabled when it can be, because it was imported to be used.
     */
    data class Success(
        val source: LxImportedSource,
        val collision: LxSourceKey? = null,
        val collidedWith: String? = null,
        /** Set when the script came from a mirror of the pasted URL, e.g. "jsDelivr 镜像". */
        val viaMirror: String? = null,
    ) : LxImportResult

    data class Failure(
        /** A short, specific line the sheet can show as-is. */
        val message: String,
        /** The technical detail (JS stack, exception), written to the lxsource log. */
        val detail: String? = null,
    ) : LxImportResult
}

/** The outcome of flipping the enable switch. */
sealed interface LxToggleResult {
    data object Ok : LxToggleResult

    /** The key is claimed by [otherName]; enabling would make "which script answers for `wy`" ambiguous. */
    data class Collision(val key: LxSourceKey, val otherName: String) : LxToggleResult

    data object NotFound : LxToggleResult
}
