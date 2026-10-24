// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validates the `inited` event a script sends after it finishes initialising.
 *
 * ## The rule is LX's own `handleInit`, not a stricter reading of the prose doc
 *
 * The authoritative behaviour is `src/main/modules/userApi/renderer/preload.js` (`handleInit`): it iterates the
 * app's six known keys, **skips** an unknown key or an entry whose `type` is not `music`, and then **filters**
 * the declared `actions`/`qualitys` down to what the app supports. It does not reject a source for declaring a
 * tier the app does not know — and that distinction matters in the real world: a shipped aggregator declares
 * `hires`/`atmos`/`master` alongside the four standard tiers, and a validator that rejected the extras refused
 * a source that LX itself accepts and runs.
 *
 * So: unknown keys and non-music entries are ignored; unknown actions and quality tiers are dropped; a missing
 * `actions` array is still an error (the host iterates it to build the provider API), while a missing `qualitys`
 * array is treated as empty — LX mobile accepts that, and a maintained multi-source script relies on it. A
 * declaration that filters down to zero usable sources is refused, because importing it would only produce a source
 * that can never answer a request.
 *
 * Pure: the JSON is a string and the answer is a value, so this is unit-testable without the JS engine.
 */
object LxInitedValidator {

    sealed interface Result {
        data class Ok(val sources: List<LxSourceDescriptor>) : Result

        /** [message] is user-facing; [detail] is the technical remainder for the log. */
        data class Error(val message: String, val detail: String? = null) : Result
    }

    fun validate(payloadJson: String): Result {
        val payload = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return Result.Error("inited 事件的数据不是 JSON 对象", detail = e.message)
        }
        val sources = payload.optJSONObject("sources")
            ?: return Result.Error("inited 事件缺少 sources 字段")

        val descriptors = mutableListOf<LxSourceDescriptor>()
        // LX iterates its own key list, so an unknown key in `sources` is ignored rather than refused.
        LxSourceKey.entries.forEach { key ->
            val entry = sources.optJSONObject(key.wire) ?: return@forEach
            if (entry.optString("type", "") != "music") return@forEach

            val declaredActions = readStringArray(entry, "actions")
                ?: return Result.Error("源 ${key.wire} 缺少 actions 数组")
            // `qualitys` is optional in practice: LX mobile stores it directly in `global.lx.qualityList` and a
            // script that omits it (typically `local`, which has no tiers) initialises there. A maintained
            // multi-source script does exactly that — refusing it would reject a source LX itself runs.
            val declaredQualities = readStringArray(entry, "qualitys").orEmpty()

            descriptors += LxSourceDescriptor(
                key = key,
                name = entry.optString("name").trim().takeIf { it.isNotEmpty() },
                actions = SUPPORTED_ACTIONS.getValue(key).filter { it in declaredActions },
                qualities = supportedQualities(key).filter { it in declaredQualities },
            )
        }

        if (descriptors.isEmpty()) {
            return Result.Error(
                "脚本初始化成功但没有声明可用的音乐源（sources 为空，或每一项的 type 都不是 music）",
            )
        }
        return Result.Ok(descriptors)
    }

    /** The actions the app can ask a source for; the value is the filtering order, as LX defines it. */
    private val SUPPORTED_ACTIONS: Map<LxSourceKey, List<String>> = LxSourceKey.entries.associateWith { key ->
        if (key.isLocal) listOf(LX_ACTION_MUSIC_URL, LX_ACTION_LYRIC, LX_ACTION_PIC) else listOf(LX_ACTION_MUSIC_URL)
    }

    /** The quality tiers a key supports. `local` supports none, exactly as LX's `supportQualitys.local = []`. */
    private fun supportedQualities(key: LxSourceKey): List<String> = if (key.isLocal) emptyList() else LxQuality.all

    /** `null` when the array is missing; non-string entries are skipped (LX's `includes` simply never matches). */
    private fun readStringArray(entry: JSONObject, name: String): List<String>? {
        val array: JSONArray = entry.optJSONArray(name) ?: return null
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            if (value is String) out += value
        }
        return out
    }
}

/**
 * The collision rule, as a pure function.
 *
 * **At most one enabled source may claim a given source key.** Two scripts both answering for `wy` would make
 * every later "get a URL for a 网易云 song" ambiguous, and picking one silently is how a source appears to
 * work on Monday and return another provider's URL on Tuesday. So enabling is refused instead of guessed.
 *
 * Returns the first conflicting `(key, other source)` in the candidate's own key order, so the message names
 * a stable key rather than whichever one a map happened to iterate first.
 */
internal fun findLxSourceKeyConflict(
    enabled: Collection<LxImportedSource>,
    candidate: LxImportedSource,
): Pair<LxSourceKey, LxImportedSource>? {
    candidate.keys.forEach { key ->
        val other = enabled.firstOrNull { it.id != candidate.id && key in it.keys }
        if (other != null) return key to other
    }
    return null
}
