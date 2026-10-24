// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

/**
 * Parses the header comment a custom-source script must carry.
 *
 * ## The rule, and why it is this strict
 *
 * LX's doc says the file's beginning must contain a block comment naming the source, one `@name`-style field
 * per line inside it (`@name 测试脚本`, `@description …`, `@version 1.0.0`, `@author …`, `@homepage …`). The
 * comment delimiters themselves are not reproduced here on purpose: Kotlin block comments nest, so quoting a
 * nested opener would end this doc comment early.
 *
 * So the comment has to be the first thing in the file. A `@name` buried in a comment after the first
 * statement is not a header — it is a coincidence — and accepting it would let a script import under a name
 * its author never declared. Everything before the closing `*\/` must therefore be whitespace.
 *
 * The parser is pure and deliberately has no knowledge of the JS engine: a header is either there or it is
 * not, and a unit test can prove both without a native library or a device.
 */
object LxScriptHeaderParser {

    /** The protocol's own limits, quoted from the doc ("建议不要过长, … 以内") and enforced on import. */
    const val MAX_NAME_LENGTH = 24
    const val MAX_DESCRIPTION_LENGTH = 36

    private val HEADER_KEYS = setOf("name", "description", "version", "author", "homepage")

    sealed interface Result {
        data class Ok(val header: LxScriptHeader) : Result

        /** [message] is user-facing and names the specific thing that is wrong. */
        data class Error(val message: String) : Result
    }

    fun parse(script: String): Result {
        val text = script.removePrefix("\uFEFF")
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return Result.Error("脚本是空的，缺少自定义源信息注释")

        // A leading block comment, and nothing but whitespace before it.
        if (!text.startsWith("/*", start)) {
            return Result.Error("脚本开头缺少自定义源信息注释（需要以 /** @name … */ 开头）")
        }
        val end = text.indexOf("*/", start + 2)
        if (end < 0) return Result.Error("脚本开头的自定义源信息注释没有结束（缺少 */）")

        val fields = parseFields(text.substring(start + 2, end))

        val name = fields["name"]?.trim().orEmpty()
        if (name.isEmpty()) return Result.Error("自定义源信息缺少 @name")
        if (name.length > MAX_NAME_LENGTH) {
            return Result.Error("源名称过长（@name 最多 $MAX_NAME_LENGTH 个字符，当前 ${name.length} 个）")
        }
        val description = fields["description"]?.trim()?.takeIf { it.isNotEmpty() }
        if (description != null && description.length > MAX_DESCRIPTION_LENGTH) {
            return Result.Error("源描述过长（@description 最多 $MAX_DESCRIPTION_LENGTH 个字符，当前 ${description.length} 个）")
        }

        return Result.Ok(
            LxScriptHeader(
                name = name,
                description = description,
                version = fields["version"]?.trim()?.takeIf { it.isNotEmpty() },
                author = fields["author"]?.trim()?.takeIf { it.isNotEmpty() },
                homepage = fields["homepage"]?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /**
     * Reads `@key value` lines out of the comment body.
     *
     * A line is JSDoc-shaped (` * @name x`) as often as it is bare (`@name x`), so the leading decoration is
     * stripped rather than required. The first occurrence of a key wins: a duplicated `@name` is a malformed
     * header, and silently taking the last one would make the displayed name differ from the first one a
     * reader sees.
     */
    private fun parseFields(commentBody: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        commentBody.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().trimStart('*').trim()
            if (!line.startsWith("@")) return@forEach
            val space = line.indexOfFirst { it.isWhitespace() }
            if (space <= 1) return@forEach
            val key = line.substring(1, space).lowercase()
            if (key !in HEADER_KEYS) return@forEach
            val value = line.substring(space + 1).trim()
            if (key !in fields) fields[key] = value
        }
        return fields
    }
}
