package com.yunyin.music.data

import android.content.Context
import android.content.SharedPreferences

/** Recently used search keywords, most recent first. */
class SearchHistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("amll_search", Context.MODE_PRIVATE)

    fun load(): List<String> =
        prefs.getString(KEY, null)?.split('\u0000')?.filter { it.isNotBlank() } ?: emptyList()

    fun add(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val next = (listOf(trimmed) + load().filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(MAX)
        prefs.edit().putString(KEY, next.joinToString("\u0000")).apply()
    }

    fun remove(keyword: String) {
        val next = load().filterNot { it == keyword }
        prefs.edit().putString(KEY, next.joinToString("\u0000")).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "history"
        const val MAX = 12
    }
}
